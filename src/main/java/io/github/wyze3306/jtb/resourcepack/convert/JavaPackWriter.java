package io.github.wyze3306.jtb.resourcepack.convert;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The files of the Java pack being built. Thread-safe, so converters may write from parallel
 * tasks; the zip is <b>deterministic</b>: entries sorted by path, one fixed timestamp (set as a
 * local DOS time, independent of the time zone), already-compressed formats stored and everything
 * else deflated at a fixed level. Equal inputs therefore give an equal SHA-1, which lets Java
 * clients reuse their download cache across proxy restarts.
 */
public final class JavaPackWriter {

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final LocalDateTime TIMESTAMP = LocalDateTime.of(2020, 1, 1, 0, 0);

    private final Map<String, byte[]> entries;

    public JavaPackWriter() {
        this.entries = new ConcurrentHashMap<>();
    }

    /** A writer starting with the files of {@code base} (which is left unchanged). */
    public JavaPackWriter(JavaPackWriter base) {
        this.entries = new ConcurrentHashMap<>(base.entries);
    }

    public void put(String path, byte[] data) {
        this.entries.put(path, data);
    }

    public void putJson(String path, JsonElement json) {
        put(path, GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
    }

    public void putText(String path, String text) {
        put(path, text.getBytes(StandardCharsets.UTF_8));
    }

    public boolean has(String path) {
        return this.entries.containsKey(path);
    }

    public byte @Nullable [] get(String path) {
        return this.entries.get(path);
    }

    public void remove(String path) {
        this.entries.remove(path);
    }

    public int size() {
        return this.entries.size();
    }

    /** All entries, sorted by path. */
    public Map<String, byte[]> sortedEntries() {
        return new TreeMap<>(this.entries);
    }

    public byte[] toZip() {
        Map<String, byte[]> sorted = sortedEntries();
        long total = 0;
        for (byte[] data : sorted.values()) {
            total += data.length;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) Math.min(Integer.MAX_VALUE - 16, total / 2 + 4096));
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            for (Map.Entry<String, byte[]> file : sorted.entrySet()) {
                byte[] data = file.getValue();
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTimeLocal(TIMESTAMP);
                if (isCompressed(file.getKey())) {
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(data.length);
                    entry.setCompressedSize(data.length);
                    entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry);
                zip.write(data);
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static boolean isCompressed(String path) {
        return path.endsWith(".png") || path.endsWith(".ogg");
    }
}
