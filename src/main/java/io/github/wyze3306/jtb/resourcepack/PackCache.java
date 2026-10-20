package io.github.wyze3306.jtb.resourcepack;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Disk cache of downloaded Bedrock packs ({@code bedrock/<id>_<version>.zip}), converted Java
 * packs ({@code java/<sha1>.zip}) and the conversion index ({@code index/<key>}: conversion key ->
 * Java pack SHA-1, {@code index/stack-<fingerprint>} -> last conversion key of a stack).
 */
public final class PackCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(PackCache.class);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Path root;

    public PackCache(Path root) {
        this.root = root;
    }

    public Path root() {
        return this.root;
    }

    private Path bedrockFile(UUID id, String version) {
        return this.root.resolve("bedrock").resolve(id + "_" + version.replaceAll("[^0-9A-Za-z._-]", "_") + ".zip");
    }

    /** The cached pack, or null when absent or when its size does not match what the server announced. */
    public byte @Nullable [] readBedrockPack(UUID id, String version, long expectedSize) {
        Path file = bedrockFile(id, version);
        try {
            if (Files.isRegularFile(file) && (expectedSize <= 0 || Files.size(file) == expectedSize)) {
                return Files.readAllBytes(file);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read cached pack {}", file, e);
        }
        return null;
    }

    public void writeBedrockPack(UUID id, String version, byte[] zip) {
        write(bedrockFile(id, version), zip);
    }

    public byte @Nullable [] readJavaPack(String sha1) {
        Path file = this.root.resolve("java").resolve(sha1 + ".zip");
        try {
            return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        } catch (IOException e) {
            return null;
        }
    }

    public boolean hasJavaPack(String sha1) {
        return Files.isRegularFile(this.root.resolve("java").resolve(sha1 + ".zip"));
    }

    public void writeJavaPack(String sha1, byte[] zip) {
        write(this.root.resolve("java").resolve(sha1 + ".zip"), zip);
    }

    /** Small key/value files (conversion index: input fingerprint -> output sha1). */
    public @Nullable String readIndex(String key) {
        Path file = this.root.resolve("index").resolve(key);
        try {
            return Files.isRegularFile(file) ? Files.readString(file).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    public void writeIndex(String key, String value) {
        write(this.root.resolve("index").resolve(key), value.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Path file, byte[] data) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "pack", ".tmp");
            Files.write(temp, data);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("Could not write {}", file, e);
        }
    }

    public CompletableFuture<byte[]> downloadCdn(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).GET().build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).thenApply(response -> {
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            return response.body();
        });
    }
}
