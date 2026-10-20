package io.github.wyze3306.jtb.resourcepack.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.wyze3306.jtb.text.BedrockLang;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The definition files of the server's packs, merged over the stack (bottom to top: a higher pack
 * replaces an entry of the same name): texture atlases, flipbooks, geometries, client entities,
 * attachables, render controllers, sound definitions and translations. Only server packs are read,
 * so everything here was added or changed by the server; vanilla flipbooks are merged underneath
 * because the Bedrock client applies them to overridden textures too.
 * <p>
 * Texture paths keep Bedrock's form ({@code textures/blocks/stone}, lower case, no extension).
 */
public final class BedrockDefinitions {

    private static final Logger LOGGER = LoggerFactory.getLogger(BedrockDefinitions.class);

    private final Map<String, List<String>> terrainTextures = new HashMap<>();
    private final Map<String, List<String>> itemTextures = new HashMap<>();
    private final Map<String, ResourceTables.Flipbook> flipbooks = new HashMap<>();
    private final Map<String, Geometry> geometries;
    private final Map<String, JsonObject> clientEntities = new TreeMap<>();
    private final Map<String, JsonObject> attachables = new TreeMap<>();
    private final Map<String, JsonObject> renderControllers = new HashMap<>();
    private final Map<String, JsonObject> soundDefinitions = new TreeMap<>();
    private final Map<String, Map<String, String>> languages = new TreeMap<>();

    BedrockDefinitions(PackStack stack, ResourceTables tables) {
        tables.flipbooks().forEach((path, flipbook) -> this.flipbooks.put("textures/" + path, flipbook));
        Map<String, Geometry.Parsed> parsedGeometries = new HashMap<>();
        for (PackFiles pack : stack.bottomToTop()) {
            readAtlas(pack, "textures/terrain_texture.json", "atlas.terrain", this.terrainTextures);
            readAtlas(pack, "textures/item_texture.json", "atlas.items", this.itemTextures);
            readFlipbooks(pack);
            for (String path : pack.list("models/")) {
                if (path.endsWith(".json") && pack.json(path) instanceof JsonObject json) {
                    for (Geometry.Parsed geometry : Geometry.parseFile(json)) {
                        parsedGeometries.put(geometry.identifier(), geometry);
                    }
                }
            }
            readDescriptions(pack, "entity/", "minecraft:client_entity", this.clientEntities);
            readDescriptions(pack, "attachables/", "minecraft:attachable", this.attachables);
            for (String path : pack.list("render_controllers/")) {
                if (path.endsWith(".json") && pack.json(path) instanceof JsonObject json
                        && json.get("render_controllers") instanceof JsonObject controllers) {
                    controllers.entrySet().forEach(e -> {
                        if (e.getValue() instanceof JsonObject controller) {
                            this.renderControllers.put(e.getKey().toLowerCase(Locale.ROOT), controller);
                        }
                    });
                }
            }
            readSounds(pack);
            readLanguages(pack);
        }
        this.geometries = Geometry.resolve(parsedGeometries);
    }

    private static void readAtlas(PackFiles pack, String path, String expectedName, Map<String, List<String>> into) {
        JsonObject atlas = pack.json(path);
        if (atlas == null) {
            return;
        }
        JsonElement name = atlas.get("texture_name");
        if (name != null && name.isJsonPrimitive() && !name.getAsString().equalsIgnoreCase(expectedName)) {
            return;
        }
        if (!(atlas.get("texture_data") instanceof JsonObject data)) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
            List<String> paths = atlasPaths(entry.getValue());
            if (!paths.isEmpty()) {
                into.put(entry.getKey().toLowerCase(Locale.ROOT), paths);
            }
        }
    }

    /**
     * Texture paths of an atlas entry: {@code "textures": "path"}, a list of paths, objects
     * ({@code {"path": ..., "overlay_color": ...}}) or {@code {"variations": [{"path": ...}]}}
     * (first variation).
     */
    private static List<String> atlasPaths(JsonElement entry) {
        if (!(entry instanceof JsonObject object)) {
            return List.of();
        }
        JsonElement textures = object.get("textures");
        List<String> out = new ArrayList<>();
        if (textures instanceof JsonArray array) {
            array.forEach(t -> out.add(texturePath(t)));
        } else if (textures != null) {
            out.add(texturePath(textures));
        }
        return out;
    }

    private static String texturePath(JsonElement texture) {
        if (texture.isJsonPrimitive()) {
            return texture.getAsString().toLowerCase(Locale.ROOT);
        }
        if (texture instanceof JsonObject object) {
            if (object.get("path") instanceof JsonElement path && path.isJsonPrimitive()) {
                return path.getAsString().toLowerCase(Locale.ROOT);
            }
            if (object.get("variations") instanceof JsonArray variations && !variations.isEmpty()) {
                return texturePath(variations.get(0));
            }
        }
        return "";
    }

    private void readFlipbooks(PackFiles pack) {
        if (!(pack.jsonElement("textures/flipbook_textures.json") instanceof JsonArray array)) {
            return;
        }
        for (JsonElement element : array) {
            if (!(element instanceof JsonObject flipbook) || !(flipbook.get("flipbook_texture") instanceof JsonElement texture)
                    || !texture.isJsonPrimitive()) {
                continue;
            }
            JsonObject normalised = new JsonObject();
            if (flipbook.has("ticks_per_frame")) {
                normalised.add("ticks", flipbook.get("ticks_per_frame"));
            }
            if (flipbook.has("frames")) {
                normalised.add("frames", flipbook.get("frames"));
            }
            if (flipbook.has("blend_frames")) {
                normalised.add("blend", flipbook.get("blend_frames"));
            }
            this.flipbooks.put(texture.getAsString().toLowerCase(Locale.ROOT), ResourceTables.flipbook(normalised));
        }
    }

    private static void readDescriptions(PackFiles pack, String folder, String rootKey, Map<String, JsonObject> into) {
        for (String path : pack.list(folder)) {
            if (!path.endsWith(".json") || !(pack.json(path) instanceof JsonObject json)
                    || !(json.get(rootKey) instanceof JsonObject root)
                    || !(root.get("description") instanceof JsonObject description)
                    || !(description.get("identifier") instanceof JsonElement id) || !id.isJsonPrimitive()) {
                continue;
            }
            into.put(id.getAsString(), description);
        }
    }

    private void readSounds(PackFiles pack) {
        JsonObject json = pack.json("sounds/sound_definitions.json");
        if (json == null) {
            return;
        }
        JsonObject definitions = json.get("sound_definitions") instanceof JsonObject wrapped ? wrapped : json;
        for (Map.Entry<String, JsonElement> entry : definitions.entrySet()) {
            if (entry.getValue() instanceof JsonObject definition) {
                this.soundDefinitions.put(entry.getKey(), definition);
            }
        }
    }

    private void readLanguages(PackFiles pack) {
        for (String path : pack.list("texts/")) {
            if (!path.endsWith(".lang") || path.indexOf('/', "texts/".length()) >= 0) {
                continue;
            }
            String code = languageCode(path.substring("texts/".length(), path.length() - ".lang".length()));
            if (code == null) {
                continue;
            }
            String text = new String(pack.get(path), StandardCharsets.UTF_8);
            Map<String, String> entries = BedrockLang.parse(List.of(text.split("\r?\n")));
            if (!entries.isEmpty()) {
                this.languages.computeIfAbsent(code, k -> new TreeMap<>()).putAll(entries);
            }
        }
    }

    /** {@code fr_fr} -> {@code fr_FR}; null when the file name is not a language code. */
    static @Nullable String languageCode(String name) {
        int underscore = name.indexOf('_');
        if (underscore != 2 && underscore != 3 || name.length() < underscore + 3) {
            return null;
        }
        return name.substring(0, underscore).toLowerCase(Locale.ROOT) + "_" + name.substring(underscore + 1).toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ access

    /** {@code terrain_texture.json} short names (lower case) -> texture paths, from the server's packs. */
    public Map<String, List<String>> terrainTextures() {
        return Collections.unmodifiableMap(this.terrainTextures);
    }

    /** {@code item_texture.json} short names (lower case) -> texture paths, from the server's packs. */
    public Map<String, List<String>> itemTextures() {
        return Collections.unmodifiableMap(this.itemTextures);
    }

    /** Flipbook of a texture path ({@code textures/blocks/x}), vanilla ones included. */
    public ResourceTables.@Nullable Flipbook flipbook(String texturePath) {
        return this.flipbooks.get(texturePath.toLowerCase(Locale.ROOT));
    }

    public @Nullable Geometry geometry(String identifier) {
        return this.geometries.get(identifier.toLowerCase(Locale.ROOT));
    }

    public int geometryCount() {
        return this.geometries.size();
    }

    /** Client entity descriptions by identifier. */
    public Map<String, JsonObject> clientEntities() {
        return Collections.unmodifiableMap(this.clientEntities);
    }

    /** Attachable descriptions by identifier (the item they are attached to). */
    public Map<String, JsonObject> attachables() {
        return Collections.unmodifiableMap(this.attachables);
    }

    public @Nullable JsonObject renderController(String name) {
        return this.renderControllers.get(name.toLowerCase(Locale.ROOT));
    }

    /** Sound event -> definition ({@code sounds}, {@code category}, {@code max_distance}...). */
    public Map<String, JsonObject> soundDefinitions() {
        return Collections.unmodifiableMap(this.soundDefinitions);
    }

    /** Bedrock language code ({@code en_US}) -> translations added or changed by the packs. */
    public Map<String, Map<String, String>> languages() {
        return Collections.unmodifiableMap(this.languages);
    }

    /** {@code key} in {@code code}, falling back to en_US, or null. */
    public @Nullable String translate(String code, String key) {
        Map<String, String> language = this.languages.get(code);
        String value = language == null ? null : language.get(key);
        if (value == null && this.languages.get("en_US") instanceof Map<String, String> english) {
            value = english.get(key);
        }
        return value;
    }

    /** Ordered map helper for readers that need deterministic iteration over a JSON object. */
    static Map<String, JsonElement> entries(@Nullable JsonElement element) {
        Map<String, JsonElement> out = new LinkedHashMap<>();
        if (element instanceof JsonObject object) {
            object.entrySet().forEach(e -> out.put(e.getKey(), e.getValue()));
        }
        return out;
    }

    static void logSummary(BedrockDefinitions definitions) {
        LOGGER.debug("Pack definitions: {} terrain textures, {} item textures, {} geometries, {} entities, {} attachables, "
                        + "{} sounds, {} languages", definitions.terrainTextures.size(), definitions.itemTextures.size(),
                definitions.geometries.size(), definitions.clientEntities.size(), definitions.attachables.size(),
                definitions.soundDefinitions.size(), definitions.languages.size());
    }
}
