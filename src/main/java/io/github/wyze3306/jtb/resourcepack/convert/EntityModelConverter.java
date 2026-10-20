package io.github.wyze3306.jtb.resourcepack.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.wyze3306.jtb.resourcepack.ConversionArtifacts;
import io.github.wyze3306.jtb.resourcepack.JavaAssetNames;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Custom entities (client entity definitions of the server's packs outside the {@code minecraft}
 * namespace): every (geometry, texture) pair of the definition is baked into one item model, bone
 * transforms included ({@link GeometryBaker}), shown in game by an {@code item_display}
 * (ViaBedrock's approach, without its loss of bone rotations). The pair the render controllers
 * select by default is evaluated with literals only ({@code Geometry.x}, {@code Texture.x},
 * {@code Array.x[...]} -> first element); bones the default controller hides with a literal
 * {@code false} are left out.
 * <p>
 * Published as {@link ConversionArtifacts.EntityModel}s for the entity translator.
 */
final class EntityModelConverter {

    /** Caps the (geometry x texture) combinations of one entity. */
    private static final int MAX_VARIANTS = 16;

    private EntityModelConverter() {
    }

    static void convert(ConversionContext context) {
        for (Map.Entry<String, JsonObject> entity : context.definitions().clientEntities().entrySet()) {
            String identifier = entity.getKey();
            if (identifier.toLowerCase(Locale.ROOT).startsWith("minecraft:")) {
                continue;
            }
            ConversionArtifacts.EntityModel model = convert(context, identifier, entity.getValue());
            if (model != null) {
                context.artifacts().putEntityModel(identifier, model);
                context.count("entity models");
            }
        }
    }

    private static ConversionArtifacts.@Nullable EntityModel convert(ConversionContext context, String identifier,
                                                                     JsonObject description) {
        Map<String, String> geometries = stringMap(description.get("geometry"));
        Map<String, String> textures = stringMap(description.get("textures"));
        if (geometries.isEmpty() || textures.isEmpty()) {
            return null;
        }
        JsonObject controller = defaultController(context, description);
        String geometryKey = controller == null ? null
                : reference(controller.get("geometry"), controller, "geometries", "geometry.");
        String textureKey = null;
        if (controller != null && controller.get("textures") instanceof JsonArray list && !list.isEmpty()) {
            textureKey = reference(list.get(0), controller, "textures", "texture.");
        }
        geometryKey = pick(geometryKey, geometries);
        textureKey = pick(textureKey, textures);
        Set<String> hidden = controller == null ? Set.of() : hiddenBones(controller);

        Set<String> pairs = new LinkedHashSet<>();
        pairs.add(geometryKey + "|" + textureKey);
        for (String g : geometries.keySet()) {
            for (String t : textures.keySet()) {
                if (pairs.size() < MAX_VARIANTS) {
                    pairs.add(g + "|" + t);
                }
            }
        }
        Map<String, ConversionArtifacts.Variant> variants = new LinkedHashMap<>();
        String defaultVariant = null;
        for (String pair : pairs) {
            String g = pair.substring(0, pair.indexOf('|'));
            String t = pair.substring(pair.indexOf('|') + 1);
            Geometry geometry = context.definitions().geometry(geometries.get(g));
            String texture = context.texture(textures.get(t), ConversionContext.TextureKind.ITEM);
            if (geometry == null || texture == null) {
                continue;
            }
            GeometryBaker.Result baked = GeometryBaker.bake(geometry, face -> "0", hidden);
            if (baked == null) {
                continue;
            }
            String variant = g + "_" + t;
            String itemModel = JavaAssetNames.entityModel(identifier, variant);
            String path = itemModel.substring(itemModel.indexOf(':') + 1);
            JsonObject json = baked.model();
            JsonObject modelTextures = new JsonObject();
            modelTextures.addProperty("0", texture);
            modelTextures.addProperty("particle", texture);
            json.add("textures", modelTextures);
            context.writer().putJson("assets/" + JavaAssetNames.NAMESPACE + "/models/" + path + ".json", json);
            context.writer().putJson("assets/" + JavaAssetNames.NAMESPACE + "/items/" + path + ".json",
                    ItemModels.plain(itemModel));
            variants.put(variant, new ConversionArtifacts.Variant(itemModel, baked.scale(), 0.5f * baked.scale()));
            if (defaultVariant == null) {
                defaultVariant = variant;
            }
        }
        return defaultVariant == null ? null : new ConversionArtifacts.EntityModel(defaultVariant, variants);
    }

    private static String pick(@Nullable String key, Map<String, String> map) {
        if (key != null && map.containsKey(key)) {
            return key;
        }
        return map.containsKey("default") ? "default" : map.keySet().iterator().next();
    }

    /** The first render controller without a condition (or with a literal true one). */
    private static @Nullable JsonObject defaultController(ConversionContext context, JsonObject description) {
        if (!(description.get("render_controllers") instanceof JsonArray list)) {
            return null;
        }
        JsonObject conditional = null;
        for (JsonElement element : list) {
            if (element.isJsonPrimitive()) {
                JsonObject controller = context.definitions().renderController(element.getAsString());
                if (controller != null) {
                    return controller;
                }
            } else if (element instanceof JsonObject object) {
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                    JsonObject controller = context.definitions().renderController(entry.getKey());
                    if (controller == null) {
                        continue;
                    }
                    String condition = entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString().trim() : "";
                    if (condition.equals("true") || condition.equals("1") || condition.equals("1.0")) {
                        return controller;
                    }
                    if (conditional == null) {
                        conditional = controller;
                    }
                }
            }
        }
        return conditional;
    }

    /**
     * Key of a {@code Geometry.x} / {@code Texture.x} reference, following {@code Array.name[...]}
     * to the array's first element.
     */
    private static @Nullable String reference(@Nullable JsonElement element, JsonObject controller, String arrayCategory,
                                              String prefix) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        String expression = element.getAsString().trim().toLowerCase(Locale.ROOT);
        for (int depth = 0; depth < 4; depth++) {
            if (expression.startsWith(prefix)) {
                return expression.substring(prefix.length()).trim();
            }
            if (!expression.startsWith("array.")) {
                return null;
            }
            int bracket = expression.indexOf('[');
            String name = (bracket < 0 ? expression : expression.substring(0, bracket)).trim();
            JsonArray values = null;
            if (controller.get("arrays") instanceof JsonObject arrays
                    && arrays.get(arrayCategory) instanceof JsonObject category) {
                for (Map.Entry<String, JsonElement> array : category.entrySet()) {
                    if (array.getKey().equalsIgnoreCase(name) && array.getValue() instanceof JsonArray a && !a.isEmpty()) {
                        values = a;
                    }
                }
            }
            if (values == null || !values.get(0).isJsonPrimitive()) {
                return null;
            }
            expression = values.get(0).getAsString().trim().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /** Bones a controller's {@code part_visibility} hides with a literal false. */
    private static Set<String> hiddenBones(JsonObject controller) {
        Set<String> hidden = new HashSet<>();
        if (controller.get("part_visibility") instanceof JsonArray list) {
            for (JsonElement element : list) {
                if (element instanceof JsonObject object) {
                    for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                        JsonElement value = entry.getValue();
                        boolean hide = value.isJsonPrimitive() && (value.getAsJsonPrimitive().isBoolean()
                                ? !value.getAsBoolean() : value.getAsString().trim().matches("false|0(\\.0)?"));
                        String bone = entry.getKey().toLowerCase(Locale.ROOT);
                        if (hide && !bone.equals("*")) {
                            hidden.add(bone);
                        } else {
                            hidden.remove(bone);
                        }
                    }
                }
            }
        }
        return hidden;
    }

    /** Keys lower-cased (Molang is case-insensitive), values as written. */
    private static Map<String, String> stringMap(@Nullable JsonElement element) {
        Map<String, String> out = new LinkedHashMap<>();
        if (element instanceof JsonObject object) {
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    out.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().getAsString());
                }
            }
        }
        return out;
    }
}
