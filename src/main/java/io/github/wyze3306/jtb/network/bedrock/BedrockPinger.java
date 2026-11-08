package io.github.wyze3306.jtb.network.bedrock;

import io.github.wyze3306.jtb.JavaToBedrock;
import io.github.wyze3306.jtb.config.ProxyConfig;
import io.github.wyze3306.jtb.network.java.JavaServer;
import io.github.wyze3306.jtb.text.BedrockText;
import io.netty.util.concurrent.ScheduledFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.cloudburstmc.netty.channel.nethernet.signaling.HttpSignalingSettings;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPClientSignaling;
import org.cloudburstmc.netty.channel.nethernet.signaling.PongData;
import org.geysermc.mcprotocollib.protocol.data.status.PlayerInfo;
import org.geysermc.mcprotocollib.protocol.data.status.ServerStatusInfo;
import org.geysermc.mcprotocollib.protocol.data.status.VersionInfo;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Pings every Bedrock server behind the proxy every few seconds (NetherNet HTTP signalling probe, which
 * answers with the server's pong data) and keeps
 * the latest answers, so the Java server list mirrors the MOTD, player count and version of the
 * server the player would join, without connecting.
 */
public final class BedrockPinger implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(BedrockPinger.class);
    private static final long INTERVAL_SECONDS = 5;
    /** A pong older than this means the server is down. */
    private static final long STALE_MILLIS = TimeUnit.SECONDS.toMillis(20);
    /** Server addresses are resolved again every this many pings (30 s), on a worker thread. */
    private static final int DNS_REFRESH_PINGS = 6;

    /**
     * The last Bedrock pong.
     */
    public record Status(String motd, String subMotd, int protocolVersion, String version, int online, int max,
                         long receivedAt) {
    }

    private final JavaToBedrock proxy;
    private final Map<InetSocketAddress, Status> statuses = new ConcurrentHashMap<>();
    /** Addresses pinged last, the only ones whose pongs are kept. */
    private volatile Set<InetSocketAddress> expected = Set.of();
    private long pings;
    private volatile boolean closed;
    private @Nullable ScheduledFuture<?> task;
    private byte @Nullable [] icon;

    public BedrockPinger(JavaToBedrock proxy) {
        this.proxy = proxy;
    }

    public void start() throws InterruptedException {
        Path iconFile = this.proxy.dataDirectory().resolve("server-icon.png");
        if (Files.isRegularFile(iconFile)) {
            try {
                this.icon = Files.readAllBytes(iconFile);
            } catch (IOException e) {
                LOGGER.warn("Could not read {}", iconFile, e);
            }
        }
        this.task = this.proxy.bedrockConnector().group().next()
                .scheduleAtFixedRate(this::ping, 0, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void ping() {
        if (this.closed) {
            return;
        }
        BedrockServers servers = this.proxy.bedrockConnector().servers();
        if (++this.pings % DNS_REFRESH_PINGS == 0) {
            this.proxy.workers().execute(servers::refresh);
        }
        Set<InetSocketAddress> expected = new HashSet<>();
        for (BedrockServers.Target target : servers.all()) {
            InetSocketAddress address = servers.address(target);
            if (!address.isUnresolved()) {
                expected.add(address);
                // The probe runs on the JDK HTTP client's threads; the answer is a plain record.
                NetherNetHTTPClientSignaling.probe(address, HttpSignalingSettings.DEFAULT).whenComplete((probe, error) -> {
                    if (error != null) {
                        LOGGER.debug("Bedrock server {} did not answer the probe: {}", address, error.toString());
                    } else if (probe != null && probe.motd() != null) {
                        onPong(address, probe.motd());
                    }
                });
            }
        }
        this.expected = expected;
        this.statuses.keySet().retainAll(expected);
    }

    private void onPong(InetSocketAddress server, PongData pong) {
        if (!this.expected.contains(server)) {
            // Only the servers we ping may fill the table (and the log).
            return;
        }
        Status status = new Status(nullToEmpty(pong.serverName()), nullToEmpty(pong.levelName()), pong.protocol(),
                nullToEmpty(pong.version()), Math.max(0, pong.playerCount()), Math.max(0, pong.maxPlayerCount()),
                System.currentTimeMillis());
        Status previous = this.statuses.put(server, status);
        if (previous == null || status.receivedAt() - previous.receivedAt() > STALE_MILLIS) {
            LOGGER.info("Bedrock server {}:{} online: \"{}\" ({} protocol {}, {}/{} players)",
                    server.getHostString(), server.getPort(), BedrockText.stripFormatting(status.motd()),
                    status.version(), status.protocolVersion(), status.online(), status.max());
        }
    }

    /** The last answer of the default server, or null when it never answered or stopped answering. */
    public @Nullable Status status() {
        BedrockServers servers = this.proxy.bedrockConnector().servers();
        return status(servers.address(servers.defaultTarget()));
    }

    /** The last answer of {@code server}, or null when it never answered or stopped answering. */
    public @Nullable Status status(InetSocketAddress server) {
        Status status = this.statuses.get(server);
        if (status == null || System.currentTimeMillis() - status.receivedAt() > STALE_MILLIS) {
            return null;
        }
        return status;
    }

    /**
     * Builds the Java server list entry from the configuration and the last pong of the server a
     * player typing {@code host} would join.
     */
    public ServerStatusInfo buildJavaStatus(@Nullable String host) {
        ProxyConfig.Java config = this.proxy.config().java();
        BedrockServers servers = this.proxy.bedrockConnector().servers();
        Status status = status(servers.address(servers.forJavaHost(host)));
        Component description;
        if (!config.motd().isEmpty()) {
            description = BedrockText.legacyToComponent(config.motd().replace("\\n", "\n"));
        } else if (status != null) {
            description = BedrockText.legacyToComponent(status.motd())
                    .append(Component.newline())
                    .append(BedrockText.legacyToComponent(status.subMotd()).colorIfAbsent(NamedTextColor.GRAY));
        } else {
            description = Component.text("Serveur Bedrock hors ligne", NamedTextColor.RED);
        }
        int online = this.proxy.sessions().size();
        int max = config.maxPlayers();
        if (status != null) {
            online = status.online();
            if (max < 0) {
                max = status.max();
            }
        }
        if (max < 0) {
            max = online;
        }
        return new ServerStatusInfo(description, new PlayerInfo(max, online, List.of()),
                new VersionInfo(JavaServer.javaVersionName(), JavaServer.javaProtocolVersion()), this.icon, false);
    }

    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    @Override
    public void close() {
        if (this.task != null) {
            this.task.cancel(false);
        }
        this.closed = true;
    }
}
