package io.github.wyze3306.jtb.network.bedrock;

import io.github.wyze3306.jtb.JavaToBedrock;
import io.github.wyze3306.jtb.auth.LoginData;
import io.github.wyze3306.jtb.skin.BedrockSkinData;
import io.github.wyze3306.jtb.skin.JavaSkinProvider;
import io.github.wyze3306.jtb.config.ProxyConfig;
import io.github.wyze3306.jtb.session.ProxySession;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2192.Bedrock_v2192;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Opens the Bedrock (RakNet) connection of each session and starts the Bedrock login.
 */
public final class BedrockConnector implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(BedrockConnector.class);

    /**
     * PowerNukkitX 1.26.50 speaks protocol 2193 with the v2192 codec; matching it exactly is what
     * lets a vanilla client in.
     */
    private static final BedrockCodec DEFAULT_CODEC = Bedrock_v2192.CODEC.toBuilder().protocolVersion(2193).build();

    private final JavaToBedrock proxy;
    private final EventLoopGroup group;

    public BedrockConnector(JavaToBedrock proxy) {
        this.proxy = proxy;
        this.group = new MultiThreadIoEventLoopGroup(0, new DefaultThreadFactory("jtb-bedrock", true), NioIoHandler.newFactory());
    }

    public static BedrockCodec defaultCodec() {
        return DEFAULT_CODEC;
    }

    public EventLoopGroup group() {
        return this.group;
    }

    /**
     * The codec to use for the configured server: the default one, adjusted to the protocol number
     * the server advertises in its ping when it differs only by that number.
     */
    public BedrockCodec codecForServer() {
        BedrockPinger.Status status = this.proxy.pinger().status();
        if (status != null && status.protocolVersion() > 0 && status.protocolVersion() != DEFAULT_CODEC.getProtocolVersion()) {
            BedrockCodec known = BedrockCodecs.byProtocol(status.protocolVersion());
            if (known != null) {
                return known;
            }
        }
        return DEFAULT_CODEC;
    }

    /**
     * Authenticates the player (configured {@link io.github.wyze3306.jtb.auth.AuthProvider}) and
     * fetches their skin in parallel, then connects. Authentication happens before the Bedrock
     * connection exists so a slow sign-in (Microsoft device code) cannot time the connection out.
     */
    public void start(ProxySession session) {
        CompletableFuture<LoginData> identity = this.proxy.authProvider().login(session);
        CompletableFuture<BedrockSkinData> skin = JavaSkinProvider.forProfile(session.getProfile(),
                this.proxy.config().features().javaSkins());
        identity.thenCombine(skin, (loginData, skinData) -> {
            session.execute(() -> connect(session, loginData, skinData));
            return null;
        }).exceptionally(error -> {
            LOGGER.warn("[{}] Bedrock authentication failed", session.getName(), error);
            session.disconnect(Component.text("Authentification Bedrock impossible : ", NamedTextColor.RED)
                    .append(Component.text(String.valueOf(rootMessage(error)), NamedTextColor.GRAY)));
            return null;
        });
    }

    private static Object rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    /**
     * Connects the session to the configured Bedrock server and runs the login with {@code login}.
     * Kicks the Java player with an explanation when the server cannot be reached.
     */
    public void connect(ProxySession session, LoginData login, BedrockSkinData skin) {
        if (session.isClosed()) {
            return;
        }
        session.setXuid(login.xuid());
        ProxyConfig.Bedrock config = this.proxy.config().bedrock();
        InetSocketAddress address = new InetSocketAddress(config.address(), config.port());
        BedrockCodec codec = codecForServer();

        Bootstrap bootstrap = new Bootstrap()
                .group(this.group)
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, codec.getRaknetProtocolVersion())
                .option(RakChannelOption.RAK_GUID, ThreadLocalRandom.current().nextLong())
                .option(RakChannelOption.RAK_CONNECT_TIMEOUT, TimeUnit.SECONDS.toMillis(config.connectTimeoutSeconds()))
                .option(RakChannelOption.RAK_SESSION_TIMEOUT, TimeUnit.SECONDS.toMillis(30))
                .handler(new BedrockClientInitializer() {
                    @Override
                    protected void initSession(BedrockClientSession bedrock) {
                        bedrock.setCodec(codec);
                        CodecRegistries.install(bedrock);
                        session.setBedrockSession(bedrock);
                        bedrock.setPacketHandler(new BedrockPacketDispatcher(session, bedrock,
                                new BedrockLoginHandler(session, bedrock, codec, login, skin)));
                        RequestNetworkSettingsPacket request = new RequestNetworkSettingsPacket();
                        request.setClientNetworkVersion(codec.getProtocolVersion());
                        bedrock.sendPacketImmediately(request);
                    }
                });

        LOGGER.info("[{}] Connecting to Bedrock server {}:{} (protocol {})", session.getName(),
                config.address(), config.port(), codec.getProtocolVersion());
        ChannelFuture future = bootstrap.connect(address);
        future.addListener(result -> {
            if (!result.isSuccess()) {
                LOGGER.warn("[{}] Could not reach the Bedrock server {}:{}: {}", session.getName(),
                        config.address(), config.port(), result.cause() == null ? "?" : result.cause().toString());
                session.disconnect(Component.text("Impossible de joindre le serveur Bedrock ", NamedTextColor.RED)
                        .append(Component.text(config.address() + ":" + config.port(), NamedTextColor.GRAY))
                        .append(Component.newline())
                        .append(Component.text(String.valueOf(result.cause() == null ? "" : result.cause().getMessage()), NamedTextColor.DARK_GRAY)));
            }
        });
    }

    @Override
    public void close() {
        this.group.shutdownGracefully(0, 2, TimeUnit.SECONDS);
    }
}
