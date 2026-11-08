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
import io.netty.util.concurrent.DefaultThreadFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPClientSignaling;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Opens the Bedrock (NetherNet) connection of each session and starts the Bedrock login.
 */
public final class BedrockConnector implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(BedrockConnector.class);

    /** Bedrock 1.26.60 (protocol 2225), what PowerNukkitX speaks. */
    private static final BedrockCodec DEFAULT_CODEC = Bedrock_v2225.CODEC;
    /** The name the NetherNet identity carries (token issuer); nothing displays it. */
    private static final String IDENTITY_DOMAIN = "javatobedrock";

    private final JavaToBedrock proxy;
    private final EventLoopGroup group;
    private final BedrockServers servers;
    /**
     * The proxy's NetherNet operator key: every offer carries an identity assertion signed with it,
     * derived for the player it connects (PowerNukkitX refuses unsigned offers with HTTP 401).
     */
    private final OperatorIdentity identity;

    public BedrockConnector(JavaToBedrock proxy) throws Exception {
        this.proxy = proxy;
        File identityFile = proxy.dataDirectory().resolve(proxy.config().bedrock().nethernetIdentityFile()).toFile();
        this.identity = OperatorIdentity.fromPemOrCreate(identityFile, IDENTITY_DOMAIN);
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

        OperatorIdentity playerIdentity;
        try {
            playerIdentity = this.identity.forPlayer(login.xuid(), login.displayName());
        } catch (Exception e) {
            LOGGER.warn("[{}] Could not sign the NetherNet identity", session.getName(), e);
            session.disconnect(Component.text("Identité NetherNet impossible : " + rootMessage(e), NamedTextColor.RED));
            return;
        }
        // NetherNet: the SDP offer goes to the server's HTTP signalling endpoint (its listener port, TCP),
        // then the game runs over WebRTC data channels. One signalling instance per connection.
        Bootstrap bootstrap = new Bootstrap()
                .group(this.group)
                .channelFactory(NetherNetChannelFactory.client(() -> new NetherNetHTTPClientSignaling()))
                .option(NetherChannelOption.NETHER_CLIENT_HANDSHAKE_TIMEOUT_MS,
                        (int) TimeUnit.SECONDS.toMillis(config.connectTimeoutSeconds()))
                .option(NetherChannelOption.NETHER_CLIENT_IDENTITY, playerIdentity)
                // No NETHER_CLIENT_SERVER_TRUST: the server's assertion is not checked. TokenTrust.ANY
                // requires an expiry, and PowerNukkitX signs with a PEM key whose token has none; the
                // proxy trusts the address it was configured with, like a RakNet connection did.
                .handler(new NetherNetClientInitializer(codec.getRaknetProtocolVersion()) {
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
