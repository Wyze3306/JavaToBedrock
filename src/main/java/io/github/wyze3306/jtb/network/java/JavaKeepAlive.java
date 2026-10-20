package io.github.wyze3306.jtb.network.java;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import io.netty.util.concurrent.ScheduledFuture;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundKeepAlivePacket;

import java.util.concurrent.TimeUnit;

/**
 * Java keep-alives, driven by the session event loop (no thread per player). Also measures the
 * Java round trip time.
 */
public final class JavaKeepAlive implements SessionState {

    public static final StateKey<JavaKeepAlive> KEY = StateKey.of("java-keepalive", JavaKeepAlive::new);

    private static final long INTERVAL_MS = 10_000;
    private static final long TIMEOUT_MS = 30_000;

    private final ProxySession session;
    private ScheduledFuture<?> task;
    private long pendingId = -1;
    private long sentAt;
    private volatile int latencyMs;

    private JavaKeepAlive(ProxySession session) {
        this.session = session;
    }

    void start() {
        if (this.task == null) {
            this.task = this.session.eventLoop().scheduleAtFixedRate(this::run, 1, 1, TimeUnit.SECONDS);
        }
    }

    private void run() {
        if (this.session.isClosed()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (this.pendingId != -1) {
            if (now - this.sentAt > TIMEOUT_MS) {
                this.session.disconnect(Component.translatable("disconnect.timeout"));
            }
            return;
        }
        if (now - this.sentAt >= INTERVAL_MS) {
            this.pendingId = now;
            this.sentAt = now;
            this.session.sendJavaDirect(new ClientboundKeepAlivePacket(now));
        }
    }

    void onResponse(long id) {
        if (id == this.pendingId) {
            this.latencyMs = (int) (System.currentTimeMillis() - this.sentAt);
            this.pendingId = -1;
        }
    }

    /** Last measured Java round trip time in milliseconds. */
    public int latencyMs() {
        return this.latencyMs;
    }

    @Override
    public void close() {
        if (this.task != null) {
            this.task.cancel(false);
        }
    }
}
