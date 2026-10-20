package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.player.PlayerState;
import io.github.wyze3306.jtb.session.ProxySession;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.actor.MoveActorAbsoluteData;
import org.cloudburstmc.protocol.bedrock.data.payload.move.MoveActorDeltaData;
import org.cloudburstmc.protocol.bedrock.packet.MotionPredictionHintsPacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveActorAbsolutePacket;
import org.cloudburstmc.protocol.bedrock.packet.MoveActorDeltaPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetActorMotionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityMotionPacket;

/**
 * Movement of entities other than the local player: {@code MoveActorAbsolute}, {@code MoveActorDelta},
 * {@code SetActorMotion} and {@code MotionPredictionHints} ({@code MovePlayer} of other players arrives
 * through {@link EntityTracker#onRemotePlayerMove}).
 * <p>
 * These are by far the most frequent packets (PowerNukkitX sends a delta per moving entity and tick):
 * the path allocates only the Java packet itself.
 */
final class MovementTranslators {

    private MovementTranslators() {
    }

    static void moveActorAbsolute(ProxySession session, MoveActorAbsolutePacket packet) {
        MoveActorAbsoluteData move = packet.getMoveData();
        EntityTracker tracker = session.get(EntityTracker.KEY);
        TrackedEntity entity = tracker.entity(move.getActorRuntimeID());
        if (entity == null) {
            return;
        }
        Vector3f position = move.getPos();
        Vector3f rotation = move.getRotation();
        if (entity.kind() == TrackedEntity.Kind.LOCAL_PLAYER) {
            // Only teleports concern the local player (e.g. moved with its vehicle); its own moves are client-driven.
            if (move.isTeleported() && position != null) {
                PlayerState player = session.get(PlayerState.KEY);
                player.teleport(position.sub(0, PlayerState.EYE_HEIGHT, 0), rotation.getY(), rotation.getX());
            }
            return;
        }
        float offset = offset(entity);
        tracker.moveTo(entity, position.getX(), position.getY() - offset, position.getZ(), rotation.getY(), rotation.getX(),
                rotation.getZ(), move.isOnGround(), move.isTeleported());
    }

    /**
     * PowerNukkitX's {@code MoveActorDelta} carries absolute coordinates; a missing coordinate or angle
     * is unchanged.
     */
    static void moveActorDelta(ProxySession session, MoveActorDeltaPacket packet) {
        MoveActorDeltaData move = packet.getMoveData();
        EntityTracker tracker = session.get(EntityTracker.KEY);
        TrackedEntity entity = tracker.entity(move.getActorRuntimeID());
        if (entity == null || entity.kind() == TrackedEntity.Kind.LOCAL_PLAYER) {
            return;
        }
        float offset = offset(entity);
        Float x = move.getNewPositionX();
        Float y = move.getNewPositionY();
        Float z = move.getNewPositionZ();
        Float pitch = move.getRotationX();
        Float yaw = move.getRotationY();
        Float headYaw = move.getRotationYHead();
        tracker.moveTo(entity,
                x != null ? x : entity.x,
                y != null ? y - offset : entity.y,
                z != null ? z : entity.z,
                yaw != null ? yaw : entity.yaw,
                pitch != null ? pitch : entity.pitch,
                headYaw != null ? headYaw : entity.headYaw,
                move.isOnGround(), move.isForceMove());
    }

    /** How far above its Java position Bedrock moves place the entity. */
    private static float offset(TrackedEntity entity) {
        return entity.definition().isPlayerLike() ? EntityTracker.playerEyeHeight(entity) : entity.definition().offset();
    }

    /**
     * Velocity. Java interpolates living entities between server positions and ignores their velocity,
     * so only the local player (knockback, through {@link PlayerState#onLocalMotion}) and non-living
     * entities (items, projectiles, vehicles, simulated by the Java client) get it.
     */
    static void setActorMotion(ProxySession session, SetActorMotionPacket packet) {
        EntityTracker tracker = session.get(EntityTracker.KEY);
        TrackedEntity entity = tracker.entity(packet.getTargetRuntimeID());
        if (entity == null) {
            return;
        }
        Vector3f motion = packet.getMotion();
        if (entity.kind() == TrackedEntity.Kind.LOCAL_PLAYER) {
            session.get(PlayerState.KEY).onLocalMotion(motion);
            return;
        }
        if (entity.definition().isLiving() || entity.kind() == TrackedEntity.Kind.CUSTOM) {
            return;
        }
        session.sendJava(new ClientboundSetEntityMotionPacket(entity.javaId(),
                Vector3d.from(motion.getX(), motion.getY(), motion.getZ())));
    }

    /** Client-side prediction hints of vehicles: Java predicts nothing from them. */
    static void motionPredictionHints(ProxySession session, MotionPredictionHintsPacket packet) {
    }
}
