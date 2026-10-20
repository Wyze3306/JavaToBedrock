package io.github.wyze3306.jtb.world.light;

import io.github.wyze3306.jtb.world.chunk.ChunkSection;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Light of one chunk column, owned by the light worker. Light sections are the Java sections plus
 * one below and one above the world (index 0 is below). A missing array means a uniform section:
 * sky light 0 or 15 ({@link #skyUniform}), block light 0.
 */
final class LightColumn {

    final int x;
    final int z;
    /** Java sections as last published by the session loop (null = air). */
    final @Nullable ChunkSection[] sections;
    final byte @Nullable [][] sky;
    final byte[] skyUniform;
    final byte @Nullable [][] block;
    /** Per column {@code x | z << 4}: light y of the lowest cell lit by the open sky (15); cells above too. */
    final int[] skySource = new int[256];
    /** Light sections changed since the column was last sent to the client. */
    long dirtySky;
    long dirtyBlock;
    /** Whether the client has this column (changes then need light update packets). */
    boolean sent;

    LightColumn(int x, int z, @Nullable ChunkSection[] sections, int lightSections) {
        this.x = x;
        this.z = z;
        this.sections = sections;
        this.sky = new byte[lightSections][];
        this.skyUniform = new byte[lightSections];
        this.block = new byte[lightSections][];
    }

    static int nibble(byte[] array, int index) {
        return (array[index >> 1] >> ((index & 1) << 2)) & 15;
    }

    static void setNibble(byte[] array, int index, int value) {
        int i = index >> 1;
        int shift = (index & 1) << 2;
        array[i] = (byte) ((array[i] & ~(15 << shift)) | (value << shift));
    }

    int sky(int lx, int ly, int lz) {
        int s = ly >> 4;
        byte[] array = this.sky[s];
        return array == null ? this.skyUniform[s] : nibble(array, ((ly & 15) << 8) | (lz << 4) | lx);
    }

    void setSky(int lx, int ly, int lz, int value) {
        int s = ly >> 4;
        byte[] array = this.sky[s];
        if (array == null) {
            int uniform = this.skyUniform[s];
            if (uniform == value) {
                return;
            }
            array = new byte[2048];
            if (uniform != 0) {
                Arrays.fill(array, (byte) (uniform | uniform << 4));
            }
            this.sky[s] = array;
        }
        setNibble(array, ((ly & 15) << 8) | (lz << 4) | lx, value);
        this.dirtySky |= 1L << s;
    }

    int block(int lx, int ly, int lz) {
        byte[] array = this.block[ly >> 4];
        return array == null ? 0 : nibble(array, ((ly & 15) << 8) | (lz << 4) | lx);
    }

    void setBlock(int lx, int ly, int lz, int value) {
        int s = ly >> 4;
        byte[] array = this.block[s];
        if (array == null) {
            if (value == 0) {
                return;
            }
            array = new byte[2048];
            this.block[s] = array;
        }
        setNibble(array, ((ly & 15) << 8) | (lz << 4) | lx, value);
        this.dirtyBlock |= 1L << s;
    }
}
