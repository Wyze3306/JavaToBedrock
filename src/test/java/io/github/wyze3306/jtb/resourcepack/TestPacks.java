package io.github.wyze3306.jtb.resourcepack;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds Bedrock test packs: plain and encrypted zips, images, TGA files, minimal Ogg Vorbis headers.
 * <p>
 * {@code main(<directory> [<real .ogg file>])} writes the two packs the end-to-end scenario
 * {@code ResourcePackScenario} expects into a PowerNukkitX {@code resource_packs} folder:
 * {@code jtb_e2e_pack.mcpack} and the encrypted {@code jtb_e2e_encrypted.mcpack} with its
 * {@code .key} file (PowerNukkitX reads the content key from {@code <pack file name>.key}).
 */
public final class TestPacks {

    public static final UUID E2E_PACK = UUID.fromString("6a2f6e3c-1b7d-4f7e-9c35-2d0b7c1e4a11");
    public static final UUID E2E_ENCRYPTED = UUID.fromString("6a2f6e3c-1b7d-4f7e-9c35-2d0b7c1e4a12");
    /** 32 ASCII characters: an AES-256 key. */
    public static final String E2E_KEY = "JtbE2eContentKey0123456789abcdef";
    public static final int RED = 0xFFE01010;
    public static final int GREEN = 0xFF10C020;
    public static final int BLUE = 0xFF1030E0;

    private TestPacks() {
    }

    public static void main(String[] args) throws IOException {
        Path directory = Path.of(args[0]);
        byte[] ogg = args.length > 1 ? Files.readAllBytes(Path.of(args[1])) : fakeVorbis();
        Files.createDirectories(directory);
        Files.write(directory.resolve("jtb_e2e_pack.mcpack"), zip(e2ePack(ogg)));
        Files.write(directory.resolve("jtb_e2e_encrypted.mcpack"), encrypted(e2eEncryptedPack(), E2E_KEY, E2E_ENCRYPTED.toString()));
        Files.writeString(directory.resolve("jtb_e2e_encrypted.mcpack.key"), E2E_KEY);
    }

    /** The plain end-to-end pack: overrides, a TGA, an animated texture, glyphs, a sound, translations, an entity. */
    public static Map<String, byte[]> e2ePack(byte[] ogg) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("manifest.json", manifest(E2E_PACK, "pack.name").getBytes(StandardCharsets.UTF_8));
        files.put("pack_icon.png", png(solid(16, 16, BLUE)));
        files.put("textures/blocks/stone.png", png(solid(16, 16, RED)));
        files.put("textures/blocks/dirt.tga", tga(solid(16, 16, GREEN), false));
        files.put("textures/blocks/sea_lantern.png", png(strip(16, 3)));
        files.put("textures/blocks/jtb_cobble.png", png(solid(32, 32, BLUE)));
        files.put("textures/items/jtb_ruby.png", png(solid(16, 16, RED)));
        files.put("textures/models/armor/jtb_ruby_1.png", png(solid(64, 32, RED)));
        files.put("textures/entity/jtb_cube.png", png(solid(64, 64, GREEN)));
        files.put("textures/terrain_texture.json", ("""
                // terrain atlas with comments and trailing commas
                {
                  "resource_pack_name": "jtb", "texture_name": "atlas.terrain",
                  "texture_data": {
                    "cobblestone": {"textures": "textures/blocks/jtb_cobble"},
                    "jtb_ruby_block": {"textures": ["textures/blocks/stone",]},
                  },
                }""").getBytes(StandardCharsets.UTF_8));
        files.put("textures/item_texture.json", """
                {"resource_pack_name": "jtb", "texture_name": "atlas.items",
                 "texture_data": {"jtb_ruby": {"textures": "textures/items/jtb_ruby"}}}""".getBytes(StandardCharsets.UTF_8));
        files.put("font/glyph_E1.png", png(glyphSheet()));
        files.put("sounds/sound_definitions.json", """
                {"format_version": "1.20.20", "sound_definitions": {
                  "jtb.test.beep": {"category": "ui", "max_distance": 24.0,
                                    "sounds": [{"name": "sounds/jtb/beep", "volume": 0.8, "pitch": 1.2}]},
                  "jtb.test.missing": {"category": "ui", "sounds": ["sounds/jtb/missing"]}
                }}""".getBytes(StandardCharsets.UTF_8));
        files.put("sounds/jtb/beep.ogg", ogg);
        files.put("texts/languages.json", "[\"en_US\", \"fr_FR\"]".getBytes(StandardCharsets.UTF_8));
        files.put("texts/en_US.lang", "﻿## JavaToBedrock e2e\npack.name=JTB E2E Pack\njtb.test.hello=Hello %s from the pack\t# comment\njtb.test.count=%d items, %1 first\n"
                .getBytes(StandardCharsets.UTF_8));
        files.put("texts/fr_FR.lang", "jtb.test.hello=Bonjour %s depuis le pack\n".getBytes(StandardCharsets.UTF_8));
        files.put("entity/jtb_cube.entity.json", """
                {"format_version": "1.10.0", "minecraft:client_entity": {"description": {
                  "identifier": "jtb:cube", "materials": {"default": "entity_alphatest"},
                  "textures": {"default": "textures/entity/jtb_cube"},
                  "geometry": {"default": "geometry.jtb.cube"},
                  "render_controllers": ["controller.render.default"]}}}""".getBytes(StandardCharsets.UTF_8));
        files.put("models/entity/jtb_cube.geo.json", """
                {"format_version": "1.12.0", "minecraft:geometry": [{
                  "description": {"identifier": "geometry.jtb.cube", "texture_width": 64, "texture_height": 64},
                  "bones": [
                    {"name": "body", "pivot": [0, 0, 0], "cubes": [{"origin": [-8, 0, -8], "size": [16, 16, 16], "uv": [0, 0]}]},
                    {"name": "head", "parent": "body", "pivot": [0, 16, 0], "rotation": [0, 45, 0],
                     "cubes": [{"origin": [-4, 16, -4], "size": [8, 8, 8], "uv": [0, 32]}]}
                  ]}]}""".getBytes(StandardCharsets.UTF_8));
        files.put("attachables/jtb_ruby_helmet.json", """
                {"format_version": "1.10.0", "minecraft:attachable": {"description": {
                  "identifier": "jtb:ruby_helmet", "materials": {"default": "armor"},
                  "textures": {"default": "textures/models/armor/jtb_ruby_1"},
                  "geometry": {"default": "geometry.humanoid.armor.helmet"},
                  "render_controllers": ["controller.render.armor"]}}}""".getBytes(StandardCharsets.UTF_8));
        return files;
    }

    /** The encrypted end-to-end pack: one texture override and one translation. */
    public static Map<String, byte[]> e2eEncryptedPack() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("manifest.json", manifest(E2E_ENCRYPTED, "JTB E2E Encrypted").getBytes(StandardCharsets.UTF_8));
        files.put("textures/blocks/gravel.png", png(solid(16, 16, GREEN)));
        files.put("texts/en_US.lang", "jtb.test.secret=Encrypted pack works\n".getBytes(StandardCharsets.UTF_8));
        return files;
    }

    public static String manifest(UUID id, String name) {
        return """
                {"format_version": 2,
                 "header": {"description": "JavaToBedrock test pack", "name": "%s", "uuid": "%s",
                            "version": [1, 0, 0], "min_engine_version": [1, 21, 0]},
                 "modules": [{"description": "resources", "type": "resources", "uuid": "%s", "version": [1, 0, 0]}]}
                """.formatted(name, id, UUID.nameUUIDFromBytes(id.toString().getBytes(StandardCharsets.UTF_8)));
    }

    public static byte[] zip(Map<String, byte[]> files) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * An encrypted pack as Mojang's tools produce it: {@code contents.json} = 256-byte header
     * (version 0, magic, content id) + the AES-256-CFB8 encrypted file index (key = content key);
     * every file except the manifest is encrypted with its own 32-character key.
     */
    public static byte[] encrypted(Map<String, byte[]> files, String contentKey, String contentId) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        StringBuilder index = new StringBuilder("{\"content\":[");
        int n = 0;
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            if (n++ > 0) {
                index.append(',');
            }
            if (file.getKey().equals("manifest.json")) {
                out.put(file.getKey(), file.getValue());
                index.append("{\"path\":\"manifest.json\"}");
                continue;
            }
            String key = String.format("%032d", n * 7919L).substring(0, 32);
            out.put(file.getKey(), aes(key.getBytes(StandardCharsets.ISO_8859_1), file.getValue()));
            index.append("{\"path\":\"").append(file.getKey()).append("\",\"key\":\"").append(key).append("\"}");
        }
        index.append("]}");
        byte[] header = new byte[256];
        header[4] = (byte) 0xFC;
        header[5] = (byte) 0xB9;
        header[6] = (byte) 0xCF;
        header[7] = (byte) 0x9B;
        byte[] id = contentId.getBytes(StandardCharsets.UTF_8);
        header[0x10] = (byte) id.length;
        System.arraycopy(id, 0, header, 0x11, id.length);
        byte[] body = aes(contentKey.getBytes(StandardCharsets.ISO_8859_1), index.toString().getBytes(StandardCharsets.UTF_8));
        byte[] contents = Arrays.copyOf(header, header.length + body.length);
        System.arraycopy(body, 0, contents, header.length, body.length);
        out.put("contents.json", contents);
        return zip(out);
    }

    private static byte[] aes(byte[] key, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(Arrays.copyOf(key, 16)));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static BufferedImage solid(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    /** A vertical strip of {@code frames} square frames, each a different colour. */
    public static BufferedImage strip(int size, int frames) {
        int[] colours = {RED, GREEN, BLUE, 0xFFFFFFFF};
        BufferedImage image = new BufferedImage(size, size * frames, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size * frames; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, colours[(y / size) % colours.length]);
            }
        }
        return image;
    }

    /** A 256 x 256 glyph sheet with a filled square in cell 0 and cell 0x21. */
    public static BufferedImage glyphSheet() {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        for (int y = 2; y < 14; y++) {
            for (int x = 2; x < 14; x++) {
                image.setRGB(x, y, RED);
                image.setRGB(16 + x, 32 + y, GREEN);
            }
        }
        return image;
    }

    public static byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Uncompressed 32-bit TGA, bottom-left origin unless {@code topToBottom}. */
    public static byte[] tga(BufferedImage image, boolean topToBottom) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] out = new byte[18 + width * height * 4];
        out[2] = 2;
        out[12] = (byte) width;
        out[13] = (byte) (width >> 8);
        out[14] = (byte) height;
        out[15] = (byte) (height >> 8);
        out[16] = 32;
        out[17] = (byte) (8 | (topToBottom ? 0x20 : 0));
        int i = 18;
        for (int row = 0; row < height; row++) {
            int y = topToBottom ? row : height - 1 - row;
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                out[i++] = (byte) argb;
                out[i++] = (byte) (argb >> 8);
                out[i++] = (byte) (argb >> 16);
                out[i++] = (byte) (argb >>> 24);
            }
        }
        return out;
    }

    /** The first Ogg page of a Vorbis stream (enough for the converter's format check). */
    public static byte[] fakeVorbis() {
        byte[] out = new byte[27 + 1 + 30];
        out[0] = 'O';
        out[1] = 'g';
        out[2] = 'g';
        out[3] = 'S';
        out[5] = 2;
        out[26] = 1;
        out[27] = 30;
        out[28] = 1;
        byte[] vorbis = "vorbis".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(vorbis, 0, out, 29, vorbis.length);
        return out;
    }

    public static BedrockPack pack(UUID id, byte[] zip) {
        return new BedrockPack(id, "1.0.0", "", "", "", zip);
    }
}
