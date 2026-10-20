package io.github.wyze3306.jtb.world.light;

import io.github.wyze3306.jtb.block.JavaBlockStates;
import io.github.wyze3306.jtb.world.chunk.BlockStorage;
import io.github.wyze3306.jtb.world.chunk.ChunkLayout;
import io.github.wyze3306.jtb.world.chunk.ChunkSection;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * Sky and block light of the loaded chunks of one session, computed like the vanilla 1.20+ engine
 * (the Java client does not light chunks it receives, and Bedrock servers send no light):
 * <ul>
 *     <li>sky: every cell of a column from the top down to the first cell that stops sky light
 *     (opacity above 0, or a face occluding vertically) has level 15;</li>
 *     <li>block: an emitting cell has its emission;</li>
 *     <li>light spreads to the 6 neighbours losing {@code max(1, opacity of the target)}, unless the
 *     shared face is occluded by either block's light occlusion shape (slabs, stairs, snow...).</li>
 * </ul>
 * Loading a column only adds light (an absent column propagates nothing), so it seeds the new
 * column's sources plus its loaded neighbours' borders and spreads, which is exact whatever the
 * loading order. Block changes run the usual removal pass (conservative: every dependent cell is
 * cleared, then refilled from the boundary) followed by an increase pass. Light never travels more
 * than 15 blocks, so every pass stays within the 3x3 columns around the chunk it starts in.
 * <p>
 * Not thread safe: confined to the session's chunk worker.
 */
public final class LightEngine {

    /** Direction offsets in {@code Direction} order: down, up, north, south, west, east. */
    private static final int[] DX = {0, 0, 0, 0, -1, 1};
    private static final int[] DY = {-1, 1, 0, 0, 0, 0};
    private static final int[] DZ = {0, 0, -1, 1, 0, 0};
    private static final int[] OPPOSITE = {1, 0, 3, 2, 5, 4};

    private static final byte[] FULL = filled(15);

    private final ChunkLayout layout;
    private final byte[] lightTable;
    private final boolean hasSky;
    private final int lightSections;
    private final int height;
    private final Long2ObjectOpenHashMap<LightColumn> columns = new Long2ObjectOpenHashMap<>();
    private final LightColumn[] region = new LightColumn[9];
    private int regionX;
    private int regionZ;
    private final IntQueue increase = new IntQueue();
    private final IntQueue decrease = new IntQueue();
    private final IntQueue emitters = new IntQueue();

    /**
     * @param lightTable {@code emission << 4 | opacity} per Java state (see {@code BlockMapper.lightTable()})
     */
    public LightEngine(ChunkLayout layout, byte[] lightTable) {
        this.layout = layout;
        this.lightTable = lightTable;
        this.hasSky = layout.skyLight();
        this.lightSections = layout.javaSections() + 2;
        this.height = this.lightSections << 4;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public boolean isLoaded(int x, int z) {
        return this.columns.containsKey(key(x, z));
    }

    // ------------------------------------------------------------------ public operations

    /** Adds a column and lights it (and the light it brings to its neighbours). */
    public void load(int x, int z, @Nullable ChunkSection[] sections) {
        LightColumn column = new LightColumn(x, z, sections, this.lightSections);
        this.columns.put(key(x, z), column);
        selectRegion(x, z);
        if (this.hasSky) {
            for (int c = 0; c < 256; c++) {
                column.skySource[c] = computeSource(column, c & 15, c >> 4);
            }
            fillSkySources(column);
            seedSkySources(column);
            seedNeighbourBorders(true);
            propagateIncrease(true);
        }
        seedEmitters(column);
        seedNeighbourBorders(false);
        propagateIncrease(false);
        column.dirtySky = 0;
        column.dirtyBlock = 0;
    }

    public void unload(int x, int z) {
        this.columns.remove(key(x, z));
    }

    public void clear() {
        this.columns.clear();
    }

    /**
     * Applies a block change: {@code section} replaced Java section {@code sectionIndex} of the column,
     * and the block at {@code (lx, y, lz)} (Java block y) went from {@code oldState} to {@code newState}.
     */
    public void blockChanged(int x, int z, int sectionIndex, ChunkSection section, int lx, int y, int lz,
                             int oldState, int newState) {
        LightColumn column = this.columns.get(key(x, z));
        if (column == null) {
            return;
        }
        column.sections[sectionIndex] = section;
        if (this.lightTable[oldState] == this.lightTable[newState]
                && JavaBlockStates.occlusion(oldState) == JavaBlockStates.occlusion(newState)) {
            return;
        }
        selectRegion(x, z);
        int ly = y - this.layout.javaMinY() + 16;
        int rx = 16 + lx;
        int rz = 16 + lz;
        if (this.hasSky) {
            int c = lx | (lz << 4);
            int oldSource = column.skySource[c];
            int newSource = computeSource(column, lx, lz);
            column.skySource[c] = newSource;
            for (int sy = oldSource; sy < newSource; sy++) {
                column.setSky(lx, sy, lz, 0);
                this.decrease.add(pack(rx, rz, sy, 15));
            }
            int level = column.sky(lx, ly, lz);
            if (ly < newSource && level > 0 && (ly < oldSource || ly >= newSource)) {
                column.setSky(lx, ly, lz, 0);
                this.decrease.add(pack(rx, rz, ly, level));
            }
            propagateDecrease(true);
            for (int sy = newSource; sy < oldSource; sy++) {
                column.setSky(lx, sy, lz, 15);
                this.increase.add(pack(rx, rz, sy, 15));
            }
            seedAround(rx, rz, ly, true);
            propagateIncrease(true);
        }
        int level = column.block(lx, ly, lz);
        if (level > 0) {
            column.setBlock(lx, ly, lz, 0);
            this.decrease.add(pack(rx, rz, ly, level));
        }
        propagateDecrease(false);
        int emission = this.lightTable[newState] >> 4 & 15;
        if (emission > 0) {
            column.setBlock(lx, ly, lz, emission);
            this.increase.add(pack(rx, rz, ly, emission));
        }
        seedAround(rx, rz, ly, false);
        propagateIncrease(false);
    }

    /** Marks a column as known by the client: later changes are reported by {@link #drainUpdates}. */
    public void markSent(int x, int z) {
        LightColumn column = this.columns.get(key(x, z));
        if (column != null) {
            column.sent = true;
            column.dirtySky = 0;
            column.dirtyBlock = 0;
        }
    }

    /** Full light of a column for its chunk packet (arrays copied). */
    public LightUpdateData lightData(int x, int z) {
        LightColumn column = this.columns.get(key(x, z));
        long all = this.lightSections >= 64 ? -1L : (1L << this.lightSections) - 1;
        return column == null ? fullBright(this.hasSky, this.lightSections) : data(column, all, all);
    }

    /** One light update per sent column whose light changed since the last call. */
    public void drainUpdates(UpdateSink sink) {
        for (LightColumn column : this.columns.values()) {
            if (column.sent && (column.dirtySky | column.dirtyBlock) != 0) {
                LightUpdateData data = data(column, column.dirtySky, column.dirtyBlock);
                column.dirtySky = 0;
                column.dirtyBlock = 0;
                sink.accept(column.x, column.z, data);
            }
        }
    }

    /** Receives a light update for a column. */
    @FunctionalInterface
    public interface UpdateSink {
        void accept(int x, int z, LightUpdateData data);
    }

    /** Light data for "full-bright" mode or columns without light: sky 15 everywhere, or block 15 without sky. */
    public static LightUpdateData fullBright(boolean hasSky, int lightSections) {
        BitSet all = new BitSet();
        all.set(0, lightSections);
        List<byte[]> full = new ArrayList<>(lightSections);
        for (int i = 0; i < lightSections; i++) {
            full.add(FULL);
        }
        if (hasSky) {
            return new LightUpdateData(all, new BitSet(), new BitSet(), (BitSet) all.clone(), full, List.of());
        }
        return new LightUpdateData(new BitSet(), all, new BitSet(), new BitSet(), List.of(), full);
    }

    private LightUpdateData data(LightColumn column, long skySections, long blockSections) {
        BitSet sky = new BitSet();
        BitSet emptySky = new BitSet();
        BitSet block = new BitSet();
        BitSet emptyBlock = new BitSet();
        List<byte[]> skyArrays = new ArrayList<>();
        List<byte[]> blockArrays = new ArrayList<>();
        for (int s = 0; s < this.lightSections; s++) {
            if (this.hasSky && (skySections >>> s & 1) != 0) {
                byte[] array = column.sky[s];
                if (array != null) {
                    sky.set(s);
                    skyArrays.add(array.clone());
                } else if (column.skyUniform[s] == 15) {
                    sky.set(s);
                    skyArrays.add(FULL);
                } else {
                    emptySky.set(s);
                }
            }
            if ((blockSections >>> s & 1) != 0) {
                byte[] array = column.block[s];
                if (array != null) {
                    block.set(s);
                    blockArrays.add(array.clone());
                } else {
                    emptyBlock.set(s);
                }
            }
        }
        return new LightUpdateData(sky, block, emptySky, emptyBlock, skyArrays, blockArrays);
    }

    // ------------------------------------------------------------------ region and cells

    /** Selects the 3x3 columns centred on chunk (x, z); region coordinates go 0..47 from (x - 1) * 16. */
    private void selectRegion(int x, int z) {
        this.regionX = (x - 1) << 4;
        this.regionZ = (z - 1) << 4;
        for (int dz = 0; dz < 3; dz++) {
            for (int dx = 0; dx < 3; dx++) {
                this.region[dx + dz * 3] = this.columns.get(key(x - 1 + dx, z - 1 + dz));
            }
        }
    }

    private static int pack(int rx, int rz, int ly, int level) {
        return rx | (rz << 6) | (ly << 12) | (level << 22);
    }

    private int stateAt(LightColumn column, int lx, int ly, int lz) {
        int s = (ly >> 4) - 1;
        if (s < 0 || s >= column.sections.length) {
            return JavaBlockStates.AIR;
        }
        ChunkSection section = column.sections[s];
        return section == null ? JavaBlockStates.AIR : section.java().get(((ly & 15) << 8) | (lz << 4) | lx);
    }

    private int opacity(int state) {
        return this.lightTable[state] & 15;
    }

    /** Light y of the lowest sky source cell of a column (cells from there up see the sky). */
    private int computeSource(LightColumn column, int lx, int lz) {
        int above = JavaBlockStates.AIR;
        for (int s = column.sections.length - 1; s >= 0; s--) {
            ChunkSection section = column.sections[s];
            if (section == null) {
                above = JavaBlockStates.AIR;
                continue;
            }
            BlockStorage java = section.java();
            if (java.isUniform()) {
                int state = java.get(0);
                if (opacity(state) == 0 && JavaBlockStates.occlusion(state) == 0) {
                    above = state;
                    continue;
                }
            }
            for (int y = 15; y >= 0; y--) {
                int state = java.get((y << 8) | (lz << 4) | lx);
                if (opacity(state) > 0 || (JavaBlockStates.occlusion(state) & JavaBlockStates.FACE_UP) != 0
                        || (JavaBlockStates.occlusion(above) & JavaBlockStates.FACE_DOWN) != 0) {
                    return ((s + 1) << 4) + y + 1;
                }
                above = state;
            }
        }
        return 0;
    }

    private void fillSkySources(LightColumn column) {
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (int source : column.skySource) {
            min = Math.min(min, source);
            max = Math.max(max, source);
        }
        for (int s = 0; s < this.lightSections; s++) {
            int bottom = s << 4;
            if (bottom >= max) {
                column.skyUniform[s] = 15;
            } else if (bottom + 16 <= min) {
                column.skyUniform[s] = 0;
            } else {
                byte[] array = new byte[2048];
                for (int c = 0; c < 256; c++) {
                    int from = Math.max(column.skySource[c] - bottom, 0);
                    for (int y = from; y < 16; y++) {
                        LightColumn.setNibble(array, (y << 8) | c, 15);
                    }
                }
                column.sky[s] = array;
            }
        }
    }

    /** Sky sources that can light something: the lowest source cell, and source cells beside lower-sourced columns. */
    private void seedSkySources(LightColumn column) {
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int source = column.skySource[lx | (lz << 4)];
                int top = source + 1;
                for (int d = 2; d < 6; d++) {
                    int nx = 16 + lx + DX[d];
                    int nz = 16 + lz + DZ[d];
                    LightColumn neighbour = this.region[(nx >> 4) + (nz >> 4) * 3];
                    if (neighbour != null) {
                        top = Math.max(top, neighbour.skySource[(nx & 15) | ((nz & 15) << 4)]);
                    }
                }
                for (int ly = source; ly < Math.min(top, this.height); ly++) {
                    this.increase.add(pack(16 + lx, 16 + lz, ly, 15));
                }
            }
        }
    }

    private void seedEmitters(LightColumn column) {
        for (int s = 0; s < column.sections.length; s++) {
            ChunkSection section = column.sections[s];
            if (section == null || !section.java().paletteContains(state -> (this.lightTable[state] & 0xF0) != 0)) {
                continue;
            }
            BlockStorage java = section.java();
            int ly0 = (s + 1) << 4;
            for (int i = 0; i < BlockStorage.SIZE; i++) {
                int emission = this.lightTable[java.get(i)] >> 4 & 15;
                if (emission > 0) {
                    int lx = i & 15;
                    int lz = (i >> 4) & 15;
                    int ly = ly0 + (i >> 8);
                    column.setBlock(lx, ly, lz, emission);
                    this.increase.add(pack(16 + lx, 16 + lz, ly, emission));
                }
            }
        }
    }

    /** Border cells of the four loaded side neighbours that may light the centre column. */
    private void seedNeighbourBorders(boolean sky) {
        LightColumn centre = this.region[4];
        for (int d = 2; d < 6; d++) {
            LightColumn neighbour = this.region[(1 + DX[d]) + (1 + DZ[d]) * 3];
            if (neighbour == null) {
                continue;
            }
            for (int i = 0; i < 16; i++) {
                // The neighbour's cells touching the centre column, and the centre cells they touch.
                int nx = DX[d] == 0 ? i : (DX[d] < 0 ? 15 : 0);
                int nz = DZ[d] == 0 ? i : (DZ[d] < 0 ? 15 : 0);
                int cx = DX[d] == 0 ? i : (DX[d] < 0 ? 0 : 15);
                int cz = DZ[d] == 0 ? i : (DZ[d] < 0 ? 0 : 15);
                for (int ly = 0; ly < this.height; ly++) {
                    int level = sky ? neighbour.sky(nx, ly, nz) : neighbour.block(nx, ly, nz);
                    if (level > 1 && level - 1 > (sky ? centre.sky(cx, ly, cz) : centre.block(cx, ly, cz))) {
                        this.increase.add(pack(16 + (DX[d] * 16) + nx, 16 + (DZ[d] * 16) + nz, ly, level));
                    }
                }
            }
        }
    }

    /** Seeds the six neighbours of a region cell with their current light. */
    private void seedAround(int rx, int rz, int ly, boolean sky) {
        for (int d = 0; d < 6; d++) {
            int nx = rx + DX[d];
            int nz = rz + DZ[d];
            int ny = ly + DY[d];
            if (ny < 0 || ny >= this.height) {
                continue;
            }
            LightColumn column = this.region[(nx >> 4) + (nz >> 4) * 3];
            if (column == null) {
                continue;
            }
            int level = sky ? column.sky(nx & 15, ny, nz & 15) : column.block(nx & 15, ny, nz & 15);
            if (level > 1) {
                this.increase.add(pack(nx, nz, ny, level));
            }
        }
    }

    // ------------------------------------------------------------------ propagation

    private void propagateIncrease(boolean sky) {
        IntQueue queue = this.increase;
        while (!queue.isEmpty()) {
            int entry = queue.poll();
            int rx = entry & 63;
            int rz = (entry >> 6) & 63;
            int ly = (entry >> 12) & 1023;
            int level = entry >>> 22;
            LightColumn from = this.region[(rx >> 4) + (rz >> 4) * 3];
            int current = sky ? from.sky(rx & 15, ly, rz & 15) : from.block(rx & 15, ly, rz & 15);
            if (current != level) {
                continue; // superseded
            }
            int fromOcclusion = JavaBlockStates.occlusion(stateAt(from, rx & 15, ly, rz & 15));
            for (int d = 0; d < 6; d++) {
                int nx = rx + DX[d];
                int nz = rz + DZ[d];
                int ny = ly + DY[d];
                if (nx < 0 || nz < 0 || nx >= 48 || nz >= 48 || ny < 0 || ny >= this.height
                        || (fromOcclusion & (1 << d)) != 0) {
                    continue;
                }
                LightColumn to = this.region[(nx >> 4) + (nz >> 4) * 3];
                if (to == null) {
                    continue;
                }
                int lx = nx & 15;
                int lz = nz & 15;
                int state = stateAt(to, lx, ny, lz);
                if ((JavaBlockStates.occlusion(state) & (1 << OPPOSITE[d])) != 0) {
                    continue;
                }
                int next = level - Math.max(1, opacity(state));
                if (next <= 0) {
                    continue;
                }
                if (sky) {
                    if (next > to.sky(lx, ny, lz)) {
                        to.setSky(lx, ny, lz, next);
                        if (next > 1) {
                            queue.add(pack(nx, nz, ny, next));
                        }
                    }
                } else if (next > to.block(lx, ny, lz)) {
                    to.setBlock(lx, ny, lz, next);
                    if (next > 1) {
                        queue.add(pack(nx, nz, ny, next));
                    }
                }
            }
        }
    }

    /**
     * Clears every cell that may have been lit through the cells queued in {@link #decrease}, and
     * queues the boundary (brighter or independent cells, emitters, sky sources) for re-lighting.
     */
    private void propagateDecrease(boolean sky) {
        IntQueue queue = this.decrease;
        while (!queue.isEmpty()) {
            int entry = queue.poll();
            int rx = entry & 63;
            int rz = (entry >> 6) & 63;
            int ly = (entry >> 12) & 1023;
            int level = entry >>> 22;
            for (int d = 0; d < 6; d++) {
                int nx = rx + DX[d];
                int nz = rz + DZ[d];
                int ny = ly + DY[d];
                if (nx < 0 || nz < 0 || nx >= 48 || nz >= 48 || ny < 0 || ny >= this.height) {
                    continue;
                }
                LightColumn to = this.region[(nx >> 4) + (nz >> 4) * 3];
                if (to == null) {
                    continue;
                }
                int lx = nx & 15;
                int lz = nz & 15;
                int current = sky ? to.sky(lx, ny, lz) : to.block(lx, ny, lz);
                if (current == 0) {
                    continue;
                }
                boolean source = sky && ny >= to.skySource[lx | (lz << 4)];
                if (!source && current < level) {
                    if (sky) {
                        to.setSky(lx, ny, lz, 0);
                    } else {
                        to.setBlock(lx, ny, lz, 0);
                        if ((this.lightTable[stateAt(to, lx, ny, lz)] & 0xF0) != 0) {
                            this.emitters.add(pack(nx, nz, ny, 0));
                        }
                    }
                    queue.add(pack(nx, nz, ny, current));
                } else {
                    this.increase.add(pack(nx, nz, ny, current));
                }
            }
        }
        while (!this.emitters.isEmpty()) {
            int entry = this.emitters.poll();
            int rx = entry & 63;
            int rz = (entry >> 6) & 63;
            int ly = (entry >> 12) & 1023;
            LightColumn column = this.region[(rx >> 4) + (rz >> 4) * 3];
            int emission = this.lightTable[stateAt(column, rx & 15, ly, rz & 15)] >> 4 & 15;
            if (emission > column.block(rx & 15, ly, rz & 15)) {
                column.setBlock(rx & 15, ly, rz & 15, emission);
                this.increase.add(pack(rx, rz, ly, emission));
            }
        }
    }

    private static byte[] filled(int level) {
        byte[] array = new byte[2048];
        Arrays.fill(array, (byte) (level | level << 4));
        return array;
    }

    /** Growable FIFO of ints. */
    private static final class IntQueue {

        private int[] data = new int[4096];
        private int head;
        private int tail;

        void add(int value) {
            if (this.tail == this.data.length) {
                if (this.head > 0) {
                    System.arraycopy(this.data, this.head, this.data, 0, this.tail - this.head);
                    this.tail -= this.head;
                    this.head = 0;
                } else {
                    this.data = Arrays.copyOf(this.data, this.data.length * 2);
                }
                if (this.tail == this.data.length) {
                    this.data = Arrays.copyOf(this.data, this.data.length * 2);
                }
            }
            this.data[this.tail++] = value;
        }

        int poll() {
            int value = this.data[this.head++];
            if (this.head == this.tail) {
                this.head = 0;
                this.tail = 0;
            }
            return value;
        }

        boolean isEmpty() {
            return this.head == this.tail;
        }
    }
}
