package io.github.wyze3306.jtb.resourcepack.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A Bedrock geometry ({@code models/**}): bones with pivots, rotations and cubes, in Bedrock model
 * units (16 per block, Y up, model front towards -Z). Both syntaxes are read: {@code format_version}
 * 1.12+ ({@code "minecraft:geometry": [{description, bones}]}) and the legacy 1.8 one
 * ({@code "geometry.name": {texturewidth, bones}}) with its inheritance ({@code "geometry.child:geometry.parent"},
 * child bones replacing parent bones of the same name).
 *
 * @param identifier lower case
 */
public record Geometry(String identifier, int textureWidth, int textureHeight, List<Bone> bones) {

    /**
     * @param rotation   bind-pose rotation in degrees (Bedrock convention)
     * @param neverRender the bone's own cubes are hidden (its children are not)
     */
    public record Bone(String name, @Nullable String parent, float[] pivot, float[] rotation, boolean neverRender,
                       List<Cube> cubes) {
    }

    /**
     * @param origin   minimum corner
     * @param pivot    rotation centre ({@code null} = centre of the box)
     * @param boxUv    {@code [u, v]} of a box-UV cube, or null for per-face UV
     * @param faces    per-face UV by Bedrock face name ({@code north}...), null for box UV
     */
    public record Cube(float[] origin, float[] size, float @Nullable [] pivot, float[] rotation, float inflate,
                       boolean mirror, float @Nullable [] boxUv, @Nullable Map<String, FaceUv> faces) {
    }

    /** Per-face UV in texture pixels; {@code width}/{@code height} may be negative (flipped). */
    public record FaceUv(float u, float v, float width, float height, int rotation, @Nullable String materialInstance) {
    }

    /**
     * Every geometry defined in one {@code models/} file. Legacy definitions that inherit from
     * another ({@code child:parent}) are returned with their parent id so the caller can resolve them
     * across files.
     */
    static List<Parsed> parseFile(JsonObject json) {
        List<Parsed> out = new ArrayList<>();
        if (json.get("minecraft:geometry") instanceof JsonArray array) {
            for (JsonElement element : array) {
                if (!(element instanceof JsonObject geometry)) {
                    continue;
                }
                JsonObject description = geometry.get("description") instanceof JsonObject d ? d : new JsonObject();
                String id = string(description, "identifier");
                if (id.isEmpty()) {
                    continue;
                }
                out.add(new Parsed(id.toLowerCase(Locale.ROOT), null, integer(description, "texture_width", 0),
                        integer(description, "texture_height", 0), bones(geometry)));
            }
        }
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String key = entry.getKey();
            if (!key.toLowerCase(Locale.ROOT).startsWith("geometry.") || !(entry.getValue() instanceof JsonObject geometry)) {
                continue;
            }
            int colon = key.indexOf(':');
            String id = (colon < 0 ? key : key.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
            String parent = colon < 0 ? null : key.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
            out.add(new Parsed(id, parent, integer(geometry, "texturewidth", 0), integer(geometry, "textureheight", 0),
                    bones(geometry)));
        }
        return out;
    }

    /** A geometry as read from its file, before inheritance ({@code 0} texture size = inherited / default). */
    record Parsed(String identifier, @Nullable String parent, int textureWidth, int textureHeight, List<Bone> bones) {
    }

    /** Resolves inheritance: parent bones first, replaced by child bones of the same name. */
    static Map<String, Geometry> resolve(Map<String, Parsed> parsed) {
        Map<String, Geometry> out = new HashMap<>();
        for (String id : parsed.keySet()) {
            Geometry geometry = resolve(id, parsed, 0);
            if (geometry != null) {
                out.put(id, geometry);
            }
        }
        return out;
    }

    private static @Nullable Geometry resolve(String id, Map<String, Parsed> parsed, int depth) {
        Parsed geometry = parsed.get(id);
        if (geometry == null || depth > 16) {
            return null;
        }
        Geometry parent = geometry.parent() == null ? null : resolve(geometry.parent(), parsed, depth + 1);
        int width = geometry.textureWidth() > 0 ? geometry.textureWidth() : parent != null ? parent.textureWidth() : 64;
        int height = geometry.textureHeight() > 0 ? geometry.textureHeight() : parent != null ? parent.textureHeight() : 64;
        if (parent == null) {
            return new Geometry(id, width, height, geometry.bones());
        }
        Map<String, Bone> bones = new LinkedHashMap<>();
        parent.bones().forEach(bone -> bones.put(bone.name().toLowerCase(Locale.ROOT), bone));
        geometry.bones().forEach(bone -> bones.put(bone.name().toLowerCase(Locale.ROOT), bone));
        return new Geometry(id, width, height, List.copyOf(bones.values()));
    }

    private static List<Bone> bones(JsonObject geometry) {
        List<Bone> bones = new ArrayList<>();
        if (!(geometry.get("bones") instanceof JsonArray array)) {
            return bones;
        }
        for (JsonElement element : array) {
            if (!(element instanceof JsonObject bone)) {
                continue;
            }
            String name = string(bone, "name");
            boolean mirror = bool(bone, "mirror");
            float inflate = number(bone, "inflate", 0);
            List<Cube> cubes = new ArrayList<>();
            if (bone.get("cubes") instanceof JsonArray cubeArray) {
                for (JsonElement cubeElement : cubeArray) {
                    if (cubeElement instanceof JsonObject cube) {
                        Cube parsed = cube(cube, mirror, inflate);
                        if (parsed != null) {
                            cubes.add(parsed);
                        }
                    }
                }
            }
            String parent = string(bone, "parent");
            bones.add(new Bone(name, parent.isEmpty() ? null : parent, vector(bone.get("pivot")), vector(bone.get("rotation")),
                    bool(bone, "neverRender") || bool(bone, "never_render"), List.copyOf(cubes)));
        }
        return bones;
    }

    private static @Nullable Cube cube(JsonObject cube, boolean boneMirror, float boneInflate) {
        if (!cube.has("origin") || !cube.has("size")) {
            return null;
        }
        float[] pivot = cube.has("pivot") ? vector(cube.get("pivot")) : null;
        boolean mirror = cube.has("mirror") ? bool(cube, "mirror") : boneMirror;
        float inflate = cube.has("inflate") ? number(cube, "inflate", 0) : boneInflate;
        JsonElement uv = cube.get("uv");
        float[] boxUv = null;
        Map<String, FaceUv> faces = null;
        if (uv instanceof JsonObject perFace) {
            faces = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> face : perFace.entrySet()) {
                if (face.getValue() instanceof JsonObject f && f.get("uv") instanceof JsonArray) {
                    float[] start = vector2(f.get("uv"));
                    float[] size = f.has("uv_size") ? vector2(f.get("uv_size")) : new float[]{0, 0};
                    String material = string(f, "material_instance");
                    faces.put(face.getKey().toLowerCase(Locale.ROOT), new FaceUv(start[0], start[1], size[0], size[1],
                            (int) number(f, "uv_rotation", 0), material.isEmpty() ? null : material));
                }
            }
        } else {
            boxUv = uv instanceof JsonArray ? vector2(uv) : new float[]{0, 0};
        }
        return new Cube(vector(cube.get("origin")), vector(cube.get("size")), pivot, vector(cube.get("rotation")),
                inflate, mirror, boxUv, faces);
    }

    static float[] vector(@Nullable JsonElement element) {
        float[] out = new float[3];
        if (element instanceof JsonArray array) {
            for (int i = 0; i < 3 && i < array.size(); i++) {
                out[i] = asFloat(array.get(i));
            }
        }
        return out;
    }

    private static float[] vector2(@Nullable JsonElement element) {
        float[] out = new float[2];
        if (element instanceof JsonArray array) {
            for (int i = 0; i < 2 && i < array.size(); i++) {
                out[i] = asFloat(array.get(i));
            }
        }
        return out;
    }

    private static float asFloat(JsonElement element) {
        try {
            return element.isJsonPrimitive() ? element.getAsFloat() : 0;
        } catch (NumberFormatException e) {
            // Molang expressions in static geometry are not supported.
            return 0;
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        try {
            return element != null && element.isJsonPrimitive() ? element.getAsInt() : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float number(JsonObject object, String key, float fallback) {
        JsonElement element = object.get(key);
        try {
            return element != null && element.isJsonPrimitive() ? element.getAsFloat() : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive()
                && (element.getAsJsonPrimitive().isBoolean() ? element.getAsBoolean() : "true".equalsIgnoreCase(element.getAsString()));
    }
}
