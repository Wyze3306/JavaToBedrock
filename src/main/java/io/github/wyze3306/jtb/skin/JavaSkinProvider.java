package io.github.wyze3306.jtb.skin;

import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.auth.texture.Texture;
import org.geysermc.mcprotocollib.auth.texture.TextureModel;
import org.geysermc.mcprotocollib.auth.texture.TextureType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Turns a Java player's Mojang skin (and cape) into a Bedrock skin. Java and Bedrock classic skins
 * share the same 64x64 UV layout, so the pixels are copied as is; only the geometry name differs
 * between wide and slim arms.
 */
public final class JavaSkinProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(JavaSkinProvider.class);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /** Skin downloads by texture hash: the same skin is fetched once per proxy run. */
    private static final Map<String, CompletableFuture<byte[]>> CACHE = new ConcurrentHashMap<>();

    private JavaSkinProvider() {
    }

    /**
     * The Bedrock skin of {@code profile}. Never fails: falls back to a generated default skin when the
     * profile has no skin or the download fails or takes more than a few seconds.
     */
    public static CompletableFuture<BedrockSkinData> forProfile(GameProfile profile, boolean enabled) {
        if (!enabled) {
            return CompletableFuture.completedFuture(DefaultSkins.forUuid(profile.getId()));
        }
        Map<TextureType, Texture> textures;
        try {
            textures = profile.getTextures(false);
        } catch (IllegalStateException e) {
            return CompletableFuture.completedFuture(DefaultSkins.forUuid(profile.getId()));
        }
        Texture skin = textures.get(TextureType.SKIN);
        if (skin == null) {
            return CompletableFuture.completedFuture(DefaultSkins.forUuid(profile.getId()));
        }
        boolean slim = skin.getModel() == TextureModel.SLIM;
        Texture cape = textures.get(TextureType.CAPE);
        CompletableFuture<byte[]> skinBytes = download(skin);
        CompletableFuture<byte[]> capeBytes = cape == null ? CompletableFuture.completedFuture(null) : download(cape);
        UUID id = profile.getId();
        return skinBytes.thenCombine(capeBytes, (skinPng, capePng) -> {
                    BedrockSkinData data = decode(skin.getHash(), skinPng, capePng, slim);
                    return data != null ? data : DefaultSkins.forUuid(id);
                })
                .completeOnTimeout(DefaultSkins.forUuid(id), 6, TimeUnit.SECONDS)
                .exceptionally(t -> DefaultSkins.forUuid(id));
    }

    private static CompletableFuture<byte[]> download(Texture texture) {
        return CACHE.computeIfAbsent(texture.getHash(), hash -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(texture.getURL()))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(response -> {
                        if (response.statusCode() != 200) {
                            throw new IllegalStateException("HTTP " + response.statusCode() + " for " + texture.getURL());
                        }
                        return response.body();
                    })
                    .whenComplete((bytes, error) -> {
                        if (error != null) {
                            CACHE.remove(hash);
                            LOGGER.debug("Skin download failed: {}", error.toString());
                        }
                    });
        });
    }

    static BedrockSkinData decode(String hash, byte[] skinPng, byte[] capePng, boolean slim) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(skinPng));
            if (image == null || image.getWidth() != 64 || (image.getHeight() != 64 && image.getHeight() != 32)) {
                return null;
            }
            byte[] cape = new byte[0];
            if (capePng != null) {
                BufferedImage capeImage = ImageIO.read(new ByteArrayInputStream(capePng));
                if (capeImage != null) {
                    cape = Images.toRgba(Images.crop(capeImage, 64, 32));
                }
            }
            return new BedrockSkinData("jtb-" + hash + (slim ? ".slim" : ".wide"), image.getWidth(), image.getHeight(),
                    Images.toRgba(image), slim, cape);
        } catch (IOException e) {
            return null;
        }
    }
}
