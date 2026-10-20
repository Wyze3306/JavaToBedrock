package io.github.wyze3306.jtb.block;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.Arrays;

/**
 * The Java 26.3 block registry: blocks, their properties and every global block state id, with the
 * per-state data the proxy needs (light, heightmaps, fluids, waterlogging, block entities). Loaded
 * once from {@code mappings/block/blocks.bin.gz} by {@link BlockData}; immutable and thread safe
 * afterwards.
 * <p>
 * State ids follow the vanilla order: blocks in registry order, and within a block the cartesian
 * product of its properties sorted by name, the first property varying slowest.
 */
public final class JavaBlockStates {

    /** Bits of {@link #occlusion(int)}, indexed like {@code Direction}: down, up, north, south, west, east. */
    public static final int FACE_DOWN = 1, FACE_UP = 2, FACE_NORTH = 4, FACE_SOUTH = 8, FACE_WEST = 16, FACE_EAST = 32;

    private static final int FLAG_MOTION_BLOCKING = 1;
    private static final int FLAG_MOTION_BLOCKING_NO_LEAVES = 2;
    private static final int FLAG_FLUID = 4;
    private static final int FLAG_AIR = 8;
    private static final BlockEntityType[] BLOCK_ENTITY_TYPES = BlockEntityType.values();

    /** Global state id of {@code minecraft:air}. */
    public static final int AIR = 0;

    private static String[] blockNames;
    private static int[] blockFirstState;
    private static int[] blockDefaultState;
    private static byte[] blockEntityTypes;
    private static String[][] propertyNames;
    private static String[][][] propertyValues;
    private static Object2IntOpenHashMap<String> blockIds;
    private static char[] stateBlock;
    private static byte[] light;
    private static byte[] occlusion;
    private static byte[] flags;
    private static char[] waterlogged;

    private JavaBlockStates() {
    }

    static void load(BlockDataReader in) throws IOException {
        int blocks = in.u16();
        String[] names = new String[blocks];
        int[] first = new int[blocks];
        int[] defaults = new int[blocks];
        byte[] blockEntities = new byte[blocks];
        String[][] props = new String[blocks][];
        String[][][] values = new String[blocks][][];
        Object2IntOpenHashMap<String> ids = new Object2IntOpenHashMap<>(blocks * 2);
        ids.defaultReturnValue(-1);
        int state = 0;
        for (int b = 0; b < blocks; b++) {
            names[b] = in.utf();
            ids.put(names[b], b);
            int defaultOffset = in.u16();
            blockEntities[b] = in.i8();
            int count = in.u8();
            props[b] = new String[count];
            values[b] = new String[count][];
            int states = 1;
            for (int p = 0; p < count; p++) {
                props[b][p] = in.utf();
                String[] v = new String[in.u8()];
                for (int i = 0; i < v.length; i++) {
                    v[i] = in.utf();
                }
                values[b][p] = v;
                states *= v.length;
            }
            first[b] = state;
            defaults[b] = state + defaultOffset;
            state += states;
        }
        int total = in.i32();
        if (total != state) {
            throw new IOException("Java state count " + total + " does not match the block properties (" + state + ")");
        }
        char[] owner = new char[total];
        for (int b = 0; b < blocks; b++) {
            int end = b + 1 < blocks ? first[b + 1] : total;
            Arrays.fill(owner, first[b], end, (char) b);
        }
        byte[] lightData = in.bytes(total);
        byte[] occlusionData = in.bytes(total);
        byte[] flagData = in.bytes(total);
        char[] twins = in.chars(total);
        blockNames = names;
        blockFirstState = first;
        blockDefaultState = defaults;
        blockEntityTypes = blockEntities;
        propertyNames = props;
        propertyValues = values;
        blockIds = ids;
        stateBlock = owner;
        light = lightData;
        occlusion = occlusionData;
        flags = flagData;
        waterlogged = twins;
    }

    /** Number of global block states; also the size the Java client expects for the global palette. */
    public static int count() {
        return light.length;
    }

    /** Number of blocks in the {@code minecraft:block} registry. */
    public static int blockCount() {
        return blockNames.length;
    }

    /** Block registry id of a state (what {@code ClientboundBlockEventPacket} carries). */
    public static int blockId(int state) {
        return stateBlock[state];
    }

    /** Block registry id of a block path such as {@code "oak_stairs"} (namespace optional), or -1. */
    public static int blockId(String name) {
        return blockIds.getInt(name.startsWith("minecraft:") ? name.substring(10) : name);
    }

    /** Block path without namespace, e.g. {@code "oak_stairs"}. */
    public static String blockName(int blockId) {
        return blockNames[blockId];
    }

    public static int firstState(int blockId) {
        return blockFirstState[blockId];
    }

    public static int stateCount(int blockId) {
        return (blockId + 1 < blockNames.length ? blockFirstState[blockId + 1] : count()) - blockFirstState[blockId];
    }

    public static int defaultState(int blockId) {
        return blockDefaultState[blockId];
    }

    /** Default state of a block path, or -1 when unknown. */
    public static int defaultState(String name) {
        int block = blockId(name);
        return block < 0 ? -1 : blockDefaultState[block];
    }

    /** Value of property {@code name} in {@code state}, or null when the block has no such property. */
    public static @Nullable String property(int state, String name) {
        int block = stateBlock[state];
        String[] names = propertyNames[block];
        int stride = 1;
        int offset = state - blockFirstState[block];
        for (int p = names.length - 1; p >= 0; p--) {
            String[] v = propertyValues[block][p];
            if (names[p].equals(name)) {
                return v[(offset / stride) % v.length];
            }
            stride *= v.length;
        }
        return null;
    }

    /** {@code state} with one property changed; {@code state} itself when the block or value does not exist. */
    public static int withProperty(int state, String name, String value) {
        int block = stateBlock[state];
        String[] names = propertyNames[block];
        int stride = 1;
        int offset = state - blockFirstState[block];
        for (int p = names.length - 1; p >= 0; p--) {
            String[] v = propertyValues[block][p];
            if (names[p].equals(name)) {
                int current = (offset / stride) % v.length;
                for (int i = 0; i < v.length; i++) {
                    if (v[i].equals(value)) {
                        return state + (i - current) * stride;
                    }
                }
                return state;
            }
            stride *= v.length;
        }
        return state;
    }

    /**
     * The state of another block with the same property values (e.g. {@code white_bed} to
     * {@code red_bed}); properties the target lacks are dropped, missing ones take the default.
     */
    public static int withBlock(int state, String blockName) {
        int target = blockId(blockName);
        if (target < 0) {
            return state;
        }
        int result = blockDefaultState[target];
        int block = stateBlock[state];
        if (block == target) {
            return state;
        }
        for (String name : propertyNames[block]) {
            String value = property(state, name);
            if (value != null) {
                result = withProperty(result, name, value);
            }
        }
        return result;
    }

    /** {@code minecraft:name[k=v,...]}, for logs and debugging. */
    public static String stateString(int state) {
        if (state < 0 || state >= count()) {
            return "invalid#" + state;
        }
        int block = stateBlock[state];
        StringBuilder out = new StringBuilder("minecraft:").append(blockNames[block]);
        String[] names = propertyNames[block];
        if (names.length > 0) {
            out.append('[');
            for (int p = 0; p < names.length; p++) {
                if (p > 0) {
                    out.append(',');
                }
                out.append(names[p]).append('=').append(property(state, names[p]));
            }
            out.append(']');
        }
        return out.toString();
    }

    /** Parses {@code [minecraft:]name[k=v,...]}; unspecified properties take their default. -1 when unknown. */
    public static int parse(String text) {
        int bracket = text.indexOf('[');
        String name = bracket < 0 ? text : text.substring(0, bracket);
        int state = defaultState(name);
        if (state < 0 || bracket < 0) {
            return state;
        }
        for (String pair : text.substring(bracket + 1, text.length() - 1).split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                state = withProperty(state, pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return state;
    }

    /** Light emitted by the state, 0..15. */
    public static int emission(int state) {
        return (light[state] >> 4) & 15;
    }

    /** How much the state dampens light passing through it, 0..15 (vanilla {@code getLightBlock}). */
    public static int opacity(int state) {
        return light[state] & 15;
    }

    /** The whole light table (emission << 4 | opacity) indexed by state; must not be modified. */
    static byte[] lightTable() {
        return light;
    }

    /** Faces fully covered by the state's light occlusion shape ({@code FACE_*} bits). */
    public static int occlusion(int state) {
        return occlusion[state];
    }

    public static boolean isAir(int state) {
        return (flags[state] & FLAG_AIR) != 0;
    }

    /** Contains a fluid (water, lava, waterlogged blocks, kelp, bubble columns...): counted in the section fluid count. */
    public static boolean hasFluid(int state) {
        return (flags[state] & FLAG_FLUID) != 0;
    }

    public static boolean isMotionBlocking(int state) {
        return (flags[state] & FLAG_MOTION_BLOCKING) != 0;
    }

    public static boolean isMotionBlockingNoLeaves(int state) {
        return (flags[state] & FLAG_MOTION_BLOCKING_NO_LEAVES) != 0;
    }

    /** The waterlogged form of a state (itself when already wet), or -1 when the block cannot hold water. */
    public static int waterlogged(int state) {
        char twin = waterlogged[state];
        return twin == 0xFFFF ? -1 : twin;
    }

    /** The block entity type the Java client expects at a block in this state, or null. */
    public static @Nullable BlockEntityType blockEntityType(int state) {
        int type = blockEntityTypes[stateBlock[state]] & 0xFF;
        return type == 0xFF ? null : BLOCK_ENTITY_TYPES[type];
    }

    public static boolean hasBlockEntity(int state) {
        return blockEntityTypes[stateBlock[state]] != (byte) 0xFF;
    }
}
