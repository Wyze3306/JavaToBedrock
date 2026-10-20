package io.github.wyze3306.jtb.player;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.payload.attribute.AttributeData;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataMap;
import org.cloudburstmc.protocol.bedrock.packet.ActorEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;

import java.util.List;

/**
 * CONTRACT (owner: player subsystem). The local player: identity on both sides, position and
 * rotation, game mode and abilities, movement emulation ({@code PlayerAuthInputPacket} every tick).
 * <p>
 * Packets that address an entity by runtime id are translated by the entity subsystem; when they
 * target the local player it forwards them to the {@code onLocal...} hooks below.
 */
public final class PlayerState implements SessionState {

    public static final StateKey<PlayerState> KEY = StateKey.of("player", PlayerState::new);

    private final ProxySession session;

    private PlayerState(ProxySession session) {
        this.session = session;
    }

    /** Called by the join translator with the StartGame packet (runtime id, position, game mode...). */
    public void onStartGame(StartGamePacket packet) {
    }

    /** Bedrock runtime id of the local player. */
    public long runtimeId() {
        return 0;
    }

    /** Java entity id of the local player. */
    public int javaEntityId() {
        return 1;
    }

    public boolean isLocal(long runtimeId) {
        return runtimeId == runtimeId();
    }

    /** Feet position (Java convention). */
    public Vector3f position() {
        return Vector3f.ZERO;
    }

    public float yaw() {
        return 0;
    }

    public float pitch() {
        return 0;
    }

    public boolean onGround() {
        return true;
    }

    public void onLocalAttributes(List<AttributeData> attributes) {
    }

    public void onLocalMotion(Vector3f motion) {
    }

    public void onLocalActorData(ActorDataMap data) {
    }

    public void onLocalEffect(MobEffectPacket packet) {
    }

    public void onLocalEvent(ActorEventPacket packet) {
    }
}
