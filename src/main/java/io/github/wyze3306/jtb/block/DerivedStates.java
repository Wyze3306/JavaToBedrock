package io.github.wyze3306.jtb.block;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Java block state properties Bedrock does not send, derived from the neighbouring blocks the way a
 * vanilla Java server does in {@code updateShape} (Bedrock 1.26 sends fence, pane, bar, tripwire and
 * stair connections itself, the mapping keeps them):
 * <ul>
 *     <li>{@code snowy} of grass blocks, podzol and mycelium under snow;</li>
 *     <li>heads and bodies of kelp, twisting, weeping and cave vines ({@code kelp} / {@code kelp_plant}...);</li>
 *     <li>the six connections of chorus plants;</li>
 *     <li>the four sides of redstone wire (none, side, up), isolated wire showing as a cross;</li>
 *     <li>{@code bottom} of scaffolding.</li>
 * </ul>
 * The result only depends on the derived block's own Bedrock state and on properties of its neighbours
 * this class never changes, so derivation needs no fixed point: each block is derived once from its
 * raw mapped state ({@link BlockMapper#toJava(int, int)}), in any order. Snow, plants and scaffolding
 * only read the blocks above and below; chorus plants and redstone wire also read the neighbouring
 * columns ({@link #readsNeighbourColumns}).
 * <p>
 * Thread safe after {@link BlockData#load()}.
 */
public final class DerivedStates {

    /** Reads the Java state of any block; positions outside the loaded world read as air. */
    @FunctionalInterface
    public interface View {
        int javaAt(int x, int y, int z);
    }

    private static final byte NONE = 0;
    private static final byte SNOWY_DIRT = 1;
    private static final byte SNOW = 2;
    private static final byte PLANT = 3;
    private static final byte CHORUS_PLANT = 4;
    private static final byte CHORUS_FLOWER = 5;
    private static final byte END_STONE = 6;
    private static final byte WIRE = 7;
    private static final byte SCAFFOLDING = 8;
    private static final byte REPEATER = 9;
    private static final byte OBSERVER = 10;
    private static final byte SIGNAL_SOURCE = 11;
    private static final byte HOPPER = 12;
    private static final byte TRAPDOOR = 13;

    /** Direction ordinals, like {@link JavaBlockStates#FACE_DOWN}...: down, up, north, south, west, east. */
    private static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    private static final int[] DX = {0, 0, 0, 0, -1, 1};
    private static final int[] DY = {-1, 1, 0, 0, 0, 0};
    private static final int[] DZ = {0, 0, -1, 1, 0, 0};
    private static final int[] HORIZONTAL = {NORTH, EAST, SOUTH, WEST};
    private static final String[] DIRECTION_NAMES = {"down", "up", "north", "south", "west", "east"};

    /** Boolean property value indices (vanilla order: true, false; checked by {@link #init}). */
    private static final int TRUE = 0, FALSE = 1;
    /** Redstone wire side values (vanilla {@code RedstoneSide} order, checked by {@link #init}). */
    private static final int SIDE_UP = 0, SIDE_SIDE = 1, SIDE_NONE = 2;

    private static byte[] kinds;
    /** Per block of kind {@link #PLANT}: its plant family. */
    private static Plant[] plants;
    /** Per block of kind {@link #SNOWY_DIRT}: its {@code snowy} property. */
    private static JavaBlockStates.Property[] snowy;
    /** Per block of kind {@link #REPEATER} or {@link #OBSERVER}: its {@code facing} as direction ordinals by value index. */
    private static int[][] facing;
    private static JavaBlockStates.Property[] facingProperty;
    private static JavaBlockStates.Property[] chorusSides;
    private static JavaBlockStates.Property[] wireSides;
    private static JavaBlockStates.Property scaffoldingBottom;
    private static JavaBlockStates.Property scaffoldingDistance;

    /**
     * A growing plant: the head (with an age) is the last block in the growth direction, the others
     * are the body.
     */
    private record Plant(int head, int body, boolean growsUp, JavaBlockStates.@Nullable Property headBerries,
                         JavaBlockStates.@Nullable Property bodyBerries) {

        boolean contains(int block) {
            return block == this.head || block == this.body;
        }
    }

    private DerivedStates() {
    }

    /** Builds the tables from {@link JavaBlockStates}; called by {@link BlockData#load()}. */
    static void init() {
        int count = JavaBlockStates.blockCount();
        byte[] k = new byte[count];
        Plant[] p = new Plant[count];
        JavaBlockStates.Property[] s = new JavaBlockStates.Property[count];
        int[][] f = new int[count][];
        JavaBlockStates.Property[] fp = new JavaBlockStates.Property[count];
        for (int block = 0; block < count; block++) {
            String name = JavaBlockStates.blockName(block);
            k[block] = kindOf(name);
            if (k[block] == SNOWY_DIRT) {
                s[block] = JavaBlockStates.propertyOf(block, "snowy");
            } else if (k[block] == REPEATER || k[block] == OBSERVER) {
                JavaBlockStates.Property property = JavaBlockStates.propertyOf(block, "facing");
                if (property == null) {
                    k[block] = SIGNAL_SOURCE;
                    continue;
                }
                int[] dirs = new int[property.values().length];
                for (int i = 0; i < dirs.length; i++) {
                    dirs[i] = direction(property.values()[i]);
                }
                f[block] = dirs;
                fp[block] = property;
            }
        }
        for (String[] family : new String[][]{{"kelp", "kelp_plant", "up"}, {"twisting_vines", "twisting_vines_plant", "up"},
                {"weeping_vines", "weeping_vines_plant", "down"}, {"cave_vines", "cave_vines_plant", "down"}}) {
            int head = JavaBlockStates.blockId(family[0]);
            int body = JavaBlockStates.blockId(family[1]);
            if (head < 0 || body < 0) {
                continue;
            }
            Plant plant = new Plant(head, body, family[2].equals("up"), JavaBlockStates.propertyOf(head, "berries"),
                    JavaBlockStates.propertyOf(body, "berries"));
            k[head] = PLANT;
            k[body] = PLANT;
            p[head] = plant;
            p[body] = plant;
        }
        int chorus = JavaBlockStates.blockId("chorus_plant");
        JavaBlockStates.Property[] chorusProps = new JavaBlockStates.Property[6];
        for (int d = 0; d < 6; d++) {
            chorusProps[d] = JavaBlockStates.propertyOf(chorus, DIRECTION_NAMES[d]);
        }
        int wire = JavaBlockStates.blockId("redstone_wire");
        JavaBlockStates.Property[] wireProps = new JavaBlockStates.Property[6];
        for (int d : HORIZONTAL) {
            wireProps[d] = JavaBlockStates.propertyOf(wire, DIRECTION_NAMES[d]);
        }
        int scaffolding = JavaBlockStates.blockId("scaffolding");
        JavaBlockStates.Property bottom = JavaBlockStates.propertyOf(scaffolding, "bottom");
        for (JavaBlockStates.Property property : chorusProps) {
            checkValues(property, "true", "false");
        }
        for (int d : HORIZONTAL) {
            checkValues(wireProps[d], "up", "side", "none");
        }
        checkValues(bottom, "true", "false");
        for (JavaBlockStates.Property property : s) {
            if (property != null) {
                checkValues(property, "true", "false");
            }
        }
        kinds = k;
        plants = p;
        snowy = s;
        facing = f;
        facingProperty = fp;
        chorusSides = chorusProps;
        wireSides = wireProps;
        scaffoldingBottom = bottom;
        scaffoldingDistance = JavaBlockStates.propertyOf(scaffolding, "distance");
    }

    private static void checkValues(JavaBlockStates.@Nullable Property property, String... values) {
        if (property == null || !Arrays.equals(property.values(), values)) {
            throw new IllegalStateException("Unexpected Java block property values: " + (property == null ? null
                    : JavaBlockStates.blockName(property.block()) + " " + Arrays.toString(property.values())));
        }
    }

    private static byte kindOf(String name) {
        return switch (name) {
            case "grass_block", "podzol", "mycelium" -> SNOWY_DIRT;
            case "snow", "snow_block", "powder_snow" -> SNOW;
            case "chorus_plant" -> CHORUS_PLANT;
            case "chorus_flower" -> CHORUS_FLOWER;
            case "end_stone" -> END_STONE;
            case "redstone_wire" -> WIRE;
            case "scaffolding" -> SCAFFOLDING;
            case "repeater" -> REPEATER;
            case "observer" -> OBSERVER;
            case "hopper" -> HOPPER;
            // Vanilla isSignalSource() blocks (comparators included; repeaters and observers above).
            case "comparator", "redstone_torch", "redstone_wall_torch", "lever", "detector_rail", "daylight_detector",
                 "tripwire_hook", "trapped_chest", "redstone_block", "target", "lectern", "sculk_sensor",
                 "calibrated_sculk_sensor", "jukebox" -> SIGNAL_SOURCE;
            default -> {
                if (name.endsWith("_button") || name.endsWith("_pressure_plate") || name.endsWith("lightning_rod")) {
                    yield SIGNAL_SOURCE;
                }
                yield name.endsWith("_trapdoor") ? TRAPDOOR : NONE;
            }
        };
    }

    private static int direction(String name) {
        for (int d = 0; d < DIRECTION_NAMES.length; d++) {
            if (DIRECTION_NAMES[d].equals(name)) {
                return d;
            }
        }
        return -1;
    }

    private static byte kind(int state) {
        return kinds[JavaBlockStates.blockId(state)];
    }

    /** Whether the state has properties this class derives (then {@link #derive} may change it). */
    public static boolean isDerived(int state) {
        byte kind = kind(state);
        return kind == SNOWY_DIRT || kind == PLANT || kind == CHORUS_PLANT || kind == WIRE || kind == SCAFFOLDING;
    }

    /** Whether deriving the state reads blocks of the neighbouring columns (chorus plants, redstone wire). */
    public static boolean readsNeighbourColumns(int state) {
        byte kind = kind(state);
        return kind == CHORUS_PLANT || kind == WIRE;
    }

    /**
     * The state a Java server would show at {@code x, y, z} for the raw mapped state {@code raw}; {@code raw}
     * itself when it has nothing to derive.
     */
    public static int derive(int raw, View view, int x, int y, int z) {
        int block = JavaBlockStates.blockId(raw);
        return switch (kinds[block]) {
            case SNOWY_DIRT -> {
                JavaBlockStates.Property property = snowy[block];
                yield property == null ? raw : property.with(raw, kind(view.javaAt(x, y + 1, z)) == SNOW ? TRUE : FALSE);
            }
            case PLANT -> plant(raw, block, view, x, y, z);
            case CHORUS_PLANT -> chorus(raw, view, x, y, z);
            case WIRE -> wire(raw, view, x, y, z);
            case SCAFFOLDING -> {
                int distance = scaffoldingDistance.index(raw);
                boolean onScaffolding = kind(view.javaAt(x, y - 1, z)) == SCAFFOLDING;
                yield scaffoldingBottom.with(raw, distance > 0 && !onScaffolding ? TRUE : FALSE);
            }
            default -> raw;
        };
    }

    private static int plant(int raw, int block, View view, int x, int y, int z) {
        Plant plant = plants[block];
        int next = view.javaAt(x, plant.growsUp() ? y + 1 : y - 1, z);
        boolean body = plant.contains(JavaBlockStates.blockId(next));
        boolean isBody = block == plant.body();
        if (body == isBody) {
            return raw;
        }
        JavaBlockStates.Property from = isBody ? plant.bodyBerries() : plant.headBerries();
        JavaBlockStates.Property to = isBody ? plant.headBerries() : plant.bodyBerries();
        int result = JavaBlockStates.defaultState(body ? plant.body() : plant.head());
        if (from != null && to != null) {
            result = to.with(result, from.index(raw));
        }
        return result;
    }

    private static int chorus(int raw, View view, int x, int y, int z) {
        int state = raw;
        for (int d = 0; d < 6; d++) {
            byte kind = kind(view.javaAt(x + DX[d], y + DY[d], z + DZ[d]));
            boolean connected = kind == CHORUS_PLANT || kind == CHORUS_FLOWER || d == DOWN && kind == END_STONE;
            state = chorusSides[d].with(state, connected ? TRUE : FALSE);
        }
        return state;
    }

    /** Vanilla {@code RedstoneWireBlock.getConnectionState} for a wire that is not a toggled dot. */
    private static int wire(int raw, View view, int x, int y, int z) {
        boolean conductorAbove = JavaBlockStates.isRedstoneConductor(view.javaAt(x, y + 1, z));
        int[] sides = new int[6];
        for (int d : HORIZONTAL) {
            sides[d] = connectingSide(view, x, y, z, d, !conductorAbove);
        }
        boolean north = sides[NORTH] != SIDE_NONE;
        boolean south = sides[SOUTH] != SIDE_NONE;
        boolean east = sides[EAST] != SIDE_NONE;
        boolean west = sides[WEST] != SIDE_NONE;
        boolean noNorthSouth = !north && !south;
        boolean noEastWest = !east && !west;
        if (!west && noNorthSouth) {
            sides[WEST] = SIDE_SIDE;
        }
        if (!east && noNorthSouth) {
            sides[EAST] = SIDE_SIDE;
        }
        if (!north && noEastWest) {
            sides[NORTH] = SIDE_SIDE;
        }
        if (!south && noEastWest) {
            sides[SOUTH] = SIDE_SIDE;
        }
        int state = raw;
        for (int d : HORIZONTAL) {
            state = wireSides[d].with(state, sides[d]);
        }
        return state;
    }

    private static int connectingSide(View view, int x, int y, int z, int d, boolean openAbove) {
        int nx = x + DX[d];
        int nz = z + DZ[d];
        int neighbour = view.javaAt(nx, y, nz);
        if (openAbove) {
            byte kind = kind(neighbour);
            boolean holdsWire = kind == TRAPDOOR || kind == HOPPER || (JavaBlockStates.sturdyFaces(neighbour) & (1 << UP)) != 0;
            if (holdsWire && kind(view.javaAt(nx, y + 1, nz)) == WIRE) {
                return (JavaBlockStates.sturdyFaces(neighbour) & (1 << opposite(d))) != 0 ? SIDE_UP : SIDE_SIDE;
            }
        }
        if (connectsTo(neighbour, d)) {
            return SIDE_SIDE;
        }
        return !JavaBlockStates.isRedstoneConductor(neighbour) && kind(view.javaAt(nx, y - 1, nz)) == WIRE ? SIDE_SIDE : SIDE_NONE;
    }

    /** Vanilla {@code RedstoneWireBlock.shouldConnectTo(state, direction)}, {@code d} pointing from the wire to the block. */
    private static boolean connectsTo(int state, int d) {
        int block = JavaBlockStates.blockId(state);
        return switch (kinds[block]) {
            case WIRE, SIGNAL_SOURCE -> true;
            case REPEATER -> {
                int f = facing[block][facingProperty[block].index(state)];
                yield f == d || f == opposite(d);
            }
            case OBSERVER -> facing[block][facingProperty[block].index(state)] == d;
            default -> false;
        };
    }

    private static int opposite(int d) {
        return d ^ 1;
    }
}
