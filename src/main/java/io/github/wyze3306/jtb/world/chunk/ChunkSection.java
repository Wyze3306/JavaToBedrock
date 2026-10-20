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

    /** A mutable copy for applying many changes with one copy of each storage. */
    public Editor edit(BlockMapper mapper) {
        return new Editor(this, mapper);
    }

    /**
     * Applies several block changes to a section, copying each storage at most once. Not thread
     * safe; {@link #build()} publishes the result as a new immutable section.
     */
    public static final class Editor {

        private final ChunkSection base;
        private final BlockMapper mapper;
        private final int air;
        private BlockStorage.Builder layer0;
        private BlockStorage.Builder layer1;
        private BlockStorage.Builder java;

        Editor(ChunkSection base, BlockMapper mapper) {
            this.base = base;
            this.mapper = mapper;
            this.air = mapper.bedrockAir();
        }

        public int javaAt(int index) {
            return this.java != null ? this.java.get(index) : this.base.java().get(index);
        }

        public int bedrockAt(int index, int layer) {
            if (layer == 0) {
                return this.layer0 != null ? this.layer0.get(index) : this.base.layer0().get(index);
            }
            if (this.layer1 != null) {
                return this.layer1.get(index);
            }
            return this.base.layer1() == null ? this.air : this.base.layer1().get(index);
        }

        /** Sets a Bedrock block in a layer and recomputes the Java state of that block. */
        public void set(int index, int layer, int runtimeId) {
            if (layer == 0) {
                if (this.layer0 == null) {
                    this.layer0 = this.base.layer0().toBuilder();
                }
                this.layer0.set(index, runtimeId);
            } else {
                if (this.layer1 == null) {
                    this.layer1 = this.base.layer1() == null ? BlockStorage.Builder.filled(this.air) : this.base.layer1().toBuilder();
                }
                this.layer1.set(index, runtimeId);
            }
            setJava(index, this.mapper.toJava(bedrockAt(index, 0), bedrockAt(index, 1)));
        }

        public void setJava(int index, int state) {
            if (this.java == null) {
                if (this.base.java().get(index) == state) {
                    return;
                }
                this.java = this.base.java().toBuilder();
            }
            this.java.set(index, state);
        }

        public ChunkSection build() {
            BlockStorage l0 = this.layer0 != null ? this.layer0.build() : this.base.layer0();
            BlockStorage l1 = this.layer1 != null ? this.layer1.build() : this.base.layer1();
            if (l1 != null && l1.isUniform() && l1.get(0) == this.air) {
                l1 = null;
            }
            BlockStorage j = this.java != null ? this.java.build() : this.base.java();
            return new ChunkSection(l0, l1, j);
        }
    }

    /** An all-air section. */
    public static ChunkSection air(int bedrockAir) {
        return new ChunkSection(BlockStorage.uniform(bedrockAir), null, BlockStorage.uniform(JavaBlockStates.AIR));
    }
}
