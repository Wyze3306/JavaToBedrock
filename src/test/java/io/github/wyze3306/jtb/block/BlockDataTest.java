package io.github.wyze3306.jtb.block;

import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated block tables: Java state ids, Bedrock hashes, the mapping both ways and the
 * per-state light data.
 */
class BlockDataTest {

    @BeforeAll
    static void load() {
        long start = System.nanoTime();
        BlockData.load();
        System.out.println("BlockData.load: " + (System.nanoTime() - start) / 1_000_000 + " ms");
    }

    @Test
    void javaStateIdsFollowTheVanillaOrder() {
        assertEquals(35723, JavaBlockStates.count());
        assertEquals(0, JavaBlockStates.parse("minecraft:air"));
        assertEquals(1, JavaBlockStates.parse("minecraft:stone"));
        // Ids verified against ViaBedrock's via_mappings and Geyser's items.json ranges.
        assertEquals(89, JavaBlockStates.parse("water[level=0]"));
        assertEquals(5474, JavaBlockStates.defaultState("oak_stairs"));
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]", JavaBlockStates.stateString(5474));
        assertEquals(681, JavaBlockStates.defaultState("note_block"));
        assertEquals("west", JavaBlockStates.property(JavaBlockStates.parse("oak_stairs[facing=west]"), "facing"));
    }

    @Test
    void propertyEditsKeepTheOtherProperties() {
        int stairs = JavaBlockStates.parse("oak_stairs[facing=east,half=top]");
        int wet = JavaBlockStates.waterlogged(stairs);
        assertEquals("true", JavaBlockStates.property(wet, "waterlogged"));
        assertEquals("east", JavaBlockStates.property(wet, "facing"));
        assertEquals(-1, JavaBlockStates.waterlogged(JavaBlockStates.parse("stone")));
        int redBed = JavaBlockStates.withBlock(JavaBlockStates.parse("white_bed[facing=east,part=head]"), "red_bed");
        assertEquals("minecraft:red_bed[facing=east,occupied=false,part=head]", JavaBlockStates.stateString(redBed));
    }

    @Test
    void lightData() {
        assertEquals(15, JavaBlockStates.opacity(JavaBlockStates.parse("stone")));
        assertEquals(0, JavaBlockStates.opacity(JavaBlockStates.parse("glass")));
        assertEquals(1, JavaBlockStates.opacity(JavaBlockStates.parse("oak_leaves")));
        assertEquals(1, JavaBlockStates.opacity(JavaBlockStates.parse("water")));
        assertEquals(15, JavaBlockStates.emission(JavaBlockStates.parse("glowstone")));
        assertEquals(14, JavaBlockStates.emission(JavaBlockStates.parse("torch")));
        assertEquals(0, JavaBlockStates.emission(JavaBlockStates.parse("furnace[lit=false]")));
        assertEquals(13, JavaBlockStates.emission(JavaBlockStates.parse("furnace[lit=true]")));
        assertEquals(9, JavaBlockStates.emission(JavaBlockStates.parse("candle[candles=3,lit=true]")));
        assertEquals(15, JavaBlockStates.opacity(JavaBlockStates.parse("stone_slab[type=double]")));
        assertEquals(0, JavaBlockStates.opacity(JavaBlockStates.parse("stone_slab[type=bottom]")));
        assertEquals(JavaBlockStates.FACE_DOWN, JavaBlockStates.occlusion(JavaBlockStates.parse("stone_slab[type=bottom]")));
        assertEquals(1, JavaBlockStates.opacity(JavaBlockStates.parse("stone_slab[type=bottom,waterlogged=true]")));
    }

    @Test
    void bedrockHashesAndMapping() {
        assertEquals(22091, BedrockBlockPalette.size());
        NbtMap states = NbtMap.builder().putString("minecraft:vertical_half", "top").build();
        int index = BedrockBlockPalette.indexOfHash(BlockStateHasher.hash("minecraft:smooth_stone_slab", states));
        assertTrue(index >= 0, "smooth_stone_slab is vanilla");
        assertEquals(JavaBlockStates.parse("smooth_stone_slab[type=top]"), BedrockBlockPalette.javaState(index));
        assertEquals(states, BedrockBlockPalette.state(index).getCompound("states"));
        assertEquals(-2, BlockStateHasher.hash("minecraft:unknown", NbtMap.EMPTY));
        int air = BedrockBlockPalette.indexOfHash(BlockStateHasher.hash("minecraft:air", NbtMap.EMPTY));
        assertEquals(JavaBlockStates.AIR, BedrockBlockPalette.javaState(air));
        int stone = BedrockBlockPalette.indexOfHash(BlockStateHasher.hash("minecraft:stone", NbtMap.EMPTY));
        assertEquals(JavaBlockStates.parse("stone"), BedrockBlockPalette.javaState(stone));
        assertEquals("minecraft:stone", BedrockBlockPalette.name(stone));
        assertNull(BedrockBlockPalette.stateValue(stone, "nothing"));
        assertNotEquals(-1, BedrockBlockPalette.indexOfJava(JavaBlockStates.parse("oak_stairs[facing=west,half=top]")));
    }

    @Test
    void everyPaletteStateMapsAndRoundTripsMostly() {
        int roundTrip = 0;
        for (int i = 0; i < BedrockBlockPalette.size(); i++) {
            int java = BedrockBlockPalette.javaState(i);
            assertTrue(java >= 0 && java < JavaBlockStates.count());
            int back = BedrockBlockPalette.indexOfJava(java);
            if (back >= 0 && BedrockBlockPalette.javaState(back) == java) {
                roundTrip++;
            }
        }
        // Several Bedrock states can show as one Java state; the reverse picks one of them.
        assertTrue(roundTrip > 15000, "round trips: " + roundTrip);
    }
}
