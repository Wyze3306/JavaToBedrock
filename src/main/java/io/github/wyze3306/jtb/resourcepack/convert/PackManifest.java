package io.github.wyze3306.jtb.resourcepack.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The parts of a Bedrock {@code manifest.json} the converter uses. Format versions 1 and 2 write
 * versions as int arrays ({@code [1, 0, 0]}), version 3 as strings ({@code "1.0.0"}).
 *
 * @param name        header name, possibly a lang key ({@code pack.name}) resolved by the pack's texts
 * @param moduleTypes module types ({@code resources}, {@code data}, {@code script}...), lower case
 */
public record PackManifest(int formatVersion, @Nullable UUID uuid, String version, String name, String description,
                           Set<String> moduleTypes, List<Subpack> subpacks) {

    /** A selectable variant of the pack, overlaid from {@code subpacks/<folderName>/}. */
    public record Subpack(String folderName, String name) {
    }

    /** True for packs with a {@code resources} module (or no module list at all). */
    public boolean isResourcePack() {
        return this.moduleTypes.isEmpty() || this.moduleTypes.contains("resources");
    }

    static PackManifest parse(JsonObject json) {
        int format = json.has("format_version") && json.get("format_version").isJsonPrimitive()
                ? json.get("format_version").getAsInt() : 1;
        JsonObject header = json.get("header") instanceof JsonObject object ? object : new JsonObject();
        UUID uuid = null;
        try {
            uuid = header.has("uuid") ? UUID.fromString(header.get("uuid").getAsString().trim()) : null;
        } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
            // A broken uuid does not prevent the conversion: the id comes from the server anyway.
        }
        Set<String> modules = new LinkedHashSet<>();
        if (json.has("modules") && json.get("modules").isJsonArray()) {
            for (JsonElement module : json.getAsJsonArray("modules")) {
                if (module.isJsonObject() && module.getAsJsonObject().has("type")) {
                    modules.add(module.getAsJsonObject().get("type").getAsString().toLowerCase(Locale.ROOT));
                }
            }
        }
        List<Subpack> subpacks = new ArrayList<>();
        if (json.has("subpacks") && json.get("subpacks").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("subpacks")) {
                if (element.isJsonObject()) {
                    JsonObject subpack = element.getAsJsonObject();
                    String folder = string(subpack, "folder_name");
                    if (!folder.isEmpty()) {
                        subpacks.add(new Subpack(folder, string(subpack, "name")));
                    }
                }
            }
        }
        return new PackManifest(format, uuid, version(header.get("version")), string(header, "name"),
                string(header, "description"), modules, subpacks);
    }

    private static String version(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            StringBuilder out = new StringBuilder();
            for (JsonElement part : array) {
                if (!out.isEmpty()) {
                    out.append('.');
                }
                out.append(part.isJsonPrimitive() ? part.getAsString() : "0");
            }
            return out.toString();
        }
        return element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }
}
