package io.github.wyze3306.jtb.world.chunk;

import io.github.wyze3306.jtb.block.JavaBlockStates;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.protocol.data.game.level.HeightmapTypes;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/**
 * Writes Java 26.3 chunk data: the section array of {@code ClientboundLevelChunkWithLightPacket}
 * (per section: {@code short blockCount, short fluidCount}, block states and biomes as paletted
 * containers) and the heightmaps the client uses. Runs on worker threads.
 * <p>
 * Paletted containers: {@code u8 bits}; 0 = single value (VarInt); blocks use a palette for 1..8
 * bits (written with at least 4, as the client reads 1..4 as a 4-bit linear palette) and the global
 * palette beyond ({@code ceil(log2(stateCount))} bits); biomes use a palette up to 3 bits. Palette:
 * VarInt length + VarInt ids, then the packed longs without length prefix (values do not span longs).
 */
public final class JavaChunkEncoder {

    private static final int BLOCK_GLOBAL_BITS = ceilLog2(JavaBlockStates.count());
    private static final int BIOME_CELLS = 64;

    private static final ThreadLocal<int[]> REMAP = ThreadLocal.withInitial(() -> {
        int[] remap = new int[JavaBlockStates.count()];
        Arrays.fill(remap, -1);
        return remap;
    });

    private JavaChunkEncoder() {
    }

    /** The section data of a chunk, bottom to top: one entry per Java section (null = air). */
    public static byte[] encodeSections(@Nullable ChunkSection[] sections, int[][] biomes, int biomeRegistrySize) {
        ByteBuf buf = Unpooled.buffer(4096 + sections.length * 64);
        try {
            int[] remap = REMAP.get();
            int biomeGlobalBits = ceilLog2(biomeRegistrySize);
            for (int s = 0; s < sections.length; s++) {
                ChunkSection section = sections[s];
                if (section == null) {
                    buf.writeShort(0);
                    buf.writeShort(0);
                    buf.writeByte(0);
                    writeVarInt(buf, JavaBlockStates.AIR);
                } else {
                    writeBlocks(buf, section.java(), remap);
                }
                writeBiomes(buf, biomes[s], biomeGlobalBits);
            }
            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }

    private static void writeBlocks(ByteBuf buf, BlockStorage storage, int[] remap) {
        int[] palette = storage.palette();
        // Distinct states (palettes may hold duplicates once Bedrock ids are mapped to Java states).
        int[] distinct = new int[palette.length];
        int[] paletteToDistinct = new int[palette.length];
        int n = 0;
        for (int i = 0; i < palette.length; i++) {
            int state = palette[i];
            int index = remap[state];
            if (index < 0) {
                index = n;
                remap[state] = n;
                distinct[n++] = state;
            }
            paletteToDistinct[i] = index;
        }
        for (int i = 0; i < n; i++) {
            remap[distinct[i]] = -1;
        }
        int blockCount = 0;
        int fluidCount = 0;
        if (storage.isUniform()) {
            int state = palette[0];
            blockCount = JavaBlockStates.isAir(state) ? 0 : BlockStorage.SIZE;
            fluidCount = JavaBlockStates.hasFluid(state) ? BlockStorage.SIZE : 0;
        } else {
            int[] perPalette = new int[palette.length];
            for (int i = 0; i < BlockStorage.SIZE; i++) {
                perPalette[storage.paletteIndex(i)]++;
            }
            for (int i = 0; i < palette.length; i++) {
                if (!JavaBlockStates.isAir(palette[i])) {
                    blockCount += perPalette[i];
                }
                if (JavaBlockStates.hasFluid(palette[i])) {
                    fluidCount += perPalette[i];
                }
            }
        }
        buf.writeShort(blockCount);
        buf.writeShort(fluidCount);
        if (n == 1) {
            buf.writeByte(0);
            writeVarInt(buf, distinct[0]);
            return;
        }
        int bits = Math.max(4, ceilLog2(n));
        boolean global = bits > 8;
        if (global) {
            bits = BLOCK_GLOBAL_BITS;
        }
        buf.writeByte(bits);
        if (!global) {
            writeVarInt(buf, n);
            for (int i = 0; i < n; i++) {
                writeVarInt(buf, distinct[i]);
            }
        }
        int perLong = 64 / bits;
        int longs = (BlockStorage.SIZE + perLong - 1) / perLong;
        int index = 0;
        for (int l = 0; l < longs; l++) {
            long value = 0;
            for (int k = 0; k < perLong && index < BlockStorage.SIZE; k++, index++) {
                int p = storage.paletteIndex(index);
                long entry = global ? palette[p] : paletteToDistinct[p];
                value |= entry << (k * bits);
            }
            buf.writeLong(value);
        }
    }

    private static void writeBiomes(ByteBuf buf, int[] cells, int globalBits) {
        if (cells.length == 1) {
            buf.writeByte(0);
            writeVarInt(buf, cells[0]);
            return;
        }
        int[] distinct = new int[BIOME_CELLS];
        int n = 0;
        int[] indices = new int[BIOME_CELLS];
        for (int i = 0; i < BIOME_CELLS; i++) {
            int found = -1;
            for (int d = 0; d < n; d++) {
                if (distinct[d] == cells[i]) {
                    found = d;
                    break;
                }
            }
            if (found < 0) {
                found = n;
                distinct[n++] = cells[i];
            }
            indices[i] = found;
        }
        if (n == 1) {
            buf.writeByte(0);
            writeVarInt(buf, distinct[0]);
            return;
        }
        int bits = ceilLog2(n);
        boolean global = bits > 3;
        if (global) {
            bits = globalBits;
        }
        buf.writeByte(bits);
        if (!global) {
            writeVarInt(buf, n);
            for (int i = 0; i < n; i++) {
                writeVarInt(buf, distinct[i]);
            }
        }
        int perLong = 64 / bits;
        int longs = (BIOME_CELLS + perLong - 1) / perLong;
        int index = 0;
        for (int l = 0; l < longs; l++) {
            long value = 0;
            for (int k = 0; k < perLong && index < BIOME_CELLS; k++, index++) {
                long entry = global ? cells[index] : indices[index];
                value |= entry << (k * bits);
            }
            buf.writeLong(value);
        }
    }

    /**
     * WORLD_SURFACE, MOTION_BLOCKING and MOTION_BLOCKING_NO_LEAVES (what the client uses): per column
     * {@code x + z * 16}, the height above the world bottom of the highest matching block, plus one (0 when none).
     */
    public static Map<HeightmapTypes, long[]> heightmaps(@Nullable ChunkSection[] sections) {
        int height = sections.length << 4;
        int bits = ceilLog2(height + 1);
        int[] surface = new int[256];
        int[] motion = new int[256];
        int[] noLeaves = new int[256];
        int remaining = 256 * 3;
        for (int s = sections.length - 1; s >= 0 && remaining > 0; s--) {
            ChunkSection section = sections[s];
            if (section == null) {
                continue;
            }
            BlockStorage java = section.java();
            if (java.isUniform() && JavaBlockStates.isAir(java.get(0))) {
                continue;
            }
            for (int column = 0; column < 256; column++) {
                if (surface[column] != 0 && motion[column] != 0 && noLeaves[column] != 0) {
                    continue;
                }
                for (int y = 15; y >= 0; y--) {
                    int state = java.get((y << 8) | column);
                    if (JavaBlockStates.isAir(state)) {
                        continue;
                    }
                    int value = (s << 4) + y + 1;
                    if (surface[column] == 0) {
                        surface[column] = value;
                        remaining--;
                    }
                    if (motion[column] == 0 && JavaBlockStates.isMotionBlocking(state)) {
                        motion[column] = value;
                        remaining--;
                    }
                    if (noLeaves[column] == 0 && JavaBlockStates.isMotionBlockingNoLeaves(state)) {
                        noLeaves[column] = value;
                        remaining--;
                        break;
                    }
                }
            }
        }
        Map<HeightmapTypes, long[]> maps = new EnumMap<>(HeightmapTypes.class);
        maps.put(HeightmapTypes.WORLD_SURFACE, pack(surface, bits));
        maps.put(HeightmapTypes.MOTION_BLOCKING, pack(motion, bits));
        maps.put(HeightmapTypes.MOTION_BLOCKING_NO_LEAVES, pack(noLeaves, bits));
        return maps;
    }

    private static long[] pack(int[] values, int bits) {
        int perLong = 64 / bits;
        long[] out = new long[(values.length + perLong - 1) / perLong];
        for (int i = 0; i < values.length; i++) {
            out[i / perLong] |= (long) values[i] << ((i % perLong) * bits);
        }
        return out;
    }

    static int ceilLog2(int value) {
        return value <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(value - 1);
    }

    static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}
