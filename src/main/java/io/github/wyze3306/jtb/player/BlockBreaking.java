package io.github.wyze3306.jtb.player;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.wyze3306.jtb.entity.EntityTracker;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import io.github.wyze3306.jtb.world.WorldState;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.LevelEvent;
import org.cloudburstmc.protocol.bedrock.data.LevelEventType;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.PlayerBlockActionData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.BlockBreakStage;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockDestructionPacket;
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
 * <b>Others</b>: Bedrock {@code LevelEvent} {@code BLOCK_START_BREAK} / {@code BLOCK_UPDATE_BREAK}
 * (data = 65535 / break ticks) / {@code BLOCK_STOP_BREAK} become Java {@code BlockDestruction}
 * stages, advanced every tick, under a proxy-allocated breaker id per position. The sound subsystem
 * owns {@code LevelEvent} and forwards these through {@link #onLevelEvent}.
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
    private final Long2ObjectMap<Crack> cracks = new Long2ObjectOpenHashMap<>();

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

    // ------------------------------------------------------------------ others' cracks

    /**
     * Sound subsystem: a Bedrock {@code LevelEvent}. Block cracking events are handled here.
     *
     * @return true when the event was a block cracking event (consumed)
     */
    public boolean onLevelEvent(LevelEventType type, Vector3f position, int data) {
        if (type != LevelEvent.BLOCK_START_BREAK && type != LevelEvent.BLOCK_UPDATE_BREAK && type != LevelEvent.BLOCK_STOP_BREAK) {
            return false;
        }
        int x = (int) Math.floor(position.getX());
        int y = (int) Math.floor(position.getY());
        int z = (int) Math.floor(position.getZ());
        if (this.digging && x == this.digPosition.getX() && y == this.digPosition.getY() && z == this.digPosition.getZ()) {
            // The Java client draws its own cracks.
            return true;
        }
        long key = pack(x, y, z);
        Crack crack = this.cracks.get(key);
        if (type == LevelEvent.BLOCK_STOP_BREAK) {
            if (crack != null) {
                this.cracks.remove(key);
                send(crack, BlockBreakStage.RESET);
            }
            return true;
        }
        if (crack == null) {
            crack = new Crack(this.session.get(EntityTracker.KEY).allocateJavaId(), Vector3i.from(x, y, z));
            this.cracks.put(key, crack);
        }
        if (type == LevelEvent.BLOCK_START_BREAK) {
            crack.progress = 0;
            crack.stage = -1;
        }
        crack.rate = Math.max(0, data) / 65535f;
        return true;
    }

    @Override
    public void tick() {
        if (this.cracks.isEmpty()) {
            return;
        }
        ObjectIterator<Long2ObjectMap.Entry<Crack>> iterator = Long2ObjectMaps.fastIterator(this.cracks);
        while (iterator.hasNext()) {
            Crack crack = iterator.next().getValue();
            crack.progress += crack.rate;
            if (crack.progress > MAX_PROGRESS) {
                iterator.remove();
                send(crack, BlockBreakStage.RESET);
                continue;
            }
            int stage = Math.min(9, (int) (crack.progress * 10));
            if (stage != crack.stage) {
                crack.stage = stage;
                send(crack, BlockBreakStage.STAGES[stage]);
            }
        }
    }

    private void send(Crack crack, BlockBreakStage stage) {
        this.session.sendJava(new ClientboundBlockDestructionPacket(crack.breakerId, crack.position, stage));
    }

    @Override
    public void close() {
        this.cracks.clear();
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
    }

    private static final class Crack {
        final int breakerId;
        final Vector3i position;
        float rate;
        float progress;
        int stage = -1;

        Crack(int breakerId, Vector3i position) {
            this.breakerId = breakerId;
            this.position = position;
        }
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
