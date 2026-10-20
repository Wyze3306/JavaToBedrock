package io.github.wyze3306.jtb.block;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Vanilla Java neighbour-derived properties: snowy dirt, plant heads, chorus plants, redstone wire, scaffolding. */
class DerivedStatesTest {

    @BeforeAll
    static void load() {
        BlockData.load();
    }

    /** A small world of Java states, air elsewhere. */
    private static final class World implements DerivedStates.View {

        private final Long2IntOpenHashMap blocks = new Long2IntOpenHashMap();

        World set(int x, int y, int z, String state) {
            int id = JavaBlockStates.parse(state);
            assertTrue(id >= 0, state);
            this.blocks.put(key(x, y, z), id);
            return this;
        }

        @Override
        public int javaAt(int x, int y, int z) {
            return this.blocks.getOrDefault(key(x, y, z), JavaBlockStates.AIR);
        }

        String derive(int x, int y, int z) {
            return JavaBlockStates.stateString(DerivedStates.derive(javaAt(x, y, z), this, x, y, z));
        }

        private static long key(int x, int y, int z) {
            return ((long) x << 40) ^ ((long) y << 20) ^ z;
        }
    }

    @Test
    void snowyDirtUnderSnow() {
        World world = new World().set(0, 0, 0, "grass_block").set(0, 1, 0, "snow[layers=1]")
                .set(1, 0, 0, "podzol").set(1, 1, 0, "snow_block").set(2, 0, 0, "mycelium[snowy=true]");
        assertEquals("minecraft:grass_block[snowy=true]", world.derive(0, 0, 0));
        assertEquals("minecraft:podzol[snowy=true]", world.derive(1, 0, 0));
        assertEquals("minecraft:mycelium[snowy=false]", world.derive(2, 0, 0), "a stale derived value is reset");
        assertTrue(DerivedStates.isDerived(JavaBlockStates.parse("grass_block")));
        assertFalse(DerivedStates.readsNeighbourColumns(JavaBlockStates.parse("grass_block")));
    }

    @Test
    void growingPlantsHaveOneHead() {
        World world = new World();
        for (int y = 0; y < 3; y++) {
            world.set(0, y, 0, "kelp[age=7]");
            world.set(1, y, 0, "weeping_vines[age=3]");
            world.set(2, y, 0, y == 1 ? "cave_vines_plant[berries=true]" : "cave_vines[age=2,berries=false]");
        }
        assertEquals("minecraft:kelp_plant", world.derive(0, 0, 0));
        assertEquals("minecraft:kelp_plant", world.derive(0, 1, 0));
        assertEquals("minecraft:kelp[age=7]", world.derive(0, 2, 0), "the top keeps its age");
        assertEquals("minecraft:weeping_vines[age=3]", world.derive(1, 0, 0), "weeping vines grow down");
        assertEquals("minecraft:weeping_vines_plant", world.derive(1, 2, 0));
        assertEquals("minecraft:cave_vines_plant[berries=true]", world.derive(2, 1, 0));
        assertEquals("minecraft:cave_vines_plant[berries=false]", world.derive(2, 2, 0));
        assertEquals("minecraft:cave_vines[age=2,berries=false]", world.derive(2, 0, 0));
        world.set(2, 0, 0, "air");
        assertEquals("minecraft:cave_vines[age=0,berries=true]", world.derive(2, 1, 0), "a body becomes a head and keeps its berries");
    }

    @Test
    void chorusPlantsConnect() {
        World world = new World().set(0, 0, 0, "end_stone").set(0, 1, 0, "chorus_plant").set(0, 2, 0, "chorus_plant")
                .set(1, 1, 0, "chorus_flower[age=5]").set(0, 1, -1, "end_stone");
        assertEquals("minecraft:chorus_plant[down=true,east=true,north=false,south=false,up=true,west=false]", world.derive(0, 1, 0));
        assertEquals("minecraft:chorus_plant[down=true,east=false,north=false,south=false,up=false,west=false]", world.derive(0, 2, 0));
        assertTrue(DerivedStates.readsNeighbourColumns(JavaBlockStates.parse("chorus_plant")));
    }

    @Test
    void redstoneWireConnections() {
        World world = new World().set(0, 0, 0, "redstone_wire[power=3]");
        assertEquals("minecraft:redstone_wire[east=side,north=side,power=3,south=side,west=side]", world.derive(0, 0, 0),
                "an isolated wire is a cross");
        world.set(0, 0, -1, "redstone_wire").set(0, 0, 1, "redstone_wire");
        assertEquals("minecraft:redstone_wire[east=none,north=side,power=3,south=side,west=none]", world.derive(0, 0, 0));
        assertEquals("minecraft:redstone_wire[east=none,north=side,power=0,south=side,west=none]", world.derive(0, 0, -1),
                "a wire ending a line points along it");
        // Up a step: a stone block east with wire on top.
        world.set(1, 0, 0, "stone").set(1, 1, 0, "redstone_wire");
        assertEquals("minecraft:redstone_wire[east=up,north=side,power=3,south=side,west=none]", world.derive(0, 0, 0));
        // A conductor above cuts the step.
        world.set(0, 1, 0, "stone");
        assertEquals("minecraft:redstone_wire[east=none,north=side,power=3,south=side,west=none]", world.derive(0, 0, 0));
        // Down a step: wire below an open neighbour; glass above is not a conductor.
        World down = new World().set(0, 0, 0, "redstone_wire").set(0, 1, 0, "glass").set(-1, -1, 0, "redstone_wire");
        assertEquals("minecraft:redstone_wire[east=side,north=none,power=0,south=none,west=side]", down.derive(0, 0, 0));
        // Components: a repeater along the axis, an observer outputting into the wire, a lever.
        World parts = new World().set(0, 0, 0, "redstone_wire").set(1, 0, 0, "repeater[facing=west]")
                .set(0, 0, -1, "observer[facing=south]").set(0, 0, 1, "observer[facing=south]");
        assertEquals("minecraft:redstone_wire[east=side,north=none,power=0,south=side,west=none]", parts.derive(0, 0, 0),
                "only the observer whose back faces the wire connects");
        parts.set(1, 0, 0, "repeater[facing=north]").set(0, 0, 1, "lever");
        assertEquals("minecraft:redstone_wire[east=none,north=side,power=0,south=side,west=none]", parts.derive(0, 0, 0));
    }

    @Test
    void scaffoldingBottom() {
        World world = new World().set(0, 0, 0, "scaffolding[bottom=true,distance=0]").set(1, 0, 0, "scaffolding[distance=2]")
                .set(1, -1, 0, "scaffolding[distance=1]").set(2, 0, 0, "scaffolding[distance=2]");
        assertEquals("minecraft:scaffolding[bottom=false,distance=0,waterlogged=false]", world.derive(0, 0, 0));
        assertEquals("minecraft:scaffolding[bottom=false,distance=2,waterlogged=false]", world.derive(1, 0, 0));
        assertEquals("minecraft:scaffolding[bottom=true,distance=2,waterlogged=false]", world.derive(2, 0, 0));
    }

    @Test
    void otherBlocksAreUnchanged() {
        World world = new World().set(0, 0, 0, "oak_fence[east=true]").set(0, 1, 0, "snow");
        assertFalse(DerivedStates.isDerived(world.javaAt(0, 0, 0)));
        assertEquals("minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=false]", world.derive(0, 0, 0));
    }
}
