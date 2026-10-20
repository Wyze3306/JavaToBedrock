package io.github.wyze3306.jtb.player;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.ClientPlayMode;
import org.cloudburstmc.protocol.bedrock.data.InputInteractionModel;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;

import java.util.EnumSet;
import java.util.Set;

/**
 * Movement emulation for server-authoritative movement ({@code SERVER_AUTHORITATIVE_V3}, the only mode
 * of PowerNukkitX): mirrors what the Java client did during one tick (position, rotation, keys,
 * sprint/sneak/swim/glide/fly state, block actions) and turns it into the single
 * {@link PlayerAuthInputPacket} a vanilla Bedrock client would send for that tick.
 * <p>
 * Conventions: positions given to this class are Java <b>feet</b> positions, the packet carries the eye
 * position ({@code +1.62}, which PowerNukkitX subtracts whatever the pose). Yaw is wrapped to
 * {@code [-180, 180)} and also used as head yaw. Edge flags ({@code START_*}/{@code STOP_*},
 * {@code *_PRESSED_RAW}/{@code *_RELEASED_RAW}) are derived by comparing with the previous tick, so
 * {@link #build()} must be called exactly once per emulated tick.
 * <p>
 * Allocation: the packet itself is new every tick (the protocol library encodes it asynchronously, so
 * it can not be reused); everything else is cached: immutable vectors until their value changes, move
 * vectors in a precomputed table, the resting velocity as a constant. Not thread-safe: session loop
 * only.
 */
final class AuthInputBuilder {

    /** Height of the eyes above the feet that Bedrock player positions include. */
    static final float EYE_HEIGHT = PlayerState.EYE_HEIGHT;
    /** PowerNukkitX boats are positioned this much above their feet (EntityBoat#getBaseOffset). */
    static final float BOAT_OFFSET = 0.37f;
    /** Java sneaking speed multiplier (attribute {@code minecraft:sneaking_speed} default). */
    private static final float SNEAK_FACTOR = 0.3f;
    private static final float DIAGONAL = (float) (1 / Math.sqrt(2));
    private static final int GLIDE_START_GRACE_TICKS = 10;
    /** Move vectors by {@code (strafe + 1) * 3 + (forward + 1)}; strafe +1 = left (Bedrock convention). */
    private static final Vector2f[] MOVE = new Vector2f[9];
    private static final Vector2f[] MOVE_SNEAKING = new Vector2f[9];
    /** {@link #velocity()} of a player standing still: gravity only. */
    private static final Vector3f RESTING_VELOCITY = Vector3f.from(0, -0.08f * 0.98f, 0);

    static {
        for (int strafe = -1; strafe <= 1; strafe++) {
            for (int forward = -1; forward <= 1; forward++) {
                float scale = strafe != 0 && forward != 0 ? DIAGONAL : 1;
                int index = (strafe + 1) * 3 + forward + 1;
                MOVE[index] = Vector2f.from(strafe * scale, forward * scale);
                MOVE_SNEAKING[index] = Vector2f.from(strafe * scale * SNEAK_FACTOR, forward * scale * SNEAK_FACTOR);
            }
        }
    }

    // Reported Java state (feet position)
    private double x, y, z;
    private double prevX, prevY, prevZ;
    private float yaw, pitch;
    private boolean onGround = true, prevOnGround = true;
    private boolean horizontalCollision;

    // Keys (ServerboundPlayerInput) this tick and the previous one
    private boolean forward, backward, left, right, jump, shift, sprintKey;
    private boolean prevJump, prevShift;

    // State as told to the server
    private boolean sneaking, sprinting, swimming, gliding, flying;
    private long glideStartTick;
    private boolean mayFly;
    private boolean feetInWater, eyesInWater;

    // Riding
    private long vehicleRuntimeId;
    private boolean vehiclePredicted;
    private boolean hasVehiclePosition;
    private double vehicleX, vehicleY, vehicleZ;
    private float vehicleYaw, vehiclePitch;

    // One-shot data for the next packet
    private final Set<PlayerAuthInputData> pending = EnumSet.noneOf(PlayerAuthInputData.class);
    private final ObjectArrayList<PlayerBlockActionData> blockActions = new ObjectArrayList<>();
    private long clientTick;

    // Cached immutable vectors
    private Vector3f positionVector;
    private Vector3f rotationVector;
    private Vector2f interactRotation;
    private Vector3f cameraOrientation;
    private Vector2f vehicleRotation;

    AuthInputBuilder() {
        rebuildPosition();
        rebuildRotation();
    }

    // ------------------------------------------------------------------ Java state

    /** A movement reported by the Java client (feet position). */
    void setPosition(double x, double y, double z) {
        if (x != this.x || y != this.y || z != this.z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.positionVector = null;
        }
    }

    /** A rotation reported by the Java client (unbounded Java yaw). */
    void setRotation(float yaw, float pitch) {
        float wrapped = wrapDegrees(yaw);
        if (wrapped != this.yaw || pitch != this.pitch) {
            this.yaw = wrapped;
            this.pitch = pitch;
            this.rotationVector = null;
        }
    }

    void setStatus(boolean onGround, boolean horizontalCollision) {
        this.onGround = onGround;
        this.horizontalCollision = horizontalCollision;
    }

    /** The player was moved by the server: no motion between the old and the new position. */
    void teleported(double x, double y, double z, float yaw, float pitch) {
        setPosition(x, y, z);
        setRotation(yaw, pitch);
        this.prevX = x;
        this.prevY = y;
        this.prevZ = z;
    }

    void setKeys(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean shift, boolean sprint) {
        this.forward = forward;
        this.backward = backward;
        this.left = left;
        this.right = right;
        this.jump = jump;
        this.shift = shift;
        this.sprintKey = sprint;
    }

    /** Java {@code START/STOP_SPRINTING} command. */
    void setSprinting(boolean sprinting) {
        if (sprinting != this.sprinting) {
            this.sprinting = sprinting;
            this.pending.remove(sprinting ? PlayerAuthInputData.STOP_SPRINTING : PlayerAuthInputData.START_SPRINTING);
            this.pending.add(sprinting ? PlayerAuthInputData.START_SPRINTING : PlayerAuthInputData.STOP_SPRINTING);
        }
    }

    /** Java {@code START_FALL_FLYING} command (elytra). */
    void startGliding() {
        if (!this.gliding) {
            this.gliding = true;
            this.glideStartTick = this.clientTick;
            this.pending.add(PlayerAuthInputData.START_GLIDING);
        }
    }

    /**
     * The Java client started or stopped flying. PowerNukkitX kicks a player that starts flying
     * without the {@code MAY_FLY} ability, so a start is refused here when flight is not allowed.
     *
     * @return {@code false} when the request was refused
     */
    boolean setFlying(boolean flying) {
        if (flying == this.flying) {
            return true;
        }
        if (flying && !this.mayFly) {
            return false;
        }
        this.flying = flying;
        this.pending.remove(flying ? PlayerAuthInputData.STOP_FLYING : PlayerAuthInputData.START_FLYING);
        this.pending.add(flying ? PlayerAuthInputData.START_FLYING : PlayerAuthInputData.STOP_FLYING);
        return true;
    }

    /** Abilities from the server: whether flight is allowed and whether the player flies. */
    void setServerFlight(boolean mayFly, boolean flying) {
        this.mayFly = mayFly;
        this.flying = flying && mayFly;
        if (!this.flying) {
            this.pending.remove(PlayerAuthInputData.START_FLYING);
        }
    }

    /**
     * The server says the player does not glide (elytra broken, landed...). Ignored right after the
     * proxy asked to start gliding, while the server's actor data may still predate that request.
     */
    void serverStoppedGliding() {
        if (this.gliding && this.clientTick - this.glideStartTick > GLIDE_START_GRACE_TICKS) {
            this.gliding = false;
        }
    }

    /** Water at the feet and at the eyes, for swimming (looked up by the caller every tick). */
    void setFluids(boolean feetInWater, boolean eyesInWater) {
        this.feetInWater = feetInWater;
        this.eyesInWater = eyesInWater;
    }

    /** Acknowledges a server {@code MovePlayer(TELEPORT)} in the next packet. */
    void handledTeleport() {
        this.pending.add(PlayerAuthInputData.HANDLE_TELEPORT);
    }

    /** The arm swung without hitting anything (PowerNukkitX plays the "attack no damage" sound). */
    void missedSwing() {
        this.pending.add(PlayerAuthInputData.MISSED_SWING);
    }

    /** The player started using an item (eating, drawing a bow...). */
    void startUsingItem() {
        this.pending.add(PlayerAuthInputData.START_USING_ITEM);
    }

    /**
     * Adds a block action ({@code playerBlockActions}) to the next packet. The action is only read when
     * the packet is encoded, so one that is never modified again may be added to several packets (the
     * per-tick continue-digging action).
     */
    void addBlockAction(PlayerBlockActionData action) {
        this.blockActions.add(action);
    }

    static PlayerBlockActionData blockAction(PlayerActionType type, Vector3i position, int face) {
        PlayerBlockActionData action = new PlayerBlockActionData();
        action.setPlayerActionType(type);
        action.setBlockPosition(position);
        action.setFacing(face);
        return action;
    }

    /** The player now rides {@code runtimeId}; boats are simulated by the client ("client predicted"). */
    void setVehicle(long runtimeId, boolean clientPredicted) {
        this.vehicleRuntimeId = runtimeId;
        this.vehiclePredicted = clientPredicted;
        this.hasVehiclePosition = false;
    }

    void clearVehicle() {
        this.vehicleRuntimeId = 0;
        this.vehiclePredicted = false;
        this.hasVehiclePosition = false;
    }

    /** Java {@code MoveVehicle}: where the client moved the vehicle it controls (feet position). */
    void setVehicleMove(double x, double y, double z, float yaw, float pitch) {
        this.vehicleX = x;
        this.vehicleY = y;
        this.vehicleZ = z;
        float bedrockYaw = wrapDegrees(yaw + 90);
        if (!this.hasVehiclePosition || bedrockYaw != this.vehicleYaw || pitch != this.vehiclePitch) {
            this.vehicleYaw = bedrockYaw;
            this.vehiclePitch = pitch;
            this.vehicleRotation = null;
        }
        this.hasVehiclePosition = true;
    }

    /** After a respawn: the server reset sprinting, sneaking, swimming, gliding and flight. */
    void reset() {
        this.sneaking = false;
        this.sprinting = false;
        this.swimming = false;
        this.gliding = false;
        this.flying = false;
        this.pending.clear();
        this.blockActions.clear();
    }

    // ------------------------------------------------------------------ getters

    double x() {
        return this.x;
    }

    double y() {
        return this.y;
    }

    double z() {
        return this.z;
    }

    float yaw() {
        return this.yaw;
    }

    float pitch() {
        return this.pitch;
    }

    boolean onGround() {
        return this.onGround;
    }

    boolean sneaking() {
        return this.sneaking;
    }

    boolean sprinting() {
        return this.sprinting;
    }

    boolean swimming() {
        return this.swimming;
    }

    boolean gliding() {
        return this.gliding;
    }

    boolean flying() {
        return this.flying;
    }

    boolean shift() {
        return this.shift;
    }

    boolean riding() {
        return this.vehicleRuntimeId != 0;
    }

    long vehicleRuntimeId() {
        return this.vehicleRuntimeId;
    }

    long clientTick() {
        return this.clientTick;
    }

    /** Eye position as reported to the server. */
    Vector3f eyePosition() {
        if (this.positionVector == null) {
            rebuildPosition();
        }
        return this.positionVector;
    }

    // ------------------------------------------------------------------ building

    /**
     * Builds the packet for the tick that just ended, then makes the current state the "previous
     * tick" and clears the one-shot data.
     */
    PlayerAuthInputPacket build() {
        Set<PlayerAuthInputData> data = this.pending;
        boolean riding = this.vehicleRuntimeId != 0;

        // Java crouches with shift unless flying, swimming or riding.
        boolean crouch = this.shift && !this.flying && !this.swimming && !riding;
        if (crouch != this.sneaking) {
            this.sneaking = crouch;
            data.add(crouch ? PlayerAuthInputData.START_SNEAKING : PlayerAuthInputData.STOP_SNEAKING);
        }
        // Java swims while sprinting in water (starting with the eyes under water).
        boolean swim = this.sprinting && this.feetInWater && (this.swimming || this.eyesInWater) && !riding && !this.flying;
        if (swim != this.swimming) {
            this.swimming = swim;
            data.add(swim ? PlayerAuthInputData.START_SWIMMING : PlayerAuthInputData.STOP_SWIMMING);
        }
        if (this.gliding && (this.onGround || this.feetInWater || this.flying || riding)) {
            this.gliding = false;
            data.add(PlayerAuthInputData.STOP_GLIDING);
        }
        if (this.prevOnGround && !this.onGround && this.y > this.prevY && this.jump && !riding && !this.flying) {
            data.add(PlayerAuthInputData.START_JUMPING);
        }

        data.add(PlayerAuthInputData.BLOCK_BREAKING_DELAY_ENABLED);
        if (this.onGround) {
            data.add(PlayerAuthInputData.VERTICAL_COLLISION);
        }
        if (this.horizontalCollision) {
            data.add(PlayerAuthInputData.HORIZONTAL_COLLISION);
        }
        if (this.forward) {
            data.add(PlayerAuthInputData.UP);
        }
        if (this.backward) {
            data.add(PlayerAuthInputData.DOWN);
        }
        if (this.left) {
            data.add(PlayerAuthInputData.LEFT);
        }
        if (this.right) {
            data.add(PlayerAuthInputData.RIGHT);
        }
        if (this.jump) {
            data.add(PlayerAuthInputData.JUMP_DOWN);
            data.add(PlayerAuthInputData.JUMPING);
            data.add(PlayerAuthInputData.WANT_UP);
            data.add(PlayerAuthInputData.JUMP_CURRENT_RAW);
            if (!this.prevJump) {
                data.add(PlayerAuthInputData.JUMP_PRESSED_RAW);
            }
        } else if (this.prevJump) {
            data.add(PlayerAuthInputData.JUMP_RELEASED_RAW);
        }
        if (this.shift) {
            data.add(PlayerAuthInputData.SNEAK_DOWN);
            data.add(PlayerAuthInputData.WANT_DOWN);
            data.add(PlayerAuthInputData.SNEAK_CURRENT_RAW);
            if (this.sneaking || this.flying) {
                // While flying, SNEAKING makes PowerNukkitX descend ("fly sneaking").
                data.add(PlayerAuthInputData.SNEAKING);
            }
            if (!this.prevShift) {
                data.add(PlayerAuthInputData.SNEAK_PRESSED_RAW);
            }
        } else if (this.prevShift) {
            data.add(PlayerAuthInputData.SNEAK_RELEASED_RAW);
        }
        if (this.sprintKey) {
            data.add(PlayerAuthInputData.SPRINT_DOWN);
        }
        if (this.sprinting || riding && this.sprintKey) {
            data.add(PlayerAuthInputData.SPRINTING);
        }
        if (!this.blockActions.isEmpty()) {
            data.add(PlayerAuthInputData.PERFORM_BLOCK_ACTIONS);
        }
        boolean predictedVehicle = this.vehiclePredicted && this.hasVehiclePosition;
        if (predictedVehicle) {
            data.add(PlayerAuthInputData.IS_IN_CLIENT_PREDICTED_VEHICLE);
        }

        int strafe = (this.left ? 1 : 0) - (this.right ? 1 : 0);
        int forwardMove = (this.forward ? 1 : 0) - (this.backward ? 1 : 0);
        int moveIndex = (strafe + 1) * 3 + forwardMove + 1;

        PlayerAuthInputPacket packet = new PlayerAuthInputPacket();
        if (this.rotationVector == null) {
            rebuildRotation();
        }
        packet.setPlayerRotation(this.rotationVector);
        if (predictedVehicle) {
            packet.setPosition(Vector3f.from(this.vehicleX, this.vehicleY + BOAT_OFFSET, this.vehicleZ));
            if (this.vehicleRotation == null) {
                this.vehicleRotation = Vector2f.from(this.vehiclePitch, this.vehicleYaw);
            }
            packet.setVehicleRotation(this.vehicleRotation);
            packet.setClientPredictedVehicle(this.vehicleRuntimeId);
        } else if (riding && this.hasVehiclePosition) {
            // Mounts the server moves from the inputs: the Java client only reports the mount's position.
            packet.setPosition(Vector3f.from(this.vehicleX, this.vehicleY + EYE_HEIGHT, this.vehicleZ));
        } else {
            packet.setPosition(eyePosition());
        }
        packet.setMoveVector(this.sneaking ? MOVE_SNEAKING[moveIndex] : MOVE[moveIndex]);
        packet.setAnalogMoveVector(MOVE[moveIndex]);
        packet.setRawMoveVector(MOVE[moveIndex]);
        packet.getInputData().addAll(data);
        packet.setInputMode(InputMode.MOUSE);
        packet.setPlayMode(ClientPlayMode.NORMAL);
        packet.setNewInteractionModel(InputInteractionModel.TOUCH);
        packet.setInteractRotation(this.interactRotation);
        packet.setCameraOrientation(this.cameraOrientation);
        packet.setClientTick(++this.clientTick);
        packet.setPosDelta(velocity());
        for (int i = 0, size = this.blockActions.size(); i < size; i++) {
            packet.getPlayerBlockActions().add(this.blockActions.get(i));
        }

        this.prevX = this.x;
        this.prevY = this.y;
        this.prevZ = this.z;
        this.prevOnGround = this.onGround;
        this.prevJump = this.jump;
        this.prevShift = this.shift;
        this.pending.clear();
        this.blockActions.clear();
        return packet;
    }

    /**
     * Velocity after this tick's movement, as the Bedrock client predicts it: the position delta with
     * ground friction and air drag applied, and gravity unless flying (ported from ViaBedrock,
     * GPL-3.0, {@code ClientPlayerPackets} CLIENT_TICK_END).
     */
    private Vector3f velocity() {
        double dx = this.x - this.prevX;
        double dy = this.y - this.prevY;
        double dz = this.z - this.prevZ;
        if (dx == 0 && dy == 0 && dz == 0) {
            return this.flying ? Vector3f.ZERO : RESTING_VELOCITY;
        }
        if (this.flying) {
            return Vector3f.from(dx, dy, dz);
        }
        double friction = this.onGround ? 0.6 * 0.98 : 0.98;
        return Vector3f.from(dx * friction * 0.91, (dy - 0.08) * 0.98, dz * friction * 0.91);
    }

    private void rebuildPosition() {
        this.positionVector = Vector3f.from(this.x, this.y + EYE_HEIGHT, this.z);
    }

    private void rebuildRotation() {
        this.rotationVector = Vector3f.from(this.pitch, this.yaw, this.yaw);
        this.interactRotation = Vector2f.from(this.pitch, this.yaw);
        double yawRad = Math.toRadians(this.yaw);
        double pitchRad = Math.toRadians(this.pitch);
        double cosPitch = Math.cos(pitchRad);
        this.cameraOrientation = Vector3f.from(-Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad), Math.cos(yawRad) * cosPitch);
    }

    /** Wraps an angle to {@code [-180, 180)}. */
    static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360;
        if (wrapped >= 180) {
            wrapped -= 360;
        } else if (wrapped < -180) {
            wrapped += 360;
        }
        return wrapped;
    }
}
