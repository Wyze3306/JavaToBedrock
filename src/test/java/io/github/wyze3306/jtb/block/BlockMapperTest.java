package io.github.wyze3306.jtb.block;

import io.github.wyze3306.jtb.world.TestSessions;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.ServerBlockProperty;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime ids of a session: hashed and ordered servers, custom blocks with properties and traits,
 * waterlogging and the reverse mapping.
 */
class BlockMapperTest {

    private static NbtMap customBlock() {
        return NbtMap.builder()
                .putCompound("menu_category", NbtMap.builder().putString("category", "construction").build())
                .putCompound("vanilla_block_data", NbtMap.builder().putInt("block_id", 10000).build())
                .putList("properties", NbtType.COMPOUND, List.of(
                        NbtMap.builder().putString("name", "test:lit").putList("enum", NbtType.BYTE, (byte) 0, (byte) 1).build(),
                        NbtMap.builder().putString("name", "test:level").putList("enum", NbtType.INT, 0, 1, 2).build()))
                .putList("traits", NbtType.COMPOUND, List.of(NbtMap.builder().putString("name", "minecraft:placement_direction")
                        .putCompound("enabled_states", NbtMap.builder().putBoolean("minecraft:cardinal_direction", true).build()).build()))
                .putCompound("components", NbtMap.builder()
                        .putCompound("minecraft:light_emission", NbtMap.builder().putByte("emission", (byte) 12).build()).build())
                .build();
    }

    private static StartGamePacket startGame(boolean hashed) {
        StartGamePacket packet = new StartGamePacket();
        packet.setBlockNetworkIdsAreHashes(hashed);
        packet.getBlockProperties().add(new ServerBlockProperty("test:lamp", customBlock()));
        // PowerNukkitX also lists its data-driven vanilla blocks: they must stay vanilla.
        packet.getBlockProperties().add(new ServerBlockProperty("minecraft:white_wool_slab", customBlock()));
        return packet;
    }

    @Test
    void hashedIdsWithCustomBlocks() {
        BlockMapper mapper = TestSessions.mapper();
        mapper.onStartGame(startGame(true));
        assertEquals(1, mapper.customBlocks().size());
        CustomBlock lamp = mapper.customBlocks().getFirst();
        assertEquals(2 * 3 * 4, lamp.states().size(), "properties x trait states");
        CustomBlock.State state = lamp.states().get(5);
        NbtMap states = state.states();
        assertEquals(state.runtimeId(), BlockStateHasher.hash("test:lamp", states));
        assertEquals(state.javaState(), mapper.toJava(state.runtimeId()));
        assertEquals("note_block", JavaBlockStates.blockName(JavaBlockStates.blockId(state.javaState())), "first carrier pool");
        assertEquals(state.runtimeId(), mapper.toBedrock(state.javaState()));
        assertEquals("test:lamp", mapper.bedrockName(state.runtimeId()));
        assertEquals(12, mapper.lightTable()[state.javaState()] >> 4 & 15, "carrier takes the custom light emission");
        int vanillaNoteBlock = mapper.toJava(BlockStateHasher.hash("minecraft:noteblock", NbtMap.EMPTY));
        assertNotEquals(state.javaState(), vanillaNoteBlock, "carriers never reuse a state vanilla blocks show as");
        int slab = BlockStateHasher.hash("minecraft:white_wool_slab", NbtMap.builder().putString("minecraft:vertical_half", "bottom").build());
        assertEquals(JavaBlockStates.parse("white_wool_slab[type=bottom]"), mapper.toJava(slab));
    }

    @Test
    void orderedIdsFollowTheNameHashOrder() {
        BlockMapper mapper = TestSessions.mapper();
        mapper.onStartGame(startGame(false));
        int custom = mapper.customBlocks().getFirst().states().getFirst().runtimeId();
        assertTrue(custom >= 0 && custom < BedrockBlockPalette.size() + 24);
        assertEquals("test:lamp", mapper.bedrockName(custom));
        int air = mapper.bedrockAir();
        assertEquals("minecraft:air", mapper.bedrockName(air));
        assertEquals(JavaBlockStates.AIR, mapper.toJava(air));
        int stone = mapper.toBedrock(JavaBlockStates.parse("stone"));
        assertEquals(JavaBlockStates.parse("stone"), mapper.toJava(stone));
        // The custom block sits among the vanilla names sorted by FNV-1 64.
        long order = BlockStateHasher.nameOrder("test:lamp");
        if (custom > 0 && mapper.bedrockName(custom - 1) != null && !"test:lamp".equals(mapper.bedrockName(custom - 1))) {
            assertTrue(Long.compareUnsigned(BlockStateHasher.nameOrder(mapper.bedrockName(custom - 1)), order) <= 0);
        }
    }

    @Test
    void waterlogging() {
        BlockMapper mapper = TestSessions.mapper();
        int stairs = mapper.toBedrock(JavaBlockStates.parse("oak_stairs[facing=south]"));
        int water = mapper.toBedrock(JavaBlockStates.parse("water"));
        assertTrue(mapper.isWater(water));
        assertEquals(JavaBlockStates.parse("oak_stairs[facing=south,waterlogged=true]"), mapper.toJava(stairs, water));
        assertEquals(JavaBlockStates.parse("oak_stairs[facing=south]"), mapper.toJava(stairs, mapper.bedrockAir()));
        int stone = mapper.toBedrock(JavaBlockStates.parse("stone"));
        assertEquals(JavaBlockStates.parse("stone"), mapper.toJava(stone, water), "stone cannot hold water in Java");
        int unknown = 123456789;
        assertEquals(JavaBlockStates.parse("coarse_dirt"), mapper.toJava(unknown), "visible placeholder for unknown ids");
    }
}
