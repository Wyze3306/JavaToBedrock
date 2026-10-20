package io.github.wyze3306.jtb.world.bench;

import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.block.JavaBlockStates;
import io.github.wyze3306.jtb.world.TestSessions;
import io.github.wyze3306.jtb.world.chunk.BedrockChunkDecoder;
import io.github.wyze3306.jtb.world.chunk.BiomeTable;
import io.github.wyze3306.jtb.world.chunk.ChunkLayout;
import io.github.wyze3306.jtb.world.chunk.ChunkSection;
import io.github.wyze3306.jtb.world.chunk.JavaChunkEncoder;
import io.github.wyze3306.jtb.world.light.LightEngine;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.common.util.VarInts;

import java.util.Arrays;
import java.util.Random;

/** DEV benchmark (not a test): decode + light + encode of a radius-8 view of synthetic terrain. */
public final class ChunkPipelineBench {

    public static void main(String[] args) {
        BlockMapper mapper = TestSessions.mapper();
        ChunkLayout layout = new ChunkLayout(-4, 24, -4, 24, true, 1);
        int[] biomes = new int[256];
        Arrays.fill(biomes, 41);
        BiomeTable table = new BiomeTable(biomes, 67);
        int[] palette = {mapper.bedrockAir(), mapper.toBedrock(JavaBlockStates.parse("stone")), mapper.toBedrock(JavaBlockStates.parse("dirt")),
                mapper.toBedrock(JavaBlockStates.parse("grass_block")), mapper.toBedrock(JavaBlockStates.parse("water")),
                mapper.toBedrock(JavaBlockStates.parse("coal_ore")), mapper.toBedrock(JavaBlockStates.parse("oak_leaves")),
                mapper.toBedrock(JavaBlockStates.parse("torch")), mapper.toBedrock(JavaBlockStates.parse("lava")),
                mapper.toBedrock(JavaBlockStates.parse("deepslate")), mapper.toBedrock(JavaBlockStates.parse("oak_log"))};
        Random random = new Random(1);
        int count = 0;
        ByteBuf[] payloads = new ByteBuf[17 * 17];
        int[][] coords = new int[17 * 17][];
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                if (x * x + z * z > 64) {
                    continue;
                }
                payloads[count] = chunk(random, palette);
                coords[count++] = new int[]{x, z};
            }
        }
        for (int round = 0; round < 4; round++) {
            long decode = 0;
            long light = 0;
            long encode = 0;
            LightEngine engine = new LightEngine(layout, lightTable());
            ChunkSection[][] decoded = new ChunkSection[count][];
            int[][][] biomeData = new int[count][][];
            for (int i = 0; i < count; i++) {
                long t = System.nanoTime();
                BedrockChunkDecoder.LevelChunkData data = BedrockChunkDecoder.decodeLevelChunk(payloads[i].duplicate(), 10, layout, mapper, table);
                decode += System.nanoTime() - t;
                decoded[i] = data.sections();
                biomeData[i] = data.biomes();
                t = System.nanoTime();
                engine.load(coords[i][0], coords[i][1], decoded[i].clone());
                light += System.nanoTime() - t;
            }
            for (int i = 0; i < count; i++) {
                long t = System.nanoTime();
                JavaChunkEncoder.encodeSections(decoded[i], biomeData[i], 67);
                JavaChunkEncoder.heightmaps(decoded[i]);
                engine.lightData(coords[i][0], coords[i][1]);
                encode += System.nanoTime() - t;
            }
            System.out.printf("round %d: %d chunks, decode %.1f ms, light %.1f ms, encode %.1f ms (per chunk %.2f / %.2f / %.2f)%n",
                    round, count, decode / 1e6, light / 1e6, encode / 1e6, decode / 1e6 / count, light / 1e6 / count, encode / 1e6 / count);
        }
    }

    private static byte[] lightTable() {
        try {
            var method = JavaBlockStates.class.getDeclaredMethod("lightTable");
            method.setAccessible(true);
            return (byte[]) method.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 10 sections: stone with ores and caves below y 64, terrain surface, some leaves and torches. */
    private static ByteBuf chunk(Random random, int[] palette) {
        ByteBuf out = Unpooled.buffer();
        for (int s = 0; s < 10; s++) {
            out.writeByte(9);
            out.writeByte(2);
            out.writeByte(s - 4);
            int[] values = new int[4096];
            for (int i = 0; i < 4096; i++) {
                int x = i >> 8;
                int z = (i >> 4) & 15;
                int y = (s - 4) * 16 + (i & 15);
                int surface = 60 + (int) (4 * Math.sin((x + z) * 0.3));
                int v;
                if (y < 0) {
                    v = random.nextInt(50) == 0 ? 0 : 9;
                } else if (y < surface - 4) {
                    v = random.nextInt(30) == 0 ? 0 : random.nextInt(40) == 0 ? 5 : 1;
                } else if (y < surface) {
                    v = 2;
                } else if (y == surface) {
                    v = 3;
                } else if (y < surface + 6 && x == 7 && z == 7) {
                    v = 10;
                } else if (y > surface + 4 && y < surface + 8 && Math.abs(x - 7) < 3 && Math.abs(z - 7) < 3) {
                    v = 6;
                } else {
                    v = 0;
                }
                if (y > 10 && y < 40 && random.nextInt(2000) == 0) {
                    v = 7;
                }
                if (y < -50 && random.nextInt(500) == 0) {
                    v = 8;
                }
                values[i] = v;
            }
            write(out, 4, palette, values);
            out.writeByte(0 << 1 | 1);
            VarInts.writeInt(out, palette[0]);
        }
        for (int i = 0; i < 24; i++) {
            if (i == 0) {
                out.writeByte(1);
                VarInts.writeInt(out, 1);
            } else {
                out.writeByte(0xFF);
            }
        }
        out.writeByte(0);
        out.writeByte(0);
        return out;
    }

    private static void write(ByteBuf out, int bits, int[] palette, int[] values) {
        out.writeByte(bits << 1 | 1);
        int perWord = 32 / bits;
        for (int w = 0; w < (4096 + perWord - 1) / perWord; w++) {
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
}
