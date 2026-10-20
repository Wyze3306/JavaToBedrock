package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;

/**
 * CONTRACT (owner: entity subsystem). Entities known to the Java client and the id mapping
 * between Bedrock runtime/unique ids (longs) and Java entity ids (ints).
 */
public final class EntityTracker implements SessionState {

    public static final StateKey<EntityTracker> KEY = StateKey.of("entity-tracker", EntityTracker::new);

    private final ProxySession session;

    private EntityTracker(ProxySession session) {
        this.session = session;
    }

    /** Registers the local player (runtime id -> Java id) before anything else spawns. */
    public void onStartGame(StartGamePacket packet) {
    }

    /** Java entity id for a Bedrock runtime id, or -1 when the entity is unknown. */
    public int javaId(long runtimeId) {
        return -1;
    }

    /** Bedrock runtime id for a Java entity id, or -1 when unknown. */
    public long runtimeId(int javaId) {
        return -1;
    }

    /** Allocates a Java entity id for a proxy-side entity (display, interaction, marker...). */
    public int allocateJavaId() {
        return -1;
    }
}
