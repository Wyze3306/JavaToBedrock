package io.github.wyze3306.jtb.player;

import io.github.wyze3306.jtb.entity.EntityTracker;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.GameType;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataMap;
import org.cloudburstmc.protocol.bedrock.data.payload.attribute.AttributeData;
import org.cloudburstmc.protocol.bedrock.packet.ActorEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;

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

    /** Bedrock player positions are at eye level. */
    public static final float EYE_HEIGHT = 1.62f;

    private final ProxySession session;
    private long runtimeId;
    private int javaEntityId = 1;
    private Vector3f position = Vector3f.ZERO;
    private float yaw;
    private float pitch;
    private boolean onGround = true;
    private GameType gameType = GameType.SURVIVAL;
    private GameType worldGameType = GameType.SURVIVAL;
    private boolean spawned;
    private int nextTeleportId = 1;

    private PlayerState(ProxySession session) {
        this.session = session;
    }

    /** Called by the join translator with the StartGame packet (runtime id, position, game mode...). */
    public void onStartGame(StartGamePacket packet) {
        this.runtimeId = packet.getRuntimeID();
        this.javaEntityId = this.session.get(EntityTracker.KEY).bind(this.runtimeId);
        this.position = packet.getPosition().sub(0, EYE_HEIGHT, 0);
        this.pitch = packet.getRotation().getX();
        this.yaw = packet.getRotation().getY();
        this.worldGameType = packet.getSettings().getGameType() == null ? GameType.SURVIVAL : packet.getSettings().getGameType();
        this.gameType = packet.getGameType() == null || packet.getGameType() == GameType.DEFAULT ? this.worldGameType : packet.getGameType();
    }

    /** Bedrock runtime id of the local player. */
    public long runtimeId() {
        return this.runtimeId;
    }

    /** Java entity id of the local player. */
    public int javaEntityId() {
        return this.javaEntityId;
    }

    public boolean isLocal(long runtimeId) {
        return runtimeId == this.runtimeId;
    }

    /** Feet position (Java convention). */
    public Vector3f position() {
        return this.position;
    }

    public float yaw() {
        return this.yaw;
    }

    public float pitch() {
        return this.pitch;
    }

    public boolean onGround() {
        return this.onGround;
    }

    /** The player's effective game type (DEFAULT resolved to the world's). */
    public GameType gameType() {
        return this.gameType;
    }

    public boolean isSpawned() {
        return this.spawned;
    }

    public void setSpawned(boolean spawned) {
        this.spawned = spawned;
    }

    /**
     * Moves the Java player (server-side teleport). {@code feet} is the feet position.
     *
     * @return the Java teleport id the client will acknowledge
     */
    public int teleport(Vector3f feet, float yaw, float pitch) {
        this.position = feet;
        this.yaw = yaw;
        this.pitch = pitch;
        int id = this.nextTeleportId++;
        this.session.sendJava(new ClientboundPlayerPositionPacket(id, Vector3d.from(feet.getX(), feet.getY(), feet.getZ()),
                Vector3d.ZERO, yaw, pitch, List.of()));
        return id;
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
