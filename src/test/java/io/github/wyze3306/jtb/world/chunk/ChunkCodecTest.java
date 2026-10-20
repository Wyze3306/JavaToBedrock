package io.github.wyze3306.jtb.world.chunk;

import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.block.JavaBlockStates;
import io.github.wyze3306.jtb.world.TestSessions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.level.HeightmapTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bedrock level chunk payloads (as PowerNukkitX writes them) to sections, and sections to Java
 * chunk data read back with MCProtocolLib's own codec.
 */
class ChunkCodecTest {

    private static final ChunkLayout OVERWORLD = new ChunkLayout(-4, 24, -4, 24, true, 1);

    private static BlockMapper mapper;
    private static int air;
    private static int stone;
    private static int stairs;
    private static int water;

    @BeforeAll
    static void setUp() {
        mapper = TestSessions.mapper();
        air = mapper.bedrockAir();
        stone = mapper.toBedrock(JavaBlockStates.parse("stone"));
        stairs = mapper.toBedrock(JavaBlockStates.parse("oak_stairs[facing=east,half=bottom]"));
        water = mapper.toBedrock(JavaBlockStates.parse("water[level=0]"));
    }

    /** Writes one paletted storage; {@code values} are in Bedrock XZY order. */
    private static void writeStorage(ByteBuf out, int bits, int[] palette, int[] values) {
        out.writeByte(bits << 1 | 1);
        if (bits == 0) {
            VarInts.writeInt(out, palette[0]);
            return;
        }
        int perWord = 32 / bits;
        int words = (4096 + perWord - 1) / perWord;
        for (int w = 0; w < words; w++) {
            int word = 0;
            for (int k = 0; k < perWord; k++) {
                int i = w * perWord + k;
                if (i < 4096) {
                    word |= values[i] << (k * bits);
                }
            }
            out.writeIntLE(word);
        }
        VarInts.writeInt(out, palette.length);
        for (int value : palette) {
            VarInts.writeInt(out, value);
        }
    }

    private static int bedrockIndex(int x, int y, int z) {
        return (x << 8) | (z << 4) | y;
    }

    private static ByteBuf levelChunk(NbtMap blockEntity) throws IOException {
        ByteBuf out = Unpooled.buffer();
        // Section y = -4: stone floor (y 0), stairs at (3, 1, 5) in water.
        out.writeByte(9);
        out.writeByte(2);
        out.writeByte(-4);
        int[] blocks = new int[4096];
        int[] liquid = new int[4096];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                blocks[bedrockIndex(x, 0, z)] = 1;
            }
        }
        blocks[bedrockIndex(3, 1, 5)] = 2;
        liquid[bedrockIndex(3, 1, 5)] = 1;
        liquid[bedrockIndex(4, 1, 5)] = 1;
        writeStorage(out, 2, new int[]{air, stone, stairs}, blocks);
        writeStorage(out, 1, new int[]{air, water}, liquid);
        // Section y = -3: uniform stone (single entry storage), one layer.
        out.writeByte(9);
        out.writeByte(1);
        out.writeByte(-3);
        writeStorage(out, 0, new int[]{stone}, null);
        // Biomes: section 0 split between plains (1) and desert (2) along x, then "same as previous".
        int[] biomes = new int[4096];
        for (int x = 8; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 16; y++) {
                    biomes[bedrockIndex(x, y, z)] = 1;
                }
            }
        }
        writeStorage(out, 1, new int[]{1, 2}, biomes);
        for (int i = 1; i < 24; i++) {
            out.writeByte(0xFF);
        }
        out.writeByte(0);
        try (NBTOutputStream nbt = NbtUtils.createNetworkWriter(new ByteBufOutputStream(out))) {
            nbt.writeTag(blockEntity);
        }
        return out;
    }

    @Test
    void decodesPowerNukkitXLevelChunks() throws IOException {
        int[] javaBiomes = new int[256];
        java.util.Arrays.fill(javaBiomes, -1);
        javaBiomes[1] = 41;
        javaBiomes[2] = 14;
        BiomeTable table = new BiomeTable(javaBiomes, 67);
        NbtMap chest = NbtMap.builder().putString("id", "Chest").putInt("x", 7).putInt("y", -63).putInt("z", 7).build();
        ByteBuf data = levelChunk(chest);
        BedrockChunkDecoder.LevelChunkData chunk = BedrockChunkDecoder.decodeLevelChunk(data, 2, OVERWORLD, mapper, table);
        assertFalse(data.isReadable(), "whole payload consumed");
        data.release();

        ChunkSection bottom = chunk.sections()[0];
        assertNotNull(bottom);
        assertEquals(JavaBlockStates.parse("stone"), bottom.javaAt(BlockStorage.index(9, 0, 2)));
        assertEquals(JavaBlockStates.AIR, bottom.javaAt(BlockStorage.index(9, 2, 2)));
        int wetStairs = JavaBlockStates.parse("oak_stairs[facing=east,half=bottom,waterlogged=true]");
        assertEquals(wetStairs, bottom.javaAt(BlockStorage.index(3, 1, 5)), "stairs with water in the liquid layer are waterlogged");
        assertEquals(JavaBlockStates.parse("water[level=0]"), bottom.javaAt(BlockStorage.index(4, 1, 5)), "water alone in the liquid layer");
        assertEquals(stairs, bottom.bedrockAt(BlockStorage.index(3, 1, 5), 0, air));
        assertEquals(water, bottom.bedrockAt(BlockStorage.index(3, 1, 5), 1, air));
        assertTrue(chunk.sections()[1].java().isUniform());
        assertNull(chunk.sections()[2], "sections above the payload are air");

        assertEquals(64, chunk.biomes()[0].length);
        assertEquals(41, chunk.biomes()[0][0], "x 0..7 is plains");
        assertEquals(14, chunk.biomes()[0][2], "x 8..15 is desert");
        assertArrayEquals(chunk.biomes()[0], chunk.biomes()[23], "copied up");
        assertEquals(1, chunk.blockEntities().size());
        assertEquals("Chest", chunk.blockEntities().getFirst().getString("id"));
    }

    @Test
    void javaChunkDataReadsBackWithTheJavaCodec() throws IOException {
        int[] javaBiomes = new int[256];
        java.util.Arrays.fill(javaBiomes, 41);
        BiomeTable table = new BiomeTable(javaBiomes, 67);
        ByteBuf data = levelChunk(NbtMap.builder().putString("id", "Sign").putInt("x", 0).putInt("y", -63).putInt("z", 0).build());
        BedrockChunkDecoder.LevelChunkData chunk = BedrockChunkDecoder.decodeLevelChunk(data, 2, OVERWORLD, mapper, table);
        data.release();
        byte[] encoded = JavaChunkEncoder.encodeSections(chunk.sections(), chunk.biomes(), 67);
        ByteBuf in = Unpooled.wrappedBuffer(encoded);
        org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection[] sections =
                new org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection[24];
        for (int i = 0; i < 24; i++) {
            sections[i] = MinecraftTypes.readChunkSection(in, JavaBlockStates.count(), 67);
        }
        assertFalse(in.isReadable());
        assertEquals(256 + 1 + 1, sections[0].getBlockCount(), "floor, stairs and water");
        assertEquals(2, sections[0].getFluidCount());
        assertEquals(4096, sections[1].getBlockCount());
        assertEquals(0, sections[2].getBlockCount());
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals(chunk.sections()[0].javaAt(BlockStorage.index(x, y, z)), sections[0].getBlock(x, y, z));
                }
            }
        }
        assertEquals(41, sections[5].getBiomeData().getPalette().idToState(0));

        Map<HeightmapTypes, long[]> heightmaps = JavaChunkEncoder.heightmaps(chunk.sections());
        long[] surface = heightmaps.get(HeightmapTypes.WORLD_SURFACE);
        assertEquals(37, surface.length, "9 bits per column, 7 per long");
        // Column (0, 0): the top block is the stone of section 1 (y -33): height above the bottom 32.
        assertEquals(32, surface[0] & 0x1FF);
    }
}
