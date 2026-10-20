package io.github.wyze3306.jtb.resourcepack.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.wyze3306.jtb.resourcepack.ConversionArtifacts;
import io.github.wyze3306.jtb.resourcepack.JavaAssetNames;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Everything a converter step works with: the server's packs and their merged definitions, the
 * static tables, the Java pack being written and the artifacts published to the translators.
 * Shared texture helpers make sure a Bedrock texture used by several models is written once.
 */
public final class ConversionContext {

    /** Atlas directory of a texture written for a custom asset. */
    public enum TextureKind {
        /** {@code assets/jtb/textures/block/...}: block models (blocks atlas). */
        BLOCK("block"),
        /** {@code assets/jtb/textures/item/...}: item, entity and attachable models (items atlas). */
        ITEM("item");

        final String directory;

        TextureKind(String directory) {
            this.directory = directory;
        }
    }

    private final PackStack stack;
    private final BedrockDefinitions definitions;
    private final ResourceTables tables;
    private final JavaPackWriter writer;
    private final ConversionArtifacts artifacts;
    private final Map<String, Optional<String>> textures = new ConcurrentHashMap<>();
    /** Vanilla textures outside the block and item atlases that models reference (see {@link #writeAtlases()}). */
    private final Set<String> extraSprites = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    public ConversionContext(PackStack stack, BedrockDefinitions definitions, ResourceTables tables, JavaPackWriter writer,
                             ConversionArtifacts artifacts) {
        this.stack = stack;
        this.definitions = definitions;
        this.tables = tables;
        this.writer = writer;
        this.artifacts = artifacts;
    }

    /**
     * A context for a second phase built on this one: copies of the pack files and artifacts written
     * so far (this context is left unchanged, several derived contexts may coexist), fresh counters.
     */
    ConversionContext derive() {
        ConversionContext derived = new ConversionContext(this.stack, this.definitions, this.tables,
                new JavaPackWriter(this.writer), this.artifacts.copy());
        derived.textures.putAll(this.textures);
        derived.extraSprites.addAll(this.extraSprites);
        return derived;
    }

    public PackStack stack() {
        return this.stack;
    }

    public BedrockDefinitions definitions() {
        return this.definitions;
    }

    public ResourceTables tables() {
        return this.tables;
    }

    public JavaPackWriter writer() {
        return this.writer;
    }

    public ConversionArtifacts artifacts() {
        return this.artifacts;
    }

    /** Counts something for the conversion summary. */
    public void count(String what) {
        count(what, 1);
    }

    public void count(String what, int amount) {
        this.counters.computeIfAbsent(what, k -> new AtomicInteger()).addAndGet(amount);
    }

    /** The counters, sorted by name. */
    public Map<String, Integer> counters() {
        Map<String, Integer> out = new TreeMap<>();
        this.counters.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    /**
     * The Java texture id for a Bedrock texture path ({@code textures/items/ruby}, no extension):
     * when a server pack has the image it is written as {@code jtb:<kind>/<path>} (with its flipbook
     * animation), else a vanilla Bedrock texture maps to its Java vanilla texture.
     *
     * @return null when neither exists
     */
    public @Nullable String texture(String bedrockPath, TextureKind kind) {
        String path = bedrockPath.toLowerCase(Locale.ROOT);
        return this.textures.computeIfAbsent(kind.directory + "|" + path, key -> Optional.ofNullable(resolveTexture(path, kind)))
                .orElse(null);
    }

    private @Nullable String resolveTexture(String path, TextureKind kind) {
        PackFiles.PackFile image = this.stack.image(path);
        if (image != null) {
            byte[] png = PackImages.toPng(image.path(), image.data());
            int[] size = png == null ? null : PackImages.size(image.path(), png);
            if (png != null && size != null) {
                String relative = path.startsWith("textures/") ? path.substring("textures/".length()) : path;
                String javaPath = kind.directory + "/" + JavaAssetNames.path(relative);
                String file = "assets/" + JavaAssetNames.NAMESPACE + "/textures/" + javaPath + ".png";
                this.writer.put(file, png);
                JsonObject animation = animation(this.definitions.flipbook(path), size[0], size[1]);
                if (animation != null) {
                    this.writer.putJson(file + ".mcmeta", animation);
                }
                count("custom textures");
                return JavaAssetNames.NAMESPACE + ":" + javaPath;
            }
            count("unreadable images");
        }
        String relative = path.startsWith("textures/") ? path.substring("textures/".length()) : path;
        var vanilla = this.tables.textures().get(relative);
        if (vanilla == null || vanilla.isEmpty()) {
            return null;
        }
        String texture = "minecraft:" + vanilla.getFirst().path();
        if (!vanilla.getFirst().path().startsWith("block/") && !vanilla.getFirst().path().startsWith("item/")) {
            this.extraSprites.add(texture);
        }
        return texture;
    }

    /**
     * Adds the vanilla textures models use from outside the block and item atlases (an entity
     * texture reused by a custom entity, for instance) to both atlases: the client merges these
     * atlas files with the vanilla ones, so the sprites exist when the models are baked.
     */
    void writeAtlases() {
        if (this.extraSprites.isEmpty()) {
            return;
        }
        JsonArray sources = new JsonArray();
        for (String sprite : new TreeSet<>(this.extraSprites)) {
            JsonObject source = new JsonObject();
            source.addProperty("type", "minecraft:single");
            source.addProperty("resource", sprite);
            sources.add(source);
        }
        JsonObject atlas = new JsonObject();
        atlas.add("sources", sources);
        this.writer.putJson("assets/minecraft/atlases/blocks.json", atlas);
        this.writer.putJson("assets/minecraft/atlases/items.json", atlas);
    }

    /**
     * The {@code .png.mcmeta} of a flipbook for a {@code width x height} strip of square frames, or
     * null when there is no flipbook or the image holds a single frame. Frame indices beyond the
     * strip are dropped (a pack may replace an animated texture with fewer frames).
     */
    public static @Nullable JsonObject animation(ResourceTables.@Nullable Flipbook flipbook, int width, int height) {
        if (flipbook == null || width <= 0 || height % width != 0 || height / width < 2) {
            return null;
        }
        int frameCount = height / width;
        JsonObject animation = new JsonObject();
        animation.addProperty("frametime", Math.max(1, flipbook.ticks() == null ? 1 : flipbook.ticks()));
        animation.addProperty("interpolate", flipbook.blend() == null || flipbook.blend());
        if (flipbook.frames() != null) {
            JsonArray frames = new JsonArray();
            for (int frame : flipbook.frames()) {
                if (frame >= 0 && frame < frameCount) {
                    frames.add(frame);
                }
            }
            if (frames.isEmpty()) {
                return null;
            }
            animation.add("frames", frames);
        }
        JsonObject mcmeta = new JsonObject();
        mcmeta.add("animation", animation);
        return mcmeta;
    }
}
