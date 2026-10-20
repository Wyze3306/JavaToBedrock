package io.github.wyze3306.jtb.network.java;

import io.github.wyze3306.jtb.JavaToBedrock;
import io.github.wyze3306.jtb.session.ProxySession;
import io.netty.channel.EventLoop;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.auth.SessionService;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.compression.CompressionConfig;
import org.geysermc.mcprotocollib.network.compression.ZlibCompression;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectingEvent;
import org.geysermc.mcprotocollib.network.event.session.PacketErrorEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundKeepAlivePacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundSelectKnownPacks;
import org.geysermc.mcprotocollib.protocol.packet.handshake.serverbound.ClientIntentionPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundConfigurationAcknowledgedPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundHelloPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginCompressionPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundHelloPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundKeyPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundLoginAcknowledgedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ping.clientbound.ClientboundPongResponsePacket;
import org.geysermc.mcprotocollib.protocol.packet.ping.serverbound.ServerboundPingRequestPacket;
import org.geysermc.mcprotocollib.protocol.packet.status.clientbound.ClientboundStatusResponsePacket;
import org.geysermc.mcprotocollib.protocol.packet.status.serverbound.ServerboundStatusRequestPacket;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives one Java connection through handshake, status, login and configuration, then forwards
 * play packets to the translators. Adapted from MCProtocolLib's {@code ServerListener} (MIT) so the
 * configuration phase can be held open until the Bedrock side is ready.
 */
final class JavaConnectionListener extends SessionAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(JavaConnectionListener.class);
    private static final KeyPair KEY_PAIR;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(1024);
            KEY_PAIR = generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final JavaToBedrock proxy;
    private final byte[] challenge = new byte[4];
    private String username = "";
    private @Nullable ProxySession proxySession;

    JavaConnectionListener(JavaToBedrock proxy) {
        this.proxy = proxy;
        ThreadLocalRandom.current().nextBytes(this.challenge);
    }

    @Override
    public void packetReceived(Session session, Packet packet) {
        MinecraftProtocol protocol = (MinecraftProtocol) session.getPacketProtocol();
        ProtocolState state = protocol.getInboundState();
        if (state == null) {
            return;
        }
        switch (state) {
            case HANDSHAKE -> handleHandshake(session, protocol, packet);
            case STATUS -> handleStatus(session, packet);
            case LOGIN -> handleLogin(session, protocol, packet);
            case CONFIGURATION -> handleConfiguration(session, protocol, packet);
            case GAME -> handleGame(session, protocol, packet);
        }
    }

    // ------------------------------------------------------------------ handshake & status

    private void handleHandshake(Session session, MinecraftProtocol protocol, Packet packet) {
        if (!(packet instanceof ClientIntentionPacket intention)) {
            return;
        }
        session.setFlag(JavaFlags.HANDSHAKE_HOST, cleanHost(intention.getHostname()));
        session.setFlag(JavaFlags.HANDSHAKE_PORT, intention.getPort());
        switch (intention.getIntent()) {
            case STATUS -> {
                protocol.setOutboundState(ProtocolState.STATUS);
                session.switchInboundState(() -> protocol.setInboundState(ProtocolState.STATUS));
            }
            case LOGIN, TRANSFER -> {
                protocol.setOutboundState(ProtocolState.LOGIN);
                int serverProtocol = protocol.getCodec().getProtocolVersion();
                if (intention.getProtocolVersion() > serverProtocol) {
                    session.disconnect(Component.translatable("multiplayer.disconnect.incompatible",
                            Component.text(protocol.getCodec().getMinecraftVersion())));
                } else if (intention.getProtocolVersion() < serverProtocol) {
                    session.disconnect(Component.translatable("multiplayer.disconnect.outdated_client",
                            Component.text(protocol.getCodec().getMinecraftVersion())));
                } else {
                    session.switchInboundState(() -> protocol.setInboundState(ProtocolState.LOGIN));
                }
            }
        }
    }

    /**
     * Forge/BungeeCord style hosts carry extra data after a NUL byte; keep only the host name.
     */
    private static String cleanHost(String host) {
        int nul = host.indexOf('\0');
        String clean = nul >= 0 ? host.substring(0, nul) : host;
        if (clean.endsWith(".")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean;
    }

    private void handleStatus(Session session, Packet packet) {
        if (packet instanceof ServerboundStatusRequestPacket) {
            session.send(new ClientboundStatusResponsePacket(this.proxy.pinger().buildJavaStatus()));
        } else if (packet instanceof ServerboundPingRequestPacket ping) {
            session.send(new ClientboundPongResponsePacket(ping.getPingTime()));
            session.disconnect(Component.translatable("multiplayer.status.request_handled"));
        }
    }

    // ------------------------------------------------------------------ login

    private void handleLogin(Session session, MinecraftProtocol protocol, Packet packet) {
        if (packet instanceof ServerboundHelloPacket hello) {
            this.username = hello.getUsername();
            if (session.getFlag(MinecraftConstants.ENCRYPT_CONNECTION, true)) {
                session.send(new ClientboundHelloPacket("", KEY_PAIR.getPublic(), this.challenge,
                        session.getFlag(MinecraftConstants.SHOULD_AUTHENTICATE, true)));
            } else {
                this.proxy.workers().execute(() -> authenticate(session, false, null));
            }
        } else if (packet instanceof ServerboundKeyPacket key) {
            PrivateKey privateKey = KEY_PAIR.getPrivate();
            if (!Arrays.equals(this.challenge, key.getEncryptedChallenge(privateKey))) {
                session.disconnect(Component.text("Invalid encryption challenge"));
                return;
            }
            SecretKey secretKey = key.getSecretKey(privateKey);
            session.setEncryption(((JtbMinecraftProtocol) protocol).encryption(secretKey));
            this.proxy.workers().execute(() -> authenticate(session,
                    session.getFlag(MinecraftConstants.SHOULD_AUTHENTICATE, true), secretKey));
        } else if (packet instanceof ServerboundLoginAcknowledgedPacket) {
            protocol.setOutboundState(ProtocolState.CONFIGURATION);
            session.switchInboundState(() -> protocol.setInboundState(ProtocolState.CONFIGURATION));
            startProxySession(session);
        }
    }

    /**
     * Runs on a worker thread: the Mojang session server call blocks.
     */
    private void authenticate(Session session, boolean onlineMode, @Nullable SecretKey key) {
        GameProfile profile;
        if (onlineMode && key != null) {
            SessionService sessionService = session.getFlag(MinecraftConstants.SESSION_SERVICE_KEY, new SessionService());
            try {
                profile = sessionService.getProfileByServer(this.username,
                        SessionService.getServerId("", KEY_PAIR.getPublic(), key));
            } catch (IOException e) {
                session.disconnect(Component.translatable("multiplayer.disconnect.authservers_down"), e);
                return;
            }
            if (profile == null) {
                session.disconnect(Component.translatable("multiplayer.disconnect.unverified_username"));
                return;
            }
        } else {
            profile = new GameProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + this.username).getBytes(StandardCharsets.UTF_8)),
                    this.username);
        }

        ProxySession existing = this.proxy.sessions().get(profile.getId());
        if (existing != null) {
            existing.disconnect(Component.translatable("multiplayer.disconnect.duplicate_login"));
        }

        session.setFlag(MinecraftConstants.PROFILE_KEY, profile);
        int threshold = session.getFlag(MinecraftConstants.SERVER_COMPRESSION_THRESHOLD, 256);
        if (threshold >= 0) {
            session.send(new ClientboundLoginCompressionPacket(threshold), () ->
                    session.setCompression(new CompressionConfig(threshold, new ZlibCompression(), true)));
        }
        session.send(new ClientboundLoginFinishedPacket(profile, UUID.randomUUID()));
        LOGGER.info("{} ({}) logged in from {}", profile.getName(), profile.getId(), session.getRemoteAddress());
    }

    private void startProxySession(Session session) {
        GameProfile profile = session.getFlag(MinecraftConstants.PROFILE_KEY);
        if (profile == null) {
            session.disconnect(Component.text("Login not completed"));
            return;
        }
        EventLoop loop = (EventLoop) session.getPacketHandlerExecutor();
        ProxySession proxySession = new ProxySession(this.proxy, session, profile, loop);
        this.proxySession = proxySession;
        session.setFlag(JavaFlags.PROXY_SESSION, proxySession);
        this.proxy.sessions().add(proxySession);
        proxySession.execute(() -> {
            proxySession.get(JavaKeepAlive.KEY).start();
            proxySession.get(JavaConfiguration.KEY).begin();
            this.proxy.bedrockConnector().start(proxySession);
        });
    }

    // ------------------------------------------------------------------ configuration

    private void handleConfiguration(Session session, MinecraftProtocol protocol, Packet packet) {
        ProxySession proxySession = this.proxySession;
        if (proxySession == null) {
            return;
        }
        proxySession.execute(() -> {
            JavaConfiguration configuration = proxySession.get(JavaConfiguration.KEY);
            if (packet instanceof ServerboundKeepAlivePacket keepAlive) {
                proxySession.get(JavaKeepAlive.KEY).onResponse(keepAlive.getPingId());
            } else if (packet instanceof ServerboundSelectKnownPacks knownPacks) {
                configuration.onKnownPacks(knownPacks.getKnownPacks());
            } else if (packet instanceof ServerboundClientInformationPacket information) {
                configuration.onClientInformation(information);
            } else if (packet instanceof ServerboundResourcePackPacket resourcePack) {
                configuration.onResourcePackStatus(resourcePack.getId(), resourcePack.getStatus());
            } else if (packet instanceof ServerboundFinishConfigurationPacket) {
                protocol.setOutboundState(ProtocolState.GAME);
                session.switchInboundState(() -> protocol.setInboundState(ProtocolState.GAME));
                configuration.onClientFinished();
            }
        });
    }

    // ------------------------------------------------------------------ game

    private void handleGame(Session session, MinecraftProtocol protocol, Packet packet) {
        ProxySession proxySession = this.proxySession;
        if (proxySession == null) {
            return;
        }
        if (packet instanceof ServerboundConfigurationAcknowledgedPacket) {
            session.switchInboundState(() -> protocol.setInboundState(ProtocolState.CONFIGURATION));
            proxySession.execute(() -> proxySession.get(JavaConfiguration.KEY).onReconfigurationAcknowledged());
            return;
        }
        if (packet instanceof ServerboundPingRequestPacket ping) {
            session.send(new ClientboundPongResponsePacket(ping.getPingTime()));
            return;
        }
        proxySession.execute(() -> {
            if (packet instanceof ServerboundKeepAlivePacket keepAlive) {
                proxySession.get(JavaKeepAlive.KEY).onResponse(keepAlive.getPingId());
                return;
            }
            if (!this.proxy.translators().translateJava(proxySession, packet) && this.proxy.config().debug().logUnhandledJava()) {
                LOGGER.info("[{}] Unhandled Java packet {}", proxySession.getName(), packet);
            }
        });
    }

    @Override
    public void packetError(PacketErrorEvent event) {
        // Logged here, the session is still closed by MCProtocolLib (an undecodable stream cannot recover).
        ProxySession proxySession = this.proxySession;
        LOGGER.warn("[{}] Java packet error ({}): {}", proxySession == null ? "?" : proxySession.getName(),
                event.getPacketClass() == null ? "unknown packet" : event.getPacketClass().getSimpleName(),
                event.getCause().toString(), event.getCause());
    }

    // ------------------------------------------------------------------ disconnect

    @Override
    public void disconnecting(DisconnectingEvent event) {
        Session session = event.getSession();
        MinecraftProtocol protocol = (MinecraftProtocol) session.getPacketProtocol();
        if (protocol.getOutboundState() == ProtocolState.LOGIN) {
            session.send(new ClientboundLoginDisconnectPacket(event.getReason()));
        } else if (protocol.getOutboundState() == ProtocolState.GAME || protocol.getOutboundState() == ProtocolState.CONFIGURATION) {
            session.send(new ClientboundDisconnectPacket(event.getReason()));
        }
    }

    @Override
    public void disconnected(DisconnectedEvent event) {
        ProxySession proxySession = this.proxySession;
        if (proxySession != null) {
            if (event.getCause() != null && LOGGER.isDebugEnabled()) {
                LOGGER.debug("[{}] Java connection error", proxySession.getName(), event.getCause());
            }
            proxySession.close("Java client disconnected");
        }
    }
}
