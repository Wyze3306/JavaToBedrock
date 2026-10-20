package io.github.wyze3306.jtb.world.chunk;

import io.github.wyze3306.jtb.block.BlockMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads Bedrock chunk payloads ({@code LevelChunkPacket.serializedChunkData}, {@code SubChunkPacket}
 * entries) into {@link ChunkSection}s and Java biomes. Runs on worker threads; pure apart from the
 * read-only {@link BlockMapper}.
 * <p>
 * Level chunk layout (what PowerNukkitX writes, and what the vanilla client reads):
 * <pre>
 * subChunkCount x sub-chunk                    (bottom up, trailing air sections omitted)
 * one biome palette per section of the DIMENSION (not subChunkCount; 0xFF = copy the previous one)
 * u8 border block count + that many bytes
 * block entities: network NBT compounds until the end (PowerNukkitX writes a lone 0 when there are none)
 * </pre>
 * Sub-chunk: {@code u8 version} (9: {@code u8 layers, i8 sectionY}; 8: {@code u8 layers}; 1: one
 * layer), then paletted storages: {@code u8 (bitsPerBlock << 1 | runtime)}, {@code ceil(4096 / (32 /
 * bits))} little-endian words with blocks in XZY order, zigzag VarInt palette size and entries
 * (runtime ids, or network NBT states when not runtime). Bits 0 is a single entry without words.
 * Like the Bedrock client, decoding stops quietly at a truncated payload and keeps what it read.
 */
public final class BedrockChunkDecoder {

    /** Biome palette header meaning "same as the previous section". */
    private static final int COPY_LAST = 0x7F;

    private static final ThreadLocal<int[]> WORDS = ThreadLocal.withInitial(() -> new int[4096]);

    /** What a level chunk payload holds. */
    public record LevelChunkData(@Nullable ChunkSection[] sections, int[][] biomes, List<NbtMap> blockEntities) {
    }

    /** One sub-chunk: its Bedrock section y, the section and the block entities that followed it. */
    public record SubChunkData(int sectionY, ChunkSection section, List<NbtMap> blockEntities) {
    }

    private BedrockChunkDecoder() {
    }

    /**
     * Decodes a full level chunk.
     *
     * @param data          the payload (read from its reader index; not released)
     * @param subChunkCount sub-chunks before the biomes (0 in sub-chunk request mode)
     */
    public static LevelChunkData decodeLevelChunk(ByteBuf data, int subChunkCount, ChunkLayout layout,
                                                  BlockMapper mapper, BiomeTable biomeTable) {
        ChunkSection[] sections = new ChunkSection[layout.javaSections()];
        int[][] biomes = new int[layout.javaSections()][];
        List<NbtMap> blockEntities = new ArrayList<>(0);
        try {
            for (int i = 0; i < subChunkCount && data.isReadable(); i++) {
                int[] sectionY = {layout.bedrockMinSection() + i};
                ChunkSection section = readSubChunk(data, sectionY, mapper);
                int index = layout.javaIndexOfBedrockSection(sectionY[0]);
                if (index >= 0 && section != null && !section.isEmpty()) {
                    sections[index] = section;
                }
            }
            int[] previous = null;
            for (int i = 0; i < layout.bedrockSections() && data.isReadable(); i++) {
                int[] cells = readBiomes(data, previous, biomeTable, layout.defaultBiome());
                int index = layout.javaIndexOfBedrockSection(layout.bedrockMinSection() + i);
                if (index >= 0) {
                    biomes[index] = cells;
                }
                previous = cells;
            }
            if (data.isReadable()) {
                data.skipBytes(Math.min(data.readUnsignedByte(), data.readableBytes()));
            }
            readBlockEntities(data, blockEntities);
        } catch (IndexOutOfBoundsException truncated) {
            // Keep whatever was read, as the Bedrock client does.
        }
        int[] fill = new int[]{layout.defaultBiome()};
        for (int i = 0; i < biomes.length; i++) {
            if (biomes[i] == null) {
                biomes[i] = i > 0 && biomes[i - 1] != null ? biomes[i - 1] : fill;
            }
        }
        return new LevelChunkData(sections, biomes, blockEntities);
    }

    /**
     * Decodes one {@code SubChunkPacket} entry payload: a sub-chunk followed by its block entities.
     *
     * @param sectionY the requested section y, used when the sub-chunk version does not carry it
     */
    public static SubChunkData decodeSubChunk(ByteBuf data, int sectionY, BlockMapper mapper) {
        int[] y = {sectionY};
        ChunkSection section = null;
        List<NbtMap> blockEntities = new ArrayList<>(0);
        try {
            section = readSubChunk(data, y, mapper);
            readBlockEntities(data, blockEntities);
        } catch (IndexOutOfBoundsException truncated) {
            // Keep whatever was read.
        }
        return new SubChunkData(y[0], section == null ? ChunkSection.air(mapper.bedrockAir()) : section, blockEntities);
    }

    /** Reads a sub-chunk; {@code sectionY[0]} is the expected y on entry and the y the sub-chunk declares on exit. */
    static @Nullable ChunkSection readSubChunk(ByteBuf data, int[] sectionY, BlockMapper mapper) {
        int version = data.readUnsignedByte();
        int layers;
        switch (version) {
            case 1 -> layers = 1;
            case 8 -> layers = data.readUnsignedByte();
            case 9 -> {
                layers = data.readUnsignedByte();
                sectionY[0] = data.readByte();
            }
            default -> throw new UnsupportedSubChunkException(version);
        }
        int air = mapper.bedrockAir();
        BlockStorage layer0 = BlockStorage.uniform(air);
        BlockStorage layer1 = null;
        for (int layer = 0; layer < layers; layer++) {
            BlockStorage storage = readBlockStorage(data, mapper);
            if (layer == 0) {
                layer0 = storage;
            } else if (layer == 1 && !(storage.isUniform() && storage.get(0) == air)) {
                layer1 = storage;
            }
        }
        return new ChunkSection(layer0, layer1, toJava(layer0, layer1, mapper));
    }

    /** Java states of a section from its two Bedrock layers. */
    static BlockStorage toJava(BlockStorage layer0, @Nullable BlockStorage layer1, BlockMapper mapper) {
        int[] palette0 = layer0.palette();
        int[] java0 = new int[palette0.length];
        for (int i = 0; i < palette0.length; i++) {
            java0[i] = mapper.toJava(palette0[i]);
        }
        boolean anyWater = false;
        boolean[] water = null;
        if (layer1 != null) {
            int[] palette1 = layer1.palette();
            water = new boolean[palette1.length];
            for (int i = 0; i < palette1.length; i++) {
                water[i] = palette1[i] != mapper.bedrockAir() && mapper.isWater(palette1[i]);
                anyWater |= water[i];
            }
        }
        if (!anyWater) {
            return layer0.isUniform() ? BlockStorage.uniform(java0[0]) : layer0.withPalette(java0);
        }
        int[] palette1 = layer1.palette();
        BlockStorage.Builder builder = BlockStorage.Builder.filled(java0[layer0.paletteIndex(0)]);
        int lastKey = -1;
        int lastJava = 0;
        for (int index = 0; index < BlockStorage.SIZE; index++) {
            int p0 = layer0.paletteIndex(index);
            int p1 = layer1.paletteIndex(index);
            int key = (p0 << 16) | p1;
            if (key != lastKey) {
                lastKey = key;
                lastJava = water[p1] ? mapper.waterlog(java0[p0], palette1[p1]) : java0[p0];
            }
            builder.set(index, lastJava);
        }
        return builder.build();
    }

    /** A block paletted storage with its entries as runtime ids. */
    static BlockStorage readBlockStorage(ByteBuf data, BlockMapper mapper) {
        int header = data.readUnsignedByte();
        int bits = header >> 1;
        boolean runtime = (header & 1) != 0;
        if (bits == 0) {
            return BlockStorage.uniform(runtime ? VarInts.readInt(data) : readPersistent(data, mapper));
        }
        if (bits == COPY_LAST) {
            throw new IllegalStateException("Block storage cannot copy the previous one");
        }
        int[] words = readWords(data, bits);
        int size = VarInts.readInt(data);
        if (size <= 0 || size > BlockStorage.SIZE) {
            throw new IllegalStateException("Invalid block palette size " + size);
        }
        int[] palette = new int[size];
        for (int i = 0; i < size; i++) {
            palette[i] = runtime ? VarInts.readInt(data) : readPersistent(data, mapper);
        }
        return unpack(words, bits, palette);
    }

    private static int readPersistent(ByteBuf data, BlockMapper mapper) {
        try (NBTInputStream in = NbtUtils.createNetworkReader(new ByteBufInputStream(data))) {
            NbtMap state = (NbtMap) in.readTag();
            return mapper.runtimeIdOf(state.getString("name"), state.getCompound("states"));
        } catch (IOException e) {
            throw new IllegalStateException("Invalid persistent block palette entry", e);
        }
    }

    private static int[] readWords(ByteBuf data, int bits) {
        if (bits > 16 || 32 % bits != 0 && bits != 3 && bits != 5 && bits != 6) {
            throw new IllegalStateException("Invalid bits per block " + bits);
        }
        int perWord = 32 / bits;
        int count = (BlockStorage.SIZE + perWord - 1) / perWord;
        int[] words = WORDS.get();
        for (int i = 0; i < count; i++) {
            words[i] = data.readIntLE();
        }
        return words;
    }

    /** Unpacks XZY-ordered indices into a storage indexed YZX. */
    private static BlockStorage unpack(int[] words, int bits, int[] palette) {
        int perWord = 32 / bits;
        int mask = (1 << bits) - 1;
        int max = palette.length - 1;
        if (palette.length <= 256) {
            byte[] out = new byte[BlockStorage.SIZE];
            int i = 0;
            for (int w = 0; i < BlockStorage.SIZE; w++) {
                int word = words[w];
                for (int k = 0; k < perWord && i < BlockStorage.SIZE; k++, i++) {
                    int value = (word >>> (k * bits)) & mask;
                    // i = x << 8 | z << 4 | y  ->  y << 8 | z << 4 | x
                    out[((i & 15) << 8) | (i & 0xF0) | (i >> 8)] = (byte) (value > max ? 0 : value);
                }
            }
            return BlockStorage.ofBytes(palette, out);
        }
        char[] out = new char[BlockStorage.SIZE];
        int i = 0;
        for (int w = 0; i < BlockStorage.SIZE; w++) {
            int word = words[w];
            for (int k = 0; k < perWord && i < BlockStorage.SIZE; k++, i++) {
                int value = (word >>> (k * bits)) & mask;
                out[((i & 15) << 8) | (i & 0xF0) | (i >> 8)] = (char) (value > max ? 0 : value);
            }
        }
        return BlockStorage.ofChars(palette, out);
    }

    /**
     * Reads one biome palette and reduces it to Java's 4x4x4 cells (majority of each cell).
     *
     * @return 1 entry when uniform, else 64 entries indexed {@code (y << 4) | (z << 2) | x}
     */
    static int[] readBiomes(ByteBuf data, int @Nullable [] previous, BiomeTable table, int fallback) {
        int header = data.readUnsignedByte();
        int bits = header >> 1;
        if (bits == COPY_LAST) {
            return previous != null ? previous : new int[]{fallback};
        }
        if (bits == 0) {
            return new int[]{table.toJava(VarInts.readInt(data), fallback)};
        }
        int[] words = readWords(data, bits);
        int size = VarInts.readInt(data);
        if (size <= 0 || size > BlockStorage.SIZE) {
            throw new IllegalStateException("Invalid biome palette size " + size);
        }
        int[] palette = new int[size];
        for (int i = 0; i < size; i++) {
            palette[i] = table.toJava(VarInts.readInt(data), fallback);
        }
        if (size == 1) {
            return new int[]{palette[0]};
        }
        int perWord = 32 / bits;
        int mask = (1 << bits) - 1;
        int[] cells = new int[64];
        int[] counts = new int[size];
        boolean uniform = true;
        for (int cell = 0; cell < 64; cell++) {
            int cx = cell & 3;
            int cz = (cell >> 2) & 3;
            int cy = cell >> 4;
            Arrays.fill(counts, 0);
            int best = 0;
            for (int dx = 0; dx < 4; dx++) {
                for (int dz = 0; dz < 4; dz++) {
                    for (int dy = 0; dy < 4; dy++) {
                        int i = ((cx * 4 + dx) << 8) | ((cz * 4 + dz) << 4) | (cy * 4 + dy);
                        int value = (words[i / perWord] >>> ((i % perWord) * bits)) & mask;
                        if (value < size && ++counts[value] > counts[best]) {
                            best = value;
                        }
                    }
                }
            }
            cells[cell] = palette[best];
            uniform &= cells[cell] == cells[0];
        }
        return uniform ? new int[]{cells[0]} : cells;
    }

    private static void readBlockEntities(ByteBuf data, List<NbtMap> out) {
        while (data.isReadable()) {
            if (data.getByte(data.readerIndex()) == 0) {
                data.skipBytes(1);
                continue;
            }
            try (NBTInputStream in = NbtUtils.createNetworkReader(new ByteBufInputStream(data))) {
                if (in.readTag() instanceof NbtMap tag) {
                    out.add(tag);
                }
            } catch (IOException | RuntimeException e) {
                return;
            }
        }
    }

    /** A legacy sub-chunk version (before 1.2.13), which no current server sends. */
    public static final class UnsupportedSubChunkException extends IllegalStateException {
        UnsupportedSubChunkException(int version) {
            super("Unsupported sub-chunk version " + version);
        }
    }
}
