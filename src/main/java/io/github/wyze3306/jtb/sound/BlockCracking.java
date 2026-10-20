package io.github.wyze3306.jtb.sound;

import io.github.wyze3306.jtb.entity.EntityTracker;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.BlockBreakStage;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockDestructionPacket;

/**
 * Block crack animations ({@code ClientboundBlockDestruction}) driven by Bedrock's cracking level events.
 * <p>
 * Bedrock sends {@code BLOCK_START_BREAK} / {@code BLOCK_UPDATE_BREAK} with the progress made per tick
 * ({@code 65535 / ticks to break}) and lets the client animate; Java wants each of the ten stages. This
 * state advances the progress every proxy tick and sends a stage when it changes. Every animation gets its own
 * Java breaker id (allocated from the entity id space) so several can run at once without being mistaken for
 * the local player's own client-side animation.
 * <p>
 * CONTRACT (owner: sound). The player module may drive an animation itself (e.g. the server-authoritative
 * breaking of the local player) with {@link #start}, {@link #update} and {@link #stop}. All methods run on the
 * session event loop.
 */
public final class BlockCracking implements SessionState {

    public static final StateKey<BlockCracking> KEY = StateKey.of("block-cracking", BlockCracking::new);

    /** Bedrock's full progress. */
    private static final int FULL = 65535;
    /** Animations are dropped if the server never stops them (one minute). */
    private static final int MAX_TICKS = 1200;

    private static final class Crack {
        final Vector3i position;
        final int breakerId;
        int progress;
        int perTick;
        int stage = -1;
        int age;

        Crack(Vector3i position, int breakerId) {
            this.position = position;
            this.breakerId = breakerId;
        }
    }

    private final ProxySession session;
    private final Long2ObjectMap<Crack> cracks = new Long2ObjectOpenHashMap<>();

    private BlockCracking(ProxySession session) {
        this.session = session;
    }

    /** Starts (or restarts) the animation at a block; {@code progressPerTick} is in 1/65535 of the full break. */
    public void start(Vector3i position, int progressPerTick) {
        Crack crack = crack(position);
        crack.progress = 0;
        crack.perTick = Math.max(0, progressPerTick);
        crack.age = 0;
        send(crack, 0);
    }

    /** Changes the speed of a running animation (another tool...); starts one when none runs. */
    public void update(Vector3i position, int progressPerTick) {
        Crack crack = this.cracks.get(key(position));
        if (crack == null) {
            start(position, progressPerTick);
        } else {
            crack.perTick = Math.max(0, progressPerTick);
        }
    }

    /** Ends the animation at a block. */
    public void stop(Vector3i position) {
        Crack crack = this.cracks.remove(key(position));
        if (crack != null) {
            this.session.sendJava(new ClientboundBlockDestructionPacket(crack.breakerId, crack.position, BlockBreakStage.RESET));
        }
    }

    /** Ends every animation (dimension change). */
    public void clear() {
        for (Crack crack : this.cracks.values()) {
            this.session.sendJava(new ClientboundBlockDestructionPacket(crack.breakerId, crack.position, BlockBreakStage.RESET));
        }
        this.cracks.clear();
    }

    public boolean isCracking(Vector3i position) {
        return this.cracks.containsKey(key(position));
    }

    @Override
    public void tick() {
        if (this.cracks.isEmpty()) {
            return;
        }
        ObjectIterator<Crack> iterator = this.cracks.values().iterator();
        while (iterator.hasNext()) {
            Crack crack = iterator.next();
            if (++crack.age > MAX_TICKS) {
                iterator.remove();
                this.session.sendJava(new ClientboundBlockDestructionPacket(crack.breakerId, crack.position, BlockBreakStage.RESET));
                continue;
            }
            crack.progress = Math.min(FULL, crack.progress + crack.perTick);
            send(crack, (int) (crack.progress * 10L / (FULL + 1)));
        }
    }

    private Crack crack(Vector3i position) {
        return this.cracks.computeIfAbsent(key(position),
                k -> new Crack(position, this.session.get(EntityTracker.KEY).allocateJavaId()));
    }

    private void send(Crack crack, int stage) {
        if (stage != crack.stage) {
            crack.stage = stage;
            this.session.sendJava(new ClientboundBlockDestructionPacket(crack.breakerId, crack.position, BlockBreakStage.STAGES[stage]));
        }
    }

    private static long key(Vector3i position) {
        return ((long) (position.getX() & 0x3FFFFFF) << 38) | ((long) (position.getZ() & 0x3FFFFFF) << 12) | (position.getY() & 0xFFF);
    }
}
