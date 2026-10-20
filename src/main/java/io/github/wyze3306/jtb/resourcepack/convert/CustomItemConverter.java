package io.github.wyze3306.jtb.resourcepack.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.wyze3306.jtb.resourcepack.JavaAssetNames;
import io.github.wyze3306.jtb.resourcepack.ServerDefinitions;
import org.cloudburstmc.nbt.NbtMap;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Custom items of the server's item registry (second phase). For each one:
 * <ul>
 *     <li>its icon ({@code item_properties."minecraft:icon"}, an {@code item_texture.json} short name)
 *     becomes a flat model ({@code item/generated}, or {@code item/handheld} for
 *     {@code hand_equipped} items) and the item definition {@code jtb:item/<namespace>/<path>}
 *     ({@link JavaAssetNames#itemModel}) the item translator puts in {@code item_model};</li>
 *     <li>an attachable with armour geometry of a wearable item becomes an equipment asset
 *     ({@link JavaAssetNames#equipmentAsset}, for {@code equippable.asset_id}) from its 64 x 32 sheet;</li>
 *     <li>another attachable becomes a 3D model shown in hand (the flat icon stays in inventories,
 *     on the ground and in frames). Bedrock positions attachables on the player's hand bone; the
 *     model is centred in its cell and uses the handheld transforms, which approximates it.</li>
 * </ul>
 */
final class CustomItemConverter {

    private CustomItemConverter() {
    }

    static void convert(ConversionContext context, List<ServerDefinitions.CustomItem> items) {
        for (ServerDefinitions.CustomItem item : items) {
            try {
                convert(context, item);
            } catch (RuntimeException e) {
                context.count("custom items failed");
            }
        }
    }

    private static void convert(ConversionContext context, ServerDefinitions.CustomItem item) {
        String identifier = item.identifier();
        NbtMap components = item.components().containsKey("components") ? item.components().getCompound("components") : item.components();
        NbtMap properties = components.getCompound("item_properties");
        String itemModel = JavaAssetNames.itemModel(identifier);
        String path = JavaAssetNames.itemModelPath(identifier);

        String icon = iconTexture(context, properties, components);
        String flatModel = null;
        if (icon != null) {
            flatModel = itemModel;
            boolean handheld = properties.getBoolean("hand_equipped", false);
            context.writer().putJson(modelFile(path), ItemModels.generated(icon, handheld));
        }

        JsonObject attachable = context.definitions().attachables().get(identifier);
        String heldModel = null;
        if (attachable != null) {
            String slot = components.getCompound("minecraft:wearable").getString("slot", "");
            if (slot.startsWith("slot.armor") && equipment(context, identifier, slot, attachable)) {
                context.count("custom armour");
            } else {
                heldModel = heldModel(context, path, attachable);
            }
        }

        JsonObject definition;
        if (heldModel != null && flatModel != null) {
            definition = ItemModels.flatInInventory(flatModel, heldModel);
        } else if (flatModel != null || heldModel != null) {
            definition = ItemModels.plain(flatModel != null ? flatModel : heldModel);
        } else {
            context.count("custom items without texture");
            return;
        }
        context.writer().putJson("assets/" + JavaAssetNames.NAMESPACE + "/items/" + path + ".json", definition);
        context.artifacts().putItemModel(identifier, itemModel);
        context.count("custom items");
    }

    /** Java texture of the item's icon short name (server atlas first, then vanilla short names). */
    private static @Nullable String iconTexture(ConversionContext context, NbtMap properties, NbtMap components) {
        String shortName = iconName(properties.get("minecraft:icon"));
        if (shortName == null) {
            shortName = iconName(components.get("minecraft:icon"));
        }
        if (shortName == null || shortName.isBlank()) {
            return null;
        }
        String key = shortName.toLowerCase(Locale.ROOT);
        List<String> paths = context.definitions().itemTextures().get(key);
        if (paths != null && !paths.isEmpty() && !paths.getFirst().isEmpty()) {
            return context.texture(paths.getFirst(), ConversionContext.TextureKind.ITEM);
        }
        List<String> vanilla = context.tables().itemAtlas().get(key);
        if (vanilla != null && !vanilla.isEmpty()) {
            return context.texture("textures/" + vanilla.getFirst(), ConversionContext.TextureKind.ITEM);
        }
        // Some servers put a texture path instead of a short name.
        return key.contains("/") ? context.texture(key, ConversionContext.TextureKind.ITEM) : null;
    }

    /** {@code {textures: {default: "x"}}}, {@code {texture: "x"}} or {@code "x"}. */
    private static @Nullable String iconName(@Nullable Object icon) {
        if (icon instanceof String name) {
            return name;
        }
        if (icon instanceof NbtMap map) {
            Object textures = map.get("textures");
            if (textures instanceof NbtMap byKey) {
                String value = byKey.getString("default", "");
                if (value.isEmpty() && !byKey.isEmpty() && byKey.values().iterator().next() instanceof String first) {
                    value = first;
                }
                return value.isEmpty() ? null : value;
            }
            if (textures instanceof String name) {
                return name;
            }
            String texture = map.getString("texture", "");
            return texture.isEmpty() ? null : texture;
        }
        return null;
    }

    /**
     * Writes an equipment asset from an armour attachable: its default texture is a 64 x 32 armour
     * sheet (same layout as Java's {@code humanoid} / {@code humanoid_leggings} layers).
     */
    private static boolean equipment(ConversionContext context, String identifier, String slot, JsonObject attachable) {
        Map<String, JsonElement> geometries = BedrockDefinitions.entries(attachable.get("geometry"));
        boolean armourGeometry = geometries.values().stream()
                .anyMatch(g -> g.isJsonPrimitive() && g.getAsString().toLowerCase(Locale.ROOT).contains("armor"));
        String texturePath = defaultTexture(attachable);
        if (!armourGeometry || texturePath == null) {
            return false;
        }
        PackFiles.PackFile image = context.stack().image(texturePath);
        byte[] png = image == null ? null : PackImages.toPng(image.path(), image.data());
        int[] size = png == null ? null : PackImages.size(image.path(), png);
        if (size == null || size[0] != size[1] * 2) {
            context.count("custom armour skipped (not a 64x32 sheet)");
            return false;
        }
        String layer = slot.equals("slot.armor.legs") ? "humanoid_leggings" : "humanoid";
        String asset = JavaAssetNames.equipmentAsset(identifier);
        String assetPath = asset.substring(asset.indexOf(':') + 1);
        context.writer().put("assets/" + JavaAssetNames.NAMESPACE + "/textures/entity/equipment/" + layer + "/" + assetPath + ".png", png);
        JsonObject texture = new JsonObject();
        texture.addProperty("texture", asset);
        JsonArray layers = new JsonArray();
        layers.add(texture);
        JsonObject byType = new JsonObject();
        byType.add(layer, layers);
        JsonObject equipment = new JsonObject();
        equipment.add("layers", byType);
        context.writer().putJson("assets/" + JavaAssetNames.NAMESPACE + "/equipment/" + assetPath + ".json", equipment);
        context.artifacts().putEquipmentAsset(identifier, asset);
        return true;
    }

    private static @Nullable String heldModel(ConversionContext context, String path, JsonObject attachable) {
        Map<String, JsonElement> geometries = BedrockDefinitions.entries(attachable.get("geometry"));
        JsonElement geometryId = geometries.getOrDefault("default", geometries.isEmpty() ? null : geometries.values().iterator().next());
        String texturePath = defaultTexture(attachable);
        if (geometryId == null || !geometryId.isJsonPrimitive() || texturePath == null) {
            return null;
        }
        Geometry geometry = context.definitions().geometry(geometryId.getAsString());
        String texture = context.texture(texturePath, ConversionContext.TextureKind.ITEM);
        if (geometry == null || texture == null) {
            return null;
        }
        GeometryBaker.Result baked = GeometryBaker.bake(geometry, face -> "0", java.util.Set.of());
        if (baked == null) {
            return null;
        }
        JsonObject model = centre(baked.model());
        JsonObject textures = new JsonObject();
        textures.addProperty("0", texture);
        textures.addProperty("particle", texture);
        model.add("textures", textures);
        model.add("display", heldDisplay(baked.scale()));
        String modelPath = path + "_held";
        context.writer().putJson(modelFile(modelPath), model);
        context.count("custom 3D items");
        return JavaAssetNames.NAMESPACE + ":" + modelPath;
    }

    private static @Nullable String defaultTexture(JsonObject description) {
        Map<String, JsonElement> textures = BedrockDefinitions.entries(description.get("textures"));
        JsonElement texture = textures.getOrDefault("default", textures.isEmpty() ? null : textures.values().iterator().next());
        return texture != null && texture.isJsonPrimitive() ? texture.getAsString() : null;
    }

    /** Moves the elements so their bounding box is centred on (8, 8, 8). */
    private static JsonObject centre(JsonObject model) {
        JsonArray elements = model.getAsJsonArray("elements");
        double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (JsonElement element : elements) {
            JsonArray from = element.getAsJsonObject().getAsJsonArray("from");
            JsonArray to = element.getAsJsonObject().getAsJsonArray("to");
            for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], Math.min(from.get(axis).getAsDouble(), to.get(axis).getAsDouble()));
                max[axis] = Math.max(max[axis], Math.max(from.get(axis).getAsDouble(), to.get(axis).getAsDouble()));
            }
        }
        double[] shift = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            shift[axis] = 8 - (min[axis] + max[axis]) / 2;
            // Stay inside the Java bounds.
            shift[axis] = Math.max(-16 - min[axis], Math.min(32 - max[axis], shift[axis]));
        }
        for (JsonElement element : elements) {
            JsonObject object = element.getAsJsonObject();
            object.add("from", shifted(object.getAsJsonArray("from"), shift));
            object.add("to", shifted(object.getAsJsonArray("to"), shift));
            if (object.get("rotation") instanceof JsonObject rotation && rotation.get("origin") instanceof JsonArray origin) {
                rotation.add("origin", shifted(origin, shift));
            }
        }
        return model;
    }

    private static JsonArray shifted(JsonArray vector, double[] shift) {
        JsonArray out = new JsonArray();
        for (int axis = 0; axis < 3; axis++) {
            out.add(Math.round((vector.get(axis).getAsDouble() + shift[axis]) * 10000) / 10000.0);
        }
        return out;
    }

    /** Java's handheld transforms in hand, block-like ones elsewhere, scaled back up by {@code scale}. */
    private static JsonObject heldDisplay(float scale) {
        JsonObject display = new JsonObject();
        display.add("thirdperson_righthand", transform(new double[]{0, -90, 55}, new double[]{0, 4, 0.5}, 0.85 * scale));
        display.add("thirdperson_lefthand", transform(new double[]{0, 90, -55}, new double[]{0, 4, 0.5}, 0.85 * scale));
        display.add("firstperson_righthand", transform(new double[]{0, -90, 25}, new double[]{1.13, 3.2, 1.13}, 0.68 * scale));
        display.add("firstperson_lefthand", transform(new double[]{0, 90, -25}, new double[]{1.13, 3.2, 1.13}, 0.68 * scale));
        display.add("head", transform(new double[]{0, 180, 0}, new double[]{0, 13, 7}, scale));
        return display;
    }

    private static JsonObject transform(double[] rotation, double[] translation, double scale) {
        JsonObject transform = new JsonObject();
        transform.add("rotation", array(rotation));
        transform.add("translation", array(translation));
        double s = Math.round(scale * 10000) / 10000.0;
        transform.add("scale", array(new double[]{s, s, s}));
        return transform;
    }

    private static JsonArray array(double[] values) {
        JsonArray array = new JsonArray();
        for (double value : values) {
            array.add(value);
        }
        return array;
    }

    private static String modelFile(String path) {
        return "assets/" + JavaAssetNames.NAMESPACE + "/models/" + path + ".json";
    }
}
