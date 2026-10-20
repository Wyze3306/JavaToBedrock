package io.github.wyze3306.jtb.player;

import io.github.wyze3306.jtb.item.TestItems;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.world.ItemFrame;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.GameType;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.cloudburstmc.protocol.bedrock.data.PlayerActionType.ABORT_DESTROY_BLOCK;
import static org.cloudburstmc.protocol.bedrock.data.PlayerActionType.CONTINUE_DESTROY_BLOCK;
import static org.cloudburstmc.protocol.bedrock.data.PlayerActionType.PREDICT_DESTROY_BLOCK;
import static org.cloudburstmc.protocol.bedrock.data.PlayerActionType.START_DESTROY_BLOCK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Java attacks on item frame entities as left clicks on the Bedrock frame block. */
class ItemFramePunchTest {

    private static final Vector3i FRAME = Vector3i.from(5, 64, -7);

    private BlockBreaking breaking;
    private PlayerState player;

    @BeforeEach
    void setUp() {
        ProxySession session = TestItems.session().session();
        this.breaking = session.get(BlockBreaking.KEY);
        this.player = session.get(PlayerState.KEY);
    }

    /** The block actions of the next auth input. */
    private List<PlayerActionType> nextInput() {
        this.breaking.beforeInput(this.player.input());
        PlayerAuthInputPacket packet = this.player.input().build();
        List<PlayerActionType> types = new ArrayList<>();
        for (PlayerBlockActionData action : packet.getPlayerBlockActions()) {
            types.add(action.getPlayerActionType());
            assertEquals(FRAME, action.getBlockPosition());
        }
        return types;
    }

    @Test
    void frameWithAnItemIsClickedNotBroken() {
        this.breaking.punchFrame(new ItemFrame(FRAME, 2, false, true));
        assertEquals(List.of(START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK), nextInput());
        assertFalse(this.breaking.isDigging(FRAME.getX(), FRAME.getY(), FRAME.getZ()));
        assertEquals(List.of(), nextInput());
    }

    @Test
    void emptyFrameBreaksAtOnceInCreative() {
        this.player.setGameType(GameType.CREATIVE);
        this.breaking.punchFrame(new ItemFrame(FRAME, 3, true, false));
        assertEquals(List.of(START_DESTROY_BLOCK, PREDICT_DESTROY_BLOCK), nextInput());
        assertEquals(List.of(), nextInput());
    }

    @Test
    void emptyFrameIsDugForItsBreakTimeInSurvival() {
        ItemFrame frame = new ItemFrame(FRAME, 2, false, false);
        this.breaking.punchFrame(frame);
        assertEquals(List.of(START_DESTROY_BLOCK), nextInput());
        assertTrue(this.breaking.isDigging(FRAME.getX(), FRAME.getY(), FRAME.getZ()));
        // Clicking again while it breaks changes nothing.
        this.breaking.punchFrame(frame);
        for (int tick = 1; tick < 8; tick++) {
            assertEquals(List.of(CONTINUE_DESTROY_BLOCK), nextInput(), "tick " + tick);
        }
        // 0.375 s by hand: finished after 8 inputs, like the end of a Java dig.
        assertEquals(List.of(CONTINUE_DESTROY_BLOCK, PREDICT_DESTROY_BLOCK, ABORT_DESTROY_BLOCK), nextInput());
        assertFalse(this.breaking.isDigging(FRAME.getX(), FRAME.getY(), FRAME.getZ()));
        assertEquals(List.of(), nextInput());
    }

    @Test
    void serverCrackSpeedTimesTheFrameDig() {
        this.breaking.punchFrame(new ItemFrame(FRAME, 2, false, false));
        assertEquals(List.of(START_DESTROY_BLOCK), nextInput());
        assertEquals(List.of(CONTINUE_DESTROY_BLOCK), nextInput());
        // The server says 20 ticks (e.g. jumping): counted from now.
        this.breaking.onServerCrack(FRAME.getX(), FRAME.getY(), FRAME.getZ(), 65535 / 20);
        for (int tick = 0; tick < 19; tick++) {
            assertEquals(List.of(CONTINUE_DESTROY_BLOCK), nextInput(), "tick " + tick);
        }
        assertEquals(List.of(CONTINUE_DESTROY_BLOCK, PREDICT_DESTROY_BLOCK, ABORT_DESTROY_BLOCK), nextInput());
    }

    @Test
    void javaDiggingReplacesTheFrameDig() {
        this.breaking.punchFrame(new ItemFrame(FRAME, 2, false, false));
        assertEquals(List.of(START_DESTROY_BLOCK), nextInput());
        Vector3i block = FRAME.add(0, -1, 0);
        this.breaking.onDig(PlayerAction.CANCEL_DIGGING, block, Direction.UP);
        this.breaking.beforeInput(this.player.input());
        PlayerAuthInputPacket packet = this.player.input().build();
        assertEquals(1, packet.getPlayerBlockActions().size());
        assertEquals(ABORT_DESTROY_BLOCK, packet.getPlayerBlockActions().getFirst().getPlayerActionType());
        for (int tick = 0; tick < 10; tick++) {
            this.breaking.beforeInput(this.player.input());
            assertTrue(this.player.input().build().getPlayerBlockActions().isEmpty());
        }
    }
}
