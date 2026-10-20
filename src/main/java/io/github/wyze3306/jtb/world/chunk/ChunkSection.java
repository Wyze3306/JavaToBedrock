package io.github.wyze3306.jtb.world.chunk;

import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.block.JavaBlockStates;
import org.jspecify.annotations.Nullable;

/**
 * One 16x16x16 section: the Bedrock block layer, the Bedrock liquid layer and the Java states they
 * show as. Immutable: block changes create a new section (copy on write), so the light worker can
 * keep reading a published section while the session loop replaces it.
 *
 * @param layer0 Bedrock runtime ids of the block layer
 * @param layer1 Bedrock runtime ids of the liquid layer (water of waterlogged blocks), null when all air
 * @param java   Java global state ids
 */
public record ChunkSection(BlockStorage layer0, @Nullable BlockStorage layer1, BlockStorage java) {

    /** Java state at a section index ({@code (y << 8) | (z << 4) | x}). */
    public int javaAt(int index) {
        return this.java.get(index);
    }

    public int bedrockAt(int index, int layer, int air) {
        if (layer == 0) {
            return this.layer0.get(index);
        }
        return this.layer1 == null ? air : this.layer1.get(index);
    }

    /** Whether the section shows only air (it can then be dropped). */
    public boolean isEmpty() {
        return this.java.isUniform() && JavaBlockStates.isAir(this.java.get(0))
                && (this.layer1 == null || this.layer1.isUniform());
    }

    /**
     * A copy with one Bedrock block changed in {@code layer}; the Java state of that block is
     * recomputed from both layers.
     */
    public ChunkSection with(int index, int layer, int runtimeId, BlockMapper mapper) {
        int air = mapper.bedrockAir();
        BlockStorage l0 = this.layer0;
        BlockStorage l1 = this.layer1;
        if (layer == 0) {
            l0 = l0.with(index, runtimeId);
        } else {
            l1 = (l1 == null ? BlockStorage.uniform(air) : l1).with(index, runtimeId);
            if (l1.isUniform() && l1.get(0) == air) {
                l1 = null;
            }
        }
        int java = mapper.toJava(l0.get(index), l1 == null ? air : l1.get(index));
        return new ChunkSection(l0, l1, this.java.with(index, java));
    }

    /** A copy whose Java state at {@code index} is {@code java} (block entity driven states: bed colours...). */
    public ChunkSection withJava(int index, int java) {
        return new ChunkSection(this.layer0, this.layer1, this.java.with(index, java));
    }

    /** An all-air section. */
    public static ChunkSection air(int bedrockAir) {
        return new ChunkSection(BlockStorage.uniform(bedrockAir), null, BlockStorage.uniform(JavaBlockStates.AIR));
    }
}
