package io.github.wyze3306.jtb.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import io.github.wyze3306.jtb.sound.SoundState;
import io.github.wyze3306.jtb.world.WorldState;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;

/**
 * Block breaking, both ways.
 * <p>
 * <b>Local player</b> (Java {@code PlayerAction} digging → auth input {@code playerBlockActions},
 * PowerNukkitX's server-authoritative breaking): START → {@code START_DESTROY_BLOCK}, then
 * {@code CONTINUE_DESTROY_BLOCK} in every following input while digging, FINISH →
 * {@code CONTINUE_DESTROY_BLOCK + PREDICT_DESTROY_BLOCK + ABORT_DESTROY_BLOCK} (the vanilla sequence
 * ViaBedrock uses), CANCEL → {@code ABORT_DESTROY_BLOCK}. The server never completes creative or
 * instant (destroy time 0) breaks by itself, and the Java client sends no FINISH for them, so those
 * get {@code START + PREDICT} in the same input.
 * <p>
 * Other players' cracks are drawn by {@link io.github.wyze3306.jtb.sound.BlockCracking}; the sound
 * subsystem skips the block the local player is digging ({@link #isDigging}).
 */
public final class BlockBreaking implements SessionState {

    public static final StateKey<BlockBreaking> KEY = StateKey.of("block-breaking", BlockBreaking::new);

    /** A crack nobody stopped is dropped after twice its expected duration. */
    private static final float MAX_PROGRESS = 2;

    private final ProxySession session;
    private boolean digging;
    private boolean startedThisTick;
    private Vector3i digPosition = Vector3i.ZERO;
    /** The {@code CONTINUE_DESTROY_BLOCK} sent every tick while digging, built once per block. */
    private PlayerBlockActionData continueAction;

    private BlockBreaking(ProxySession session) {
        this.session = session;
    }

    // ------------------------------------------------------------------ local digging

    /**
     * Java {@code PlayerAction} START/CANCEL/FINISH/CHANGE_DIGGING_DIRECTION. When breaking is not
     * allowed (no {@code MINE} ability, immutable world) the client's predicted break is undone.
     */
    void onDig(PlayerAction action, Vector3i position, Direction direction) {
        PlayerState player = this.session.get(PlayerState.KEY);
        AuthInputBuilder input = player.input();
        int face = direction.ordinal();
        switch (action) {
            case START_DIGGING -> {
                if (!player.abilities().canMine()) {
                    resync(position);
                    return;
                }
                input.addBlockAction(AuthInputBuilder.blockAction(PlayerActionType.START_DESTROY_BLOCK, position, face));
                int javaState = this.session.get(WorldState.KEY).javaBlockAt(position.getX(), position.getY(), position.getZ());
                if (player.javaGameMode() == GameMode.CREATIVE || InstantBreak.isInstant(javaState)) {
                    input.addBlockAction(AuthInputBuilder.blockAction(PlayerActionType.PREDICT_DESTROY_BLOCK, position, face));
                    this.session.get(SoundState.KEY).onLocalBlockBroken(position.getX(), position.getY(), position.getZ());
                    this.digging = false;
                } else {
                    this.digging = true;
                    this.startedThisTick = true;
                    this.digPosition = position;
                    this.continueAction = AuthInputBuilder.blockAction(PlayerActionType.CONTINUE_DESTROY_BLOCK, position, face);
                }
            }
            case CANCEL_DIGGING -> {
                input.addBlockAction(AuthInputBuilder.blockAction(PlayerActionType.ABORT_DESTROY_BLOCK, position, face));
                this.digging = false;
            }
            case FINISH_DIGGING -> {
                this.digging = false;
                if (!player.abilities().canMine()) {
                    resync(position);
                    return;
                }
                input.addBlockAction(AuthInputBuilder.blockAction(PlayerActionType.CONTINUE_DESTROY_BLOCK, position, face));
                input.addBlockAction(AuthInputBuilder.blockAction(PlayerActionType.PREDICT_DESTROY_BLOCK, position, face));
                input.addBlockAction(AuthInputBuilder.blockAction(PlayerActionType.ABORT_DESTROY_BLOCK, position, 0));
                this.session.get(SoundState.KEY).onLocalBlockBroken(position.getX(), position.getY(), position.getZ());
            }
            case CHANGE_DIGGING_DIRECTION -> {
                this.continueAction = AuthInputBuilder.blockAction(PlayerActionType.CONTINUE_DESTROY_BLOCK, position, face);
                input.addBlockAction(this.continueAction);
                this.startedThisTick = true;
            }
            default -> {
            }
        }
    }

    /** Right before each auth input: the per-tick {@code CONTINUE_DESTROY_BLOCK} while digging. */
    void beforeInput(AuthInputBuilder input) {
        if (this.digging && !this.startedThisTick) {
            input.addBlockAction(this.continueAction);
        }
        this.startedThisTick = false;
    }

    boolean digging() {
        return this.digging;
    }

    /** Undoes the Java client's predicted break with the block the proxy knows. */
    private void resync(Vector3i position) {
        WorldState world = this.session.get(WorldState.KEY);
        if (world.isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) {
            this.session.sendJava(new ClientboundBlockUpdatePacket(new BlockChangeEntry(position,
                    world.javaBlockAt(position.getX(), position.getY(), position.getZ()))));
        }
    }

    /** Stops digging (death, respawn). */
    void reset() {
        this.digging = false;
    }

    /**
     * Whether the local player is digging this block: its Java client draws the cracks itself, so the
     * server's cracking level events for it are not forwarded.
     */
    public boolean isDigging(int x, int y, int z) {
        return this.digging && x == this.digPosition.getX() && y == this.digPosition.getY() && z == this.digPosition.getZ();
    }

    /**
     * Java block states broken instantly in survival ({@code mappings/player/instant_break.json},
     * generated by {@code tools/gen_player_mappings.py}).
     */
    static final class InstantBreak {

        private static final BitSet STATES = load();

        private InstantBreak() {
        }

        static boolean isInstant(int javaStateId) {
            return javaStateId > 0 && STATES.get(javaStateId);
        }

        private static BitSet load() {
            try (InputStream in = InstantBreak.class.getResourceAsStream("/mappings/player/instant_break.json")) {
                if (in == null) {
                    throw new IllegalStateException("Missing mappings/player/instant_break.json");
                }
                JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                BitSet states = new BitSet(json.get("stateCount").getAsInt());
                for (var element : json.getAsJsonArray("ranges")) {
                    JsonArray range = element.getAsJsonArray();
                    states.set(range.get(0).getAsInt(), range.get(1).getAsInt() + 1);
                }
                return states;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
