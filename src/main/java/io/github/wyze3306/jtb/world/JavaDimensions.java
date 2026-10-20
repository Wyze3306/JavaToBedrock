package io.github.wyze3306.jtb.world;

import io.github.wyze3306.jtb.mapping.JavaRegistries;
import net.kyori.adventure.key.Key;

/**
 * Bedrock dimensions (0 overworld, 1 nether, 2 end) to the vanilla Java dimension types and level
 * names. Heights match: overworld -64..320, nether 0..256 (Bedrock uses 0..128 of it), end 0..256.
 */
public final class JavaDimensions {

    public static final Key OVERWORLD = Key.key("minecraft:overworld");
    public static final Key NETHER = Key.key("minecraft:the_nether");
    public static final Key END = Key.key("minecraft:the_end");
    public static final Key[] WORLD_NAMES = {OVERWORLD, NETHER, END};

    private JavaDimensions() {
    }

    public static Key key(int bedrockDimension) {
        return switch (bedrockDimension) {
            case 1 -> NETHER;
            case 2 -> END;
            default -> OVERWORLD;
        };
    }

    /** Id of the dimension type in the synchronised {@code minecraft:dimension_type} registry. */
    public static int typeId(int bedrockDimension) {
        return JavaRegistries.registry("minecraft:dimension_type").id(key(bedrockDimension));
    }

    public static int minY(int bedrockDimension) {
        return bedrockDimension == 0 ? -64 : 0;
    }

    public static int height(int bedrockDimension) {
        return bedrockDimension == 0 ? 384 : 256;
    }
}
