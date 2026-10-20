package io.github.wyze3306.jtb.e2e;

import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.packet.Packet;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What a scenario can see and do: the session, the player position, received packet counts, the
 * last packet of each type, and named checks.
 */
public final class ClientContext {

    private final Session session;
    final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    final Map<Class<?>, Packet> last = new ConcurrentHashMap<>();
    final List<String> checks = new CopyOnWriteArrayList<>();
    final List<String> failures = new CopyOnWriteArrayList<>();
    final List<String> chat = new CopyOnWriteArrayList<>();
    volatile double x, y, z;
    volatile float yaw, pitch;
    volatile boolean positioned;

    ClientContext(Session session) {
        this.session = session;
    }

    public Session session() {
        return this.session;
    }

    public void send(Packet packet) {
        this.session.send(packet);
    }

    public double x() { return this.x; }
    public double y() { return this.y; }
    public double z() { return this.z; }
    public float yaw() { return this.yaw; }
    public float pitch() { return this.pitch; }

    public void setPosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /** How many packets of this simple class name were received. */
    public int count(String simpleName) {
        AtomicInteger count = this.counts.get(simpleName);
        return count == null ? 0 : count.get();
    }

    @SuppressWarnings("unchecked")
    public <P extends Packet> P last(Class<P> type) {
        return (P) this.last.get(type);
    }

    /** System chat and action bar lines received so far (plain text). */
    public List<String> chat() {
        return this.chat;
    }

    /** Records an expectation; failures make the run fail. */
    public void check(String name, boolean ok) {
        (ok ? this.checks : this.failures).add(name);
        log((ok ? "CHECK OK   " : "CHECK FAIL ") + name);
    }

    public void log(String message) {
        System.out.println("[client] " + message);
    }
}
