package io.github.wyze3306.jtb.player;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.data.ClientPlayMode;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthInputBuilderTest {

    private static final float EPSILON = 1e-5f;

    private static AuthInputBuilder standingAt(double x, double y, double z) {
        AuthInputBuilder input = new AuthInputBuilder();
        input.teleported(x, y, z, 0, 0);
        input.setStatus(true, false);
        return input;
    }

    private static void walk(AuthInputBuilder input, boolean forward, boolean backward, boolean left, boolean right,
                             boolean jump, boolean shift, boolean sprint) {
        input.setKeys(forward, backward, left, right, jump, shift, sprint);
    }

    @Test
    void idleInputIsCompleteAndEncodes() throws Exception {
        AuthInputBuilder input = standingAt(10.5, 64, -3.25);
        PlayerAuthInputPacket packet = input.build();

        assertEquals(Vector3f.from(10.5, 64 + PlayerState.EYE_HEIGHT, -3.25), packet.getPosition());
        assertEquals(Vector2f.ZERO, packet.getMoveVector());
        assertEquals(InputMode.MOUSE, packet.getInputMode());
        assertEquals(ClientPlayMode.NORMAL, packet.getPlayMode());
        assertEquals(1, packet.getClientTick());
        assertTrue(packet.getInputData().contains(BLOCK_BREAKING_DELAY_ENABLED));
        assertTrue(packet.getInputData().contains(VERTICAL_COLLISION));
        assertFalse(packet.getInputData().contains(PERFORM_BLOCK_ACTIONS));
        assertNull(packet.getClientPredictedVehicle());

        PlayerAuthInputPacket decoded = roundTrip(packet);
        assertEquals(packet.getPosition(), decoded.getPosition());
        assertEquals(packet.getPlayerRotation(), decoded.getPlayerRotation());
        assertEquals(packet.getInputData(), decoded.getInputData());
        assertEquals(1, decoded.getClientTick());

        assertEquals(2, input.build().getClientTick());
    }

    @Test
    void moveVectorFollowsKeys() {
        AuthInputBuilder input = standingAt(0, 64, 0);
        walk(input, true, false, false, false, false, false, false);
        PlayerAuthInputPacket forward = input.build();
        assertEquals(Vector2f.from(0, 1), forward.getMoveVector());
        assertTrue(forward.getInputData().contains(UP));

        walk(input, true, false, true, false, false, false, false);
        PlayerAuthInputPacket diagonal = input.build();
        float d = (float) (1 / Math.sqrt(2));
        assertEquals(d, diagonal.getMoveVector().getX(), EPSILON, "left is +x");
        assertEquals(d, diagonal.getMoveVector().getY(), EPSILON);
        assertTrue(diagonal.getInputData().containsAll(EnumSet.of(UP, LEFT)));
        assertEquals(diagonal.getMoveVector(), diagonal.getRawMoveVector());

        walk(input, true, true, true, true, false, false, false);
        assertEquals(Vector2f.ZERO, input.build().getMoveVector(), "opposite keys cancel out like on Java");
    }

    @Test
    void sneakIsEdgeTriggered() {
        AuthInputBuilder input = standingAt(0, 64, 0);
        walk(input, true, false, false, false, false, true, false);
        PlayerAuthInputPacket start = input.build();
        assertTrue(start.getInputData().containsAll(EnumSet.of(START_SNEAKING, SNEAKING, SNEAK_DOWN, SNEAK_PRESSED_RAW, SNEAK_CURRENT_RAW)));
        assertEquals(0.3f, start.getMoveVector().getY(), EPSILON, "sneaking slows the move vector");
        assertEquals(1f, start.getRawMoveVector().getY(), EPSILON, "raw move vector is not scaled");

        PlayerAuthInputPacket held = input.build();
        assertFalse(held.getInputData().contains(START_SNEAKING));
        assertTrue(held.getInputData().contains(SNEAKING));

        walk(input, false, false, false, false, false, false, false);
        PlayerAuthInputPacket stop = input.build();
        assertTrue(stop.getInputData().containsAll(EnumSet.of(STOP_SNEAKING, SNEAK_RELEASED_RAW)));
        assertFalse(stop.getInputData().contains(SNEAKING));
        assertFalse(input.build().getInputData().contains(STOP_SNEAKING));
    }

    @Test
    void sprintAndJump() {
        AuthInputBuilder input = standingAt(0, 64, 0);
        input.setSprinting(true);
        walk(input, true, false, false, false, false, false, true);
        PlayerAuthInputPacket start = input.build();
        assertTrue(start.getInputData().containsAll(EnumSet.of(START_SPRINTING, SPRINTING, SPRINT_DOWN)));
        assertFalse(input.build().getInputData().contains(START_SPRINTING));

        walk(input, true, false, false, false, true, false, true);
        input.setPosition(0, 64.42, 0.3);
        input.setStatus(false, false);
        PlayerAuthInputPacket jump = input.build();
        assertTrue(jump.getInputData().containsAll(EnumSet.of(START_JUMPING, JUMP_DOWN, JUMPING, JUMP_PRESSED_RAW)));
        assertFalse(jump.getInputData().contains(VERTICAL_COLLISION));

        walk(input, true, false, false, false, false, false, true);
        PlayerAuthInputPacket release = input.build();
        assertTrue(release.getInputData().contains(JUMP_RELEASED_RAW));
        assertFalse(release.getInputData().contains(START_JUMPING));

        input.setSprinting(false);
        PlayerAuthInputPacket stop = input.build();
        assertTrue(stop.getInputData().contains(STOP_SPRINTING));
        assertFalse(stop.getInputData().contains(SPRINTING));
    }

    @Test
    void flyingNeedsPermission() {
        AuthInputBuilder input = standingAt(0, 64, 0);
        assertFalse(input.setFlying(true), "PowerNukkitX kicks a player that flies without MAY_FLY");
        assertFalse(input.build().getInputData().contains(START_FLYING));

        input.setServerFlight(true, false);
        assertTrue(input.setFlying(true));
        walk(input, false, false, false, false, false, true, false);
        PlayerAuthInputPacket flying = input.build();
        assertTrue(flying.getInputData().contains(START_FLYING));
        assertTrue(flying.getInputData().contains(SNEAKING), "SNEAKING makes the flying player descend");
        assertFalse(flying.getInputData().contains(START_SNEAKING), "Java does not crouch while flying");

        input.setServerFlight(false, false);
        assertFalse(input.flying(), "revoked flight stops flying");
    }

    @Test
    void oneShotsAreClearedAfterOneTick() {
        AuthInputBuilder input = standingAt(0, 64, 0);
        input.handledTeleport();
        input.missedSwing();
        input.addBlockAction(AuthInputBuilder.blockAction(PlayerActionType.START_DESTROY_BLOCK, Vector3i.from(1, 63, 2), 1));
        input.addBlockAction(AuthInputBuilder.blockAction(PlayerActionType.PREDICT_DESTROY_BLOCK, Vector3i.from(1, 63, 2), 1));
        PlayerAuthInputPacket packet = input.build();
        assertTrue(packet.getInputData().containsAll(EnumSet.of(HANDLE_TELEPORT, MISSED_SWING, PERFORM_BLOCK_ACTIONS)));
        assertEquals(2, packet.getPlayerBlockActions().size());
        assertEquals(PlayerActionType.PREDICT_DESTROY_BLOCK, packet.getPlayerBlockActions().get(1).getPlayerActionType());

        PlayerAuthInputPacket next = input.build();
        assertFalse(next.getInputData().contains(HANDLE_TELEPORT));
        assertFalse(next.getInputData().contains(PERFORM_BLOCK_ACTIONS));
        assertTrue(next.getPlayerBlockActions().isEmpty());
    }

    @Test
    void rotationIsWrappedAndCached() throws Exception {
        AuthInputBuilder input = standingAt(0, 64, 0);
        input.setRotation(370, 12);
        PlayerAuthInputPacket first = input.build();
        assertEquals(Vector3f.from(12, 10, 10), first.getPlayerRotation(), "pitch, yaw, head yaw");
        assertEquals(Vector2f.from(12, 10), first.getInteractRotation());
        assertSame(first.getPlayerRotation(), input.build().getPlayerRotation(), "unchanged rotation allocates nothing");

        input.setRotation(-190, 0);
        assertEquals(170, input.build().getPlayerRotation().getY(), EPSILON);
        input.setRotation(180, 0);
        assertEquals(-180, input.build().getPlayerRotation().getY(), EPSILON);

        input.setRotation(90, 0);
        PlayerAuthInputPacket west = input.build();
        assertEquals(-1, west.getCameraOrientation().getX(), EPSILON, "yaw 90 looks towards -X");
        assertEquals(0, west.getCameraOrientation().getZ(), EPSILON);
        roundTrip(west);
    }

    @Test
    void restingVelocityIsShared() {
        AuthInputBuilder input = standingAt(0, 64, 0);
        PlayerAuthInputPacket first = input.build();
        assertSame(first.getPosDelta(), input.build().getPosDelta());
        assertSame(first.getPosition(), input.build().getPosition());
        input.setPosition(0.2, 64, 0);
        assertTrue(input.build().getPosDelta().getX() > 0);
    }

    @Test
    void swimmingNeedsSprintInWater() {
        AuthInputBuilder input = standingAt(0, 60, 0);
        input.setSprinting(true);
        input.setFluids(true, true);
        PlayerAuthInputPacket start = input.build();
        assertTrue(start.getInputData().contains(START_SWIMMING));
        assertTrue(input.swimming());
        input.setFluids(true, false);
        assertFalse(input.build().getInputData().contains(STOP_SWIMMING), "keeps swimming with the head out");
        input.setFluids(false, false);
        assertTrue(input.build().getInputData().contains(STOP_SWIMMING));
    }

    @Test
    void glidingStopsOnGround() {
        AuthInputBuilder input = standingAt(0, 100, 0);
        input.setStatus(false, false);
        input.startGliding();
        assertTrue(input.build().getInputData().contains(START_GLIDING));
        input.setStatus(true, false);
        assertTrue(input.build().getInputData().contains(STOP_GLIDING));
        assertFalse(input.gliding());
    }

    @Test
    void boatIsClientPredicted() throws Exception {
        AuthInputBuilder input = standingAt(0, 62, 0);
        input.setVehicle(42, true);
        PlayerAuthInputPacket beforeMove = input.build();
        assertNull(beforeMove.getClientPredictedVehicle(), "no vehicle position yet");

        input.setVehicleMove(5, 62, 6, 0, 0);
        walk(input, true, false, false, false, false, true, false);
        PlayerAuthInputPacket packet = input.build();
        assertTrue(packet.getInputData().contains(IS_IN_CLIENT_PREDICTED_VEHICLE));
        assertEquals(42L, packet.getClientPredictedVehicle());
        assertEquals(Vector3f.from(5, 62 + AuthInputBuilder.BOAT_OFFSET, 6), packet.getPosition());
        assertEquals(90, packet.getVehicleRotation().getY(), EPSILON, "Bedrock boat yaw = Java yaw + 90");
        assertFalse(packet.getInputData().contains(START_SNEAKING), "shift dismounts, it does not crouch");
        roundTrip(packet);

        input.clearVehicle();
        assertNull(input.build().getClientPredictedVehicle());
    }

    private static PlayerAuthInputPacket roundTrip(PlayerAuthInputPacket packet) throws Exception {
        BedrockCodec codec = Bedrock_v2225.CODEC;
        BedrockCodecHelper helper = codec.createHelper();
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.tryEncode(helper, buffer, packet);
            int id = codec.getPacketDefinition(PlayerAuthInputPacket.class).getId();
            return (PlayerAuthInputPacket) codec.tryDecode(helper, buffer, id);
        } finally {
            buffer.release();
        }
    }

    @Test
    void everyInputFlagOrdinalFitsTheWireFormat() {
        // The v2225 serializer writes each flag as its ordinal: the enum must not be reordered.
        assertEquals(37, PlayerAuthInputData.HANDLE_TELEPORT.ordinal());
        assertEquals(42, PlayerAuthInputData.START_FLYING.ordinal());
        assertEquals(48, PlayerAuthInputData.BLOCK_BREAKING_DELAY_ENABLED.ordinal());
    }
}
