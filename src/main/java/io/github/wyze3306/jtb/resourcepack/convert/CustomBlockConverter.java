package io.github.wyze3306.jtb.resourcepack.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.wyze3306.jtb.resourcepack.CustomBlockCarrier;
import io.github.wyze3306.jtb.resourcepack.JavaAssetNames;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Custom blocks (second phase): every custom block state the block mapper put on an unused Java
 * carrier state gets a block model, and the carrier's blockstate file is rewritten with all its
 * states - vanilla ones keep their vanilla models (a pack's blockstate file replaces vanilla's
 * entirely).
 * <p>
 * The state's components are the block's {@code components} overridden by every permutation whose
 * condition holds ({@link MolangConditions}). From them:
 * {@code minecraft:geometry} ({@code minecraft:geometry.full_block} or absent -> {@code block/cube},
 * {@code minecraft:geometry.cross} -> {@code block/cross}, else the pack geometry baked by
 * {@link GeometryBaker}, {@code bone_visibility} literals honoured), {@code minecraft:material_instances}
 * (face -> {@code mappings} alias -> {@code *}; texture short names from {@code terrain_texture.json},
 * vanilla short names included) and {@code minecraft:transformation} rotations in 90 degree steps
 * about X/Y (variant {@code x}/{@code y}). Render methods and tints are not expressible per model.
 */
final class CustomBlockConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(CustomBlockConverter.class);
    private static final String[] FACES = {"down", "up", "north", "south", "west", "east"};

    private CustomBlockConverter() {
    }

    /** The model a custom state shows and its rotation. */
    private record Appearance(String model, int x, int y) {
        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("model", this.model);
            if (this.x != 0) {
                json.addProperty("x", this.x);
            }
            if (this.y != 0) {
                json.addProperty("y", this.y);
            }
            return json;
        }
    }

    static void convert(ConversionContext context, List<CustomBlockCarrier> carriers) {
        Map<String, Map<String, Appearance>> byBlock = new TreeMap<>();
        Map<String, Map<String, Integer>> models = new HashMap<>();
        for (CustomBlockCarrier carrier : carriers) {
            ResourceTables.Carrier table = context.tables().carrier(carrier.javaBlock());
            if (table == null) {
                context.count("custom block states on unsupported carriers");
                continue;
            }
            Appearance appearance;
            try {
                appearance = appearance(context, carrier, models.computeIfAbsent(carrier.bedrockIdentifier(), k -> new HashMap<>()));
            } catch (RuntimeException e) {
                LOGGER.debug("Custom block {} not converted", carrier.bedrockIdentifier(), e);
                appearance = null;
            }
            if (appearance == null) {
                context.count("custom block states without model");
                continue;
            }
            byBlock.computeIfAbsent(carrier.javaBlock(), k -> new LinkedHashMap<>())
                    .put(stateKey(table, carrier.javaProperties()), appearance);
            context.artifacts().addCustomBlock(carrier.bedrockIdentifier());
            context.count("custom block states");
        }
        byBlock.forEach((block, states) -> writeBlockstate(context, context.tables().carrier(block), states));
        for (String identifier : models.keySet()) {
            if (!models.get(identifier).isEmpty()) {
                String itemPath = JavaAssetNames.itemModelPath(identifier);
                context.writer().putJson("assets/" + JavaAssetNames.NAMESPACE + "/items/" + itemPath + ".json",
                        ItemModels.plain(JavaAssetNames.customBlockModel(identifier, 0)));
                context.artifacts().putItemModel(identifier, JavaAssetNames.itemModel(identifier));
            }
        }
    }

    // ------------------------------------------------------------------ models

    private static @Nullable Appearance appearance(ConversionContext context, CustomBlockCarrier carrier,
                                                   Map<String, Integer> models) {
        NbtMap components = components(carrier.definition(), carrier.bedrockStates());
        JsonObject model = model(context, components);
        if (model == null) {
            return null;
        }
        String json = JavaPackWriter.GSON.toJson(model);
        Integer index = models.get(json);
        if (index == null) {
            index = models.size();
            models.put(json, index);
            String modelId = JavaAssetNames.customBlockModel(carrier.bedrockIdentifier(), index);
            context.writer().put("assets/" + JavaAssetNames.NAMESPACE + "/models/" + modelId.substring(modelId.indexOf(':') + 1)
                    + ".json", json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        NbtMap transformation = components.getCompound("minecraft:transformation");
        int x = Math.floorMod(-transformation.getInt("RX", 0) * 90, 360);
        int y = Math.floorMod(-transformation.getInt("RY", 0) * 90, 360);
        return new Appearance(JavaAssetNames.customBlockModel(carrier.bedrockIdentifier(), index), x, y);
    }

    /** The block's components with the matching permutations applied in order. */
    static NbtMap components(NbtMap definition, NbtMap states) {
        Map<String, Object> components = new LinkedHashMap<>(definition.getCompound("components"));
        for (NbtMap permutation : definition.getList("permutations", NbtType.COMPOUND)) {
            if (MolangConditions.test(permutation.getString("condition", ""), states)) {
                components.putAll(permutation.getCompound("components"));
            }
        }
        return NbtMap.fromMap(components);
    }

    private static @Nullable JsonObject model(ConversionContext context, NbtMap components) {
        NbtMap materials = components.getCompound("minecraft:material_instances");
        Object geometryComponent = components.get("minecraft:geometry");
        String geometryId = geometryComponent instanceof NbtMap g ? g.getString("identifier", "") :
                geometryComponent instanceof String s ? s : "";
        geometryId = geometryId.toLowerCase(Locale.ROOT);
        Map<String, String> textures = new LinkedHashMap<>();
        if (geometryId.isEmpty() || geometryId.equals("minecraft:geometry.full_block")) {
            for (String face : FACES) {
                String texture = materialTexture(context, materials, face);
                if (texture == null) {
                    return null;
                }
                textures.put(face, texture);
            }
            textures.put("particle", textures.get("north"));
            return parented("minecraft:block/cube", textures);
        }
        if (geometryId.equals("minecraft:geometry.cross")) {
            String texture = materialTexture(context, materials, "*");
            if (texture == null) {
                return null;
            }
            textures.put("cross", texture);
            textures.put("particle", texture);
            return parented("minecraft:block/cross", textures);
        }
        Geometry geometry = context.definitions().geometry(geometryId);
        if (geometry == null) {
            return null;
        }
        Set<String> hidden = new HashSet<>();
        if (geometryComponent instanceof NbtMap g) {
            g.getCompound("bone_visibility").forEach((bone, visible) -> {
                if (visible instanceof Byte b && b == 0 || "false".equals(visible) || "0".equals(visible)) {
                    hidden.add(bone.toLowerCase(Locale.ROOT));
                }
            });
        }
        Map<String, String> variables = new HashMap<>();
        GeometryBaker.Result baked = GeometryBaker.bake(geometry, instance -> {
            String texture = materialTexture(context, materials, instance);
            if (texture == null) {
                return null;
            }
            return variables.computeIfAbsent(texture, t -> String.valueOf(variables.size()));
        }, hidden);
        if (baked == null) {
            return null;
        }
        JsonObject model = baked.model();
        new TreeMap<>(swap(variables)).forEach((variable, texture) -> textures.put(String.valueOf(variable), texture));
        textures.put("particle", textures.values().iterator().next());
        JsonObject result = parented("minecraft:block/block", textures);
        result.add("elements", model.get("elements"));
        return result;
    }

    private static Map<Integer, String> swap(Map<String, String> variables) {
        Map<Integer, String> out = new HashMap<>();
        variables.forEach((texture, variable) -> out.put(Integer.parseInt(variable), texture));
        return out;
    }

    private static JsonObject parented(String parent, Map<String, String> textures) {
        JsonObject model = new JsonObject();
        model.addProperty("parent", parent);
        JsonObject json = new JsonObject();
        textures.forEach(json::addProperty);
        model.add("textures", json);
        return model;
    }

    /** Java texture of a material instance: name -> {@code mappings} alias -> {@code *}. */
    private static @Nullable String materialTexture(ConversionContext context, NbtMap materialInstances, String name) {
        NbtMap materials = materialInstances.getCompound("materials");
        NbtMap mappings = materialInstances.getCompound("mappings");
        String key = name;
        if (!materials.containsKey(key) && mappings.get(key) instanceof String alias) {
            key = alias;
        }
        NbtMap material = materials.getCompound(key, null);
        if (material == null) {
            material = materials.getCompound("*", null);
        }
        String shortName = material == null ? "" : material.getString("texture", "").toLowerCase(Locale.ROOT);
        if (shortName.isEmpty()) {
            return null;
        }
        List<String> paths = context.definitions().terrainTextures().get(shortName);
        if (paths != null && !paths.isEmpty() && !paths.getFirst().isEmpty()) {
            return context.texture(paths.getFirst(), ConversionContext.TextureKind.BLOCK);
        }
        List<String> vanilla = context.tables().terrainAtlas().get(shortName);
        if (vanilla != null && !vanilla.isEmpty()) {
            return context.texture("textures/" + vanilla.getFirst(), ConversionContext.TextureKind.BLOCK);
        }
        return null;
    }

    // ------------------------------------------------------------------ blockstates

    private static String stateKey(ResourceTables.Carrier carrier, Map<String, String> properties) {
        StringBuilder key = new StringBuilder();
        for (ResourceTables.Property property : carrier.properties()) {
            if (!key.isEmpty()) {
                key.append(',');
            }
            key.append(property.name()).append('=').append(properties.getOrDefault(property.name(), ""));
        }
        return key.toString();
    }

    /** Every state of a carrier, as property maps in Java order. */
    private static List<Map<String, String>> allStates(ResourceTables.Carrier carrier) {
        List<Map<String, String>> states = new ArrayList<>();
        states.add(new LinkedHashMap<>());
        for (ResourceTables.Property property : carrier.properties()) {
            List<Map<String, String>> next = new ArrayList<>(states.size() * property.values().size());
            for (Map<String, String> state : states) {
                for (String value : property.values()) {
                    Map<String, String> copy = new LinkedHashMap<>(state);
                    copy.put(property.name(), value);
                    next.add(copy);
                }
            }
            states = next;
        }
        return states;
    }

    private static void writeBlockstate(ConversionContext context, ResourceTables.Carrier carrier, Map<String, Appearance> custom) {
        JsonObject vanilla = carrier.blockstate();
        JsonObject blockstate = new JsonObject();
        List<Map<String, String>> states = allStates(carrier);
        if (vanilla.get("variants") instanceof JsonObject variants) {
            JsonObject out = new JsonObject();
            for (Map<String, String> state : states) {
                String key = stateKey(carrier, state);
                Appearance appearance = custom.get(key);
                if (appearance != null) {
                    out.add(key, appearance.toJson());
                    continue;
                }
                JsonElement model = vanillaVariant(variants, state);
                if (model != null) {
                    out.add(key, model);
                }
            }
            blockstate.add("variants", out);
        } else if (vanilla.get("multipart") instanceof JsonArray parts) {
            JsonArray out = new JsonArray();
            for (JsonElement element : parts) {
                JsonObject part = element.getAsJsonObject();
                List<Map<String, String>> matching = new ArrayList<>();
                boolean allMatch = true;
                for (Map<String, String> state : states) {
                    if (!custom.containsKey(stateKey(carrier, state)) && matches(part.get("when"), state)) {
                        matching.add(state);
                    } else if (matches(part.get("when"), state)) {
                        allMatch = false;
                    }
                }
                if (matching.isEmpty()) {
                    continue;
                }
                JsonObject copy = part.deepCopy();
                if (!allMatch) {
                    JsonArray or = new JsonArray();
                    for (Map<String, String> state : matching) {
                        or.add(condition(state));
                    }
                    JsonObject when = new JsonObject();
                    when.add("OR", or);
                    copy.add("when", when);
                }
                out.add(copy);
            }
            for (Map<String, String> state : states) {
                Appearance appearance = custom.get(stateKey(carrier, state));
                if (appearance != null) {
                    JsonObject part = new JsonObject();
                    part.add("when", condition(state));
                    part.add("apply", appearance.toJson());
                    out.add(part);
                }
            }
            blockstate.add("multipart", out);
        } else {
            return;
        }
        String path = carrier.block().substring(carrier.block().indexOf(':') + 1);
        context.writer().putJson("assets/minecraft/blockstates/" + path + ".json", blockstate);
    }

    private static JsonObject condition(Map<String, String> state) {
        JsonObject condition = new JsonObject();
        state.forEach(condition::addProperty);
        return condition;
    }

    /** The vanilla variant a state uses: the first key whose properties all match ({@code ""} matches all). */
    private static @Nullable JsonElement vanillaVariant(JsonObject variants, Map<String, String> state) {
        for (Map.Entry<String, JsonElement> variant : variants.entrySet()) {
            boolean match = true;
            if (!variant.getKey().isEmpty()) {
                for (String condition : variant.getKey().split(",")) {
                    int eq = condition.indexOf('=');
                    if (eq < 0 || !condition.substring(eq + 1).equals(state.get(condition.substring(0, eq)))) {
                        match = false;
                        break;
                    }
                }
            }
            if (match) {
                return variant.getValue();
            }
        }
        return null;
    }

    /** Multipart {@code when}: property values ({@code "a|b"}), {@code OR} and {@code AND} lists; absent = always. */
    private static boolean matches(@Nullable JsonElement when, Map<String, String> state) {
        if (when == null) {
            return true;
        }
        if (!(when instanceof JsonObject object)) {
            return false;
        }
        if (object.get("OR") instanceof JsonArray or) {
            for (JsonElement element : or) {
                if (matches(element, state)) {
                    return true;
                }
            }
            return false;
        }
        if (object.get("AND") instanceof JsonArray and) {
            for (JsonElement element : and) {
                if (!matches(element, state)) {
                    return false;
                }
            }
            return true;
        }
        for (Map.Entry<String, JsonElement> condition : object.entrySet()) {
            String value = state.get(condition.getKey());
            boolean any = false;
            for (String option : condition.getValue().getAsString().split("\\|")) {
                any |= option.equals(value);
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }
}
