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
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A scriptable, display-less Java 26.3 client used to test the proxy end to end against a real
 * Bedrock server: it logs in (offline), answers the configuration phase like a vanilla client
 * (known packs, downloads and verifies the resource pack), joins, accepts teleports, walks a bit,
 * chats and runs a command, then prints a report of everything it received.
 * <p>
 * Usage: {@code gradle runHeadlessClient -Phost=127.0.0.1 -Pport=25565 -Pname=Tester -Pseconds=30}
 */
public final class HeadlessJavaClient {

    private final Map<String, AtomicInteger> received = new ConcurrentHashMap<>();
    private final AtomicReference<String> disconnectReason = new AtomicReference<>();
    private final CountDownLatch joined = new CountDownLatch(1);
    private final AtomicReference<double[]> position = new AtomicReference<>();
    private final List<String> chat = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean packVerified;

    public static void main(String[] args) throws Exception {
        String host = System.getProperty("host", "127.0.0.1");
        int port = Integer.getInteger("port", 25565);
        String name = System.getProperty("name", "JtbTester");
        int seconds = Integer.getInteger("seconds", 30);
        int code = new HeadlessJavaClient().run(host, port, name, seconds);
        System.exit(code);
    }

    int run(String host, int port, String name, int seconds) throws Exception {
        MinecraftProtocol protocol = new MinecraftProtocol(name);
        ClientSession session = ClientNetworkSessionFactory.factory()
                .setRemoteSocketAddress(new InetSocketAddress(host, port))
                .setProtocol(protocol)
                .create();
        session.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session s, Packet packet) {
                onPacket(s, packet);
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                disconnectReason.set(PlainTextComponentSerializer.plainText().serialize(event.getReason())
                        + (event.getCause() != null ? " (" + event.getCause() + ")" : ""));
                joined.countDown();
            }
        });
        log("Connecting to " + host + ":" + port + " as " + name);
        session.connect(true);

        boolean ok = this.joined.await(90, TimeUnit.SECONDS);
        if (!ok || this.disconnectReason.get() != null) {
            log("JOIN FAILED: " + (ok ? this.disconnectReason.get() : "timeout"));
            report();
            return 1;
        }
        log("Joined. Playing for " + seconds + "s");
        long end = System.currentTimeMillis() + seconds * 1000L;
        int step = 0;
        while (System.currentTimeMillis() < end && session.isConnected()) {
            double[] pos = this.position.get();
            if (pos != null && step < 40) {
                // Walk along +X a little, like a player holding W.
                pos[0] += 0.2;
                session.send(new ServerboundMovePlayerPosRotPacket(true, false, pos[0], pos[1], pos[2], (float) pos[3], (float) pos[4]));
            }
            if (step == 20) {
                session.send(new ServerboundChatPacket("Bonjour depuis Java !", Instant.now().toEpochMilli(), 0L, null, 0, new BitSet(), 0));
            }
            if (step == 40) {
                session.send(new ServerboundChatCommandPacket("list"));
            }
            step++;
            Thread.sleep(50);
        }
        session.disconnect(Component.text("Test finished"));
        report();
        return this.disconnectReason.get() == null || this.disconnectReason.get().startsWith("Test finished") ? 0 : 1;
    }

    private void onPacket(Session session, Packet packet) {
        this.received.computeIfAbsent(packet.getClass().getSimpleName(), k -> new AtomicInteger()).incrementAndGet();
        if (packet instanceof ClientboundSelectKnownPacks) {
            session.send(new ServerboundSelectKnownPacks(List.of(new KnownPack("minecraft", "core",
                    session.getPacketProtocol() instanceof MinecraftProtocol p ? p.getCodec().getMinecraftVersion() : "26.3"))));
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
            double[] pos = new double[]{teleport.getPosition().getX(), teleport.getPosition().getY(), teleport.getPosition().getZ(),
                    teleport.getYRot(), teleport.getXRot()};
            this.position.set(pos);
            session.send(new ServerboundAcceptTeleportationPacket(teleport.getId(), pos[0], pos[1], pos[2], (float) pos[3], (float) pos[4]));
            log("Teleport to " + teleport.getPosition());
            this.joined.countDown();
        } else if (packet instanceof ClientboundSystemChatPacket chatPacket) {
            String text = PlainTextComponentSerializer.plainText().serialize(chatPacket.getContent());
            this.chat.add((chatPacket.isOverlay() ? "[actionbar] " : "") + text);
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
        new TreeMap<>(this.received).forEach((type, count) -> log(String.format("  %5d  %s", count.get(), type)));
        log("==== Chat ====");
        this.chat.forEach(line -> log("  " + line));
        log("Resource pack verified: " + this.packVerified);
        log("Disconnect: " + this.disconnectReason.get());
    }

    private static void log(String message) {
        System.out.println("[client] " + message);
    }
}
