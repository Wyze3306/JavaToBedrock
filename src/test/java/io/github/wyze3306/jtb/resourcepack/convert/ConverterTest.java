package io.github.wyze3306.jtb.resourcepack.convert;

import io.github.wyze3306.jtb.block.MolangConditions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.wyze3306.jtb.resourcepack.BedrockPack;
import io.github.wyze3306.jtb.resourcepack.ConversionArtifacts;
import io.github.wyze3306.jtb.resourcepack.CustomBlockCarrier;
import io.github.wyze3306.jtb.resourcepack.JavaAssetNames;
import io.github.wyze3306.jtb.resourcepack.ServerDefinitions;
import io.github.wyze3306.jtb.resourcepack.TestPacks;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The conversion of a Bedrock pack stack into a Java pack. */
class ConverterTest {

    private static List<BedrockPack> e2eStack() {
        return List.of(TestPacks.pack(TestPacks.E2E_PACK, TestPacks.zip(TestPacks.e2ePack(TestPacks.fakeVorbis()))),
                new BedrockPack(TestPacks.E2E_ENCRYPTED, "1.0.0", TestPacks.E2E_KEY, TestPacks.E2E_ENCRYPTED.toString(), "",
                        TestPacks.encrypted(TestPacks.e2eEncryptedPack(), TestPacks.E2E_KEY, TestPacks.E2E_ENCRYPTED.toString())));
    }

    static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), in.readAllBytes());
            }
        }
        return files;
    }

    private static BufferedImage image(Map<String, byte[]> files, String path) {
        byte[] data = files.get(path);
        assertNotNull(data, path + " missing");
        return PackImages.decode(path, data);
    }

    private static JsonObject json(Map<String, byte[]> files, String path) {
        byte[] data = files.get(path);
        assertNotNull(data, path + " missing");
        return LenientJson.parse(data).getAsJsonObject();
    }

    // ------------------------------------------------------------------ whole stack

    @Test
    void stackConversionWritesEveryKindOfAsset() throws IOException {
        BedrockToJavaConverter.ConvertedZip converted = BedrockToJavaConverter.convert(e2eStack(), ServerDefinitions.EMPTY);
        Map<String, byte[]> files = unzip(converted.zip());

        JsonObject pack = json(files, "pack.mcmeta").getAsJsonObject("pack");
        assertEquals(97, pack.get("min_format").getAsInt());
        assertEquals(97, pack.get("max_format").getAsInt());
        assertTrue(pack.getAsJsonObject("description").get("text").getAsString().startsWith("JTB E2E Pack (+1)"));
        assertTrue(files.containsKey("pack.png"));

        // Vanilla overrides: PNG, TGA, short name redirect, encrypted pack, animation.
        assertEquals(TestPacks.RED, image(files, "assets/minecraft/textures/block/stone.png").getRGB(3, 3));
        assertEquals(TestPacks.GREEN, image(files, "assets/minecraft/textures/block/dirt.png").getRGB(3, 3));
        assertEquals(TestPacks.BLUE, image(files, "assets/minecraft/textures/block/cobblestone.png").getRGB(3, 3));
        assertEquals(TestPacks.GREEN, image(files, "assets/minecraft/textures/block/gravel.png").getRGB(3, 3));
        JsonObject animation = json(files, "assets/minecraft/textures/block/sea_lantern.png.mcmeta").getAsJsonObject("animation");
        assertEquals(5, animation.get("frametime").getAsInt(), "vanilla Bedrock flipbook of sea_lantern");

        // Glyphs.
        assertTrue(files.containsKey("assets/jtb/textures/font/glyph_e1.png"));
        JsonObject provider = json(files, "assets/minecraft/font/default.json").getAsJsonArray("providers").get(0).getAsJsonObject();
        assertEquals("jtb:font/glyph_e1.png", provider.get("file").getAsString());
        assertEquals(8, provider.get("height").getAsInt());
        assertEquals(7, provider.get("ascent").getAsInt());
        assertEquals(16, provider.getAsJsonArray("chars").size());
        assertEquals('', provider.getAsJsonArray("chars").get(0).getAsString().charAt(0));
        assertTrue(converted.artifacts().hasGlyph(0xE121));

        // Sounds: only events with a playable file.
        JsonObject beep = json(files, "assets/jtb/sounds.json").getAsJsonObject("jtb.test.beep");
        JsonObject sound = beep.getAsJsonArray("sounds").get(0).getAsJsonObject();
        assertEquals("jtb:jtb/beep", sound.get("name").getAsString());
        assertEquals(24, sound.get("attenuation_distance").getAsInt());
        assertTrue(files.containsKey("assets/jtb/sounds/jtb/beep.ogg"));
        assertFalse(json(files, "assets/jtb/sounds.json").has("jtb.test.missing"));
        assertEquals("jtb:jtb.test.beep", converted.artifacts().soundEvent("jtb.test.beep"));
        assertEquals(JavaAssetNames.soundEvent("jtb.test.beep"), converted.artifacts().soundEvent("jtb.test.beep"));

        // Translations, merged over both packs, placeholders rewritten for Java.
        JsonObject english = json(files, "assets/jtb/lang/en_us.json");
        assertEquals("Hello %s from the pack", english.get("jtb.test.hello").getAsString());
        assertEquals("%s items, %1$s first", english.get("jtb.test.count").getAsString());
        assertEquals("Encrypted pack works", english.get("jtb.test.secret").getAsString());
        assertEquals("Bonjour %s depuis le pack", json(files, "assets/jtb/lang/fr_fr.json").get("jtb.test.hello").getAsString());
        assertEquals("Hello %s from the pack", converted.artifacts().languages().get("en_US").get("jtb.test.hello"));

        // Custom entity, bones baked (the head is rotated by its bone).
        ConversionArtifacts.EntityModel entity = converted.artifacts().entityModel("jtb:cube");
        assertNotNull(entity);
        assertEquals("default_default", entity.defaultVariant());
        assertEquals("jtb:entity/jtb/cube/default_default", entity.defaultModel().itemModel());
        JsonObject model = json(files, "assets/jtb/models/entity/jtb/cube/default_default.json");
        JsonArray elements = model.getAsJsonArray("elements");
        assertEquals(2, elements.size());
        JsonObject head = elements.get(1).getAsJsonObject().getAsJsonObject("rotation");
        assertEquals("y", head.get("axis").getAsString());
        assertEquals(-45, head.get("angle").getAsDouble(), 1e-6);
        assertTrue(files.containsKey("assets/jtb/items/entity/jtb/cube/default_default.json"));
        assertTrue(files.containsKey("assets/jtb/textures/item/entity/jtb_cube.png"));

        // Artifacts travel inside the zip.
        ConversionArtifacts restored = ConversionArtifacts.fromJson(json(files, BedrockToJavaConverter.ARTIFACTS_PATH));
        assertEquals(converted.artifacts().toJson(), restored.toJson());
    }

    @Test
    void outputIsDeterministic() throws IOException {
        byte[] first = BedrockToJavaConverter.convert(e2eStack(), ServerDefinitions.EMPTY).zip();
        byte[] second = BedrockToJavaConverter.convert(e2eStack(), ServerDefinitions.EMPTY).zip();
        assertArrayEquals(first, second);
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(first))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                names.add(entry.getName());
                assertEquals(LocalDateTime.of(2020, 1, 1, 0, 0), entry.getTimeLocal());
            }
        }
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(null);
        assertEquals(sorted, names);
    }

    @Test
    void secondPhaseDoesNotChangeTheFirst() throws IOException {
        BedrockToJavaConverter.StackConversion stack = BedrockToJavaConverter.convertStack(e2eStack());
        byte[] empty = BedrockToJavaConverter.complete(stack, ServerDefinitions.EMPTY).zip();
        BedrockToJavaConverter.complete(stack, new ServerDefinitions(List.of(ruby()), List.of()));
        assertArrayEquals(empty, BedrockToJavaConverter.complete(stack, ServerDefinitions.EMPTY).zip());
    }

    @Test
    void unreadablePacksAreSkipped() throws IOException {
        List<BedrockPack> packs = new ArrayList<>(e2eStack());
        packs.add(TestPacks.pack(UUID.randomUUID(), new byte[]{1, 2, 3}));
        Map<String, byte[]> files = unzip(BedrockToJavaConverter.convert(packs, ServerDefinitions.EMPTY).zip());
        assertTrue(files.containsKey("assets/minecraft/textures/block/stone.png"));
    }

    @Test
    void vanillaEntityTexturesAreAddedToTheAtlases() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>(TestPacks.e2ePack(TestPacks.fakeVorbis()));
        files.put("entity/jtb_cube.entity.json", new String(files.get("entity/jtb_cube.entity.json"), StandardCharsets.UTF_8)
                .replace("textures/entity/jtb_cube", "textures/entity/cow/cow_v2").getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> out = unzip(BedrockToJavaConverter.convert(List.of(TestPacks.pack(TestPacks.E2E_PACK,
                TestPacks.zip(files))), ServerDefinitions.EMPTY).zip());
        assertEquals("minecraft:entity/cow/cow_temperate", json(out, "assets/jtb/models/entity/jtb/cube/default_default.json")
                .getAsJsonObject("textures").get("0").getAsString());
        for (String atlas : List.of("blocks", "items")) {
            JsonObject source = json(out, "assets/minecraft/atlases/" + atlas + ".json").getAsJsonArray("sources").get(0).getAsJsonObject();
            assertEquals("minecraft:single", source.get("type").getAsString());
            assertEquals("minecraft:entity/cow/cow_temperate", source.get("resource").getAsString());
        }
    }

    // ------------------------------------------------------------------ vanilla textures

    @Test
    void textureTableMapsKnownPaths() {
        ResourceTables tables = ResourceTables.get();
        assertEquals("block/oak_planks", tables.textures().get("blocks/planks_oak").getFirst().path());
        assertEquals("block/grass_block_top", tables.textures().get("blocks/grass_top").getFirst().path());
        assertEquals("item/water_bucket", tables.textures().get("items/bucket_water").getFirst().path());
        assertEquals("entity/equipment/humanoid/diamond", tables.textures().get("models/armor/diamond_1").getFirst().path());
        assertEquals(List.of("blocks/stone"), tables.sources("block/stone").subList(0, 1));
        assertNull(tables.textures().get("blocks/grass_side"), "tint-mask textures are not copied 1:1");
        assertEquals(5, tables.flipbooks().get("blocks/sea_lantern").ticks());
        assertEquals(List.of("ambient/cave/cave1"), tables.javaSoundFiles("cave/cave1"));
    }

    @Test
    void overridesWithAnotherLayoutAreSkipped() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("manifest.json", TestPacks.manifest(TestPacks.E2E_PACK, "Layouts").getBytes(StandardCharsets.UTF_8));
        files.put("textures/blocks/stone.png", TestPacks.png(TestPacks.solid(16, 32, TestPacks.RED)));
        files.put("textures/blocks/planks_oak.png", TestPacks.png(TestPacks.solid(64, 64, TestPacks.RED)));
        files.put("textures/blocks/sea_lantern.png", TestPacks.png(TestPacks.solid(16, 16, TestPacks.RED)));
        Map<String, byte[]> out = unzip(BedrockToJavaConverter.convert(List.of(TestPacks.pack(TestPacks.E2E_PACK,
                TestPacks.zip(files))), ServerDefinitions.EMPTY).zip());
        assertFalse(out.containsKey("assets/minecraft/textures/block/stone.png"), "16x32 is not a 16x16 layout");
        assertTrue(out.containsKey("assets/minecraft/textures/block/oak_planks.png"), "HD textures are kept");
        assertTrue(out.containsKey("assets/minecraft/textures/block/sea_lantern.png"), "single frame of an animation");
        assertFalse(out.containsKey("assets/minecraft/textures/block/sea_lantern.png.mcmeta"));
    }

    @Test
    void animationDropsFramesBeyondTheStrip() {
        ResourceTables.Flipbook flipbook = new ResourceTables.Flipbook(3, new int[]{0, 1, 5, 2}, false);
        JsonObject mcmeta = ConversionContext.animation(flipbook, 16, 48);
        JsonObject animation = mcmeta.getAsJsonObject("animation");
        assertEquals(3, animation.get("frametime").getAsInt());
        assertFalse(animation.get("interpolate").getAsBoolean());
        assertEquals("[0,1,2]", animation.get("frames").toString());
        assertNull(ConversionContext.animation(flipbook, 16, 16));
        assertNull(ConversionContext.animation(flipbook, 16, 20));
    }

    // ------------------------------------------------------------------ lang

    @Test
    void langPlaceholdersAndLocales() {
        assertEquals("%s and %s", LangConverter.toJava("%s and %d"));
        assertEquals("%1$s then %2$s", LangConverter.toJava("%1 then %2$d"));
        assertEquals("100%% sure\nnext", LangConverter.toJava("100% sure~LINEBREAK~next"));
        assertEquals("%% and %%", LangConverter.toJava("%% and %"));
        assertEquals("%s", LangConverter.toJava("%.2f"));
        assertEquals("no_no", LangConverter.javaLocale("nb_NO"));
        assertEquals("fr_ca", LangConverter.javaLocale("fr_CA"));
        assertEquals("en_US", BedrockDefinitions.languageCode("en_us"));
        assertNull(BedrockDefinitions.languageCode("languages"));
    }

    // ------------------------------------------------------------------ sounds

    @Test
    void onlyOggVorbisIsCopied() {
        assertTrue(SoundConverter.isVorbis(TestPacks.fakeVorbis()));
        byte[] opus = TestPacks.fakeVorbis();
        System.arraycopy("OpusHe".getBytes(StandardCharsets.US_ASCII), 0, opus, 28, 6);
        assertFalse(SoundConverter.isVorbis(opus));
        assertFalse(SoundConverter.isVorbis("RIFF....WAVE".getBytes(StandardCharsets.US_ASCII)));
    }

    // ------------------------------------------------------------------ custom items

    private static ServerDefinitions.CustomItem ruby() {
        NbtMap icon = NbtMap.builder().putCompound("textures", NbtMap.builder().putString("default", "jtb_ruby").build()).build();
        NbtMap components = NbtMap.builder().putCompound("components", NbtMap.builder()
                .putCompound("item_properties", NbtMap.builder().putCompound("minecraft:icon", icon)
                        .putBoolean("hand_equipped", true).build()).build()).build();
        return new ServerDefinitions.CustomItem("jtb:ruby", components);
    }

    private static ServerDefinitions.CustomItem helmet() {
        NbtMap icon = NbtMap.builder().putCompound("textures", NbtMap.builder().putString("default", "jtb_ruby").build()).build();
        NbtMap components = NbtMap.builder().putCompound("components", NbtMap.builder()
                .putCompound("item_properties", NbtMap.builder().putCompound("minecraft:icon", icon).build())
                .putCompound("minecraft:wearable", NbtMap.builder().putString("slot", "slot.armor.head").build())
                .build()).build();
        return new ServerDefinitions.CustomItem("jtb:ruby_helmet", components);
    }

    @Test
    void customItemsGetModelsAndEquipment() throws IOException {
        ServerDefinitions.CustomItem apple = new ServerDefinitions.CustomItem("jtb:vanilla_icon", NbtMap.builder()
                .putCompound("components", NbtMap.builder().putCompound("item_properties", NbtMap.builder()
                        .putCompound("minecraft:icon", NbtMap.builder().putString("texture", "apple").build()).build()).build()).build());
        ServerDefinitions.CustomItem missing = new ServerDefinitions.CustomItem("jtb:no_icon", NbtMap.EMPTY);
        BedrockToJavaConverter.ConvertedZip converted = BedrockToJavaConverter.convert(e2eStack(),
                new ServerDefinitions(List.of(ruby(), helmet(), apple, missing), List.of()));
        Map<String, byte[]> files = unzip(converted.zip());

        assertEquals("jtb:item/jtb/ruby", converted.artifacts().itemModel("jtb:ruby"));
        assertEquals(JavaAssetNames.itemModel("jtb:ruby"), converted.artifacts().itemModel("jtb:ruby"));
        JsonObject definition = json(files, "assets/jtb/items/item/jtb/ruby.json");
        assertEquals("jtb:item/jtb/ruby", definition.getAsJsonObject("model").get("model").getAsString());
        JsonObject model = json(files, "assets/jtb/models/item/jtb/ruby.json");
        assertEquals("minecraft:item/handheld", model.get("parent").getAsString());
        assertEquals("jtb:item/items/jtb_ruby", model.getAsJsonObject("textures").get("layer0").getAsString());
        assertTrue(files.containsKey("assets/jtb/textures/item/items/jtb_ruby.png"));

        assertEquals("jtb:jtb/ruby_helmet", converted.artifacts().equipmentAsset("jtb:ruby_helmet"));
        JsonObject equipment = json(files, "assets/jtb/equipment/jtb/ruby_helmet.json");
        assertEquals("jtb:jtb/ruby_helmet", equipment.getAsJsonObject("layers").getAsJsonArray("humanoid").get(0)
                .getAsJsonObject().get("texture").getAsString());
        assertTrue(files.containsKey("assets/jtb/textures/entity/equipment/humanoid/jtb/ruby_helmet.png"));

        assertEquals("minecraft:item/apple", json(files, "assets/jtb/models/item/jtb/vanilla_icon.json")
                .getAsJsonObject("textures").get("layer0").getAsString(), "vanilla short names use vanilla textures");
        assertNull(converted.artifacts().itemModel("jtb:no_icon"));
    }

    // ------------------------------------------------------------------ custom blocks

    private static NbtMap blockDefinition() {
        NbtMap materials = NbtMap.builder().putCompound("materials", NbtMap.builder()
                .putCompound("*", NbtMap.builder().putString("texture", "jtb_ruby_block").putString("render_method", "opaque").build())
                .putCompound("up", NbtMap.builder().putString("texture", "cobblestone").build())
                .build()).build();
        NbtMap lit = NbtMap.builder().putString("condition", "q.block_state('jtb:lit') == 1 && !(q.block_state('jtb:facing') != 'east')")
                .putCompound("components", NbtMap.builder()
                        .putCompound("minecraft:transformation", NbtMap.builder().putInt("RX", 0).putInt("RY", 1).putInt("RZ", 0).build())
                        .putCompound("minecraft:material_instances", NbtMap.builder().putCompound("materials", NbtMap.builder()
                                .putCompound("*", NbtMap.builder().putString("texture", "jtb_ruby_block").build()).build()).build())
                        .build()).build();
        return NbtMap.builder()
                .putCompound("components", NbtMap.builder().putCompound("minecraft:material_instances", materials).build())
                .putList("permutations", NbtType.COMPOUND, new NbtList<>(NbtType.COMPOUND, lit))
                .build();
    }

    @Test
    void customBlocksRewriteTheCarrierBlockstate() throws IOException {
        NbtMap off = NbtMap.builder().putByte("jtb:lit", (byte) 0).putString("jtb:facing", "east").build();
        NbtMap on = NbtMap.builder().putByte("jtb:lit", (byte) 1).putString("jtb:facing", "east").build();
        List<CustomBlockCarrier> carriers = List.of(
                new CustomBlockCarrier("jtb:ruby_block", off, blockDefinition(), "minecraft:note_block",
                        Map.of("instrument", "harp", "note", "1", "powered", "false")),
                new CustomBlockCarrier("jtb:ruby_block", on, blockDefinition(), "minecraft:note_block",
                        Map.of("instrument", "harp", "note", "2", "powered", "false")),
                new CustomBlockCarrier("jtb:other", NbtMap.EMPTY, blockDefinition(), "minecraft:brown_mushroom_block",
                        Map.of("down", "false", "east", "false", "north", "false", "south", "false", "up", "false", "west", "false")));
        BedrockToJavaConverter.ConvertedZip converted = BedrockToJavaConverter.convert(e2eStack(),
                new ServerDefinitions(List.of(), carriers));
        Map<String, byte[]> files = unzip(converted.zip());

        JsonObject variants = json(files, "assets/minecraft/blockstates/note_block.json").getAsJsonObject("variants");
        assertEquals(27 * 25 * 2, variants.size(), "every note block state is listed");
        assertEquals("minecraft:block/note_block", variants.getAsJsonObject("instrument=harp,note=0,powered=false").get("model").getAsString());
        JsonObject first = variants.getAsJsonObject("instrument=harp,note=1,powered=false");
        assertEquals("jtb:block/custom/jtb/ruby_block", first.get("model").getAsString());
        assertFalse(first.has("y"));
        JsonObject second = variants.getAsJsonObject("instrument=harp,note=2,powered=false");
        assertEquals("jtb:block/custom/jtb/ruby_block_1", second.get("model").getAsString(), "the lit permutation looks different");
        assertEquals(270, second.get("y").getAsInt());

        JsonObject cube = json(files, "assets/jtb/models/block/custom/jtb/ruby_block.json");
        assertEquals("minecraft:block/cube", cube.get("parent").getAsString());
        assertEquals("jtb:block/blocks/stone", cube.getAsJsonObject("textures").get("north").getAsString(),
                "the server pack ships textures/blocks/stone");
        assertEquals("jtb:block/blocks/jtb_cobble", cube.getAsJsonObject("textures").get("up").getAsString());
        assertTrue(files.containsKey("assets/jtb/textures/block/blocks/jtb_cobble.png"));
        assertEquals("jtb:item/jtb/ruby_block", converted.artifacts().itemModel("jtb:ruby_block"));
        assertTrue(converted.artifacts().customBlocks().contains("jtb:ruby_block"));

        JsonArray multipart = json(files, "assets/minecraft/blockstates/brown_mushroom_block.json").getAsJsonArray("multipart");
        JsonObject last = multipart.get(multipart.size() - 1).getAsJsonObject();
        assertEquals("jtb:block/custom/jtb/other", last.getAsJsonObject("apply").get("model").getAsString());
        JsonObject northPart = multipart.get(0).getAsJsonObject();
        assertTrue(northPart.getAsJsonObject("when").has("OR") || northPart.getAsJsonObject("when").has("north"));
    }

    @Test
    void molangConditions() {
        NbtMap states = NbtMap.builder().putByte("a:lit", (byte) 1).putString("a:dir", "north").putInt("a:n", 3).build();
        assertTrue(MolangConditions.test("q.block_state('a:lit') == 1", states));
        assertTrue(MolangConditions.test("query.block_state('a:lit') == true", states));
        assertTrue(MolangConditions.test("q.block_state('a:dir') == 'north' && q.block_state('a:n') >= 3", states));
        assertFalse(MolangConditions.test("q.block_state('a:dir') == 'south' || q.block_state('a:n') < 2", states));
        assertTrue(MolangConditions.test("!(q.block_state('a:n') != 3)", states));
        assertTrue(MolangConditions.test("q.block_property('a:n') == 3", states));
        assertFalse(MolangConditions.test("q.unknown_query == 1", states));
        assertFalse(MolangConditions.test("q.block_state('a:missing') == 1", states));
        assertTrue(MolangConditions.test("", states));
    }
}
