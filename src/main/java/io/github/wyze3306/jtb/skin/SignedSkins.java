package io.github.wyze3306.jtb.skin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.wyze3306.jtb.JavaToBedrock;
import io.github.wyze3306.jtb.ProxyService;
import io.github.wyze3306.jtb.config.ProxyConfig;
import org.cloudburstmc.protocol.bedrock.data.payload.skin.ArmSizeType;
import org.cloudburstmc.protocol.bedrock.data.payload.skin.SerializedSkin;
import org.cloudburstmc.protocol.bedrock.data.payload.skin.SkinImage;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Mojang-signed textures for Bedrock skins. Java clients only draw a player skin whose
 * {@code textures} profile property is signed by Mojang, so a Bedrock skin has to be uploaded to a
 * Mojang account before Java players can see it. Sources, best first:
 * <ol>
 *     <li>Java players joining through this proxy: their Mojang profile is signed, and the Bedrock
 *     server relays to everyone the very pixels the proxy sent, so the image hash finds the original
 *     signed textures back ({@link #remember});</li>
 *     <li><a href="https://mineskin.org">MineSkin</a> when an API key is configured: uploads the exact
 *     skin (about 2 seconds, 20 skins a minute on the free plan);</li>
 *     <li>the GeyserMC global API: the last skin of a Bedrock player seen by any Geyser server, by
 *     XUID (so only on servers with Xbox authentication), possibly out of date.</li>
 * </ol>
 * Signed skins are kept in {@code cache/skins}, so each skin is uploaded once. Uploads run one at a
 * time on their own thread, in line with MineSkin's rate limit; lookups never block the caller.
 */
public final class SignedSkins implements ProxyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SignedSkins.class);
    private static final String MINESKIN = "https://api.mineskin.org";
    private static final String GEYSER_SKIN = "https://api.geysermc.org/v2/skin/";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** Pending uploads beyond this are dropped: the players keep the default skin. */
    private static final int MAX_PENDING_UPLOADS = 256;
    private static final int MAX_JOB_POLLS = 15;

    /** A signed {@code textures} property value. */
    public record Textures(String value, String signature) {

        public GameProfile.Property toProperty() {
            return new GameProfile.Property("textures", this.value, this.signature);
        }
    }

    /**
     * A Bedrock skin reduced to what Java can draw: a classic 64x32 / 64x64 skin or a 128-pixel HD
     * one (scaled down). Persona skins use another UV layout and are not supported.
     *
     * @param hash SHA-256 of the image, the cache key
     */
    public record Skin(String hash, int width, int height, byte[] rgba, boolean slim) {

        /** The Java-compatible part of {@code skin}, or {@code null} when Java cannot show it. */
        public static @Nullable Skin of(@Nullable SerializedSkin skin) {
            if (skin == null || skin.isPersona() || skin.getImageData() == null) {
                return null;
            }
            SkinImage image = skin.getImageData();
            int width = image.getWidth();
            int height = image.getHeight();
            byte[] rgba = image.getImageBytes();
            boolean supported = (width == 64 && (height == 32 || height == 64)) || (width == 128 && (height == 64 || height == 128));
            if (!supported || rgba == null || rgba.length != width * height * 4) {
                return null;
            }
            String patch = skin.getResourcePatch();
            boolean slim = skin.getArmSize() == ArmSizeType.SLIM
                    || (patch != null && patch.toLowerCase(Locale.ROOT).contains("slim"));
            return new Skin(SignedSkins.hash(width, height, rgba), width, height, rgba, slim);
        }
    }

    private final JavaToBedrock proxy;
    private final ProxyConfig.Skins config;
    private final Path directory;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Map<String, Textures> known = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<@Nullable Textures>> pending = new ConcurrentHashMap<>();
    /** Skins that could not be signed during this run: not retried until the proxy restarts. */
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private ThreadPoolExecutor uploads;
    private volatile boolean mineSkinEnabled;

    public SignedSkins(JavaToBedrock proxy) {
        this.proxy = proxy;
        this.config = proxy.config().skins();
        this.directory = proxy.dataDirectory().resolve("cache").resolve("skins");
    }

    @Override
    public void start() throws IOException {
        Files.createDirectories(this.directory);
        this.mineSkinEnabled = this.config.enabled() && !this.config.mineskinApiKey().isBlank();
        this.uploads = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_PENDING_UPLOADS), runnable -> {
            Thread thread = new Thread(runnable, "JavaToBedrock skin uploads");
            thread.setDaemon(true);
            return thread;
        });
        if (this.config.enabled()) {
            LOGGER.info("Bedrock skins for Java players: {}{}", this.mineSkinEnabled ? "MineSkin" : "Java players' skins only",
                    this.config.geyserApi() ? " + GeyserMC global API" : "");
        }
    }

    @Override
    public void close() {
        if (this.uploads != null) {
            this.uploads.shutdownNow();
        }
    }

    /**
     * The signed textures already known for {@code skin}, without any I/O: what to put in the player
     * profile right away. When this is {@code null}, add the player without textures and call
     * {@link #resolve} to update the profile later.
     */
    public @Nullable Textures cached(Skin skin) {
        return this.config.enabled() ? this.known.get(skin.hash()) : null;
    }

    /**
     * Finds or creates signed textures for {@code skin}. Completes on a background thread, with
     * {@code null} when the skin cannot be signed (the player keeps the default Java skin); never
     * completes exceptionally. Concurrent requests for the same skin share one upload.
     *
     * @param xuid the player's Xbox user id from the player list, if any (GeyserMC lookup)
     */
    public CompletableFuture<@Nullable Textures> resolve(Skin skin, @Nullable String xuid) {
        if (!this.config.enabled()) {
            return CompletableFuture.completedFuture(null);
        }
        Textures known = this.known.get(skin.hash());
        if (known != null) {
            return CompletableFuture.completedFuture(known);
        }
        if (this.failed.contains(skin.hash())) {
            return CompletableFuture.completedFuture(null);
        }
        return this.pending.computeIfAbsent(skin.hash(), hash -> CompletableFuture
                .supplyAsync(() -> readCache(hash), this.proxy.workers())
                .thenCompose(cached -> cached != null ? CompletableFuture.completedFuture(cached) : upload(skin))
                .thenCompose(signed -> signed != null || xuid == null || xuid.isEmpty() || !this.config.geyserApi()
                        ? CompletableFuture.completedFuture(signed) : geyser(xuid))
                .exceptionally(error -> {
                    LOGGER.debug("Could not sign skin {}: {}", hash, error.toString());
                    return null;
                })
                .whenComplete((signed, error) -> {
                    if (signed != null) {
                        this.known.put(hash, signed);
                    } else {
                        this.failed.add(hash);
                    }
                    this.pending.remove(hash);
                }));
    }

    /**
     * Records the signed textures of a skin the proxy sends to the Bedrock server (a Java player's
     * Mojang skin), so that other Java players see that player with the original skin.
     */
    public void remember(int width, int height, byte[] rgba, Textures textures) {
        String hash = hash(width, height, rgba);
        if (textures.equals(this.known.put(hash, textures))) {
            return;
        }
        this.failed.remove(hash);
        this.proxy.workers().execute(() -> writeCache(hash, textures));
    }

    // ------------------------------------------------------------------ MineSkin

    private CompletableFuture<@Nullable Textures> upload(Skin skin) {
        if (!this.mineSkinEnabled) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<@Nullable Textures> result = new CompletableFuture<>();
        try {
            this.uploads.execute(() -> {
                try {
                    Textures signed = mineSkin(skin);
                    if (signed != null) {
                        writeCache(skin.hash(), signed);
                    }
                    result.complete(signed);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result.complete(null);
                } catch (Exception e) {
                    LOGGER.debug("MineSkin upload failed: {}", e.toString());
                    result.complete(null);
                }
            });
        } catch (RejectedExecutionException e) {
            result.complete(null);
        }
        return result;
    }

    /**
     * Queues the skin at MineSkin and waits for the job (blocking, on the upload thread). The API is
     * the one of the official client (github.com/InventivetalentDev/MineskinClient): {@code POST
     * /v2/queue} with a multipart {@code file}, then {@code GET /v2/queue/<job id>} about once a second,
     * the skin being in {@code skin.texture.data} of either response or at {@code GET /v2/skins/<uuid>}.
     */
    private @Nullable Textures mineSkin(Skin skin) throws IOException, InterruptedException {
        String boundary = "jtb-" + skin.hash().substring(0, 16);
        ByteArrayOutputStream body = new ByteArrayOutputStream(8192);
        formField(body, boundary, "variant", skin.slim() ? "slim" : "classic");
        formField(body, boundary, "visibility", "unlisted");
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(javaPng(skin));
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        JsonObject queued = mineSkinRequest(request("/v2/queue")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
        Textures signed = texturesOf(queued);
        if (signed != null || queued == null) {
            return signed;
        }
        JsonObject job = queued.getAsJsonObject("job");
        if (job == null || !job.has("id")) {
            return null;
        }
        String jobId = job.get("id").getAsString();
        Thread.sleep(2000);
        for (int attempt = 0; attempt < MAX_JOB_POLLS; attempt++) {
            JsonObject status = mineSkinRequest(request("/v2/queue/" + jobId).GET());
            if (status == null) {
                return null;
            }
            signed = texturesOf(status);
            if (signed != null) {
                return signed;
            }
            JsonObject state = status.getAsJsonObject("job");
            String value = state == null || !state.has("status") ? "unknown" : state.get("status").getAsString();
            if (value.equals("completed") && state.has("result") && !state.get("result").isJsonNull()) {
                return texturesOf(mineSkinRequest(request("/v2/skins/" + state.get("result").getAsString()).GET()));
            }
            if (value.equals("failed") || value.equals("unknown")) {
                return null;
            }
            Thread.sleep(1000);
        }
        return null;
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(MINESKIN + path))
                .timeout(TIMEOUT)
                .header("User-Agent", JavaToBedrock.NAME + "/" + JavaToBedrock.VERSION)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + this.config.mineskinApiKey().trim());
    }

    /**
     * Sends a MineSkin request, honouring the delay the API asks for before the next one. A rejected
     * API key turns MineSkin off for this run instead of failing every upload.
     */
    private @Nullable JsonObject mineSkinRequest(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<String> response = this.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            this.mineSkinEnabled = false;
            LOGGER.warn("MineSkin rejected the API key (HTTP {}): Bedrock skins will not be uploaded. Check skins.mineskin-api-key",
                    response.statusCode());
            return null;
        }
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(response.body());
            json = element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            json = null;
        }
        if (json == null) {
            return null;
        }
        JsonObject rateLimit = json.getAsJsonObject("rateLimit");
        if (rateLimit != null && rateLimit.has("next") && rateLimit.get("next").isJsonObject()) {
            JsonElement relative = rateLimit.getAsJsonObject("next").get("relative");
            if (relative != null && relative.isJsonPrimitive()) {
                Thread.sleep(Math.min(60_000, Math.max(0, relative.getAsLong())));
            }
        }
        if (response.statusCode() >= 400 || (json.has("success") && !json.get("success").getAsBoolean())) {
            LOGGER.debug("MineSkin answered HTTP {}: {}", response.statusCode(), json.get("errors"));
            return null;
        }
        return json;
    }

    /** {@code skin.texture.data.{value,signature}} of a MineSkin response. */
    private static @Nullable Textures texturesOf(@Nullable JsonObject response) {
        if (response == null || !response.has("skin") || !response.get("skin").isJsonObject()) {
            return null;
        }
        JsonObject texture = response.getAsJsonObject("skin").getAsJsonObject("texture");
        JsonObject data = texture == null ? null : texture.getAsJsonObject("data");
        return data == null ? null : textures(data);
    }

    private static void formField(ByteArrayOutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    /** The skin as a Java skin file: HD skins are scaled down to 64 pixels wide (nearest pixel). */
    static byte[] javaPng(Skin skin) throws IOException {
        BufferedImage image = Images.fromRgba(skin.rgba(), skin.width(), skin.height());
        if (skin.width() == 128) {
            BufferedImage scaled = new BufferedImage(64, skin.height() / 2, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < scaled.getHeight(); y++) {
                for (int x = 0; x < 64; x++) {
                    scaled.setRGB(x, y, image.getRGB(x * 2, y * 2));
                }
            }
            image = scaled;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ GeyserMC global API

    /**
     * {@code GET https://api.geysermc.org/v2/skin/<xuid>}: {@code {hash, is_steve, texture_id, value,
     * signature}}, or an empty object when no Geyser server has seen the player.
     */
    private CompletableFuture<@Nullable Textures> geyser(String xuid) {
        if (!xuid.chars().allMatch(Character::isDigit)) {
            return CompletableFuture.completedFuture(null);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(GEYSER_SKIN + xuid))
                .timeout(TIMEOUT)
                .header("User-Agent", JavaToBedrock.NAME + "/" + JavaToBedrock.VERSION)
                .GET()
                .build();
        return this.http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() != 200) {
                return null;
            }
            JsonElement element = JsonParser.parseString(response.body());
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject json = element.getAsJsonObject();
            if (json.has("is_steve") && json.get("is_steve").getAsBoolean()) {
                return null;
            }
            return textures(json);
        });
    }

    // ------------------------------------------------------------------ cache

    private static @Nullable Textures textures(JsonObject json) {
        JsonElement value = json.get("value");
        JsonElement signature = json.get("signature");
        if (value == null || signature == null || !value.isJsonPrimitive() || !signature.isJsonPrimitive()) {
            return null;
        }
        String v = value.getAsString();
        String s = signature.getAsString();
        return v.isEmpty() || s.isEmpty() ? null : new Textures(v, s);
    }

    private @Nullable Textures readCache(String hash) {
        Path file = this.directory.resolve(hash + ".json");
        try {
            if (Files.isRegularFile(file)) {
                return textures(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Unreadable skin cache entry {}: {}", file, e.toString());
        }
        return null;
    }

    private void writeCache(String hash, Textures textures) {
        JsonObject json = new JsonObject();
        json.addProperty("value", textures.value());
        json.addProperty("signature", textures.signature());
        try {
            Path temp = Files.createTempFile(this.directory, hash, ".tmp");
            Files.writeString(temp, json.toString(), StandardCharsets.UTF_8);
            Files.move(temp, this.directory.resolve(hash + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.debug("Could not cache skin {}: {}", hash, e.toString());
        }
    }

    static String hash(int width, int height, byte[] rgba) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((byte) (width >> 8));
            digest.update((byte) width);
            digest.update((byte) (height >> 8));
            digest.update((byte) height);
            digest.update(rgba);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
