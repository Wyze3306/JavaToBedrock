package io.github.wyze3306.jtb.assets;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bedrock vanilla client assets (language files, vanilla textures, entity models...) fetched on
 * demand from Mojang's official {@code bedrock-samples} repository at the tag matching the
 * supported version, and cached on disk. Nothing from the game is redistributed with the proxy.
 * <p>
 * Files are fetched synchronously on the calling thread: call from a worker, never from a session
 * event loop or a Netty thread.
 */
public final class BedrockAssets {

    private static final Logger LOGGER = LoggerFactory.getLogger(BedrockAssets.class);
    public static final String VERSION_TAG = "v1.26.50.4";
    private static final String BASE_URL = "https://raw.githubusercontent.com/Mojang/bedrock-samples/" + VERSION_TAG + "/";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static volatile Path cacheDirectory = Path.of("cache", "bedrock-assets", VERSION_TAG);
    private static final Map<String, Boolean> MISSING = new ConcurrentHashMap<>();

    private BedrockAssets() {
    }

    public static void setCacheRoot(Path root) {
        cacheDirectory = root.resolve("bedrock-assets").resolve(VERSION_TAG);
    }

    /**
     * Returns the local copy of {@code path} (relative to the repository root, e.g.
     * {@code resource_pack/texts/en_US.lang}), downloading it first if needed.
     *
     * @return the file, or null when it does not exist upstream or cannot be downloaded
     */
    public static @Nullable Path get(String path) {
        if (path.contains("..")) {
            throw new IllegalArgumentException("Invalid asset path " + path);
        }
        Path local = cacheDirectory.resolve(path);
        if (Files.isRegularFile(local)) {
            return local;
        }
        if (MISSING.containsKey(path)) {
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                MISSING.put(path, Boolean.TRUE);
                return null;
            }
            Files.createDirectories(local.getParent());
            Path temp = Files.createTempFile(local.getParent(), "download", ".tmp");
            Files.write(temp, response.body());
            Files.move(temp, local, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return local;
        } catch (IOException e) {
            LOGGER.warn("Could not download Bedrock asset {}: {}", path, e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
