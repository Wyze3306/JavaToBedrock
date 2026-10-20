package io.github.wyze3306.jtb.resourcepack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.wyze3306.jtb.JavaToBedrock;
import io.github.wyze3306.jtb.ProxyService;
import io.github.wyze3306.jtb.config.ProxyConfig;
import io.github.wyze3306.jtb.session.ProxySession;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Converts Bedrock pack stacks into Java packs (once per distinct stack, cached in memory and on
 * disk) and serves them over a small embedded HTTP server.
 */
public final class JavaPackService implements ProxyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(JavaPackService.class);

    private final JavaToBedrock proxy;
    private final PackCache cache;
    private final Map<String, CompletableFuture<PackConversion>> conversions = new ConcurrentHashMap<>();
    private final Map<String, byte[]> served = new ConcurrentHashMap<>();
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

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            String sha1 = name.endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
            byte[] zip = sha1.matches("[0-9a-f]{40}") ? this.served.get(sha1) : null;
            if (zip == null && sha1.matches("[0-9a-f]{40}")) {
                zip = this.cache.readJavaPack(sha1);
            }
            if (zip == null || !"GET".equals(exchange.getRequestMethod())) {
                byte[] body = "Not found".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
            exchange.sendResponseHeaders(200, zip.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(zip);
            }
        }
    }

    /**
     * Converts {@code packs} (or returns the cached conversion of the same stack). Runs on a
     * worker; the future completes off the session event loop.
     */
    public CompletableFuture<PackConversion> prepare(ProxySession session, List<BedrockPack> packs) {
        String fingerprint = fingerprint(packs);
        return this.conversions.computeIfAbsent(fingerprint, key -> CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            try {
                BedrockToJavaConverter.ConvertedZip converted = BedrockToJavaConverter.convert(packs);
                String sha1 = sha1(converted.zip());
                JavaPack pack = new JavaPack(UUID.nameUUIDFromBytes(sha1.getBytes(StandardCharsets.UTF_8)), sha1, converted.zip());
                this.served.put(sha1, converted.zip());
                this.cache.writeJavaPack(sha1, converted.zip());
                LOGGER.info("Converted {} Bedrock pack(s) into a {} KiB Java pack in {} ms", packs.size(),
                        converted.zip().length / 1024, (System.nanoTime() - start) / 1_000_000);
                return new PackConversion(pack, converted.artifacts());
            } catch (Exception e) {
                this.conversions.remove(key);
                throw new IllegalStateException("Resource pack conversion failed", e);
            }
        }, this.proxy.workers()));
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

    private static String fingerprint(List<BedrockPack> packs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("v" + BedrockToJavaConverter.VERSION).getBytes(StandardCharsets.UTF_8));
            for (BedrockPack pack : packs) {
                digest.update((pack.id() + "|" + pack.version() + "|" + pack.subpack() + "|").getBytes(StandardCharsets.UTF_8));
                digest.update(MessageDigest.getInstance("SHA-256").digest(pack.zip()));
            }
            return HexFormat.of().formatHex(digest.digest());
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

    @Override
    public void close() {
        if (this.http != null) {
            this.http.stop(0);
        }
    }
}
