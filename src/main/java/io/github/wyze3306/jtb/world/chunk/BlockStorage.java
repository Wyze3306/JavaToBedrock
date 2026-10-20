package io.github.wyze3306.jtb.world.chunk;

import java.util.Arrays;
import java.util.function.IntPredicate;

/**
 * 16x16x16 values (block runtime ids or Java states) as a palette plus one index per block, indexed
 * {@code (y << 8) | (z << 4) | x} like Java chunk sections and light arrays.
 * <p>
 * Instances are immutable once published: edits return a copy ({@link #with}) or go through a
 * {@link Builder}, so sections can be shared between the session loop and the light worker without
 * locking. Index arrays may be shared between two storages with different palettes (Java states and
 * Bedrock ids of the same section).
 */
public final class BlockStorage {

    public static final int SIZE = 4096;

    private final int[] palette;
    /** One byte per block when the palette has at most 256 entries, else {@link #wide}; both null when uniform. */
    private final byte[] narrow;
    private final char[] wide;

    private BlockStorage(int[] palette, byte[] narrow, char[] wide) {
        this.palette = palette;
        this.narrow = narrow;
        this.wide = wide;
    }

    /** A storage holding {@code value} everywhere. */
    public static BlockStorage uniform(int value) {
        return new BlockStorage(new int[]{value}, null, null);
    }

    /** A storage over a palette and byte indices (taken, not copied). */
    static BlockStorage ofBytes(int[] palette, byte[] indices) {
        return palette.length == 1 ? uniform(palette[0]) : new BlockStorage(palette, indices, null);
    }

    /** A storage over a palette and char indices (taken, not copied). */
    static BlockStorage ofChars(int[] palette, char[] indices) {
        return palette.length == 1 ? uniform(palette[0]) : new BlockStorage(palette, null, indices);
    }

    /** The same indices with another palette of the same size (e.g. Bedrock ids mapped to Java states). */
    public BlockStorage withPalette(int[] newPalette) {
        if (newPalette.length != this.palette.length) {
            throw new IllegalArgumentException("Palette size differs");
        }
        return new BlockStorage(newPalette, this.narrow, this.wide);
    }

    public int get(int index) {
        if (this.narrow != null) {
            return this.palette[this.narrow[index] & 0xFF];
        }
        if (this.wide != null) {
            return this.palette[this.wide[index]];
        }
        return this.palette[0];
    }

    public static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    public boolean isUniform() {
        return this.narrow == null && this.wide == null;
    }

    /** The palette (shared, must not be modified). May contain values no block uses. */
    public int[] palette() {
        return this.palette;
    }

    /** Palette index of a block. */
    public int paletteIndex(int index) {
        if (this.narrow != null) {
            return this.narrow[index] & 0xFF;
        }
        return this.wide != null ? this.wide[index] : 0;
    }

    /** Whether some palette entry satisfies the test (a cheap pre-check before scanning blocks). */
    public boolean paletteContains(IntPredicate test) {
        for (int value : this.palette) {
            if (test.test(value)) {
                return true;
            }
        }
        return false;
    }

    /** A copy with one block changed. */
    public BlockStorage with(int index, int value) {
        if (get(index) == value) {
            return this;
        }
        Builder builder = toBuilder();
        builder.set(index, value);
        return builder.build();
    }

    public Builder toBuilder() {
        Builder builder = new Builder(this.palette.length + 4);
        builder.palette = Arrays.copyOf(this.palette, Math.max(this.palette.length + 4, 8));
        builder.size = this.palette.length;
        if (this.narrow != null) {
            builder.wide = new char[SIZE];
            for (int i = 0; i < SIZE; i++) {
                builder.wide[i] = (char) (this.narrow[i] & 0xFF);
            }
        } else if (this.wide != null) {
            builder.wide = this.wide.clone();
        } else {
            builder.wide = new char[SIZE];
        }
        return builder;
    }

    /** Mutable storage used while decoding or applying many changes; not thread safe. */
    public static final class Builder {

        private int[] palette;
        private int size;
        private char[] wide;

        Builder(int capacity) {
            this.palette = new int[Math.max(capacity, 8)];
        }

        /** A builder filled with {@code value}. */
        public static Builder filled(int value) {
            Builder builder = new Builder(8);
            builder.palette[0] = value;
            builder.size = 1;
            builder.wide = new char[SIZE];
            return builder;
        }

        public int get(int index) {
            return this.palette[this.wide[index]];
        }

        public void set(int index, int value) {
            this.wide[index] = (char) indexOf(value);
        }

        private int indexOf(int value) {
            for (int i = 0; i < this.size; i++) {
                if (this.palette[i] == value) {
                    return i;
                }
            }
            if (this.size == this.palette.length) {
                this.palette = Arrays.copyOf(this.palette, this.size * 2);
            }
            this.palette[this.size] = value;
            return this.size++;
        }

        /** An immutable storage, with the palette compacted to the values still in use. */
        public BlockStorage build() {
            int[] remap = new int[this.size];
            Arrays.fill(remap, -1);
            int used = 0;
            int[] compact = new int[this.size];
            for (int i = 0; i < SIZE; i++) {
                int old = this.wide[i];
                if (remap[old] < 0) {
                    remap[old] = used;
                    compact[used++] = this.palette[old];
                }
            }
            int[] palette = Arrays.copyOf(compact, used);
            if (used == 1) {
                return uniform(palette[0]);
            }
            if (used <= 256) {
                byte[] narrow = new byte[SIZE];
                for (int i = 0; i < SIZE; i++) {
                    narrow[i] = (byte) remap[this.wide[i]];
                }
                return new BlockStorage(palette, narrow, null);
            }
            char[] wide = new char[SIZE];
            for (int i = 0; i < SIZE; i++) {
                wide[i] = (char) remap[this.wide[i]];
            }
            return new BlockStorage(palette, null, wide);
        }
    }
}
