package io.github.wyze3306.jtb.world.light;

import io.github.wyze3306.jtb.block.BlockData;
import io.github.wyze3306.jtb.block.JavaBlockStates;
import io.github.wyze3306.jtb.world.chunk.BlockStorage;
import io.github.wyze3306.jtb.world.chunk.ChunkLayout;
import io.github.wyze3306.jtb.world.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sky and block light on small worlds of 4 sections (y 0..63), compared with the vanilla rules.
 */
class LightEngineTest {

    private static final ChunkLayout LAYOUT = new ChunkLayout(0, 4, 0, 4, true, 0);
    private static int stone;
    private static int glowstone;
    private static int torch;
    private static int bottomSlab;
    private static byte[] lightTable;

    @BeforeAll
    static void setUp() throws ReflectiveOperationException {
        BlockData.load();
        stone = JavaBlockStates.parse("stone");
        glowstone = JavaBlockStates.parse("glowstone");
        torch = JavaBlockStates.parse("torch");
        bottomSlab = JavaBlockStates.parse("stone_slab[type=bottom]");
        var method = JavaBlockStates.class.getDeclaredMethod("lightTable");
        method.setAccessible(true);
        lightTable = (byte[]) method.invoke(null);
    }

    /** A column filled with stone up to {@code ground} (exclusive), air above. */
    private static ChunkSection[] column(int ground) {
        ChunkSection[] sections = new ChunkSection[4];
        for (int s = 0; s < 4; s++) {
            BlockStorage.Builder java = BlockStorage.Builder.filled(JavaBlockStates.AIR);
            boolean any = false;
            for (int y = 0; y < 16; y++) {
                if ((s << 4) + y < ground) {
                    any = true;
                    for (int i = 0; i < 256; i++) {
                        java.set((y << 8) | i, stone);
                    }
                }
            }
            if (any) {
                sections[s] = new ChunkSection(BlockStorage.uniform(0), null, java.build());
            }
        }
        return sections;
    }

    private static void set(ChunkSection[] sections, int x, int y, int z, int state) {
        int s = y >> 4;
        ChunkSection section = sections[s] == null ? ChunkSection.air(0) : sections[s];
        sections[s] = section.withJava(BlockStorage.index(x, y & 15, z), state);
    }

    private static int cell(int x, int y, int z) {
        return x | (z << 4) | (y << 8);
    }

    /** Light level from a light update data (light section = block section + 1). */
    private static int level(LightUpdateData data, boolean sky, int x, int y, int z) {
        BitSet mask = sky ? data.getSkyYMask() : data.getBlockYMask();
        List<byte[]> arrays = sky ? data.getSkyUpdates() : data.getBlockUpdates();
        int section = (y >> 4) + 1;
        int array = 0;
        for (int bit = mask.nextSetBit(0); bit >= 0; bit = mask.nextSetBit(bit + 1), array++) {
            if (bit == section) {
                int index = ((y & 15) << 8) | (z << 4) | x;
                return (arrays.get(array)[index >> 1] >> ((index & 1) << 2)) & 15;
            }
        }
        return 0;
    }

    @Test
    void openSkyAndCave() {
        LightEngine engine = new LightEngine(LAYOUT, lightTable);
        ChunkSection[] sections = column(32);
        // A 3x3 shaft from the surface down to y 20, and a tunnel at y 20 going +x.
        for (int y = 20; y < 32; y++) {
            for (int x = 6; x <= 8; x++) {
                for (int z = 6; z <= 8; z++) {
                    set(sections, x, y, z, JavaBlockStates.AIR);
                }
            }
        }
        for (int x = 9; x < 16; x++) {
            set(sections, x, 20, 7, JavaBlockStates.AIR);
        }
        engine.load(0, 0, sections);
        LightUpdateData data = engine.lightData(0, 0);
        assertEquals(15, level(data, true, 0, 40, 0), "open sky");
        assertEquals(15, level(data, true, 7, 20, 7), "straight down a shaft keeps full sky light");
        assertEquals(0, level(data, true, 0, 10, 0), "inside stone");
        assertEquals(14, level(data, true, 9, 20, 7), "one block into the tunnel");
        assertEquals(8, level(data, true, 15, 20, 7), "sky light fades along the tunnel");
        assertEquals(0, level(data, false, 7, 20, 7), "no emitter, no block light");
    }

    @Test
    void emittersAndUpdates() {
        LightEngine engine = new LightEngine(LAYOUT, lightTable);
        ChunkSection[] sections = column(32);
        set(sections, 8, 40, 8, glowstone);
        engine.load(0, 0, sections);
        LightUpdateData data = engine.lightData(0, 0);
        assertEquals(15, level(data, false, 8, 40, 8));
        assertEquals(14, level(data, false, 9, 40, 8));
        assertEquals(12, level(data, false, 8, 40, 11));
        assertEquals(14, level(data, false, 8, 32, 8) + 7, "8 blocks down: 15 - 8");
        engine.markSent(0, 0);

        // Remove the glowstone: block light goes away and is reported.
        set(sections, 8, 40, 8, JavaBlockStates.AIR);
        engine.blocksChanged(0, 0, new int[]{cell(8, 40, 8)}, new int[]{glowstone}, new int[]{JavaBlockStates.AIR});
        LightUpdateData cleared = engine.lightData(0, 0);
        assertEquals(0, level(cleared, false, 9, 40, 8));
        int[] updates = {0};
        engine.drainUpdates((x, z, update) -> updates[0]++);
        assertEquals(1, updates[0], "one light update for the changed column");

        // A roof over the open sky darkens below it; a torch below lights it again.
        ChunkSection[] roofed = sections.clone();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                set(roofed, x, 50, z, stone);
            }
        }
        LightEngine roof = new LightEngine(LAYOUT, lightTable);
        roof.load(0, 0, roofed);
        assertEquals(0, level(roof.lightData(0, 0), true, 8, 40, 8), "no sky under a closed roof");
        set(roofed, 8, 40, 8, torch);
        roof.blocksChanged(0, 0, new int[]{cell(8, 40, 8)}, new int[]{JavaBlockStates.AIR}, new int[]{torch});
        assertEquals(14, level(roof.lightData(0, 0), false, 8, 40, 8));
        assertEquals(13, level(roof.lightData(0, 0), false, 8, 41, 8));
        // Opening the roof above lets the sky back in down the hole.
        set(roofed, 8, 50, 8, JavaBlockStates.AIR);
        roof.blocksChanged(0, 0, new int[]{cell(8, 50, 8)}, new int[]{stone}, new int[]{JavaBlockStates.AIR});
        assertEquals(15, level(roof.lightData(0, 0), true, 8, 45, 8));
        assertEquals(14, level(roof.lightData(0, 0), true, 9, 45, 8));
        // Closing it again removes it.
        set(roofed, 8, 50, 8, stone);
        roof.blocksChanged(0, 0, new int[]{cell(8, 50, 8)}, new int[]{JavaBlockStates.AIR}, new int[]{stone});
        assertEquals(0, level(roof.lightData(0, 0), true, 8, 45, 8));
        assertEquals(0, level(roof.lightData(0, 0), true, 9, 45, 8));
        // Several changes at once (one packet): a 3x3 hole opens the roof.
        int[] cells = new int[9];
        int[] old = new int[9];
        int[] now = new int[9];
        for (int i = 0; i < 9; i++) {
            set(roofed, 7 + i % 3, 50, 7 + i / 3, JavaBlockStates.AIR);
            cells[i] = cell(7 + i % 3, 50, 7 + i / 3);
            old[i] = stone;
            now[i] = JavaBlockStates.AIR;
        }
        roof.blocksChanged(0, 0, cells, old, now);
        assertEquals(15, level(roof.lightData(0, 0), true, 7, 40, 9), "sky down the 3x3 hole");
        assertEquals(14, level(roof.lightData(0, 0), true, 10, 45, 8));
    }

    @Test
    void lightCrossesChunkBordersWhateverTheLoadingOrder() {
        for (boolean emitterFirst : new boolean[]{true, false}) {
            LightEngine engine = new LightEngine(LAYOUT, lightTable);
            ChunkSection[] withTorch = column(32);
            set(withTorch, 15, 40, 8, glowstone);
            ChunkSection[] plain = column(32);
            if (emitterFirst) {
                engine.load(0, 0, withTorch);
                engine.load(1, 0, plain);
            } else {
                engine.load(1, 0, plain);
                engine.load(0, 0, withTorch);
            }
            LightUpdateData neighbour = engine.lightData(1, 0);
            assertEquals(14, level(neighbour, false, 0, 40, 8), "first block of the next chunk");
            assertEquals(5, level(neighbour, false, 9, 40, 8));
        }
    }

    @Test
    void slabsBlockLightGoingDown() {
        LightEngine engine = new LightEngine(LAYOUT, lightTable);
        ChunkSection[] sections = column(32);
        // A slab roof at y 40 over the whole column, glowstone above it.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                set(sections, x, 40, z, bottomSlab);
            }
        }
        set(sections, 8, 41, 8, glowstone);
        engine.load(0, 0, sections);
        LightUpdateData data = engine.lightData(0, 0);
        assertEquals(15, level(data, true, 2, 40, 2), "sky light enters the bottom slab from above");
        assertEquals(0, level(data, true, 2, 39, 2), "but not through its bottom face");
        assertEquals(0, level(data, false, 8, 39, 8), "neither does block light");
        assertTrue(level(data, false, 8, 40, 8) >= 14);
    }
}
