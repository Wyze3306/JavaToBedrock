package io.github.wyze3306.jtb.resourcepack;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.wyze3306.jtb.JavaToBedrock;
import io.github.wyze3306.jtb.ProxyService;
import io.github.wyze3306.jtb.config.ProxyConfig;
import io.github.wyze3306.jtb.resourcepack.convert.BedrockToJavaConverter;
import io.github.wyze3306.jtb.resourcepack.convert.LenientJson;
import io.github.wyze3306.jtb.session.ProxySession;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Converts Bedrock pack stacks into Java packs and serves them over a small embedded HTTP server.
 * <p>
 * Conversion runs in two phases ({@link BedrockToJavaConverter}): the stack-only phase starts as
 * soon as a session receives the pack stack ({@link #prepareStack}), the completion once the
 * session knows the server's definitions ({@link #complete}). Both are memoised in memory (a few
 * recent entries) and the result is cached on disk: {@code index/<key>} maps the conversion key
 * (converter version, packs, definitions) to the SHA-1 of {@code java/<sha1>.zip}, whose
 * {@code jtb/artifacts.json} restores the artifacts, so a restart serves the same pack without
 * converting again. When the last conversion of a stack is on disk, the first phase is not even
 * started until it turns out to be needed.
 */
public final class JavaPackService implements ProxyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(JavaPackService.class);
    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
    private static final int MEMORY_ENTRIES = 4;

    private final JavaToBedrock proxy;
    private final PackCache cache;
    private final Map<String, StackHandle> stacks = lru(MEMORY_ENTRIES);
    private final Map<String, CompletableFuture<PackConversion>> conversions = lru(MEMORY_ENTRIES * 2);
    private final Map<String, byte[]> served = lru(MEMORY_ENTRIES);
    private @Nullable HttpServer http;

    public JavaPackService(JavaToBedrock proxy) {
        this.proxy = proxy;
        this.cache = new PackCache(proxy.dataDirectory().resolve(proxy.config().resourcePacks().cacheDirectory()));
    }

    public PackCache cache() {
        return this.cache;
    }

    @Override
    public void start() throws IOException {
        ProxyConfig.ResourcePacks config = this.proxy.config().resourcePacks();
        if (!config.enabled()) {
            return;
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(config.httpBind(), config.httpPort()), 64);
        server.createContext("/packs/", this::serve);
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread thread = new Thread(r, "jtb-pack-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        this.http = server;
        LOGGER.info("Resource pack HTTP server listening on {}:{}", config.httpBind(), server.getAddress().getPort());
    }

    /** {@code GET|HEAD /packs/<sha1>.zip}: the converted pack, from memory or the disk cache. */
    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            String sha1 = name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
            byte[] zip = null;
            if (SHA1.matcher(sha1).matches()) {
                synchronized (this.served) {
                    zip = this.served.get(sha1);
                }
                if (zip == null) {
                    zip = this.cache.readJavaPack(sha1);
                }
            }
            boolean head = "HEAD".equals(method);
            if (!head && !"GET".equals(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (zip == null) {
                byte[] body = "Not found".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(404, head ? -1 : body.length);
                if (!head) {
                    exchange.getResponseBody().write(body);
                }
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + sha1 + ".zip\"");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
            exchange.getResponseHeaders().set("ETag", "\"" + sha1 + "\"");
            if (head) {
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(zip.length));
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, zip.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(zip);
            }
        }
    }

    // ------------------------------------------------------------------ conversion

    /** A pack stack whose first conversion phase may be running, done, or not started yet. */
    public final class StackHandle {
        private final String fingerprint;
        private final List<BedrockPack> packs;
        private @Nullable CompletableFuture<BedrockToJavaConverter.StackConversion> phase;

        private StackHandle(String fingerprint, List<BedrockPack> packs) {
            this.fingerprint = fingerprint;
            this.packs = List.copyOf(packs);
        }

        public String fingerprint() {
            return this.fingerprint;
        }

        synchronized CompletableFuture<BedrockToJavaConverter.StackConversion> phase() {
            if (this.phase == null) {
                this.phase = CompletableFuture.supplyAsync(() -> BedrockToJavaConverter.convertStack(this.packs), workers());
            }
            return this.phase;
        }
    }

    /**
     * Starts (or reuses) the stack-only conversion of {@code packs}, top of the stack first. Safe
     * from any thread.
     */
    public StackHandle prepareStack(List<BedrockPack> packs) {
        String fingerprint = fingerprint(packs);
        StackHandle handle;
        synchronized (this.stacks) {
            handle = this.stacks.computeIfAbsent(fingerprint, key -> new StackHandle(key, packs));
        }
        String lastKey = this.cache.readIndex("stack-" + fingerprint);
        String lastSha1 = lastKey == null ? null : this.cache.readIndex(lastKey);
        if (lastSha1 == null || !this.cache.hasJavaPack(lastSha1)) {
            handle.phase();
        }
        return handle;
    }

    /**
     * The finished pack for a stack and the server's definitions: from memory, from the disk cache,
     * or converted (waiting for the first phase without blocking a thread). The future completes on
     * a worker thread.
     */
    public CompletableFuture<PackConversion> complete(StackHandle stack, ServerDefinitions definitions) {
        String key = sha256("v" + BedrockToJavaConverter.VERSION + "|" + stack.fingerprint + "|" + definitions.fingerprint());
        synchronized (this.conversions) {
            CompletableFuture<PackConversion> existing = this.conversions.get(key);
            if (existing != null && !existing.isCompletedExceptionally()) {
                return existing;
            }
            CompletableFuture<PackConversion> future = CompletableFuture
                    .supplyAsync(() -> loadCached(key), workers())
                    .thenCompose(cached -> cached != null ? CompletableFuture.completedFuture(cached)
                            : stack.phase().thenApplyAsync(phase -> finish(key, stack, phase, definitions), workers()));
            this.conversions.put(key, future);
            return future;
        }
    }

    private PackConversion finish(String key, StackHandle stack, BedrockToJavaConverter.StackConversion phase,
                                  ServerDefinitions definitions) {
        long start = System.nanoTime();
        BedrockToJavaConverter.ConvertedZip converted = BedrockToJavaConverter.complete(phase, definitions);
        String sha1 = sha1(converted.zip());
        PackConversion conversion = new PackConversion(new JavaPack(packId(sha1), sha1, converted.zip()), converted.artifacts());
        remember(conversion.pack());
        this.cache.writeJavaPack(sha1, converted.zip());
        this.cache.writeIndex(key, sha1);
        this.cache.writeIndex("stack-" + stack.fingerprint, key);
        LOGGER.info("Converted {} Bedrock pack(s) {} into a {} KiB Java pack in {} + {} ms: {}", phase.packs(),
                BedrockToJavaConverter.packNames(phase), converted.zip().length / 1024, phase.millis(),
                (System.nanoTime() - start) / 1_000_000, converted.counters());
        return conversion;
    }

    /** The conversion stored on disk under {@code key}, or null. */
    private @Nullable PackConversion loadCached(String key) {
        String sha1 = this.cache.readIndex(key);
        byte[] zip = sha1 == null ? null : this.cache.readJavaPack(sha1);
        if (zip == null || !sha1(zip).equals(sha1)) {
            return null;
        }
        ConversionArtifacts artifacts = readArtifacts(zip);
        if (artifacts == null) {
            return null;
        }
        JavaPack pack = new JavaPack(packId(sha1), sha1, zip);
        remember(pack);
        LOGGER.info("Using the cached Java pack {} ({} KiB)", sha1, zip.length / 1024);
        return new PackConversion(pack, artifacts);
    }

    private static @Nullable ConversionArtifacts readArtifacts(byte[] zip) {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().equals(BedrockToJavaConverter.ARTIFACTS_PATH)) {
                    JsonObject json = LenientJson.object(in.readAllBytes());
                    return json == null ? null : ConversionArtifacts.fromJson(json);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Unreadable cached pack", e);
        }
        return null;
    }

    private void remember(JavaPack pack) {
        synchronized (this.served) {
            this.served.put(pack.sha1(), pack.zip());
        }
    }

    private Executor workers() {
        return this.proxy.workers();
    }

    /** The URL the Java client downloads {@code pack} from. */
    public String url(ProxySession session, JavaPack pack) {
        ProxyConfig.ResourcePacks config = this.proxy.config().resourcePacks();
        String base = config.publicUrl();
        if (base == null || base.isBlank()) {
            String host = session.getJavaHost();
            if (host == null || host.isBlank()) {
                host = "127.0.0.1";
            }
            if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
                host = "[" + host + "]";
            }
            int port = this.http != null ? this.http.getAddress().getPort() : config.httpPort();
            base = "http://" + host + ":" + port;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/packs/" + pack.sha1() + ".zip";
    }

    /** Stable id of a pack: same content, same id (the client keys its pack cache on it). */
    static UUID packId(String sha1) {
        return UUID.nameUUIDFromBytes(sha1.getBytes(StandardCharsets.UTF_8));
    }

    static String fingerprint(List<BedrockPack> packs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("v" + BedrockToJavaConverter.VERSION).getBytes(StandardCharsets.UTF_8));
            for (BedrockPack pack : packs) {
                digest.update((pack.id() + "|" + pack.version() + "|" + pack.subpack() + "|" + pack.contentKey() + "|")
                        .getBytes(StandardCharsets.UTF_8));
                digest.update(MessageDigest.getInstance("SHA-256").digest(pack.zip()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static <K, V> Map<K, V> lru(int size) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > size;
            }
        };
    }

    @Override
    public void close() {
        if (this.http != null) {
            this.http.stop(0);
        }
    }
}
