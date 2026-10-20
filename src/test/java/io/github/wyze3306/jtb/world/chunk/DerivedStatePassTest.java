package io.github.wyze3306.jtb.world.chunk;

import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.block.JavaBlockStates;
import io.github.wyze3306.jtb.world.TestSessions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Derived Java states of a decoded column: across sections, column borders read as air. */
class DerivedStatePassTest {

    private static final ChunkLayout OVERWORLD = new ChunkLayout(-4, 24, -4, 24, true, 1);

    private static BlockMapper mapper;

    @BeforeAll
    static void setUp() {
        mapper = TestSessions.mapper();
    }

    private static int bedrock(String javaState) {
        return mapper.toBedrock(JavaBlockStates.parse(javaState));
    }

    private static String javaAt(ChunkSection[] sections, int x, int y, int z) {
        ChunkSection section = sections[OVERWORLD.sectionIndex(y)];
        return JavaBlockStates.stateString(section == null ? JavaBlockStates.AIR : section.javaAt(BlockStorage.index(x, y & 15, z)));
    }

    @Test
    void derivesAcrossSectionsWithinTheColumn() {
        int air = mapper.bedrockAir();
        BlockStorage.Builder low = BlockStorage.Builder.filled(air);
        BlockStorage.Builder high = BlockStorage.Builder.filled(air);
        // Grass at the top of section 0 (y = 15) under snow at the bottom of section 1 (y = 16).
        low.set(BlockStorage.index(3, 15, 4), bedrock("grass_block"));
        high.set(BlockStorage.index(3, 0, 4), bedrock("snow[layers=2]"));
        // Redstone wire on the column border with nothing loaded beyond: a line along the border.
        low.set(BlockStorage.index(15, 1, 8), bedrock("redstone_wire"));
        low.set(BlockStorage.index(15, 1, 9), bedrock("redstone_wire"));
        // Untouched section without derived blocks.
        BlockStorage.Builder plain = BlockStorage.Builder.filled(air);
        plain.set(0, bedrock("stone"));
        ChunkSection[] sections = new ChunkSection[OVERWORLD.javaSections()];
        int base = OVERWORLD.sectionIndex(0);
        sections[base] = section(low);
        sections[base + 1] = section(high);
        sections[base + 2] = section(plain);
        ChunkSection untouched = sections[base + 2];

        int changed = DerivedStatePass.deriveColumn(sections, OVERWORLD, mapper);

        assertEquals("minecraft:grass_block[snowy=true]", javaAt(sections, 3, 15, 4));
        assertEquals("minecraft:redstone_wire[east=none,north=side,power=0,south=side,west=none]", javaAt(sections, 15, 1, 8));
        assertEquals("minecraft:redstone_wire[east=none,north=side,power=0,south=side,west=none]", javaAt(sections, 15, 1, 9));
        assertEquals(3, changed);
        assertSame(untouched, sections[base + 2], "sections without derived blocks are not copied");
        assertEquals(bedrock("grass_block"), sections[base].layer0().get(BlockStorage.index(3, 15, 4)), "Bedrock layer unchanged");
    }

    private static ChunkSection section(BlockStorage.Builder layer0) {
        BlockStorage blocks = layer0.build();
        return new ChunkSection(blocks, null, BedrockChunkDecoder.toJava(blocks, null, mapper));
    }
}
