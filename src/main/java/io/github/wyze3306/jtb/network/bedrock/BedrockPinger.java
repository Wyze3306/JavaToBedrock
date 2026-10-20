package io.github.wyze3306.jtb.network.bedrock;

import io.github.wyze3306.jtb.JavaToBedrock;
import io.github.wyze3306.jtb.config.ProxyConfig;
import io.github.wyze3306.jtb.network.java.JavaServer;
import io.github.wyze3306.jtb.text.BedrockText;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.RakPing;
import org.cloudburstmc.netty.channel.raknet.RakPong;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Pings the Bedrock server every few seconds (RakNet unconnected ping) and keeps the latest answer,
 * so the Java server list mirrors its MOTD, player count and version without connecting.
 */
public final class BedrockPinger implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(BedrockPinger.class);
    private static final long INTERVAL_SECONDS = 5;
    /** A pong older than this means the server is down. */
    private static final long STALE_MILLIS = TimeUnit.SECONDS.toMillis(20);

    /**
     * The last Bedrock pong.
     */
    public record Status(String motd, String subMotd, int protocolVersion, String version, int online, int max,
                         long receivedAt) {
    }

    private final JavaToBedrock proxy;
    private volatile @Nullable Status status;
    private @Nullable Channel channel;
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
        Bootstrap bootstrap = new Bootstrap()
                .group(this.proxy.bedrockConnector().group())
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        try {
                            if (msg instanceof RakPong pong) {
                                onPong(BedrockPong.fromRakNet(pong.getPongData()));
                            }
                        } catch (Exception e) {
                            LOGGER.debug("Invalid Bedrock pong", e);
                        } finally {
                            ReferenceCountUtil.release(msg);
                        }
                    }
                });
        this.channel = bootstrap.bind(0).sync().channel();
        this.task = this.channel.eventLoop().scheduleAtFixedRate(this::ping, 0, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void ping() {
        Channel channel = this.channel;
        if (channel == null || !channel.isActive()) {
            return;
        }
        ProxyConfig.Bedrock config = this.proxy.config().bedrock();
        InetSocketAddress address = new InetSocketAddress(config.address(), config.port());
        if (address.isUnresolved()) {
            return;
        }
        channel.writeAndFlush(new RakPing(System.currentTimeMillis(), address));
    }

    private void onPong(BedrockPong pong) {
        Status previous = this.status;
        Status status = new Status(nullToEmpty(pong.motd()), nullToEmpty(pong.subMotd()), pong.protocolVersion(),
                nullToEmpty(pong.version()), Math.max(0, pong.playerCount()), Math.max(0, pong.maximumPlayerCount()),
                System.currentTimeMillis());
        this.status = status;
        if (previous == null) {
            LOGGER.info("Bedrock server online: \"{}\" ({} protocol {}, {}/{} players)",
                    BedrockText.stripFormatting(status.motd()), status.version(), status.protocolVersion(),
                    status.online(), status.max());
        }
    }

    /** The last answer, or null when the server never answered or stopped answering. */
    public @Nullable Status status() {
        Status status = this.status;
        if (status == null || System.currentTimeMillis() - status.receivedAt() > STALE_MILLIS) {
            return null;
        }
        return status;
    }

    /**
     * Builds the Java server list entry from the configuration and the last Bedrock pong.
     */
    public ServerStatusInfo buildJavaStatus() {
        ProxyConfig.Java config = this.proxy.config().java();
        Status status = status();
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
            max = 0;
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
        if (this.channel != null) {
            this.channel.close();
        }
    }
}
