package io.github.wyze3306.jtb.block;

import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The components of one custom block state: the definition's components with every matching
 * permutation applied in order, and the part of them that decides how the state looks.
 */
public final class CustomBlockComponents {

    /**
     * Components that change how a state is drawn: its model (geometry, textures, rotation) and the
     * light it gives or blocks, which the Java carrier state carries.
     */
    private static final String[] VISUAL = {"minecraft:geometry", "minecraft:material_instances",
            "minecraft:transformation", "minecraft:unit_cube", "minecraft:light_emission", "minecraft:light_dampening"};

    private CustomBlockComponents() {
    }

    /** The block's components with the matching permutations applied in order. */
    public static NbtMap components(NbtMap definition, NbtMap states) {
        Map<String, Object> components = new LinkedHashMap<>(definition.getCompound("components"));
        for (NbtMap permutation : definition.getList("permutations", NbtType.COMPOUND)) {
            if (MolangConditions.test(permutation.getString("condition", ""), states)) {
                components.putAll(permutation.getCompound("components"));
            }
        }
        return NbtMap.fromMap(components);
    }

    /**
     * A key equal for two states drawn the same way: their visual components. States that only
     * differ by properties nothing visual depends on (a counter, a facing the model ignores) share
     * it, and may share one Java carrier state (within one block, so Java to Bedrock stays the same block).
     */
    public static String appearanceKey(NbtMap definition, NbtMap states) {
        NbtMap components = components(definition, states);
        Map<String, Object> visual = new TreeMap<>();
        for (String name : VISUAL) {
            Object value = components.get(name);
            if (value != null) {
                visual.put(name, value);
            }
        }
        return visual.toString();
    }
}
