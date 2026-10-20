package io.github.wyze3306.jtb.resourcepack.convert;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.wyze3306.jtb.resourcepack.BedrockPack;
import io.github.wyze3306.jtb.resourcepack.TestPacks;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading Bedrock packs: lenient JSON, TGA, zip layouts, encryption, subpacks. */
class PackReadingTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    // ------------------------------------------------------------------ JSON

    @Test
    void lenientJsonAcceptsBomCommentsAndTrailingCommas() {
        JsonElement json = LenientJson.parse("﻿// header comment\n{\n  /* block */ \"a\": [1, 2,],\n"
                + "  \"b\": {\"c\": \"x // not a comment, }\",},  # hash comment\n}");
        JsonObject object = json.getAsJsonObject();
        assertEquals(2, object.getAsJsonArray("a").size(), "trailing comma must not add a null element");
        assertEquals("x // not a comment, }", object.getAsJsonObject("b").get("c").getAsString());
    }

    @Test
    void lenientJsonKeepsEscapedQuotes() {
        JsonObject object = LenientJson.parse("{\"a\": \"say \\\"hi\\\", // ok\",}").getAsJsonObject();
        assertEquals("say \"hi\", // ok", object.get("a").getAsString());
    }

    @Test
    void lenientJsonObjectReturnsNullOnGarbage() {
        assertNull(LenientJson.object("not json {".getBytes(StandardCharsets.UTF_8)));
        assertNull(LenientJson.object("[1, 2]".getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ TGA

    @Test
    void tgaBottomLeftAndTopLeftOrigins() {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFF112233);
        image.setRGB(2, 0, 0x80445566);
        image.setRGB(1, 1, 0xFF778899);
        for (boolean topToBottom : new boolean[]{false, true}) {
            BufferedImage decoded = TgaDecoder.decode(TestPacks.tga(image, topToBottom));
            assertEquals(3, decoded.getWidth());
            assertEquals(2, decoded.getHeight());
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 3; x++) {
                    assertEquals(image.getRGB(x, y), decoded.getRGB(x, y), "pixel " + x + "," + y + " top=" + topToBottom);
                }
            }
        }
    }

    @Test
    void tgaRlePacketsMayCrossScanlines() {
        // 2x2, type 10 (RLE true colour), 24 bpp, bottom-left: one run of 3 red pixels crossing the
        // first row, then one raw blue pixel.
        byte[] data = {0, 0, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 2, 0, 24, 0,
                (byte) 0x82, 0, 0, (byte) 0xFF,
                0x00, (byte) 0xFF, 0, 0};
        BufferedImage image = TgaDecoder.decode(data);
        assertEquals(0xFFFF0000, image.getRGB(0, 1));
        assertEquals(0xFFFF0000, image.getRGB(1, 1));
        assertEquals(0xFFFF0000, image.getRGB(0, 0));
        assertEquals(0xFF0000FF, image.getRGB(1, 0));
    }

    @Test
    void tgaIsConvertedToPng() {
        byte[] tga = TestPacks.tga(TestPacks.solid(4, 4, TestPacks.GREEN), false);
        byte[] png = PackImages.toPng("textures/blocks/dirt.tga", tga);
        assertNotNull(png);
        assertTrue(PackImages.isPng(png));
        assertArrayEquals(new int[]{4, 4}, PackImages.size("x.png", png));
        assertEquals(TestPacks.GREEN, PackImages.decode("x.png", png).getRGB(1, 1));
        assertArrayEquals(new int[]{4, 4}, PackImages.size("textures/blocks/dirt.tga", tga));
    }

    @Test
    void tgaRejectsBadHeaders() {
        assertThrows(IllegalArgumentException.class, () -> TgaDecoder.decode(new byte[5]));
        assertNull(PackImages.decode("x.tga", new byte[]{1, 2, 3}));
    }

    // ------------------------------------------------------------------ zip layouts

    @Test
    void manifestOneFolderDeepAndCaseInsensitiveLookups() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("MyPack/manifest.json", TestPacks.manifest(ID, "Folder pack").getBytes(StandardCharsets.UTF_8));
        files.put("MyPack/Textures/Blocks/Stone.PNG", TestPacks.png(TestPacks.solid(16, 16, TestPacks.RED)));
        files.put("other/ignored.txt", new byte[]{1});
        PackFiles pack = PackFiles.open(TestPacks.pack(ID, TestPacks.zip(files)));
        assertEquals("Folder pack", pack.manifest().name());
        assertEquals("1.0.0", pack.manifest().version());
        assertTrue(pack.manifest().isResourcePack());
        assertNotNull(pack.image("textures/blocks/stone"));
        assertFalse(pack.has("other/ignored.txt"));
    }

    @Test
    void nestedZipIsOpened() throws IOException {
        Map<String, byte[]> inner = Map.of("manifest.json", TestPacks.manifest(ID, "Inner").getBytes(StandardCharsets.UTF_8),
                "textures/blocks/stone.png", TestPacks.png(TestPacks.solid(16, 16, TestPacks.RED)));
        byte[] outer = TestPacks.zip(Map.of("pack.zip", TestPacks.zip(inner)));
        PackFiles pack = PackFiles.open(TestPacks.pack(ID, outer));
        assertEquals("Inner", pack.manifest().name());
        assertNotNull(pack.get("textures/blocks/stone.png"));
    }

    @Test
    void missingManifestIsAnError() {
        byte[] zip = TestPacks.zip(Map.of("textures/a.png", new byte[]{1}));
        assertThrows(IOException.class, () -> PackFiles.open(TestPacks.pack(ID, zip)));
    }

    @Test
    void formatVersion3StringVersions() {
        JsonObject json = LenientJson.parse("{\"format_version\": 3, \"header\": {\"name\": \"n\", \"uuid\": \"" + ID
                + "\", \"version\": \"2.1.0\"}, \"modules\": [{\"type\": \"data\"}]}").getAsJsonObject();
        PackManifest manifest = PackManifest.parse(json);
        assertEquals("2.1.0", manifest.version());
        assertEquals(ID, manifest.uuid());
        assertFalse(manifest.isResourcePack(), "a behaviour pack is not converted");
    }

    // ------------------------------------------------------------------ encryption

    @Test
    void encryptedPackIsDecrypted() throws IOException {
        byte[] stone = TestPacks.png(TestPacks.solid(16, 16, TestPacks.RED));
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("manifest.json", TestPacks.manifest(ID, "Secret").getBytes(StandardCharsets.UTF_8));
        files.put("textures/blocks/stone.png", stone);
        files.put("texts/en_US.lang", "a.b=c\n".getBytes(StandardCharsets.UTF_8));
        byte[] zip = TestPacks.encrypted(files, TestPacks.E2E_KEY, ID.toString());

        Map<String, byte[]> raw = PackFiles.readZip(zip);
        assertTrue(PackFiles.isEncrypted(raw.get("contents.json")));
        assertFalse(PackImages.isPng(raw.get("textures/blocks/stone.png")), "the test pack really is encrypted");

        PackFiles pack = PackFiles.open(new BedrockPack(ID, "1.0.0", TestPacks.E2E_KEY, ID.toString(), "", zip));
        assertArrayEquals(stone, pack.get("textures/blocks/stone.png"));
        assertEquals("a.b=c\n", new String(pack.get("texts/en_US.lang"), StandardCharsets.UTF_8));
        assertNull(pack.get("contents.json"));
    }

    @Test
    void wrongContentKeyIsReported() {
        Map<String, byte[]> files = Map.of("manifest.json", TestPacks.manifest(ID, "Secret").getBytes(StandardCharsets.UTF_8),
                "textures/a.png", new byte[]{1, 2, 3});
        byte[] zip = TestPacks.encrypted(files, TestPacks.E2E_KEY, ID.toString());
        String wrong = "x".repeat(32);
        assertThrows(IOException.class, () -> PackFiles.open(new BedrockPack(ID, "1.0.0", wrong, "", "", zip)));
    }

    @Test
    void plainContentsJsonIsNotTakenForEncryption() {
        assertFalse(PackFiles.isEncrypted("{\"content\": []}".getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ subpacks

    @Test
    void selectedSubpackIsOverlaid() throws IOException {
        byte[] low = TestPacks.png(TestPacks.solid(16, 16, TestPacks.RED));
        byte[] high = TestPacks.png(TestPacks.solid(32, 32, TestPacks.GREEN));
        String manifest = TestPacks.manifest(ID, "Subpacks").replace("\"modules\"",
                "\"subpacks\": [{\"folder_name\": \"high\", \"name\": \"High quality\", \"memory_tier\": 2}], \"modules\"");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
        files.put("textures/blocks/stone.png", low);
        files.put("subpacks/high/textures/blocks/stone.png", high);
        byte[] zip = TestPacks.zip(files);

        PackFiles plain = PackFiles.open(TestPacks.pack(ID, zip));
        assertArrayEquals(low, plain.get("textures/blocks/stone.png"));
        assertFalse(plain.has("subpacks/high/textures/blocks/stone.png"));

        PackFiles selected = PackFiles.open(TestPacks.pack(ID, zip).withSubpack("high"));
        assertArrayEquals(high, selected.get("textures/blocks/stone.png"));
        PackFiles byName = PackFiles.open(TestPacks.pack(ID, zip).withSubpack("High quality"));
        assertArrayEquals(high, byName.get("textures/blocks/stone.png"));
    }
}
