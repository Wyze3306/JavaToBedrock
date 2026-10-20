package io.github.wyze3306.jtb.network.java;

import io.github.wyze3306.jtb.session.ProxySession;
import org.geysermc.mcprotocollib.network.Flag;

/**
 * Session flags used by the proxy on MCProtocolLib sessions.
 */
public final class JavaFlags {

    /** Host name sent by the client in its handshake (the address it typed). */
    public static final Flag<String> HANDSHAKE_HOST = new Flag<>("jtb-handshake-host", String.class);
    /** Port sent by the client in its handshake. */
    public static final Flag<Integer> HANDSHAKE_PORT = new Flag<>("jtb-handshake-port", Integer.class);
    /** The proxy session bound to this Java connection, once logged in. */
    public static final Flag<ProxySession> PROXY_SESSION = new Flag<>("jtb-proxy-session", ProxySession.class);

    private JavaFlags() {
    }
}
