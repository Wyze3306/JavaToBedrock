package io.github.wyze3306.jtb.block;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Big-endian reader over the decompressed block tables, with bulk array reads. */
final class BlockDataReader {

    private final ByteBuffer buffer;

    BlockDataReader(byte[] data) {
        this.buffer = ByteBuffer.wrap(data);
    }

    int u8() {
        return this.buffer.get() & 0xFF;
    }

    byte i8() {
        return this.buffer.get();
    }

    int u16() {
        return this.buffer.getShort() & 0xFFFF;
    }

    int i32() {
        return this.buffer.getInt();
    }

    String utf() {
        int length = u16();
        String value = new String(this.buffer.array(), this.buffer.position(), length, StandardCharsets.UTF_8);
        this.buffer.position(this.buffer.position() + length);
        return value;
    }

    byte[] bytes(int count) {
        byte[] out = new byte[count];
        this.buffer.get(out);
        return out;
    }

    char[] chars(int count) {
        char[] out = new char[count];
        this.buffer.asCharBuffer().get(out);
        this.buffer.position(this.buffer.position() + count * 2);
        return out;
    }

    int[] ints(int count) {
        int[] out = new int[count];
        this.buffer.asIntBuffer().get(out);
        this.buffer.position(this.buffer.position() + count * 4);
        return out;
    }
}
