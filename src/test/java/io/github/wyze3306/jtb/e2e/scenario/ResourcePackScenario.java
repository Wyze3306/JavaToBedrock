package io.github.wyze3306.jtb.e2e.scenario;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.wyze3306.jtb.e2e.ClientContext;
import io.github.wyze3306.jtb.e2e.E2eScenario;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Checks the converted resource pack end to end: the Bedrock server's packs are downloaded,
 * decrypted and converted, the Java client gets a pack push during configuration, downloads it with
 * a matching SHA-1 and the zip holds the expected converted files.
 * <p>
 * Server set-up (PowerNukkitX instance {@code N}): the packs written by
 * {@code io.github.wyze3306.jtb.resourcepack.TestPacks <pnx>/resource_packs [<a real .ogg>]} (a plain
 * pack with texture overrides, a TGA, an animation, a short-name redirect, a glyph sheet, a sound,
 * translations, a custom entity and an armour attachable, plus an encrypted pack with its
 * {@code .key}), and the test plugin of {@code src/test/resources/e2e/pnx-plugin} (custom items
 * {@code jtb:ruby} and {@code jtb:ruby_helmet}) built into {@code <pnx>/plugins}:
 * <pre>
 * javac -d out -cp powernukkitx.jar src/test/resources/e2e/pnx-plugin/jtb/e2e/JtbE2eItems.java
 * cp src/test/resources/e2e/pnx-plugin/plugin.yml out/ &amp;&amp; jar cf plugins/JtbE2eItems.jar -C out .
 * </pre>
 */
public final class ResourcePackScenario implements E2eScenario {

    private final CountDownLatch inspected = new CountDownLatch(1);
    private volatile boolean pushed;
    private volatile boolean pushedBeforeLogin;
    private volatile long pushedAt;

    @Override
    public void onPacket(ClientContext context, Packet packet) {
        if (packet instanceof ClientboundResourcePackPushPacket push && !this.pushed) {
            this.pushed = true;
            this.pushedAt = System.currentTimeMillis();
            this.pushedBeforeLogin = context.count("ClientboundLoginPacket") == 0;
            Thread.ofVirtual().start(() -> {
                try {
                    inspect(context, push);
                } catch (Exception e) {
                    context.check("converted pack readable (" + e + ")", false);
                } finally {
                    this.inspected.countDown();
                }
            });
        }
    }

    private void inspect(ClientContext context, ClientboundResourcePackPushPacket push) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<Void> head = http.send(HttpRequest.newBuilder(URI.create(push.getUrl()))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(push.getUrl())).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        byte[] zip = response.body();
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(zip));
        context.log("Pack " + push.getUrl() + ": HTTP " + response.statusCode() + ", " + zip.length + " bytes, sha1 " + sha1
                + ", required " + push.isRequired());
        context.check("pack served over HTTP", response.statusCode() == 200
                && "application/zip".equals(response.headers().firstValue("Content-Type").orElse("")));
        context.check("pack SHA-1 matches the push", sha1.equalsIgnoreCase(push.getHash()));
        context.check("HEAD answers with the pack length", head.statusCode() == 200
                && String.valueOf(zip.length).equals(head.headers().firstValue("Content-Length").orElse("")));

        Map<String, byte[]> files = unzip(zip);
        context.log("Pack holds " + files.size() + " files");
        JsonObject mcmeta = json(files, "pack.mcmeta");
        context.check("pack.mcmeta targets format 97", mcmeta != null
                && mcmeta.getAsJsonObject("pack").get("min_format").getAsInt() == 97
                && mcmeta.getAsJsonObject("pack").get("max_format").getAsInt() == 97);
        context.check("pack icon", files.containsKey("pack.png"));

        String blocks = "assets/minecraft/textures/block/";
        context.check("vanilla texture override (stone, red)", colour(files, blocks + "stone.png") == 0xFFE01010);
        context.check("TGA converted to PNG (dirt, green)", colour(files, blocks + "dirt.png") == 0xFF10C020);
        context.check("short-name redirect (cobblestone, blue)", colour(files, blocks + "cobblestone.png") == 0xFF1030E0);
        context.check("encrypted pack decrypted (gravel, green)", colour(files, blocks + "gravel.png") == 0xFF10C020);
        JsonObject lantern = json(files, "assets/minecraft/textures/block/sea_lantern.png.mcmeta");
        context.check("flipbook animation (sea lantern)", lantern != null
                && lantern.getAsJsonObject("animation").get("frametime").getAsInt() == 5);

        JsonObject font = json(files, "assets/minecraft/font/default.json");
        context.check("glyph sheet E1 in the default font", font != null
                && files.containsKey("assets/jtb/textures/font/glyph_e1.png")
                && font.toString().contains("jtb:font/glyph_e1.png"));

        JsonObject sounds = json(files, "assets/jtb/sounds.json");
        context.check("custom sound event jtb:jtb.test.beep", sounds != null && sounds.has("jtb.test.beep")
                && files.containsKey("assets/jtb/sounds/jtb/beep.ogg"));

        JsonObject english = json(files, "assets/jtb/lang/en_us.json");
        JsonObject french = json(files, "assets/jtb/lang/fr_fr.json");
        context.check("translations of both packs", english != null && english.has("jtb.test.hello")
                && english.has("jtb.test.secret") && french != null && french.has("jtb.test.hello"));

        context.check("custom entity model jtb:cube", files.containsKey("assets/jtb/models/entity/jtb/cube/default_default.json")
                && files.containsKey("assets/jtb/items/entity/jtb/cube/default_default.json"));

        JsonObject ruby = json(files, "assets/jtb/items/item/jtb/ruby.json");
        context.check("custom item model jtb:item/jtb/ruby (from the item registry)", ruby != null
                && files.containsKey("assets/jtb/models/item/jtb/ruby.json")
                && files.containsKey("assets/jtb/textures/item/items/jtb_ruby.png"));
        context.check("custom armour equipment jtb:jtb/ruby_helmet",
                files.containsKey("assets/jtb/equipment/jtb/ruby_helmet.json")
                && files.containsKey("assets/jtb/textures/entity/equipment/humanoid/jtb/ruby_helmet.png"));

        JsonObject artifacts = json(files, "jtb/artifacts.json");
        context.check("artifacts stored in the pack", artifacts != null
                && artifacts.getAsJsonObject("item_models").has("jtb:ruby")
                && artifacts.getAsJsonObject("sound_events").has("jtb.test.beep")
                && artifacts.getAsJsonObject("entity_models").has("jtb:cube"));
    }

    @Override
    public void finish(ClientContext context) {
        try {
            this.inspected.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        context.check("resource pack pushed", this.pushed);
        context.check("pushed during the configuration phase (before login)", this.pushedBeforeLogin);
        context.log("Pack pushed at " + this.pushedAt);
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), in.readAllBytes());
            }
        }
        return files;
    }

    private static JsonObject json(Map<String, byte[]> files, String path) {
        byte[] data = files.get(path);
        return data == null ? null : JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static int colour(Map<String, byte[]> files, String path) {
        try {
            byte[] data = files.get(path);
            BufferedImage image = data == null ? null : ImageIO.read(new ByteArrayInputStream(data));
            return image == null ? 0 : image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
        } catch (IOException e) {
            return 0;
        }
    }
}
