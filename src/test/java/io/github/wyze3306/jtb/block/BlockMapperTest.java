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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        // States drawn alike share their carrier: Java -> Bedrock gives one of them, of the same block and look.
        int back = mapper.toBedrock(state.javaState());
        assertEquals("test:lamp", mapper.bedrockName(back));
        assertEquals(state.javaState(), mapper.toJava(back));
        assertEquals("test:lamp", mapper.bedrockName(state.runtimeId()));
        assertEquals(12, mapper.lightTable()[state.javaState()] >> 4 & 15, "carrier takes the custom light emission");
        int vanillaNoteBlock = mapper.toJava(BlockStateHasher.hash("minecraft:noteblock", NbtMap.EMPTY));
        assertNotEquals(state.javaState(), vanillaNoteBlock, "carriers never reuse a state vanilla blocks show as");
        int slab = BlockStateHasher.hash("minecraft:white_wool_slab", NbtMap.builder().putString("minecraft:vertical_half", "bottom").build());
        assertEquals(JavaBlockStates.parse("white_wool_slab[type=bottom]"), mapper.toJava(slab));
    }

    /** A block whose {@code test:n} values (0 to {@code looks - 1}) each have their own texture. */
    private static NbtMap manyLooks(int looks, boolean collision) {
        List<Integer> values = new java.util.ArrayList<>();
        List<NbtMap> permutations = new java.util.ArrayList<>();
        for (int n = 0; n < looks; n++) {
            values.add(n);
            permutations.add(NbtMap.builder().putString("condition", "q.block_state('test:n') == " + n)
                    .putCompound("components", NbtMap.builder().putCompound("minecraft:material_instances", NbtMap.builder()
                            .putCompound("mappings", NbtMap.EMPTY).putCompound("materials", NbtMap.builder()
                                    .putCompound("*", NbtMap.builder().putString("texture", "t" + n).build()).build()).build()).build())
                    .build());
        }
        org.cloudburstmc.nbt.NbtMapBuilder components = NbtMap.builder();
        if (!collision) {
            components.putCompound("minecraft:collision_box", NbtMap.builder().putBoolean("enabled", false)
                    .putList("origin", NbtType.FLOAT, 0f, 0f, 0f).putList("size", NbtType.FLOAT, 0f, 0f, 0f).build());
        }
        return NbtMap.builder()
                .putCompound("vanilla_block_data", NbtMap.builder().putInt("block_id", 10001).build())
                .putList("properties", NbtType.COMPOUND, List.of(
                        NbtMap.builder().putString("name", "test:n").putList("enum", NbtType.INT, values).build()))
                .putList("permutations", NbtType.COMPOUND, permutations)
                .putCompound("components", components.build())
                .build();
    }

    @Test
    void looksBeyondTheCarriersAreDisplayed() {
        BlockMapper mapper = TestSessions.mapper();
        StartGamePacket packet = new StartGamePacket();
        packet.setBlockNetworkIdsAreHashes(true);
        int capacity = CustomBlockAllocator.capacity(CustomBlockAllocator.Pool.OPAQUE);
        // Defined first and with many looks: the carriers still go to the decoration blocks first.
        packet.getBlockProperties().add(new ServerBlockProperty("test:machine", manyLooks(capacity + 100, true)));
        packet.getBlockProperties().add(new ServerBlockProperty("test:ghost", manyLooks(3, false)));
        packet.getBlockProperties().add(new ServerBlockProperty("test:deco", manyLooks(2, true)));
        mapper.onStartGame(packet);
        CustomBlock machine = null;
        CustomBlock deco = null;
        for (CustomBlock block : mapper.customBlocks()) {
            if (block.identifier().equals("test:machine")) {
                machine = block;
            } else if (block.identifier().equals("test:deco")) {
                deco = block;
            }
        }
        assertNotNull(machine);
        assertNotNull(deco);
        for (CustomBlock.State state : deco.states()) {
            assertEquals(-1, mapper.blockDisplay(state.runtimeId()), "few looks: carriers");
            assertNotEquals(JavaBlockStates.defaultState("barrier"), state.javaState());
        }
        int displayed = 0;
        for (CustomBlock.State state : machine.states()) {
            int display = mapper.blockDisplay(state.runtimeId());
            if (display >= 0) {
                displayed++;
                assertEquals(JavaBlockStates.defaultState("barrier"), mapper.toJava(state.runtimeId()), "invisible block with collision");
                assertEquals("test:machine", mapper.blockDisplays().get(display).identifier());
            }
        }
        // capacity carriers: 2 for deco, 3 for ghost, the rest for the machine.
        assertEquals(capacity + 100 - (capacity - 5), displayed);
        assertEquals(displayed, mapper.blockDisplays().size());
        assertEquals(JavaBlockStates.parse("barrier"), mapper.toJava(mapper.toBedrock(JavaBlockStates.parse("barrier"))),
                "Java barriers stay vanilla barriers");
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
