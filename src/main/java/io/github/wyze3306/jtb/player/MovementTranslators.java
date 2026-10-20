package io.github.wyze3306.jtb.player;

import io.github.wyze3306.jtb.entity.EntityTracker;
import io.github.wyze3306.jtb.session.ProxySession;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.ClientInputLockComponent;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PredictionType;
import org.cloudburstmc.protocol.bedrock.data.payload.move.PositionMode;
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket;
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateClientInputLocksPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerStatusOnlyPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerAbilitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerCommandPacket;

import java.util.Set;

/**
 * Movement in both directions: Java movement, keys, sprint/glide/flight commands and vehicle moves
 * feed {@link AuthInputBuilder}; Bedrock position corrections become Java teleports.
 */
final class MovementTranslators {

    /** Server positions closer than this to the client's are not worth a Java teleport. */
    private static final double SAME_POSITION_SQUARED = 0.01 * 0.01;

    private MovementTranslators() {
    }

    // ------------------------------------------------------------------ Java -> Bedrock

    static void movePos(ProxySession session, ServerboundMovePlayerPosPacket packet) {
        session.get(PlayerState.KEY).onJavaMove(true, packet.getX(), packet.getY(), packet.getZ(), false, 0, 0,
                packet.isOnGround(), packet.isHorizontalCollision());
    }

    static void movePosRot(ProxySession session, ServerboundMovePlayerPosRotPacket packet) {
        session.get(PlayerState.KEY).onJavaMove(true, packet.getX(), packet.getY(), packet.getZ(), true, packet.getYaw(), packet.getPitch(),
                packet.isOnGround(), packet.isHorizontalCollision());
    }

    static void moveRot(ProxySession session, ServerboundMovePlayerRotPacket packet) {
        session.get(PlayerState.KEY).onJavaMove(false, 0, 0, 0, true, packet.getYaw(), packet.getPitch(),
                packet.isOnGround(), packet.isHorizontalCollision());
    }

    static void moveStatus(ProxySession session, ServerboundMovePlayerStatusOnlyPacket packet) {
        session.get(PlayerState.KEY).onJavaMove(false, 0, 0, 0, false, 0, 0, packet.isOnGround(), packet.isHorizontalCollision());
    }

    static void acceptTeleport(ProxySession session, ServerboundAcceptTeleportationPacket packet) {
        session.get(PlayerState.KEY).onTeleportAccepted(packet.getId(), packet.getX(), packet.getY(), packet.getZ(),
                packet.getYRot(), packet.getXRot());
    }

    /** Key state, sent by the Java client whenever it changes. Shift while riding dismounts. */
    static void playerInput(ProxySession session, ServerboundPlayerInputPacket packet) {
        PlayerState player = session.get(PlayerState.KEY);
        AuthInputBuilder input = player.input();
        if (packet.isShift() && !input.shift() && input.riding()) {
            player.requestDismount();
        }
        input.setKeys(packet.isForward(), packet.isBackward(), packet.isLeft(), packet.isRight(), packet.isJump(),
                packet.isShift(), packet.isSprint());
    }

    static void clientTickEnd(ProxySession session) {
        session.get(PlayerState.KEY).onClientTickEnd();
    }

    /**
     * Java start/stop flying (double jump). Only forwarded when the server allows flight: PowerNukkitX
     * kicks a player that starts flying without it. A refused start is undone on the client.
     */
    static void abilities(ProxySession session, ServerboundPlayerAbilitiesPacket packet) {
        PlayerState player = session.get(PlayerState.KEY);
        if (player.input().setFlying(packet.isFlying())) {
            player.abilities().setFlying(packet.isFlying());
        } else {
            player.sendAbilities();
        }
    }

    static void playerCommand(ProxySession session, ServerboundPlayerCommandPacket packet) {
        PlayerState player = session.get(PlayerState.KEY);
        switch (packet.getState()) {
            case START_SPRINTING -> {
                player.input().setSprinting(true);
                player.stats().onJavaSprint();
            }
            case STOP_SPRINTING -> {
                player.input().setSprinting(false);
                player.stats().onJavaSprint();
            }
            case START_ELYTRA_FLYING -> player.input().startGliding();
            case LEAVE_BED -> {
                PlayerActionPacket action = new PlayerActionPacket();
                action.setPlayerRuntimeID(player.runtimeId());
                action.setAction(PlayerActionType.STOP_SLEEPING);
                action.setBlockPosition(Vector3i.ZERO);
                action.setResultPos(Vector3i.ZERO);
                action.setFace(-1);
                session.sendBedrock(action);
            }
            case OPEN_VEHICLE_INVENTORY -> {
                if (player.vehicleRuntimeId() != 0) {
                    Interactions.interact(session, InteractPacket.Action.OPEN_INVENTORY, player.vehicleRuntimeId());
                }
            }
            default -> {
                // Horse jumps: the jump key release (JUMP_RELEASED_RAW) is what Bedrock servers read.
            }
        }
    }

    /** Java {@code MoveVehicle}: the client moved the vehicle it controls. */
    static void moveVehicle(ProxySession session, ServerboundMoveVehiclePacket packet) {
        session.get(PlayerState.KEY).input().setVehicleMove(packet.getPosition().getX(), packet.getPosition().getY(),
                packet.getPosition().getZ(), packet.getYRot(), packet.getXRot());
    }

    // ------------------------------------------------------------------ Bedrock -> Java

    /**
     * Server-side moves. For the local player: {@code TELEPORT} (commands, plugins) is a full teleport
     * that the next input acknowledges ({@code HANDLE_TELEPORT}); {@code RESPAWN} is PowerNukkitX
     * reverting a refused move (too far, unloaded chunk): absolute position but the camera is kept;
     * {@code NORMAL} (e.g. the dimension change acknowledgement) only teleports when the position
     * really differs; {@code ONLY_HEAD_ROT} is sent while riding and ignored.
     */
    static void movePlayer(ProxySession session, MovePlayerPacket packet) {
        PlayerState player = session.get(PlayerState.KEY);
        if (!player.isLocal(packet.getPlayerRuntimeID())) {
            session.get(EntityTracker.KEY).onRemotePlayerMove(packet);
            return;
        }
        PositionMode mode = packet.getPositionMode() == null ? PositionMode.NORMAL : packet.getPositionMode();
        if (mode == PositionMode.ONLY_HEAD_ROT) {
            return;
        }
        Vector3f eye = packet.getPosition();
        double x = eye.getX();
        double y = eye.getY() - player.serverEyeHeight();
        double z = eye.getZ();
        float yaw = packet.getRotation().getY();
        float pitch = packet.getRotation().getX();
        switch (mode) {
            case TELEPORT -> player.teleport(x, y, z, yaw, pitch, PlayerState.TELEPORT_ACKNOWLEDGE);
            case RESPAWN -> player.teleport(x, y, z, yaw, pitch, PlayerState.TELEPORT_KEEP_ROTATION);
            default -> {
                Vector3f current = player.position();
                double dx = current.getX() - x, dy = current.getY() - y, dz = current.getZ() - z;
                if (dx * dx + dy * dy + dz * dz > SAME_POSITION_SQUARED) {
                    player.teleport(x, y, z, yaw, pitch, 0);
                }
            }
        }
    }

    /**
     * Movement corrections of servers with client rewind (not PowerNukkitX): the client is put back at
     * the server's position, keeping its camera and momentum so it does not rubber-band.
     */
    static void correctPrediction(ProxySession session, CorrectPlayerMovePredictionPacket packet) {
        if (packet.getPredictionType() != PredictionType.PLAYER) {
            return;
        }
        PlayerState player = session.get(PlayerState.KEY);
        long tick = player.input().clientTick();
        if (packet.getTick() > tick || packet.getTick() < tick - player.rewindHistorySize()) {
            return;
        }
        Vector3f eye = packet.getPos();
        player.teleport(eye.getX(), eye.getY() - PlayerState.EYE_HEIGHT, eye.getZ(), player.yaw(), player.pitch(),
                PlayerState.TELEPORT_KEEP_ROTATION | PlayerState.TELEPORT_KEEP_VELOCITY);
        player.input().setStatus(packet.isOnGround(), false);
    }

    /**
     * Input locks ({@code /inputpermission}, cutscenes). Java has no such thing: movement and jumping
     * are locked through attribute modifiers, dismounting by not forwarding it. Camera, sneak and
     * per-direction locks cannot be enforced on a Java client (the server still ignores those inputs).
     */
    static void inputLocks(ProxySession session, UpdateClientInputLocksPacket packet) {
        PlayerState player = session.get(PlayerState.KEY);
        Set<ClientInputLockComponent> locks = packet.getInputLockComponents();
        player.stats().setLocks(locks.contains(ClientInputLockComponent.MOVEMENT), locks.contains(ClientInputLockComponent.JUMP));
        player.setDismountLocked(locks.contains(ClientInputLockComponent.DISMOUNT));
    }
}
