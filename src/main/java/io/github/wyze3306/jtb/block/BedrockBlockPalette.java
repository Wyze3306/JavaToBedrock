package io.github.wyze3306.jtb.block;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The vanilla Bedrock 1.26.50 block palette (22091 states) with, for each state, its hashed network
 * id and the Java state it shows as. Entries are in palette order, which is the runtime id order
 * servers use when {@code blockNetworkIdsAreHashes} is false (sorted by the FNV-1 64 hash of the
 * name). Loaded once by {@link BlockData}; immutable and thread safe afterwards.
 */
public final class BedrockBlockPalette {

    /** Bit of {@link #itemFrame(int)} set for {@code minecraft:glow_frame}. */
    public static final int GLOW_FRAME = 8;

    /** A Bedrock state property: name, NBT tag type and the values the palette uses, in order. */
    record Property(String name, NbtType<?> type, Object[] values) {
    }

    private static String[] names;
    private static Property[][] nameProperties;
    private static char[] entryName;
    private static int[] valueOffset;
    private static byte[] values;
    private static int[] hashes;
    private static char[] javaStates;
    private static char[] javaToEntry;
    private static Int2IntOpenHashMap byHash;
    private static Int2IntOpenHashMap potted;
    /** Per entry: -1, or the item frame value of {@link #itemFrame(int)}. */
    private static byte[] itemFrames;
    private static Set<String> nameSet;
    private static AtomicReferenceArray<NbtMap> stateCache;

    private BedrockBlockPalette() {
    }

    static void load(BlockDataReader in) throws IOException {
        int nameCount = in.u16();
        String[] n = new String[nameCount];
        Property[][] props = new Property[nameCount][];
        for (int i = 0; i < nameCount; i++) {
            n[i] = in.utf();
            props[i] = new Property[in.u8()];
            for (int p = 0; p < props[i].length; p++) {
                String name = in.utf();
                int tag = in.u8();
                Object[] v = new Object[in.u8()];
                for (int k = 0; k < v.length; k++) {
                    v[k] = switch (tag) {
                        case 1 -> in.i8();
                        case 3 -> in.i32();
                        case 8 -> in.utf();
                        default -> throw new IOException("Unsupported Bedrock state tag type " + tag);
                    };
                }
                NbtType<?> type = switch (tag) {
                    case 1 -> NbtType.BYTE;
                    case 3 -> NbtType.INT;
                    default -> NbtType.STRING;
                };
                props[i][p] = new Property(name, type, v);
            }
        }
        int size = in.i32();
        char[] entryNames = new char[size];
        int[] offsets = new int[size + 1];
        byte[] valueBuffer = new byte[size * 8];
        int used = 0;
        for (int e = 0; e < size; e++) {
            int name = in.u16();
            entryNames[e] = (char) name;
            offsets[e] = used;
            int count = props[name].length;
            if (used + count > valueBuffer.length) {
                valueBuffer = Arrays.copyOf(valueBuffer, valueBuffer.length * 2);
            }
            for (int p = 0; p < count; p++) {
                valueBuffer[used++] = in.i8();
            }
        }
        offsets[size] = used;
        int[] h = in.ints(size);
        Int2IntOpenHashMap hashIndex = new Int2IntOpenHashMap(size * 2);
        hashIndex.defaultReturnValue(-1);
        for (int e = 0; e < size; e++) {
            hashIndex.put(h[e], e);
        }
        char[] toJava = in.chars(size);
        char[] fromJava = in.chars(JavaBlockStates.count());
        int pottedCount = in.u16();
        Int2IntOpenHashMap pots = new Int2IntOpenHashMap(pottedCount * 2);
        pots.defaultReturnValue(-1);
        for (int i = 0; i < pottedCount; i++) {
            pots.put(in.u16(), in.u16());
        }
        names = n;
        nameSet = Set.of(n);
        nameProperties = props;
        entryName = entryNames;
        valueOffset = offsets;
        values = Arrays.copyOf(valueBuffer, used);
        hashes = h;
        javaStates = toJava;
        javaToEntry = fromJava;
        byHash = hashIndex;
        potted = pots;
        stateCache = new AtomicReferenceArray<>(size);
        itemFrames = itemFrameTable(size);
    }

    /** Item frame values of every entry, read from the {@code facing_direction} of the two frame blocks. */
    private static byte[] itemFrameTable(int size) {
        byte[] table = new byte[size];
        Arrays.fill(table, (byte) -1);
        for (int e = 0; e < size; e++) {
            String name = name(e);
            boolean glow = name.equals("minecraft:glow_frame");
            if ((glow || name.equals("minecraft:frame")) && stateValue(e, "facing_direction") instanceof Number facing) {
                table[e] = (byte) ((facing.intValue() & 7) | (glow ? GLOW_FRAME : 0));
            }
        }
        return table;
    }

    /** Number of vanilla Bedrock states. */
    public static int size() {
        return hashes.length;
    }

    /** Palette index of a hashed network id, or -1 when it is not a vanilla state. */
    public static int indexOfHash(int hash) {
        return byHash.get(hash);
    }

    /** Hashed network id of a palette entry ({@code minecraft:unknown} is -2, as PowerNukkitX registers it). */
    public static int hash(int index) {
        return hashes[index];
    }

    /** Java state shown for a palette entry (block layer, not waterlogged). */
    public static int javaState(int index) {
        return javaStates[index];
    }

    /** Palette index of the Bedrock state that best represents a Java state, or -1. */
    public static int indexOfJava(int javaState) {
        char index = javaToEntry[javaState];
        return index == 0xFFFF ? -1 : index;
    }

    /** Namespaced Bedrock block name of an entry, e.g. {@code minecraft:stone}. */
    public static String name(int index) {
        return names[entryName[index]];
    }

    /** Whether a Bedrock block name belongs to the vanilla palette. */
    public static boolean isVanillaName(String name) {
        return nameSet.contains(name);
    }

    /** All vanilla block names, in palette order. */
    static String[] names() {
        return names;
    }

    /** The {@code {name, states}} compound of an entry (cached, shared: do not modify). */
    public static NbtMap state(int index) {
        NbtMap state = stateCache.get(index);
        if (state == null) {
            int name = entryName[index];
            Property[] props = nameProperties[name];
            NbtMapBuilder states = NbtMap.builder();
            for (int p = 0; p < props.length; p++) {
                Object value = props[p].values()[values[valueOffset[index] + p]];
                states.put(props[p].name(), value);
            }
            state = NbtMap.builder().putString("name", names[name]).putCompound("states", states.build()).build();
            stateCache.set(index, state);
        }
        return state;
    }

    /** Value of a state property of an entry, or null. */
    public static Object stateValue(int index, String property) {
        int name = entryName[index];
        Property[] props = nameProperties[name];
        for (int p = 0; p < props.length; p++) {
            if (props[p].name().equals(property)) {
                return props[p].values()[values[valueOffset[index] + p]];
            }
        }
        return null;
    }

    /**
     * Item frame of an entry: -1 when it is neither {@code minecraft:frame} nor {@code minecraft:glow_frame},
     * else its {@code facing_direction} (0 down, 1 up, 2 north, 3 south, 4 west, 5 east, the order of
     * Java's {@code Direction}) plus {@link #GLOW_FRAME} for a glow frame.
     */
    public static int itemFrame(int index) {
        return itemFrames[index];
    }

    /** The potted Java state for a Java plant state ({@code minecraft:air} gives the empty pot), or -1. */
    public static int pottedState(int plantJavaState) {
        return potted.get(plantJavaState);
    }

    /** Vanilla hash to palette index map; read only. */
    static Int2IntMap hashIndex() {
        return byHash;
    }
}
