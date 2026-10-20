package io.github.wyze3306.jtb.session;

import io.github.wyze3306.jtb.JavaToBedrock;
import io.github.wyze3306.jtb.config.ProxyConfig;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.netty.channel.raknet.RakChannel;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * One Java player connected through the proxy: the Java connection, the matching Bedrock
 * connection, and every piece of translation state.
 * <p>
 * <b>Threading:</b> all state is confined to {@link #eventLoop()}. Network callbacks from either
 * side must hop onto it with {@link #execute(Runnable)}. {@link #sendJava(Packet)} and
 * {@link #sendBedrock(BedrockPacket)} are safe from any thread.
 */
public final class ProxySession {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxySession.class);

    /**
     * Where the Java connection is in its life.
     */
    public enum JavaPhase {
        /** Login done, the client sits in the configuration phase while the Bedrock side connects. */
        CONFIGURATION,
        /** In game: play packets flow. */
        GAME,
        CLOSED
    }

    private final JavaToBedrock proxy;
    private final Session javaSession;
    private final GameProfile profile;
    private final EventLoop eventLoop;
    private Object[] states = new Object[Math.max(64, StateKey.count() + 16)];
    private final ArrayDeque<Packet> pendingGamePackets = new ArrayDeque<>();

    private @Nullable BedrockClientSession bedrockSession;
    private volatile JavaPhase javaPhase = JavaPhase.CONFIGURATION;
    private @Nullable ScheduledFuture<?> tickTask;
    private long tickCount;
    private String locale = "en_us";
    private String bedrockName;
    private String xuid = "";
    private volatile boolean closed;

    public ProxySession(JavaToBedrock proxy, Session javaSession, GameProfile profile, EventLoop eventLoop) {
        this.proxy = proxy;
        this.javaSession = javaSession;
        this.profile = profile;
        this.eventLoop = eventLoop;
        this.bedrockName = proxy.config().bedrock().usernamePrefix() + profile.getName();
    }

    // ------------------------------------------------------------------ identity

    /** The Java username (as verified by Mojang in online mode). */
    public String getName() {
        return this.profile.getName();
    }

    /** The name used on the Bedrock server (Java name + configured prefix). */
    public String getBedrockName() {
        return this.bedrockName;
    }

    public void setBedrockName(String bedrockName) {
        this.bedrockName = bedrockName;
    }

    /**
     * The Xbox user id the Bedrock server knows this player by (derived from the Java identity in
     * offline mode). Servers with Xbox authentication drop chat whose sender XUID differs.
     */
    public String getXuid() {
        return this.xuid;
    }

    public void setXuid(String xuid) {
        this.xuid = xuid;
    }

    public GameProfile getProfile() {
        return this.profile;
    }

    /** The Java client locale, lower case (e.g. {@code fr_fr}). Updated from client information. */
    public String getLocale() {
        return this.locale;
    }

    public void setLocale(String locale) {
        this.locale = locale.toLowerCase(Locale.ROOT);
    }

    public JavaToBedrock proxy() {
        return this.proxy;
    }

    public ProxyConfig config() {
        return this.proxy.config();
    }

    public Logger logger() {
        return LOGGER;
    }

    // ------------------------------------------------------------------ state

    /**
     * Returns the state for {@code key}, creating it on first use. Must be called on the event loop.
     */
    @SuppressWarnings("unchecked")
    public <T extends SessionState> T get(StateKey<T> key) {
        if (key.index() >= this.states.length) {
            // A state class loaded after this session was created: its key index is new.
            this.states = Arrays.copyOf(this.states, Math.max(key.index() + 16, this.states.length * 2));
        }
        Object state = this.states[key.index()];
        if (state == null) {
            state = key.create(this);
            this.states[key.index()] = state;
        }
        return (T) state;
    }

    /**
     * Returns the state for {@code key} if it was already created, without creating it.
     */
    @SuppressWarnings("unchecked")
    public <T extends SessionState> @Nullable T getIfPresent(StateKey<T> key) {
        return key.index() < this.states.length ? (T) this.states[key.index()] : null;
    }

    // ------------------------------------------------------------------ threading

    public EventLoop eventLoop() {
        return this.eventLoop;
    }

    public boolean inEventLoop() {
        return this.eventLoop.inEventLoop();
    }

    /**
     * Runs {@code task} on the session event loop (directly when already on it). Exceptions are
     * logged, never propagated, so one faulty translator cannot kill the session loop.
     */
    public void execute(Runnable task) {
        if (this.closed) {
            return;
        }
        if (this.eventLoop.inEventLoop()) {
            runSafely(task);
        } else {
            this.eventLoop.execute(() -> runSafely(task));
        }
    }

    public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
        return this.eventLoop.schedule(() -> runSafely(task), delay, unit);
    }

    private void runSafely(Runnable task) {
        if (this.closed) {
            return;
        }
        try {
            task.run();
        } catch (Throwable t) {
            LOGGER.error("[{}] Error in session task", getName(), t);
        }
    }

    // ------------------------------------------------------------------ Java side

    public Session javaSession() {
        return this.javaSession;
    }

    public JavaPhase javaPhase() {
        return this.javaPhase;
    }

    /**
     * Sends a play-state packet to the Java client. While the client is still in the configuration
     * phase, packets are queued and flushed in order as soon as it enters the game.
     */
    public void sendJava(Packet packet) {
        if (this.javaPhase == JavaPhase.GAME) {
            this.javaSession.send(packet);
            return;
        }
        if (this.javaPhase == JavaPhase.CLOSED) {
            return;
        }
        // Queue on the event loop to keep ordering with the flush in enterGame().
        execute(() -> {
            if (this.javaPhase == JavaPhase.GAME) {
                this.javaSession.send(packet);
            } else if (this.javaPhase == JavaPhase.CONFIGURATION) {
                this.pendingGamePackets.add(packet);
            }
        });
    }

    /**
     * Queues the Java login (play) packet ahead of every play packet already queued: the Java client
     * requires it first, while Bedrock servers send a few packets before {@code StartGame}. Must run on
     * the event loop, while the client is still configuring.
     */
    public void sendJavaLogin(Packet loginPacket) {
        if (this.javaPhase == JavaPhase.CONFIGURATION) {
            this.pendingGamePackets.addFirst(loginPacket);
        } else {
            this.javaSession.send(loginPacket);
        }
    }

    /**
     * Sends a packet regardless of the Java phase (used by the configuration phase itself).
     */
    public void sendJavaDirect(Packet packet) {
        this.javaSession.send(packet);
    }

    /**
     * Switches to the game phase and flushes queued play packets. Called by the configuration
     * handler once the client acknowledged the end of configuration and the Java login packet was
     * sent. Must run on the event loop.
     */
    public void enterGame() {
        this.javaPhase = JavaPhase.GAME;
        Packet packet;
        while ((packet = this.pendingGamePackets.poll()) != null) {
            this.javaSession.send(packet);
        }
        if (this.tickTask == null) {
            long period = this.proxy.tickPeriodNanos();
            this.tickTask = this.eventLoop.scheduleAtFixedRate(this::tick, period, period, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Returns to the configuration phase (Java {@code StartConfiguration}); play packets are queued
     * again until {@link #enterGame()}.
     */
    public void enterConfiguration() {
        this.javaPhase = JavaPhase.CONFIGURATION;
    }

    /** Drops queued play packets (they belong to a world the client is leaving). */
    public void clearPendingGamePackets() {
        this.pendingGamePackets.clear();
    }

    /**
     * Moves the player to another Bedrock server without disconnecting the Java client: closes the
     * current Bedrock connection, discards every Bedrock-side state, sends the client back to the
     * configuration phase and connects to {@code target}. Must run on the event loop.
     */
    public void switchServer(InetSocketAddress target) {
        LOGGER.info("[{}] Switching to Bedrock server {}:{}", getName(), target.getHostString(), target.getPort());
        detachBedrock("Transferred");
        this.proxy.bedrockConnector().start(this, target);
    }

    /**
     * Leaves the current Bedrock server while keeping the Java player: closes the Bedrock connection
     * and every state describing its world, and sends the Java client back to the configuration
     * phase (when in game) to wait for the next server's StartGame.
     */
    public void detachBedrock(String reason) {
        BedrockClientSession old = this.bedrockSession;
        this.bedrockSession = null;
        if (old != null && old.isConnected()) {
            old.disconnect(reason);
        }
        for (int i = 0; i < this.states.length; i++) {
            Object state = this.states[i];
            if (state != null && !((SessionState) state).survivesServerSwitch()) {
                try {
                    ((SessionState) state).close();
                } catch (Throwable t) {
                    LOGGER.error("[{}] Error while closing {}", getName(), state.getClass().getSimpleName(), t);
                }
                this.states[i] = null;
            }
        }
        if (this.tickTask != null) {
            this.tickTask.cancel(false);
            this.tickTask = null;
        }
        get(io.github.wyze3306.jtb.network.java.JavaConfiguration.KEY).startServerSwitch();
    }

    /**
     * The host name the Java player typed to connect (used to build resource pack URLs).
     */
    public @Nullable String getJavaHost() {
        SocketAddress local = this.javaSession.getLocalAddress();
        String host = this.javaSession.getFlag(io.github.wyze3306.jtb.network.java.JavaFlags.HANDSHAKE_HOST);
        if (host != null && !host.isBlank()) {
            return host;
        }
        if (local instanceof InetSocketAddress inet) {
            return inet.getHostString();
        }
        return null;
    }

    // ------------------------------------------------------------------ Bedrock side

    public @Nullable BedrockClientSession bedrockSession() {
        return this.bedrockSession;
    }

    public void setBedrockSession(BedrockClientSession bedrockSession) {
        this.bedrockSession = bedrockSession;
    }

    /**
     * The RakNet round trip time to the Bedrock server in milliseconds, or -1 when not connected.
     */
    public long bedrockPingMillis() {
        BedrockClientSession session = this.bedrockSession;
        if (session == null || !session.isConnected()
                || !(session.getPeer().getChannel() instanceof RakChannel rak)) {
            return -1;
        }
        RakSessionCodec codec = rak.rakPipeline().get(RakSessionCodec.class);
        return codec == null ? -1 : codec.getPing();
    }

    public boolean isBedrockConnected() {
        BedrockClientSession session = this.bedrockSession;
        return session != null && session.isConnected();
    }

    /**
     * Queues a packet to the Bedrock server (batched and flushed by the protocol library).
     */
    public void sendBedrock(BedrockPacket packet) {
        BedrockClientSession session = this.bedrockSession;
        if (session != null && session.isConnected()) {
            session.sendPacket(packet);
        }
    }

    /**
     * Sends a packet to the Bedrock server and flushes immediately.
     */
    public void sendBedrockImmediately(BedrockPacket packet) {
        BedrockClientSession session = this.bedrockSession;
        if (session != null && session.isConnected()) {
            session.sendPacketImmediately(packet);
        }
    }

    // ------------------------------------------------------------------ ticking

    /** Number of proxy ticks since the session entered the game. */
    public long tickCount() {
        return this.tickCount;
    }

    private void tick() {
        if (this.closed) {
            return;
        }
        this.tickCount++;
        for (Object state : this.states) {
            if (state != null) {
                try {
                    ((SessionState) state).tick();
                } catch (Throwable t) {
                    LOGGER.error("[{}] Error while ticking {}", getName(), state.getClass().getSimpleName(), t);
                }
            }
        }
    }

    // ------------------------------------------------------------------ closing

    public boolean isClosed() {
        return this.closed;
    }

    /**
     * Kicks the Java player and closes the Bedrock connection.
     */
    public void disconnect(Component reason) {
        if (this.javaSession.isConnected()) {
            this.javaSession.disconnect(reason);
        }
        close("Java disconnect");
    }

    public void disconnect(String reason) {
        disconnect(Component.text(reason));
    }

    /**
     * Releases everything. Idempotent and callable from any thread.
     */
    public void close(String reason) {
        if (this.eventLoop.inEventLoop()) {
            close0(reason);
        } else {
            this.eventLoop.execute(() -> close0(reason));
        }
    }

    private void close0(String reason) {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.javaPhase = JavaPhase.CLOSED;
        if (this.tickTask != null) {
            this.tickTask.cancel(false);
        }
        BedrockClientSession bedrock = this.bedrockSession;
        if (bedrock != null && bedrock.isConnected()) {
            bedrock.disconnect(reason);
        }
        for (int i = 0; i < this.states.length; i++) {
            Object state = this.states[i];
            if (state != null) {
                try {
                    ((SessionState) state).close();
                } catch (Throwable t) {
                    LOGGER.error("[{}] Error while closing {}", getName(), state.getClass().getSimpleName(), t);
                }
            }
        }
        Arrays.fill(this.states, null);
        this.pendingGamePackets.clear();
        this.proxy.sessions().remove(this);
        LOGGER.info("[{}] Session closed ({})", getName(), reason);
    }

    @Override
    public String toString() {
        return "ProxySession[" + getName() + "]";
    }
}
