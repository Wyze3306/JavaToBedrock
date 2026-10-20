package io.github.wyze3306.jtb.e2e;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.KnownPack;
import org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundSelectKnownPacks;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundSelectKnownPacks;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A scriptable, display-less Java 26.3 client used to test the proxy end to end against a real
 * Bedrock server. It logs in (offline), answers the configuration phase like a vanilla client
 * (known packs, downloads and verifies the resource pack), joins, accepts teleports and runs an
 * {@link E2eScenario}, then prints a report of everything it received.
 * <p>
 * {@code -Dhost -Dport -Dname -Dseconds -Dscenario=default}
 */
public final class HeadlessJavaClient {

    private final AtomicReference<String> disconnectReason = new AtomicReference<>();
    private final CountDownLatch joined = new CountDownLatch(1);
    private volatile boolean packVerified;
    private ClientContext context;
    private E2eScenario scenario;

    public static void main(String[] args) throws Exception {
        String host = System.getProperty("host", "127.0.0.1");
        int port = Integer.getInteger("port", 25565);
        String name = System.getProperty("name", "JtbTester");
        int seconds = Integer.getInteger("seconds", 30);
        String scenario = System.getProperty("scenario", "default");
        System.exit(new HeadlessJavaClient().run(host, port, name, seconds, scenario));
    }

    static E2eScenario loadScenario(String name) throws ReflectiveOperationException {
        StringBuilder className = new StringBuilder(HeadlessJavaClient.class.getPackageName()).append(".scenario.");
        for (String part : name.split("[_-]")) {
            if (!part.isEmpty()) {
                className.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
            }
        }
        className.append("Scenario");
        return (E2eScenario) Class.forName(className.toString()).getDeclaredConstructor().newInstance();
    }

    int run(String host, int port, String name, int seconds, String scenarioName) throws Exception {
        this.scenario = loadScenario(scenarioName);
        MinecraftProtocol protocol = new MinecraftProtocol(name);
        ClientSession session = ClientNetworkSessionFactory.factory()
                .setRemoteSocketAddress(new InetSocketAddress(host, port))
                .setProtocol(protocol)
                .create();
        this.context = new ClientContext(session);
        session.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session s, Packet packet) {
                try {
                    onPacket(s, packet);
                    HeadlessJavaClient.this.scenario.onPacket(HeadlessJavaClient.this.context, packet);
                } catch (Throwable t) {
                    log("Error handling " + packet.getClass().getSimpleName() + ": " + t);
                    t.printStackTrace(System.out);
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                disconnectReason.set(PlainTextComponentSerializer.plainText().serialize(event.getReason())
                        + (event.getCause() != null ? " (" + event.getCause() + ")" : ""));
                joined.countDown();
            }
        });
        log("Connecting to " + host + ":" + port + " as " + name + ", scenario " + scenarioName);
        session.connect(true);

        boolean ok = this.joined.await(120, TimeUnit.SECONDS);
        if (!ok || this.disconnectReason.get() != null) {
            log("JOIN FAILED: " + (ok ? this.disconnectReason.get() : "timeout"));
            report();
            return 1;
        }
        log("Joined. Playing for " + seconds + "s");
        this.scenario.onJoined(this.context);
        long end = System.currentTimeMillis() + seconds * 1000L;
        int tick = 0;
        while (System.currentTimeMillis() < end && session.isConnected()) {
            this.scenario.tick(this.context, tick++);
            Thread.sleep(50);
        }
        this.context.check("still connected at the end", session.isConnected());
        this.scenario.finish(this.context);
        session.disconnect(Component.text("Test finished"));
        report();
        return this.context.failures.isEmpty() ? 0 : 1;
    }

    private void onPacket(Session session, Packet packet) {
        this.context.counts.computeIfAbsent(packet.getClass().getSimpleName(), k -> new AtomicInteger()).incrementAndGet();
        this.context.last.put(packet.getClass(), packet);
        if (packet instanceof ClientboundSelectKnownPacks) {
            String version = session.getPacketProtocol() instanceof MinecraftProtocol p ? p.getCodec().getMinecraftVersion() : "26.3";
            session.send(new ServerboundSelectKnownPacks(List.of(new KnownPack("minecraft", "core", version))));
        } else if (packet instanceof ClientboundResourcePackPushPacket push) {
            session.send(new ServerboundResourcePackPacket(push.getId(), ResourcePackStatus.ACCEPTED));
            Thread.ofVirtual().start(() -> {
                ResourcePackStatus status = verifyPack(push.getUrl(), push.getHash());
                session.send(new ServerboundResourcePackPacket(push.getId(), ResourcePackStatus.DOWNLOADED));
                session.send(new ServerboundResourcePackPacket(push.getId(), status));
            });
        } else if (packet instanceof ClientboundLoginPacket login) {
            log("Login: entity " + login.getEntityId() + ", dimension " + login.getCommonPlayerSpawnInfo().getWorldName()
                    + ", gamemode " + login.getCommonPlayerSpawnInfo().getGameMode());
        } else if (packet instanceof ClientboundPlayerPositionPacket teleport) {
            double x = teleport.getPosition().getX();
            double y = teleport.getPosition().getY();
            double z = teleport.getPosition().getZ();
            this.context.setPosition(x, y, z);
            this.context.yaw = teleport.getYRot();
            this.context.pitch = teleport.getXRot();
            session.send(new ServerboundAcceptTeleportationPacket(teleport.getId(), x, y, z, teleport.getYRot(), teleport.getXRot()));
            log("Teleport to " + teleport.getPosition() + " (id " + teleport.getId() + ")");
            this.context.positioned = true;
            this.joined.countDown();
        } else if (packet instanceof ClientboundSystemChatPacket chatPacket) {
            String text = PlainTextComponentSerializer.plainText().serialize(chatPacket.getContent());
            this.context.chat.add((chatPacket.isOverlay() ? "[actionbar] " : "") + text);
        }
    }

    private ResourcePackStatus verifyPack(String url, String sha1) {
        try {
            HttpResponse<byte[]> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    .send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(response.body()));
            this.packVerified = response.statusCode() == 200 && actual.equalsIgnoreCase(sha1);
            log("Resource pack " + url + " -> HTTP " + response.statusCode() + ", " + response.body().length
                    + " bytes, sha1 " + (this.packVerified ? "OK" : "MISMATCH"));
            return this.packVerified ? ResourcePackStatus.SUCCESSFULLY_LOADED : ResourcePackStatus.FAILED_DOWNLOAD;
        } catch (Exception e) {
            log("Resource pack download failed: " + e);
            return ResourcePackStatus.FAILED_DOWNLOAD;
        }
    }

    private void report() {
        log("==== Packets received ====");
        new TreeMap<>(this.context.counts).forEach((type, count) -> log(String.format("  %5d  %s", count.get(), type)));
        log("==== Chat ====");
        this.context.chat.forEach(line -> log("  " + line));
        log("Resource pack verified: " + this.packVerified);
        log("Checks passed: " + this.context.checks.size() + ", failed: " + this.context.failures.size() + " " + this.context.failures);
        log("Disconnect: " + this.disconnectReason.get());
    }

    private static void log(String message) {
        System.out.println("[client] " + message);
    }
}
