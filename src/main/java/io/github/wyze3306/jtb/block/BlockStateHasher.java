package io.github.wyze3306.jtb.block;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.nbt.NBTOutputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

/**
 * Bedrock block runtime ids as servers compute them.
 * <ul>
 *     <li>Hashed ids ({@code blockNetworkIdsAreHashes}, PowerNukkitX): FNV-1a 32 of the little-endian
 *     NBT {@code {name, states}} with the states sorted by key and their exact tag types (booleans are
 *     bytes); {@code minecraft:unknown} is -2.</li>
 *     <li>Ordered ids: the index in the palette sorted (stably) by the unsigned FNV-1 64 hash of the
 *     block name (multiply, then xor).</li>
 * </ul>
 */
public final class BlockStateHasher {

    private BlockStateHasher() {
    }

    /** Hashed runtime id of a Bedrock state. */
    public static int hash(String name, NbtMap states) {
        if (name.equals("minecraft:unknown")) {
            return -2;
        }
        NbtMap tag = NbtMap.builder()
                .putString("name", name)
                .putCompound("states", NbtMap.fromMap(new TreeMap<>(states)))
                .build();
        ByteBuf buffer = Unpooled.buffer(128);
        try (NBTOutputStream out = NbtUtils.createWriterLE(new ByteBufOutputStream(buffer))) {
            out.writeTag(tag);
            int h = 0x811c9dc5;
            for (int i = buffer.readerIndex(), end = buffer.writerIndex(); i < end; i++) {
                h ^= buffer.getByte(i) & 0xff;
                h *= 0x01000193;
            }
            return h;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            buffer.release();
        }
    }

    /** FNV-1 64 of a block name: the sort key of the ordered palette (compare unsigned). */
    public static long nameOrder(String name) {
        long h = 0xcbf29ce484222325L;
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            h *= 0x100000001b3L;
            h ^= b & 0xff;
        }
        return h;
    }
}
