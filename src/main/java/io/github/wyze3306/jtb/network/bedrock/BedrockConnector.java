package io.github.wyze3306.jtb.network.bedrock;

import io.github.wyze3306.jtb.JavaToBedrock;
import io.github.wyze3306.jtb.auth.LoginData;
import io.github.wyze3306.jtb.skin.BedrockSkinData;
import io.github.wyze3306.jtb.skin.JavaSkinProvider;
import io.github.wyze3306.jtb.skin.SignedSkins;
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
    private final BedrockServers servers;

    public BedrockConnector(JavaToBedrock proxy) {
        this.proxy = proxy;
        this.servers = new BedrockServers(proxy.config().bedrock());
        this.group = new MultiThreadIoEventLoopGroup(0, new DefaultThreadFactory("jtb-bedrock", true), NioIoHandler.newFactory());
    }

    public BedrockServers servers() {
        return this.servers;
    }

    public static BedrockCodec defaultCodec() {
        return DEFAULT_CODEC;
    }

    public EventLoopGroup group() {
        return this.group;
    }

    /**
     * The codec to use for {@code server}: the default one, adjusted to the protocol number the server
     * advertises in its ping when it differs only by that number.
     */
    public BedrockCodec codecForServer(InetSocketAddress server) {
        BedrockPinger.Status status = this.proxy.pinger().status(server);
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
     * Session event loop.
     */
    public void start(ProxySession session) {
        start(session, this.servers.address(this.servers.forJavaHost(session.getJavaHost())));
    }

    /**
     * Same as {@link #start(ProxySession)} with an explicit server. Starting a connection supersedes
     * any earlier attempt of the session: whatever that attempt still does (late failure, late
     * success) is ignored. Session event loop.
     */
    public void start(ProxySession session, InetSocketAddress target) {
        BedrockReconnect reconnect = session.get(BedrockReconnect.KEY);
        int attempt = reconnect.connecting(target);
        CompletableFuture<LoginData> identity = this.proxy.authProvider().login(session);
        CompletableFuture<BedrockSkinData> skin = JavaSkinProvider.forProfile(session.getProfile(),
                this.proxy.config().features().javaSkins(), this.proxy.service(SignedSkins.class));
        identity.thenCombine(skin, (loginData, skinData) -> {
            session.execute(() -> {
                if (reconnect.isCurrent(attempt)) {
                    connect(session, target, loginData, skinData, attempt);
                }
            });
            return null;
        }).exceptionally(error -> {
            LOGGER.warn("[{}] Bedrock authentication failed", session.getName(), error);
            session.disconnect(Component.text("Authentification Bedrock impossible : ", NamedTextColor.RED)
                    .append(Component.text(String.valueOf(rootMessage(error)), NamedTextColor.GRAY)));
            return null;
        });
    }

    /**
     * {@link #start(ProxySession, InetSocketAddress)} for a host name, resolved on a worker thread so
     * a slow DNS lookup never blocks an event loop (Bedrock transfers, waiting room retries).
     */
    public void start(ProxySession session, String host, int port) {
        CompletableFuture.supplyAsync(() -> new InetSocketAddress(host, port), this.proxy.workers())
                .thenAccept(address -> session.execute(() -> {
                    if (!session.isClosed()) {
                        start(session, address);
                    }
                }));
    }

    private static Object rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    /**
     * Connects the session to a Bedrock server and runs the login with {@code login}. When the server
     * cannot be reached the player waits in the waiting room or is kicked with an explanation.
     */
    private void connect(ProxySession session, InetSocketAddress address, LoginData login, BedrockSkinData skin, int attempt) {
        if (session.isClosed()) {
            return;
        }
        session.setXuid(login.xuid());
        ProxyConfig.Bedrock config = this.proxy.config().bedrock();
        BedrockCodec codec = codecForServer(address);
        BedrockReconnect reconnect = session.get(BedrockReconnect.KEY);

        Bootstrap bootstrap = new Bootstrap()
                .group(this.group)
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, codec.getRaknetProtocolVersion())
                .option(RakChannelOption.RAK_GUID, ThreadLocalRandom.current().nextLong())
                .option(RakChannelOption.RAK_CONNECT_TIMEOUT, TimeUnit.SECONDS.toMillis(config.connectTimeoutSeconds()))
                // A crashed server is noticed after this delay (a clean stop sends a disconnect packet);
                // RakNet acks run on network threads, so a lagging server does not trip it.
                .option(RakChannelOption.RAK_SESSION_TIMEOUT, TimeUnit.SECONDS.toMillis(15))
                .handler(new BedrockClientInitializer() {
                    @Override
                    protected void initSession(BedrockClientSession bedrock) {
                        // Netty thread: everything that touches the session state hops to its loop.
                        bedrock.setCodec(codec);
                        CodecRegistries.install(bedrock);
                        bedrock.setPacketHandler(new BedrockPacketDispatcher(session, bedrock,
                                new BedrockLoginHandler(session, bedrock, codec, login, skin)));
                        session.execute(() -> {
                            if (session.isClosed() || !reconnect.isCurrent(attempt)) {
                                // Superseded by a server switch while connecting.
                                bedrock.disconnect("Superseded");
                                return;
                            }
                            session.setBedrockSession(bedrock);
                            RequestNetworkSettingsPacket request = new RequestNetworkSettingsPacket();
                            request.setClientNetworkVersion(codec.getProtocolVersion());
                            bedrock.sendPacketImmediately(request);
                        });
                    }
                });

        LOGGER.info("[{}] Connecting to Bedrock server {}:{} (protocol {})", session.getName(),
                address.getHostString(), address.getPort(), codec.getProtocolVersion());
        ChannelFuture future = bootstrap.connect(address);
        future.addListener(result -> {
            if (!result.isSuccess()) {
                session.execute(() -> {
                    if (!reconnect.isCurrent(attempt)) {
                        return;
                    }
                    LOGGER.warn("[{}] Could not reach the Bedrock server {}:{}: {}", session.getName(),
                            address.getHostString(), address.getPort(), result.cause() == null ? "?" : result.cause().toString());
                    if (reconnect.onConnectionLost(Component.text("injoignable"))) {
                        return;
                    }
                    session.disconnect(Component.text("Impossible de joindre le serveur Bedrock ", NamedTextColor.RED)
                            .append(Component.text(address.getHostString() + ":" + address.getPort(), NamedTextColor.GRAY))
                            .append(Component.newline())
                            .append(Component.text(String.valueOf(result.cause() == null ? "" : result.cause().getMessage()), NamedTextColor.DARK_GRAY)));
                });
            }
        });
    }

    @Override
    public void close() {
        this.group.shutdownGracefully(0, 2, TimeUnit.SECONDS);
    }
}
