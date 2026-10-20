package io.github.wyze3306.jtb.block;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.cloudburstmc.nbt.NbtMap;

/**
 * Chooses the Java state that displays each custom Bedrock block state.
 * <p>
 * <b>Carriers.</b> Java has no custom blocks, so each custom state is given a vanilla Java state that
 * no vanilla Bedrock state uses (a <i>carrier</i>), which a converted resource pack can then retexture
 * through the carrier's blockstate file (1516 carriers in all). Pools, in order: {@code note_block} (1150 states, vanilla
 * Bedrock uses one: the pitch lives in the block entity), then {@code brown_mushroom_block},
 * {@code red_mushroom_block} and {@code mushroom_stem} (64 states each, Bedrock uses at most 16). All
 * are opaque full cubes, the right physics for most custom blocks. Allocation is deterministic: the
 * same definitions, offered in the same order, always get the same carriers, so a cached converted
 * pack stays valid.
 * <p>
 * <b>Extension point.</b> The resource pack subsystem reads {@link BlockMapper#customBlocks()} after
 * StartGame: for every {@link CustomBlock.State} it finds the carrier in {@code javaState} and writes
 * a model for it. A future allocator can pick another pool per visual class (e.g. leaves for
 * cut-out textures, tripwire for blocks without collision) by overriding {@link #poolFor}.
 * <p>
 * When carriers are disabled ({@code features.custom-blocks: false}) or exhausted, custom blocks show
 * as {@link #FALLBACK} (stone).
 */
public class CustomBlockAllocator {

    /** Shown for custom blocks that get no carrier. */
    public static final String FALLBACK = "minecraft:stone";

    /** Carrier pools, tried in order. */
    public enum Pool {
        /** Opaque full cubes. */
        OPAQUE
    }

    private static int[] opaqueCarriers = new int[0];

    private final boolean useCarriers;
    private int nextOpaque;

    public CustomBlockAllocator(boolean useCarriers) {
        this.useCarriers = useCarriers;
    }

    /** Computes the free carrier states once the vanilla tables are loaded. */
    static void initCarriers() {
        IntOpenHashSet used = new IntOpenHashSet();
        for (int i = 0; i < BedrockBlockPalette.size(); i++) {
            int java = BedrockBlockPalette.javaState(i);
            used.add(java);
            int wet = JavaBlockStates.waterlogged(java);
            if (wet >= 0) {
                used.add(wet);
            }
        }
        IntArrayList free = new IntArrayList();
        for (String carrier : new String[]{"note_block", "brown_mushroom_block", "red_mushroom_block", "mushroom_stem"}) {
            int block = JavaBlockStates.blockId(carrier);
            int first = JavaBlockStates.firstState(block);
            for (int s = first, end = first + JavaBlockStates.stateCount(block); s < end; s++) {
                if (!used.contains(s)) {
                    free.add(s);
                }
            }
        }
        opaqueCarriers = free.toIntArray();
    }

    /** Number of free carrier states of a pool. */
    public static int capacity(Pool pool) {
        return opaqueCarriers.length;
    }

    /**
     * The pool a custom block state should take its carrier from.
     *
     * @param definition the block definition NBT
     * @param states     the state's {@code states} compound
     */
    protected Pool poolFor(String identifier, NbtMap definition, NbtMap states) {
        return Pool.OPAQUE;
    }

    /** Java state for one custom block state. Called in permutation order for every custom block. */
    public int allocate(String identifier, NbtMap definition, NbtMap states) {
        if (this.useCarriers) {
            Pool pool = poolFor(identifier, definition, states);
            if (pool == Pool.OPAQUE && this.nextOpaque < opaqueCarriers.length) {
                return opaqueCarriers[this.nextOpaque++];
            }
        }
        return JavaBlockStates.parse(FALLBACK);
    }
}
