package io.github.wyze3306.jtb.world.blockentity;

import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.block.JavaBlockStates;
import io.github.wyze3306.jtb.world.TestSessions;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Bedrock block entity NBT to Java block entity NBT and the block states it implies.
 */
class BlockEntityTranslatorTest {

    private static BlockMapper mapper;

    @BeforeAll
    static void setUp() {
        mapper = TestSessions.mapper();
    }

    @Test
    void signText() {
        NbtMap bedrock = NbtMap.builder().putString("id", "Sign")
                .putCompound("FrontText", NbtMap.builder().putString("Text", "Hello\n§cWorld").putInt("SignTextColor", 0xFFB02E26)
                        .putBoolean("IgnoreLighting", true).build())
                .putCompound("BackText", NbtMap.builder().putString("Text", "").build())
                .putByte("IsWaxed", (byte) 1).build();
        NbtMap java = BlockEntityTranslator.toJava(BlockEntityType.SIGN, JavaBlockStates.defaultState("oak_sign"), bedrock);
        NbtMap front = java.getCompound("front_text");
        assertEquals(List.of("Hello", "§cWorld", "", ""), front.getList("messages", NbtType.STRING));
        assertEquals("red", front.getString("color"));
        assertEquals(true, front.getBoolean("has_glowing_text"));
        assertEquals(true, java.getBoolean("is_waxed"));
    }

    @Test
    void signUpdateKeepsTheOtherSide() {
        NbtMap stored = NbtMap.builder().putString("id", "Sign")
                .putCompound("FrontText", NbtMap.builder().putString("Text", "old").build())
                .putCompound("BackText", NbtMap.builder().putString("Text", "back").build()).build();
        NbtMap update = BlockEntityTranslator.signUpdate(stored, 1, 2, 3, false, true, List.of("a", "b", "", ""));
        assertEquals("a\nb", update.getCompound("FrontText").getString("Text"));
        assertEquals("back", update.getCompound("BackText").getString("Text"));
        assertEquals(2, update.getInt("y"));
        assertEquals("HangingSign", BlockEntityTranslator.signUpdate(null, 0, 0, 0, true, false, List.of("x")).getString("id"));
    }

    @Test
    void statesFromBlockEntities() {
        int whiteBed = JavaBlockStates.parse("white_bed[facing=north,part=head]");
        assertEquals(JavaBlockStates.parse("red_bed[facing=north,part=head]"),
                BlockEntityTranslator.javaState(whiteBed, NbtMap.builder().putByte("color", (byte) 14).build(), 0, 0, mapper));
        int blackBanner = JavaBlockStates.parse("black_banner[rotation=4]");
        assertEquals(JavaBlockStates.parse("white_banner[rotation=4]"),
                BlockEntityTranslator.javaState(blackBanner, NbtMap.builder().putInt("Base", 15).build(), 0, 0, mapper));
        int skull = JavaBlockStates.parse("skeleton_skull[rotation=0]");
        assertEquals(JavaBlockStates.parse("skeleton_skull[rotation=7]"),
                BlockEntityTranslator.javaState(skull, NbtMap.builder().putByte("Rot", (byte) 7).build(), 0, 0, mapper));
        int chest = JavaBlockStates.parse("chest[facing=north,type=single]");
        NbtMap paired = NbtMap.builder().putInt("pairx", 11).putInt("pairz", 20).build();
        assertEquals(JavaBlockStates.parse("chest[facing=north,type=left]"), BlockEntityTranslator.javaState(chest, paired, 10, 20, mapper),
                "partner east of a north-facing chest");
        assertEquals(JavaBlockStates.parse("chest[facing=north,type=right]"), BlockEntityTranslator.javaState(chest, paired, 12, 20, mapper));
        int pot = JavaBlockStates.defaultState("flower_pot");
        NbtMap poppy = NbtMap.builder().putCompound("PlantBlock", NbtMap.builder().putString("name", "minecraft:poppy")
                .putCompound("states", NbtMap.EMPTY).build()).build();
        assertEquals(JavaBlockStates.defaultState("potted_poppy"), BlockEntityTranslator.javaState(pot, poppy, 0, 0, mapper));
        int lectern = JavaBlockStates.parse("lectern[facing=east]");
        assertEquals("true", JavaBlockStates.property(BlockEntityTranslator.javaState(lectern,
                NbtMap.builder().putBoolean("hasBook", true).build(), 0, 0, mapper), "has_book"));
    }

    @Test
    void bannerPatternsAndSpawner() {
        NbtMap banner = NbtMap.builder().putInt("Base", 0).putList("Patterns", NbtType.COMPOUND,
                NbtMap.builder().putString("Pattern", "bs").putInt("Color", 1).build()).build();
        NbtMap java = BlockEntityTranslator.toJava(BlockEntityType.BANNER, JavaBlockStates.defaultState("black_banner"), banner);
        NbtMap pattern = java.getList("patterns", NbtType.COMPOUND).getFirst();
        assertEquals("minecraft:stripe_bottom", pattern.getString("pattern"));
        assertEquals("red", pattern.getString("color"));
        NbtMap spawner = BlockEntityTranslator.toJava(BlockEntityType.MOB_SPAWNER, JavaBlockStates.defaultState("spawner"),
                NbtMap.builder().putString("EntityIdentifier", "minecraft:zombie_pigman").build());
        assertEquals("minecraft:zombified_piglin", spawner.getCompound("SpawnData").getCompound("entity").getString("id"));
        assertFalse(BlockEntityTranslator.toJava(BlockEntityType.CHEST, JavaBlockStates.defaultState("chest"), null).containsKey("Items"));
    }
}
