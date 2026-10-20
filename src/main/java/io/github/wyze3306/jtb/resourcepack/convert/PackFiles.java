package io.github.wyze3306.jtb.resourcepack.convert;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.wyze3306.jtb.resourcepack.BedrockPack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * The files of one Bedrock pack, ready to convert: read from its zip (or from a zip nested in it,
 * as CDN downloads and {@code .mcaddon} files are), rooted at the folder holding
 * {@code manifest.json} (the root or one folder deep, like PowerNukkitX accepts), decrypted when the
 * pack is encrypted, with the selected subpack overlaid.
 * <p>
 * Paths are relative to the pack root, {@code /}-separated and looked up case-insensitively
 * (packs authored on Windows mix cases; Java resource paths are lower case anyway).
 * <p>
 * Encrypted packs ({@code contents.json} starting with version {@code 00000000} and magic
 * {@code FCB9CF9B}, 256-byte header, then AES-256-CFB8 with the content key from
 * {@code ResourcePacksInfo}, IV = first 16 key bytes) list one key per file; the decryption follows
 * ViaBedrock's {@code ResourcePack#decryptContent} (GPL-3.0, RaphiMC).
 */
public final class PackFiles {

    private static final Logger LOGGER = LoggerFactory.getLogger(PackFiles.class);
    private static final byte[] ENCRYPTED_VERSION = {0, 0, 0, 0};
    private static final byte[] ENCRYPTED_MAGIC = {(byte) 0xFC, (byte) 0xB9, (byte) 0xCF, (byte) 0x9B};
    private static final int ENCRYPTED_HEADER = 256;
    private static final Set<String> NEVER_ENCRYPTED = Set.of("manifest.json", "pack_manifest.json", "pack_icon.png",
            "pack_icon.jpg", "readme.txt");
    private static final List<String> IMAGE_EXTENSIONS = List.of(".png", ".tga", ".jpg", ".jpeg");
    /** Refuse packs that inflate beyond this (zip bombs). */
    private static final long MAX_UNCOMPRESSED = 2L * 1024 * 1024 * 1024;
    private static final int MAX_NESTING = 2;

    /** A file of the pack: its path (lower case) and content. */
    public record PackFile(String path, byte[] data) {
    }

    private final String name;
    private final PackManifest manifest;
    private final Map<String, byte[]> files;
    private volatile @Nullable List<String> sortedPaths;

    private PackFiles(String name, PackManifest manifest, Map<String, byte[]> files) {
        this.name = name;
        this.manifest = manifest;
        this.files = files;
    }

    /**
     * Reads, decrypts and roots a downloaded pack.
     *
     * @throws IOException when the zip is unreadable or has no manifest
     */
    public static PackFiles open(BedrockPack pack) throws IOException {
        String label = pack.id() + "_" + pack.version();
        Map<String, byte[]> files = readRooted(pack.zip(), 0);
        if (files == null) {
            throw new IOException("No manifest.json in pack " + label);
        }
        JsonObject manifestJson = LenientJson.object(files.getOrDefault("manifest.json", files.get("pack_manifest.json")));
        if (manifestJson == null) {
            throw new IOException("Unreadable manifest.json in pack " + label);
        }
        PackManifest manifest = PackManifest.parse(manifestJson);
        String displayName = manifest.name().isEmpty() ? label : manifest.name() + " (" + label + ")";

        byte[] key = pack.contentKey().getBytes(StandardCharsets.ISO_8859_1);
        if (isEncrypted(files.get("contents.json"))) {
            if (key.length == 0) {
                LOGGER.warn("Pack {} is encrypted but the server sent no content key: its files cannot be read", displayName);
            } else {
                decrypt(files, "", key, pack.contentId(), displayName);
            }
        }
        applySubpack(files, manifest, pack.subpack(), key, pack.contentId(), displayName);
        return new PackFiles(displayName, manifest, files);
    }

    /** Builds a pack from already extracted files (tests, embedded packs). Paths are lower-cased. */
    public static PackFiles of(String name, PackManifest manifest, Map<String, byte[]> files) {
        Map<String, byte[]> lower = new HashMap<>();
        files.forEach((path, data) -> lower.putIfAbsent(path.toLowerCase(Locale.ROOT), data));
        return new PackFiles(name, manifest, lower);
    }

    public String name() {
        return this.name;
    }

    public PackManifest manifest() {
        return this.manifest;
    }

    public byte @Nullable [] get(String path) {
        return this.files.get(path.toLowerCase(Locale.ROOT));
    }

    public boolean has(String path) {
        return this.files.containsKey(path.toLowerCase(Locale.ROOT));
    }

    /** Every path starting with {@code prefix} (lower case), sorted. */
    public List<String> list(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String path : sortedPaths()) {
            if (path.startsWith(lower)) {
                out.add(path);
            }
        }
        return out;
    }

    /** Number of files in the pack. */
    public int size() {
        return this.files.size();
    }

    private List<String> sortedPaths() {
        List<String> paths = this.sortedPaths;
        if (paths == null) {
            paths = this.files.keySet().stream().sorted().toList();
            this.sortedPaths = paths;
        }
        return paths;
    }

    /**
     * The image a Bedrock texture reference ({@code textures/blocks/stone}, no extension) points to
     * in this pack: tries {@code .png}, {@code .tga}, {@code .jpg}, {@code .jpeg}, then the path as
     * given (some references carry their extension).
     */
    public @Nullable PackFile image(String reference) {
        String base = reference.toLowerCase(Locale.ROOT);
        for (String extension : IMAGE_EXTENSIONS) {
            byte[] data = this.files.get(base + extension);
            if (data != null) {
                return new PackFile(base + extension, data);
            }
        }
        byte[] data = this.files.get(base);
        return data != null && IMAGE_EXTENSIONS.stream().anyMatch(base::endsWith) ? new PackFile(base, data) : null;
    }

    /** The JSON object at {@code path}, or null when absent or unreadable. */
    public @Nullable JsonObject json(String path) {
        return LenientJson.object(get(path));
    }

    /** The JSON value at {@code path}, or null when absent or unreadable. */
    public @Nullable JsonElement jsonElement(String path) {
        byte[] data = get(path);
        if (data == null) {
            return null;
        }
        try {
            return LenientJson.parse(data);
        } catch (RuntimeException e) {
            LOGGER.debug("Unreadable JSON {} in pack {}: {}", path, this.name, e.toString());
            return null;
        }
    }

    @Override
    public String toString() {
        return this.name;
    }

    // ------------------------------------------------------------------ zip

    /** The files under the folder holding the manifest, or null when there is none (also nested). */
    private static @Nullable Map<String, byte[]> readRooted(byte[] zip, int depth) throws IOException {
        Map<String, byte[]> all = readZip(zip);
        String root = findRoot(all);
        if (root != null) {
            if (root.isEmpty()) {
                return all;
            }
            Map<String, byte[]> rooted = new HashMap<>();
            all.forEach((path, data) -> {
                if (path.startsWith(root)) {
                    rooted.putIfAbsent(path.substring(root.length()), data);
                }
            });
            return rooted;
        }
        if (depth >= MAX_NESTING) {
            return null;
        }
        // A CDN download or an .mcaddon: the pack is a zip inside the zip. Prefer a resource pack.
        Map<String, byte[]> fallback = null;
        for (String path : new TreeMap<>(all).keySet()) {
            if ((path.endsWith(".zip") || path.endsWith(".mcpack")) && path.chars().filter(c -> c == '/').count() <= 1) {
                Map<String, byte[]> nested;
                try {
                    nested = readRooted(all.get(path), depth + 1);
                } catch (IOException e) {
                    continue;
                }
                if (nested == null) {
                    continue;
                }
                JsonObject manifest = LenientJson.object(nested.getOrDefault("manifest.json", nested.get("pack_manifest.json")));
                if (manifest != null && PackManifest.parse(manifest).isResourcePack()) {
                    return nested;
                }
                if (fallback == null) {
                    fallback = nested;
                }
            }
        }
        return fallback;
    }

    private static @Nullable String findRoot(Map<String, byte[]> files) {
        if (files.containsKey("manifest.json") || files.containsKey("pack_manifest.json")) {
            return "";
        }
        String best = null;
        for (String path : files.keySet()) {
            int slash = path.indexOf('/');
            if (slash > 0 && path.indexOf('/', slash + 1) < 0) {
                String file = path.substring(slash + 1);
                if (file.equals("manifest.json") || file.equals("pack_manifest.json")) {
                    String root = path.substring(0, slash + 1);
                    if (best == null || root.compareTo(best) < 0) {
                        best = root;
                    }
                }
            }
        }
        return best;
    }

    static Map<String, byte[]> readZip(byte[] zip) throws IOException {
        try {
            return readZipStream(zip, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Entry names that are not UTF-8: PowerNukkitX retries the same way.
            return readZipStream(zip, Charset.forName("GBK"));
        } catch (ZipException e) {
            // ZipInputStream rejects some valid zips (stored entries with data descriptors): read the
            // central directory instead.
            return readZipFile(zip);
        }
    }

    private static Map<String, byte[]> readZipStream(byte[] zip, Charset charset) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), charset)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                total += put(files, entry, in, total);
            }
        }
        return files;
    }

    private static Map<String, byte[]> readZipFile(byte[] zip) throws IOException {
        Path temp = Files.createTempFile("jtb-pack", ".zip");
        try {
            Files.write(temp, zip);
            Map<String, byte[]> files = new HashMap<>();
            long total = 0;
            try (ZipFile file = new ZipFile(temp.toFile())) {
                Enumeration<? extends ZipEntry> entries = file.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    try (InputStream in = file.getInputStream(entry)) {
                        total += put(files, entry, in, total);
                    }
                }
            }
            return files;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static long put(Map<String, byte[]> files, ZipEntry entry, InputStream in, long total) throws IOException {
        if (entry.isDirectory()) {
            return 0;
        }
        String path = normalize(entry.getName());
        if (path == null) {
            return 0;
        }
        byte[] data = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 16, MAX_UNCOMPRESSED - total + 1));
        if (total + data.length > MAX_UNCOMPRESSED) {
            throw new IOException("Pack inflates beyond " + MAX_UNCOMPRESSED + " bytes");
        }
        files.putIfAbsent(path.toLowerCase(Locale.ROOT), data);
        return data.length;
    }

    private static @Nullable String normalize(String name) {
        String path = name.replace('\\', '/');
        while (path.startsWith("/") || path.startsWith("./")) {
            path = path.substring(path.startsWith("/") ? 1 : 2);
        }
        if (path.isEmpty() || path.endsWith("/") || path.startsWith("__macosx/") || path.startsWith("__MACOSX/")) {
            return null;
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                return null;
            }
        }
        return path;
    }

    // ------------------------------------------------------------------ encryption

    static boolean isEncrypted(byte @Nullable [] contents) {
        return contents != null && contents.length > ENCRYPTED_HEADER
                && Arrays.equals(contents, 0, 4, ENCRYPTED_VERSION, 0, 4)
                && Arrays.equals(contents, 4, 8, ENCRYPTED_MAGIC, 0, 4);
    }

    /**
     * Decrypts the {@code contents.json} under {@code prefix} and every file it lists with a key (paths
     * relative to {@code prefix}). A content id that differs from the one the server announced is only
     * logged: PowerNukkitX announces the pack UUID, whatever tool encrypted the pack.
     */
    static void decrypt(Map<String, byte[]> files, String prefix, byte[] contentKey, String expectedContentId, String pack)
            throws IOException {
        byte[] contents = files.get(prefix + "contents.json");
        if (!isEncrypted(contents)) {
            return;
        }
        int idLength = contents[0x10] & 0xFF;
        String contentId = new String(contents, 0x11, Math.min(idLength, ENCRYPTED_HEADER - 0x11), StandardCharsets.UTF_8);
        if (!expectedContentId.isEmpty() && !contentId.equalsIgnoreCase(expectedContentId)) {
            LOGGER.debug("Pack {}: content id {} differs from the announced {}", pack, contentId, expectedContentId);
        }
        JsonObject index;
        try {
            byte[] plain = aesCfb8(contentKey, contents, ENCRYPTED_HEADER, contents.length - ENCRYPTED_HEADER);
            index = LenientJson.object(plain);
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot decrypt contents.json of " + pack + ": " + e.getMessage(), e);
        }
        if (index == null || !index.has("content") || !index.get("content").isJsonArray()) {
            throw new IOException("Wrong content key for pack " + pack + " (contents.json does not decrypt to JSON)");
        }
        int decrypted = 0;
        for (JsonElement element : index.getAsJsonArray("content")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            JsonElement fileKey = entry.get("key");
            JsonElement filePath = entry.get("path");
            if (fileKey == null || fileKey.isJsonNull() || filePath == null || !filePath.isJsonPrimitive()) {
                continue;
            }
            String path = normalize(filePath.getAsString());
            if (path == null) {
                continue;
            }
            String lower = path.toLowerCase(Locale.ROOT);
            if (NEVER_ENCRYPTED.contains(lower)) {
                continue;
            }
            byte[] data = files.get(prefix + lower);
            if (data == null) {
                continue;
            }
            try {
                byte[] key = fileKey.getAsString().getBytes(StandardCharsets.ISO_8859_1);
                files.put(prefix + lower, aesCfb8(key, data, 0, data.length));
                decrypted++;
            } catch (GeneralSecurityException e) {
                LOGGER.debug("Pack {}: cannot decrypt {}: {}", pack, path, e.toString());
            }
        }
        files.remove(prefix + "contents.json");
        LOGGER.debug("Pack {}: decrypted {} file(s)", pack, decrypted);
    }

    private static byte[] aesCfb8(byte[] key, byte[] data, int offset, int length) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(Arrays.copyOf(key, 16)));
        return cipher.doFinal(data, offset, length);
    }

    // ------------------------------------------------------------------ subpacks

    /**
     * Overlays {@code subpacks/<folder>/} of the selected subpack (matched by folder or display
     * name) onto the root, then drops every subpack folder.
     */
    private static void applySubpack(Map<String, byte[]> files, PackManifest manifest, String selected, byte[] key,
                                     String contentId, String pack) throws IOException {
        if (!selected.isEmpty()) {
            PackManifest.Subpack match = null;
            for (PackManifest.Subpack subpack : manifest.subpacks()) {
                if (subpack.folderName().equalsIgnoreCase(selected)) {
                    match = subpack;
                    break;
                }
                if (match == null && subpack.name().equalsIgnoreCase(selected)) {
                    match = subpack;
                }
            }
            if (match == null) {
                LOGGER.debug("Pack {}: selected subpack {} is not declared", pack, selected);
            } else {
                String prefix = "subpacks/" + match.folderName().toLowerCase(Locale.ROOT) + "/";
                if (key.length > 0) {
                    decrypt(files, prefix, key, contentId, pack);
                }
                Map<String, byte[]> overlay = new HashMap<>();
                files.forEach((path, data) -> {
                    if (path.startsWith(prefix)) {
                        overlay.put(path.substring(prefix.length()), data);
                    }
                });
                files.putAll(overlay);
            }
        }
        files.keySet().removeIf(path -> path.startsWith("subpacks/"));
    }
}
