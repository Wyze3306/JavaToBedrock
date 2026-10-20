package io.github.wyze3306.jtb.world;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.world.chunk.ChunkLayout;
import io.github.wyze3306.jtb.world.chunk.ChunkSection;
import io.github.wyze3306.jtb.world.chunk.JavaChunkEncoder;
import io.github.wyze3306.jtb.world.light.LightEngine;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLightUpdatePacket;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The per-session serial worker that owns the light of the loaded chunks and builds chunk packets.
 * Tasks run one at a time, in submission order, on the shared worker pool; results go back to the
 * session loop with {@code session.execute}, which keeps their order. Light changes of chunks the
 * client already has are sent as light updates when the session loop calls {@link #flushLight()}.
 * <p>
 * The session loop must submit, for a chunk, {@link #load} before {@link #build}, and every block
 * change of a loaded chunk through {@link #blocksChanged}: the worker's copy of the sections is the
 * one packets are built from.
 */
final class ChunkWorker implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkWorker.class);

    /** Receives the worker's output on the session loop. */
    interface Output {
        void chunkBuilt(long token, ClientboundLevelChunkWithLightPacket packet, long nanos);

        void lightUpdated(int x, int z, ClientboundLightUpdatePacket packet);
    }

    private final ProxySession session;
    private final Executor executor;
    private final Output output;
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final boolean fullBright;
    private final byte[] lightTable;
    private volatile boolean closed;
    /** Session loop: whether light may have changed since the last {@link #flushLight}. */
    private boolean lightChanged;
    // Worker-confined state.
    private ChunkLayout layout;
    private @Nullable LightEngine light;
    private int biomeRegistrySize;
    private final Long2ObjectOpenHashMap<ChunkSection[]> sections = new Long2ObjectOpenHashMap<>();
    private final ChunkStats stats;

    ChunkWorker(ProxySession session, Executor executor, Output output, boolean fullBright, byte[] lightTable, ChunkStats stats) {
        this.session = session;
        this.stats = stats;
        this.executor = executor;
        this.output = output;
        this.fullBright = fullBright;
        this.lightTable = lightTable;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private void submit(Runnable task) {
        if (this.closed) {
            return;
        }
        this.tasks.add(task);
        if (this.scheduled.compareAndSet(false, true)) {
            try {
                this.executor.execute(this);
            } catch (RejectedExecutionException e) {
                this.scheduled.set(false);
            }
        }
    }

    @Override
    public void run() {
        try {
            Runnable task;
            int done = 0;
            while (!this.closed && (task = this.tasks.poll()) != null) {
                try {
                    task.run();
                } catch (Throwable t) {
                    LOGGER.error("[{}] Chunk worker task failed", this.session.getName(), t);
                }
                if (++done == 256) {
                    break; // let other sessions use this thread
                }
            }
        } finally {
            this.scheduled.set(false);
            if (!this.tasks.isEmpty() && !this.closed && this.scheduled.compareAndSet(false, true)) {
                try {
                    this.executor.execute(this);
                } catch (RejectedExecutionException e) {
                    this.scheduled.set(false);
                }
            }
        }
    }

    /**
     * Sends the light changes of chunks the client has, as one light update per chunk (called once
     * per tick by the session loop, like a vanilla server broadcasts light changes).
     */
    void flushLight() {
        if (!this.lightChanged) {
            return;
        }
        this.lightChanged = false;
        submit(() -> {
            LightEngine engine = this.light;
            if (engine == null) {
                return;
            }
            engine.drainUpdates((x, z, data) -> {
                ClientboundLightUpdatePacket packet = new ClientboundLightUpdatePacket(x, z, data.getSkyYMask(),
                        data.getBlockYMask(), data.getEmptySkyYMask(), data.getEmptyBlockYMask(),
                        data.getSkyUpdates(), data.getBlockUpdates());
                this.session.execute(() -> this.output.lightUpdated(x, z, packet));
            });
        });
    }

    /** Starts a new world (join, dimension change): every chunk is forgotten. */
    void reset(ChunkLayout layout, int biomeRegistrySize) {
        submit(() -> {
            this.layout = layout;
            this.biomeRegistrySize = biomeRegistrySize;
            this.sections.clear();
            this.light = this.fullBright ? null : new LightEngine(layout, this.lightTable);
        });
    }

    void load(int x, int z, @Nullable ChunkSection[] sections) {
        this.lightChanged = true;
        ChunkSection[] copy = sections.clone();
        submit(() -> {
            this.sections.put(key(x, z), copy);
            if (this.light != null) {
                long start = System.nanoTime();
                this.light.load(x, z, copy);
                this.stats.lit(System.nanoTime() - start);
            }
        });
    }

    void unload(int x, int z) {
        submit(() -> {
            this.sections.remove(key(x, z));
            if (this.light != null) {
                this.light.unload(x, z);
            }
        });
    }

    /**
     * Block changes in one column: replaced sections ({@code indices}/{@code sections}) and the cells
     * whose Java state changed ({@code x | z << 4 | (y - minY) << 8}).
     */
    void blocksChanged(int x, int z, int[] indices, ChunkSection[] sections, int[] cells, int[] oldStates, int[] newStates) {
        this.lightChanged = true;
        submit(() -> {
            ChunkSection[] column = this.sections.get(key(x, z));
            if (column == null) {
                return;
            }
            for (int i = 0; i < indices.length; i++) {
                column[indices[i]] = sections[i];
            }
            if (this.light != null && cells.length > 0) {
                this.light.blocksChanged(x, z, cells, oldStates, newStates);
            }
        });
    }

    /** Encodes a loaded chunk with its current light; {@code token} comes back with the packet. */
    void build(long token, int x, int z, int[][] biomes, BlockEntityInfo[] blockEntities) {
        submit(() -> {
            ChunkSection[] column = this.sections.get(key(x, z));
            if (column == null) {
                return;
            }
            long start = System.nanoTime();
            byte[] data = JavaChunkEncoder.encodeSections(column, biomes, this.biomeRegistrySize);
            LightUpdateData lightData;
            if (this.light != null) {
                lightData = this.light.lightData(x, z);
                this.light.markSent(x, z);
            } else {
                lightData = LightEngine.fullBright(this.layout.skyLight(), this.layout.javaSections() + 2);
            }
            ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(x, z, data,
                    JavaChunkEncoder.heightmaps(column), blockEntities, lightData);
            long nanos = System.nanoTime() - start;
            this.session.execute(() -> this.output.chunkBuilt(token, packet, nanos));
        });
    }

    void close() {
        this.closed = true;
        this.tasks.clear();
    }
}
