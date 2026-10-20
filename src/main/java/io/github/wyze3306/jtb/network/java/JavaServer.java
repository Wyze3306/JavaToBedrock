package io.github.wyze3306.jtb.network.java;

import io.github.wyze3306.jtb.JavaToBedrock;
import io.github.wyze3306.jtb.config.ProxyConfig;
import io.netty.channel.EventLoop;
import org.geysermc.mcprotocollib.auth.SessionService;
import org.geysermc.mcprotocollib.network.Server;
import org.geysermc.mcprotocollib.network.server.NetworkServer;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * The TCP listener Java clients connect to.
 */
public final class JavaServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(JavaServer.class);

    private final JavaToBedrock proxy;
    private Server server;

    public JavaServer(JavaToBedrock proxy) {
        this.proxy = proxy;
    }

    public static String javaVersionName() {
        return MinecraftCodec.CODEC.getMinecraftVersion();
    }

    public static int javaProtocolVersion() {
        return MinecraftCodec.CODEC.getProtocolVersion();
    }

    public void bind() {
        ProxyConfig.Java config = this.proxy.config().java();
        // Every Java session handles its packets on its own proxy event loop: the same loop then
        // owns the whole ProxySession, so translation never needs locks.
        this.server = new NetworkServer(new InetSocketAddress(config.bind(), config.port()),
                () -> new JtbMinecraftProtocol(this.proxy),
                () -> (EventLoop) this.proxy.sessionLoops().next());
        this.server.setGlobalFlag(MinecraftConstants.SESSION_SERVICE_KEY, new SessionService());
        this.server.setGlobalFlag(MinecraftConstants.SHOULD_AUTHENTICATE, config.onlineMode());
        this.server.setGlobalFlag(MinecraftConstants.ENCRYPT_CONNECTION, config.onlineMode());
        this.server.setGlobalFlag(MinecraftConstants.SERVER_COMPRESSION_THRESHOLD, config.compressionThreshold());
        this.server.bind(true);
        LOGGER.info("Listening for Java {} clients on {}:{} (online-mode: {})", javaVersionName(),
                config.bind(), config.port(), config.onlineMode());
    }

    public void close() {
        if (this.server != null) {
            this.server.close(true);
        }
    }
}
