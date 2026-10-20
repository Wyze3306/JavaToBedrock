package io.github.wyze3306.jtb.world;

import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.block.DerivedStates;
import io.github.wyze3306.jtb.block.JavaBlockStates;
import io.github.wyze3306.jtb.config.ProxyConfig;
import io.github.wyze3306.jtb.player.PlayerState;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.world.blockentity.BlockEntityTranslator;
import io.github.wyze3306.jtb.world.chunk.BedrockChunkDecoder;
import io.github.wyze3306.jtb.world.chunk.BiomeTable;
import io.github.wyze3306.jtb.world.chunk.BlockStorage;
import io.github.wyze3306.jtb.world.chunk.ChunkLayout;
import io.github.wyze3306.jtb.world.chunk.ChunkSection;
import io.github.wyze3306.jtb.world.chunk.DerivedStatePass;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.payload.chunk.SubChunkPacketData;
import org.cloudburstmc.protocol.bedrock.data.payload.chunk.SubChunkRequestResult;
import org.cloudburstmc.protocol.bedrock.data.payload.common.DimensionType;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkRequestPacket;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchStartPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLightUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSectionBlocksUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetChunkCacheCenterPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetChunkCacheRadiusPacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The chunks of the current dimension, from Bedrock packets to Java packets. Lives on the session
 * loop; the heavy work runs elsewhere:
 * <ol>
 *     <li>a Bedrock chunk is decoded on the worker pool (any thread, in parallel) into Java sections,
 *     biomes and block entities, then stored here;</li>
 *     <li>the session's {@link ChunkWorker} lights it (light also flows into the loaded neighbours);</li>
 *     <li>once its 8 neighbours are loaded too, or will not come (outside the server's view), or after
 *     a short wait, the worker builds the Java packet, so a chunk rarely needs a light update later;</li>
 *     <li>built packets go to the client nearest first, in vanilla-like batches paced by the client's
 *     {@code ChunkBatchReceived} answers.</li>
 * </ol>
 * Block changes update the stored sections (copy on write), the worker's light, and the client; a
 * change the packet already built does not show is resent right after it. Stored Java states include
 * the properties Java derives from neighbours ({@link DerivedStates}): derived within the column while
 * decoding, across column borders when a neighbour loads, and around every block change.
 */
final class ChunkManager implements ChunkWorker.Output {

    private static final long NEIGHBOUR_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(400);
    private static final long SUB_CHUNK_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    /** Vanilla PlayerChunkSender pacing. */
    private static final float START_CHUNKS_PER_TICK = 9f;
    private static final int MAX_UNACKNOWLEDGED_BATCHES = 10;

    private final ProxySession session;
    private final WorldState world;
    private final BlockMapper mapper;
    private final boolean fullBright;
    private final Long2ObjectOpenHashMap<ChunkColumn> columns = new Long2ObjectOpenHashMap<>();
    private final ObjectArrayList<ChunkColumn> waiting = new ObjectArrayList<>();
    private final ObjectArrayList<ChunkColumn> queued = new ObjectArrayList<>();
    private final ChunkStats stats = new ChunkStats();
    private final ChunkWorker worker;
    private ChunkLayout layout;
    private BiomeTable biomes;
    private long nextToken = 1;
    private boolean closed;

    private boolean viewKnown;
    private int viewX;
    private int viewZ;
    private int viewRadius = 8;
    private int cacheCenterX = Integer.MIN_VALUE;
    private int cacheCenterZ = Integer.MIN_VALUE;
    private int playerChunkX = Integer.MIN_VALUE;
    private int playerChunkZ = Integer.MIN_VALUE;

    private float desiredChunksPerTick = START_CHUNKS_PER_TICK;
    private float batchQuota;
    private int unacknowledgedBatches;
    private int maxUnacknowledgedBatches = 1;

    ChunkManager(ProxySession session, WorldState world, ChunkLayout layout, BiomeTable biomes) {
        this.session = session;
        this.world = world;
        this.mapper = session.get(BlockMapper.KEY);
        this.fullBright = session.config().world().lighting() == ProxyConfig.Lighting.FULL_BRIGHT;
        this.layout = layout;
        this.biomes = biomes;
        this.worker = new ChunkWorker(session, session.proxy().workers(), this, this.fullBright, this.mapper.lightTable(), this.stats);
        this.worker.reset(layout, biomes.registrySize());
    }

    /** A new world (dimension change): every chunk is dropped; the client drops its own on respawn. */
    void reset(ChunkLayout newLayout, BiomeTable newBiomes) {
        this.columns.clear();
        this.waiting.clear();
        this.queued.clear();
        this.layout = newLayout;
        this.biomes = newBiomes;
        this.viewKnown = false;
        this.cacheCenterX = Integer.MIN_VALUE;
        this.playerChunkX = Integer.MIN_VALUE;
        this.worker.reset(newLayout, newBiomes.registrySize());
    }

    /** Custom biomes changed the table (normally before the first chunk; chunks already decoded keep theirs). */
    void setBiomes(BiomeTable newBiomes) {
        this.biomes = newBiomes;
        this.worker.setBiomeRegistrySize(newBiomes.registrySize());
    }

    ChunkLayout layout() {
        return this.layout;
    }

    ChunkStats stats() {
        return this.stats;
    }

    // ------------------------------------------------------------------ queries

    @Nullable ChunkColumn column(int chunkX, int chunkZ) {
        return this.columns.get(ChunkColumn.key(chunkX, chunkZ));
    }

    boolean isLoaded(int chunkX, int chunkZ) {
        ChunkColumn column = column(chunkX, chunkZ);
        return column != null && column.isDecoded();
    }

    private @Nullable ChunkSection section(int x, int y, int z) {
        ChunkColumn column = column(x >> 4, z >> 4);
        if (column == null || column.sections == null) {
            return null;
        }
        int index = this.layout.sectionIndex(y);
        return index < 0 ? null : column.sections[index];
    }

    int javaBlockAt(int x, int y, int z) {
        ChunkSection section = section(x, y, z);
        return section == null ? JavaBlockStates.AIR : section.javaAt(BlockStorage.index(x & 15, y & 15, z & 15));
    }

    int bedrockBlockAt(int x, int y, int z, int layer) {
        ChunkSection section = section(x, y, z);
        int air = this.mapper.bedrockAir();
        return section == null ? air : section.bedrockAt(BlockStorage.index(x & 15, y & 15, z & 15), layer, air);
    }

    @Nullable NbtMap bedrockBlockEntityAt(int x, int y, int z) {
        ChunkColumn column = column(x >> 4, z >> 4);
        return column == null ? null : column.bedrockBlockEntities.get(BlockEntityTranslator.packLocal(x, y, z, this.layout.javaMinY()));
    }

    // ------------------------------------------------------------------ level chunks

    void onLevelChunk(LevelChunkPacket packet) {
        int x = packet.getChunkX();
        int z = packet.getChunkZ();
        discard(this.columns.remove(ChunkColumn.key(x, z)));
        ChunkColumn column = new ChunkColumn(x, z, this.nextToken++);
        this.columns.put(ChunkColumn.key(x, z), column);
        if (packet.isCacheEnabled()) {
            this.session.logger().warn("[{}] Bedrock server sent a cached chunk although the blob cache is off", this.session.getName());
        }
        boolean request = packet.isClientNeedsToRequestSubChunks();
        if (request) {
            Integer limit = packet.getClientRequestSubChunkLimit();
            int count = limit == null || limit < 0 ? this.layout.bedrockSections() : Math.min(limit + 1, this.layout.bedrockSections());
            for (int i = 0; i < count; i++) {
                column.requestedSections.add(this.layout.bedrockMinSection() + i);
            }
        }
        int subChunks = request ? 0 : packet.getSubChunksCount();
        ByteBuf data = packet.getSerializedChunkData().retainedSlice();
        ChunkLayout chunkLayout = this.layout;
        BiomeTable biomeTable = this.biomes;
        BlockMapper blockMapper = this.mapper;
        this.session.proxy().workers().execute(() -> {
            long start = System.nanoTime();
            Decoded decoded;
            try {
                BedrockChunkDecoder.LevelChunkData chunk = BedrockChunkDecoder.decodeLevelChunk(data, subChunks, chunkLayout, blockMapper, biomeTable);
                decoded = finishDecode(x, z, chunk.sections(), chunk.biomes(), chunk.blockEntities(), chunkLayout, blockMapper, true);
            } catch (RuntimeException e) {
                this.session.logger().warn("[{}] Cannot decode chunk {},{}: {}", this.session.getName(), x, z, e.toString());
                decoded = finishDecode(x, z, new ChunkSection[chunkLayout.javaSections()], fill(chunkLayout), List.of(), chunkLayout,
                        blockMapper, false);
            } finally {
                data.release();
            }
            Decoded result = decoded;
            long nanos = System.nanoTime() - start;
            this.session.execute(() -> onDecoded(column, result, nanos));
        });
    }

    private static int[][] fill(ChunkLayout layout) {
        int[][] biomes = new int[layout.javaSections()][];
        for (int i = 0; i < biomes.length; i++) {
            biomes[i] = new int[]{layout.defaultBiome()};
        }
        return biomes;
    }

    /** Decoded column data, ready to be stored. */
    private record Decoded(@Nullable ChunkSection[] sections, int[][] biomes, Int2ObjectOpenHashMap<NbtMap> blockEntities,
                           IntOpenHashSet javaBlockEntities) {
    }

    /**
     * Worker side: indexes the block entities, applies the states they imply, derives the states that
     * depend on neighbours within the column ({@code derive}) and finds the Java block entity positions.
     */
    private static Decoded finishDecode(int chunkX, int chunkZ, @Nullable ChunkSection[] sections, int[][] biomes,
                                        List<NbtMap> blockEntities, ChunkLayout layout, BlockMapper mapper, boolean derive) {
        int minY = layout.javaMinY();
        Int2ObjectOpenHashMap<NbtMap> bedrock = new Int2ObjectOpenHashMap<>(blockEntities.size());
        for (NbtMap nbt : blockEntities) {
            int x = nbt.getInt("x");
            int y = nbt.getInt("y");
            int z = nbt.getInt("z");
            int index = layout.sectionIndex(y);
            if (x >> 4 != chunkX || z >> 4 != chunkZ || index < 0) {
                continue;
            }
            bedrock.put(BlockEntityTranslator.packLocal(x, y, z, minY), nbt);
            ChunkSection section = sections[index];
            if (section != null) {
                int i = BlockStorage.index(x & 15, y & 15, z & 15);
                int base = mapper.toJava(section.layer0().get(i), section.layer1() == null ? mapper.bedrockAir() : section.layer1().get(i));
                int fixed = BlockEntityTranslator.javaState(base, nbt, x, z, mapper);
                if (fixed != section.javaAt(i)) {
                    sections[index] = section.withJava(i, fixed);
                }
            }
        }
        if (derive) {
            DerivedStatePass.deriveColumn(sections, layout, mapper);
        }
        IntOpenHashSet java = new IntOpenHashSet();
        for (int s = 0; s < sections.length; s++) {
            ChunkSection section = sections[s];
            if (section == null || !section.java().paletteContains(JavaBlockStates::hasBlockEntity)) {
                continue;
            }
            for (int i = 0; i < BlockStorage.SIZE; i++) {
                if (JavaBlockStates.hasBlockEntity(section.javaAt(i))) {
                    java.add((i & 0xFF) | (((s << 4) | (i >> 8)) << 8));
                }
            }
        }
        return new Decoded(sections, biomes, bedrock, java);
    }

    private void onDecoded(ChunkColumn column, Decoded decoded, long nanos) {
        if (this.closed || this.columns.get(ChunkColumn.key(column.x, column.z)) != column) {
            return;
        }
        this.stats.decoded(nanos);
        column.sections = decoded.sections();
        column.biomes = decoded.biomes();
        column.bedrockBlockEntities.putAll(decoded.blockEntities());
        column.javaBlockEntities.addAll(decoded.javaBlockEntities());
        if (!column.requestedSections.isEmpty()) {
            column.stage = ChunkColumn.Stage.REQUESTING;
            column.requestedAt = System.nanoTime();
            requestSubChunks(column);
            return;
        }
        loaded(column);
    }

    /**
     * The column has all its blocks: apply what arrived meanwhile, derive the states across its borders,
     * light it, wait for neighbours.
     */
    private void loaded(ChunkColumn column) {
        if (column.stage == ChunkColumn.Stage.REQUESTING) {
            DerivedStatePass.deriveColumn(column.sections, this.layout, this.mapper);
        }
        ChangeSet changes = new ChangeSet();
        if (!column.pendingBlocks.isEmpty()) {
            Edit edit = new Edit();
            for (int[] change : column.pendingBlocks) {
                edit.set(change[0], change[1], change[2], change[3], change[4]);
            }
            column.pendingBlocks.clear();
            edit.finish(changes);
        }
        deriveBorders(column, changes);
        flushChanges(changes);
        column.stage = ChunkColumn.Stage.WAITING;
        column.readyAt = System.nanoTime() + NEIGHBOUR_WAIT_NANOS;
        this.worker.load(column.x, column.z, column.sections);
        for (NbtMap nbt : column.pendingBlockEntities) {
            onBlockEntity(Vector3i.from(nbt.getInt("x"), nbt.getInt("y"), nbt.getInt("z")), nbt);
        }
        column.pendingBlockEntities.clear();
        this.waiting.add(column);
        long now = System.nanoTime();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                ChunkColumn neighbour = column(column.x + dx, column.z + dz);
                if (neighbour != null && neighbour.stage == ChunkColumn.Stage.WAITING && isReady(neighbour, now)) {
                    build(neighbour);
                }
            }
        }
    }

    private boolean isReady(ChunkColumn column, long now) {
        if (this.fullBright || now >= column.readyAt) {
            return true;
        }
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                ChunkColumn neighbour = column(column.x + dx, column.z + dz);
                if (neighbour != null ? !neighbour.isDecoded() : isExpected(column.x + dx, column.z + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether the server will send this chunk (inside its view circle). */
    private boolean isExpected(int x, int z) {
        if (!this.viewKnown) {
            return true;
        }
        int dx = x - this.viewX;
        int dz = z - this.viewZ;
        return dx * dx + dz * dz <= this.viewRadius * this.viewRadius;
    }

    private void build(ChunkColumn column) {
        this.waiting.remove(column);
        column.stage = ChunkColumn.Stage.BUILDING;
        this.worker.build(column.token, column.x, column.z, column.biomes, blockEntityInfos(column));
    }

    private BlockEntityInfo[] blockEntityInfos(ChunkColumn column) {
        BlockEntityInfo[] infos = new BlockEntityInfo[column.javaBlockEntities.size()];
        int n = 0;
        int minY = this.layout.javaMinY();
        for (int packed : column.javaBlockEntities) {
            int lx = packed & 15;
            int lz = (packed >> 4) & 15;
            int y = (packed >> 8) + minY;
            int state = javaBlockAt((column.x << 4) | lx, y, (column.z << 4) | lz);
            BlockEntityType type = JavaBlockStates.blockEntityType(state);
            if (type != null) {
                infos[n++] = new BlockEntityInfo(lx, y, lz, type,
                        BlockEntityTranslator.toJava(type, state, column.bedrockBlockEntities.get(packed)));
            }
        }
        return n == infos.length ? infos : Arrays.copyOf(infos, n);
    }

    @Override
    public void chunkBuilt(long token, ClientboundLevelChunkWithLightPacket packet, long nanos) {
        ChunkColumn column = column(packet.getX(), packet.getZ());
        if (this.closed || column == null || column.token != token || column.stage != ChunkColumn.Stage.BUILDING) {
            return;
        }
        this.stats.built(nanos);
        column.packet = packet;
        column.stage = ChunkColumn.Stage.QUEUED;
        this.queued.add(column);
    }

    @Override
    public void lightUpdated(int x, int z, ClientboundLightUpdatePacket packet) {
        ChunkColumn column = column(x, z);
        if (this.closed || column == null) {
            return;
        }
        if (column.stage == ChunkColumn.Stage.SENT) {
            this.session.sendJava(packet);
        } else if (column.stage == ChunkColumn.Stage.QUEUED) {
            column.after.add(packet);
        }
    }

    // ------------------------------------------------------------------ sub-chunk request mode

    private void requestSubChunks(ChunkColumn column) {
        SubChunkRequestPacket request = new SubChunkRequestPacket();
        request.setDimensionType(DimensionType.from(this.world.dimension()));
        request.setCenterPos(Vector3i.from(column.x, 0, column.z));
        for (int sectionY : column.requestedSections) {
            request.getSubChunkPosOffsetList().add(Vector3i.from(0, sectionY, 0));
        }
        this.session.sendBedrock(request);
    }

    void onSubChunk(SubChunkPacket packet) {
        if (packet.getDimensionType() != null && packet.getDimensionType().getValue() != this.world.dimension()) {
            return;
        }
        Vector3i center = packet.getCenterPos();
        List<SubChunkPacketData> entries = packet.getSubChunkData();
        List<int[]> positions = new ArrayList<>(entries.size());
        List<ByteBuf> payloads = new ArrayList<>(entries.size());
        for (SubChunkPacketData entry : entries) {
            Vector3i offset = entry.getSubChunkPosOffset();
            int[] position = {center.getX() + offset.getX(), center.getY() + offset.getY(), center.getZ() + offset.getZ()};
            ChunkColumn column = column(position[0], position[2]);
            if (column == null || column.stage != ChunkColumn.Stage.REQUESTING || !column.requestedSections.contains(position[1])) {
                continue;
            }
            positions.add(position);
            SubChunkRequestResult result = entry.getSubChunkRequestResult();
            ByteBuf data = entry.getSerializedSubChunk();
            payloads.add(result == SubChunkRequestResult.SUCCESS && data != null ? data.retainedSlice() : null);
        }
        if (positions.isEmpty()) {
            return;
        }
        BlockMapper blockMapper = this.mapper;
        this.session.proxy().workers().execute(() -> {
            List<BedrockChunkDecoder.SubChunkData> decoded = new ArrayList<>(positions.size());
            for (int i = 0; i < positions.size(); i++) {
                ByteBuf data = payloads.get(i);
                if (data == null) {
                    decoded.add(null);
                    continue;
                }
                try {
                    decoded.add(BedrockChunkDecoder.decodeSubChunk(data, positions.get(i)[1], blockMapper));
                } catch (RuntimeException e) {
                    decoded.add(null);
                } finally {
                    data.release();
                }
            }
            this.session.execute(() -> onSubChunksDecoded(positions, decoded));
        });
    }

    private void onSubChunksDecoded(List<int[]> positions, List<BedrockChunkDecoder.SubChunkData> decoded) {
        if (this.closed) {
            return;
        }
        for (int i = 0; i < positions.size(); i++) {
            int[] position = positions.get(i);
            ChunkColumn column = column(position[0], position[2]);
            if (column == null || column.stage != ChunkColumn.Stage.REQUESTING || !column.requestedSections.remove(position[1])) {
                continue;
            }
            BedrockChunkDecoder.SubChunkData data = decoded.get(i);
            int index = this.layout.javaIndexOfBedrockSection(position[1]);
            if (data != null && index >= 0) {
                ChunkSection[] sections = column.sections;
                sections[index] = data.section().isEmpty() ? null : data.section();
                Decoded extra = finishDecode(column.x, column.z, sections, column.biomes, data.blockEntities(), this.layout,
                        this.mapper, false);
                column.bedrockBlockEntities.putAll(extra.blockEntities());
                column.javaBlockEntities.addAll(extra.javaBlockEntities());
            }
            if (column.requestedSections.isEmpty()) {
                loaded(column);
            }
        }
    }

    // ------------------------------------------------------------------ view

    void onPublisherUpdate(int blockX, int blockZ, int radiusBlocks) {
        this.viewKnown = true;
        this.viewX = blockX >> 4;
        this.viewZ = blockZ >> 4;
        this.viewRadius = Math.max(1, radiusBlocks >> 4);
        setCacheCenter(this.viewX, this.viewZ);
        int before = this.columns.size();
        unloadOutside();
        if (this.session.logger().isDebugEnabled() && before != this.columns.size()) {
            this.session.logger().debug("[{}] View {},{} r{}: {} chunks dropped", this.session.getName(), this.viewX, this.viewZ,
                    this.viewRadius, before - this.columns.size());
        }
    }

    void onRadiusUpdated(int radius) {
        this.viewRadius = radius;
        this.session.sendJava(new ClientboundSetChunkCacheRadiusPacket(radius));
        if (this.viewKnown) {
            unloadOutside();
        }
    }

    private void setCacheCenter(int x, int z) {
        if (x != this.cacheCenterX || z != this.cacheCenterZ) {
            this.cacheCenterX = x;
            this.cacheCenterZ = z;
            this.session.sendJava(new ClientboundSetChunkCacheCenterPacket(x, z));
        }
    }

    /** Drops chunks the Bedrock client would forget: beyond the view circle (with a little margin). */
    private void unloadOutside() {
        int limit = (this.viewRadius + 2) * (this.viewRadius + 2);
        List<ChunkColumn> remove = null;
        for (ChunkColumn column : this.columns.values()) {
            int dx = column.x - this.viewX;
            int dz = column.z - this.viewZ;
            if (dx * dx + dz * dz > limit) {
                if (remove == null) {
                    remove = new ArrayList<>();
                }
                remove.add(column);
            }
        }
        if (remove != null) {
            for (ChunkColumn column : remove) {
                this.columns.remove(ChunkColumn.key(column.x, column.z));
                discard(column);
            }
        }
    }

    private void discard(@Nullable ChunkColumn column) {
        if (column == null) {
            return;
        }
        if (column.stage == ChunkColumn.Stage.SENT) {
            this.session.sendJava(new ClientboundForgetLevelChunkPacket(column.x, column.z));
        }
        if (column.isDecoded()) {
            this.worker.unload(column.x, column.z);
        }
        this.waiting.remove(column);
        this.queued.remove(column);
        column.packet = null;
    }

    // ------------------------------------------------------------------ blocks

    /** Applies one Bedrock block change ({@code UpdateBlockPacket}). */
    void setBlock(int x, int y, int z, int layer, int runtimeId) {
        BlockBatch batch = new BlockBatch();
        batch.add(x, y, z, layer, runtimeId);
        setBlocks(batch, null);
    }

    /** Bedrock block changes of one packet: x, y, z, layer and runtime id per change. */
    static final class BlockBatch {

        private final IntArrayList values = new IntArrayList();

        void add(int x, int y, int z, int layer, int runtimeId) {
            this.values.add(x);
            this.values.add(y);
            this.values.add(z);
            this.values.add(layer);
            this.values.add(runtimeId);
        }

        int size() {
            return this.values.size() / 5;
        }
    }

    /**
     * Applies Bedrock block changes: each touched section is copied once, the light worker gets one
     * message per chunk, and the client gets block updates (grouped in {@code javaChanges} when given,
     * else sent right away), the states derived around the changes included.
     */
    void setBlocks(BlockBatch batch, @Nullable ChangeSet javaChanges) {
        ChangeSet changes = javaChanges != null ? javaChanges : new ChangeSet();
        int[] v = batch.values.elements();
        Edit edit = new Edit();
        ChunkColumn column = null;
        for (int i = 0, n = batch.size(); i < n; i++) {
            int x = v[i * 5];
            int y = v[i * 5 + 1];
            int z = v[i * 5 + 2];
            int layer = v[i * 5 + 3];
            int runtimeId = v[i * 5 + 4];
            if (layer < 0 || layer > 1) {
                continue;
            }
            if (column == null || column.x != x >> 4 || column.z != z >> 4) {
                column = column(x >> 4, z >> 4);
                if (column == null) {
                    continue;
                }
            }
            if (column.stage == ChunkColumn.Stage.DECODING || column.stage == ChunkColumn.Stage.REQUESTING) {
                column.pendingBlocks.add(new int[]{x, y, z, layer, runtimeId});
                continue;
            }
            edit.set(x, y, z, layer, runtimeId);
        }
        edit.finish(changes);
        if (javaChanges == null) {
            flushChanges(changes);
        }
    }

    /** Derives the blocks on both sides of the borders between a column just loaded and its loaded neighbours. */
    private void deriveBorders(ChunkColumn column, ChangeSet changes) {
        Edit edit = null;
        for (int d = 0; d < 4; d++) {
            int dx = d == 0 ? 1 : d == 1 ? -1 : 0;
            int dz = d == 2 ? 1 : d == 3 ? -1 : 0;
            ChunkColumn neighbour = column(column.x + dx, column.z + dz);
            if (neighbour == null || neighbour.sections == null) {
                continue;
            }
            edit = collectBorder(column, dx, dz, edit);
            edit = collectBorder(neighbour, -dx, -dz, edit);
        }
        if (edit != null) {
            edit.finish(changes);
        }
    }

    /** Queues for derivation the blocks of {@code column} on its side facing {@code dx, dz} that read the next column. */
    private @Nullable Edit collectBorder(ChunkColumn column, int dx, int dz, @Nullable Edit edit) {
        int minY = this.layout.javaMinY();
        for (int s = 0; s < column.sections.length; s++) {
            ChunkSection section = column.sections[s];
            if (section == null || !section.java().paletteContains(DerivedStates::readsNeighbourColumns)) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                for (int t = 0; t < 16; t++) {
                    int lx = dx > 0 ? 15 : dx < 0 ? 0 : t;
                    int lz = dz > 0 ? 15 : dz < 0 ? 0 : t;
                    if (DerivedStates.readsNeighbourColumns(section.javaAt(BlockStorage.index(lx, y, lz)))) {
                        if (edit == null) {
                            edit = new Edit();
                        }
                        edit.deriveAt((column.x << 4) | lx, minY + (s << 4) + y, (column.z << 4) | lz);
                    }
                }
            }
        }
        return edit;
    }

    private static long packPosition(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /**
     * Changes to the stored columns: Bedrock blocks first, then the Java states derived around them
     * ({@link DerivedStates}, reading the edited state); {@link #finish} publishes each column once.
     */
    private final class Edit implements DerivedStates.View {

        private final Long2ObjectOpenHashMap<ColumnEdit> columns = new Long2ObjectOpenHashMap<>(4);
        private final LongOpenHashSet derive = new LongOpenHashSet();
        private @Nullable ColumnEdit last;

        /** The edit of a column with decoded sections, created on first use; null otherwise. */
        private @Nullable ColumnEdit columnEdit(int chunkX, int chunkZ, boolean create) {
            ColumnEdit edit = this.last;
            if (edit != null && edit.column.x == chunkX && edit.column.z == chunkZ) {
                return edit;
            }
            long key = ChunkColumn.key(chunkX, chunkZ);
            edit = this.columns.get(key);
            if (edit == null) {
                ChunkColumn column = create ? column(chunkX, chunkZ) : null;
                if (column == null || column.sections == null) {
                    return null;
                }
                edit = new ColumnEdit(column);
                this.columns.put(key, edit);
            }
            this.last = edit;
            return edit;
        }

        /** A Bedrock block change in a decoded column; the blocks around it are derived again by {@link #finish}. */
        void set(int x, int y, int z, int layer, int runtimeId) {
            ColumnEdit edit = columnEdit(x >> 4, z >> 4, true);
            if (edit != null && edit.set(x, y, z, layer, runtimeId)) {
                deriveAround(x, y, z);
            }
        }

        /** Queues the blocks whose derived states may read {@code x, y, z}. */
        private void deriveAround(int x, int y, int z) {
            this.derive.add(packPosition(x, y, z));
            for (int dy = -1; dy <= 1; dy++) {
                if (dy != 0) {
                    this.derive.add(packPosition(x, y + dy, z));
                }
                this.derive.add(packPosition(x + 1, y + dy, z));
                this.derive.add(packPosition(x - 1, y + dy, z));
                this.derive.add(packPosition(x, y + dy, z + 1));
                this.derive.add(packPosition(x, y + dy, z - 1));
            }
        }

        void deriveAt(int x, int y, int z) {
            this.derive.add(packPosition(x, y, z));
        }

        @Override
        public int javaAt(int x, int y, int z) {
            int index = ChunkManager.this.layout.sectionIndex(y);
            if (index < 0) {
                return JavaBlockStates.AIR;
            }
            ColumnEdit edit = columnEdit(x >> 4, z >> 4, false);
            return edit != null ? edit.javaAt(index, BlockStorage.index(x & 15, y & 15, z & 15)) : javaBlockAt(x, y, z);
        }

        void finish(ChangeSet changes) {
            LongIterator positions = this.derive.iterator();
            while (positions.hasNext()) {
                long p = positions.nextLong();
                int x = (int) (p >> 38);
                int z = (int) (p << 26 >> 38);
                int y = (int) (p << 52 >> 52);
                int current = javaAt(x, y, z);
                if (!DerivedStates.isDerived(current)) {
                    continue;
                }
                ColumnEdit edit = columnEdit(x >> 4, z >> 4, true);
                if (edit == null) {
                    continue;
                }
                int index = ChunkManager.this.layout.sectionIndex(y);
                int state = DerivedStates.derive(edit.rawAt(index, BlockStorage.index(x & 15, y & 15, z & 15)), this, x, y, z);
                if (state != current) {
                    edit.setJava(x, y, z, state);
                }
            }
            this.derive.clear();
            for (ColumnEdit edit : this.columns.values()) {
                edit.finish(changes);
            }
        }
    }

    /** Pending changes of one column: section editors and the cells whose Java state changed. */
    private final class ColumnEdit {

        final ChunkColumn column;
        final ChunkSection.Editor[] editors;
        final IntArrayList cells = new IntArrayList();
        final IntArrayList oldStates = new IntArrayList();
        final IntArrayList newStates = new IntArrayList();
        /** Cell -> its position in the lists, so a cell changed twice is published once. */
        private final Int2IntOpenHashMap slots = new Int2IntOpenHashMap();

        ColumnEdit(ChunkColumn column) {
            this.column = column;
            this.editors = new ChunkSection.Editor[column.sections.length];
            this.slots.defaultReturnValue(-1);
        }

        private ChunkSection.Editor editor(int index) {
            ChunkSection.Editor editor = this.editors[index];
            if (editor == null) {
                ChunkSection section = this.column.sections[index];
                editor = (section == null ? ChunkSection.air(ChunkManager.this.mapper.bedrockAir()) : section).edit(ChunkManager.this.mapper);
                this.editors[index] = editor;
            }
            return editor;
        }

        int javaAt(int index, int i) {
            ChunkSection.Editor editor = this.editors[index];
            if (editor != null) {
                return editor.javaAt(i);
            }
            ChunkSection section = this.column.sections[index];
            return section == null ? JavaBlockStates.AIR : section.javaAt(i);
        }

        /** The Java state the Bedrock layers map to, before derivation. */
        int rawAt(int index, int i) {
            ChunkSection.Editor editor = this.editors[index];
            if (editor != null) {
                return ChunkManager.this.mapper.toJava(editor.bedrockAt(i, 0), editor.bedrockAt(i, 1));
            }
            ChunkSection section = this.column.sections[index];
            int air = ChunkManager.this.mapper.bedrockAir();
            return section == null ? JavaBlockStates.AIR : ChunkManager.this.mapper.toJava(section.bedrockAt(i, 0, air), section.bedrockAt(i, 1, air));
        }

        /** Applies a Bedrock block change; returns whether the Java state changed. */
        boolean set(int x, int y, int z, int layer, int runtimeId) {
            int index = ChunkManager.this.layout.sectionIndex(y);
            if (index < 0) {
                return false;
            }
            ChunkSection.Editor editor = editor(index);
            int i = BlockStorage.index(x & 15, y & 15, z & 15);
            int oldJava = editor.javaAt(i);
            int oldBedrock = editor.bedrockAt(i, 0);
            editor.set(i, layer, runtimeId);
            int newJava = editor.javaAt(i);
            int packed = BlockEntityTranslator.packLocal(x, y, z, ChunkManager.this.layout.javaMinY());
            NbtMap nbt = this.column.bedrockBlockEntities.get(packed);
            if (nbt != null && layer == 0 && oldBedrock != runtimeId) {
                if (JavaBlockStates.blockEntityType(oldJava) != JavaBlockStates.blockEntityType(newJava)) {
                    this.column.bedrockBlockEntities.remove(packed);
                } else {
                    int fixed = BlockEntityTranslator.javaState(newJava, nbt, x, z, ChunkManager.this.mapper);
                    if (fixed != newJava) {
                        editor.setJava(i, fixed);
                        newJava = fixed;
                    }
                }
            }
            record(packed, oldJava, newJava);
            return oldJava != newJava;
        }

        /** Changes only the Java state of a block (a derived state). */
        void setJava(int x, int y, int z, int state) {
            int index = ChunkManager.this.layout.sectionIndex(y);
            ChunkSection.Editor editor = editor(index);
            int i = BlockStorage.index(x & 15, y & 15, z & 15);
            int old = editor.javaAt(i);
            editor.setJava(i, state);
            record(BlockEntityTranslator.packLocal(x, y, z, ChunkManager.this.layout.javaMinY()), old, state);
        }

        private void record(int packed, int oldState, int newState) {
            int slot = this.slots.get(packed);
            if (slot >= 0) {
                this.newStates.set(slot, newState);
            } else if (oldState != newState) {
                this.slots.put(packed, this.cells.size());
                this.cells.add(packed);
                this.oldStates.add(oldState);
                this.newStates.add(newState);
            }
        }

        void finish(ChangeSet javaChanges) {
            IntArrayList indices = new IntArrayList();
            List<ChunkSection> sections = new ArrayList<>();
            for (int s = 0; s < this.editors.length; s++) {
                if (this.editors[s] != null) {
                    ChunkSection section = this.editors[s].build();
                    this.column.sections[s] = section.isEmpty() ? null : section;
                    indices.add(s);
                    sections.add(section);
                }
            }
            // Drop cells changed and changed back.
            int kept = 0;
            for (int k = 0; k < this.cells.size(); k++) {
                if (this.oldStates.getInt(k) != this.newStates.getInt(k)) {
                    this.cells.set(kept, this.cells.getInt(k));
                    this.oldStates.set(kept, this.oldStates.getInt(k));
                    this.newStates.set(kept, this.newStates.getInt(k));
                    kept++;
                }
            }
            this.cells.size(kept);
            this.oldStates.size(kept);
            this.newStates.size(kept);
            if (!indices.isEmpty() && this.column.stage != ChunkColumn.Stage.DECODING && this.column.stage != ChunkColumn.Stage.REQUESTING) {
                ChunkManager.this.worker.blocksChanged(this.column.x, this.column.z, indices.toIntArray(),
                        sections.toArray(new ChunkSection[0]), this.cells.toIntArray(), this.oldStates.toIntArray(),
                        this.newStates.toIntArray());
            }
            published(this.column, this.cells, this.newStates, javaChanges);
        }
    }

    /** Client side of changed Java states: block entity positions, then updates (or dirty marks before the chunk is sent). */
    private void published(ChunkColumn column, IntArrayList cells, IntArrayList newStates, @Nullable ChangeSet javaChanges) {
        int minY = this.layout.javaMinY();
        for (int k = 0; k < cells.size(); k++) {
            int packed = cells.getInt(k);
            int state = newStates.getInt(k);
            if (JavaBlockStates.hasBlockEntity(state)) {
                column.javaBlockEntities.add(packed);
            } else {
                column.javaBlockEntities.remove(packed);
            }
            if (column.stage == ChunkColumn.Stage.SENT) {
                BlockChangeEntry entry = new BlockChangeEntry(Vector3i.from((column.x << 4) | (packed & 15), (packed >> 8) + minY,
                        (column.z << 4) | ((packed >> 4) & 15)), state);
                if (javaChanges == null) {
                    this.session.sendJava(new ClientboundBlockUpdatePacket(entry));
                } else {
                    javaChanges.add(entry);
                }
            } else if (column.isBuilt()) {
                column.dirty.add(packed);
            }
        }
    }

    /** Java block changes collected while applying a multi-block Bedrock packet, sent grouped by section. */
    static final class ChangeSet {

        private final Long2ObjectOpenHashMap<List<BlockChangeEntry>> sections = new Long2ObjectOpenHashMap<>();

        void add(BlockChangeEntry entry) {
            Vector3i p = entry.getPosition();
            this.sections.computeIfAbsent(sectionKey(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4), k -> new ArrayList<>()).add(entry);
        }
    }

    /** Sends collected changes: a block update for a lone change, else one section update per section. */
    void flushChanges(ChangeSet changes) {
        for (Long2ObjectMap.Entry<List<BlockChangeEntry>> section : changes.sections.long2ObjectEntrySet()) {
            List<BlockChangeEntry> entries = section.getValue();
            if (entries.size() == 1) {
                this.session.sendJava(new ClientboundBlockUpdatePacket(entries.getFirst()));
            } else {
                long key = section.getLongKey();
                this.session.sendJava(new ClientboundSectionBlocksUpdatePacket((int) (key >> 42), (int) (key << 44 >> 44),
                        (int) (key << 22 >> 42), entries.toArray(new BlockChangeEntry[0])));
            }
        }
    }

    private static long sectionKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (z & 0x3FFFFF) << 20) | (y & 0xFFFFF);
    }

    /** A Bedrock block entity arrived or changed ({@code BlockActorDataPacket}). */
    void onBlockEntity(Vector3i position, NbtMap nbt) {
        int x = position.getX();
        int y = position.getY();
        int z = position.getZ();
        ChunkColumn column = column(x >> 4, z >> 4);
        int index = this.layout.sectionIndex(y);
        if (column == null || index < 0) {
            return;
        }
        if (column.sections == null || column.stage == ChunkColumn.Stage.REQUESTING) {
            column.pendingBlockEntities.add(nbt.toBuilder().putInt("x", x).putInt("y", y).putInt("z", z).build());
            return;
        }
        int packed = BlockEntityTranslator.packLocal(x, y, z, this.layout.javaMinY());
        column.bedrockBlockEntities.put(packed, nbt);
        ChunkSection section = column.sections[index];
        int i = BlockStorage.index(x & 15, y & 15, z & 15);
        int current = section == null ? JavaBlockStates.AIR : section.javaAt(i);
        if (section != null) {
            int base = this.mapper.toJava(section.layer0().get(i), section.layer1() == null ? this.mapper.bedrockAir() : section.layer1().get(i));
            int fixed = BlockEntityTranslator.javaState(base, nbt, x, z, this.mapper);
            if (fixed != current) {
                ChunkSection updated = section.withJava(i, fixed);
                column.sections[index] = updated;
                if (column.stage != ChunkColumn.Stage.DECODING && column.stage != ChunkColumn.Stage.REQUESTING) {
                    this.worker.blocksChanged(column.x, column.z, new int[]{index}, new ChunkSection[]{updated},
                            new int[]{packed}, new int[]{current}, new int[]{fixed});
                }
                published(column, IntArrayList.of(packed), IntArrayList.of(fixed), null);
                current = fixed;
            }
        }
        BlockEntityType type = JavaBlockStates.blockEntityType(current);
        if (type == null) {
            return;
        }
        if (column.stage == ChunkColumn.Stage.SENT) {
            this.session.sendJava(new ClientboundBlockEntityDataPacket(position, type, BlockEntityTranslator.toJava(type, current, nbt)));
        } else if (column.isBuilt()) {
            column.dirty.add(packed);
        }
    }

    // ------------------------------------------------------------------ sending

    void onBatchReceived(float desiredChunksPerTick) {
        this.unacknowledgedBatches = Math.max(0, this.unacknowledgedBatches - 1);
        this.desiredChunksPerTick = Float.isNaN(desiredChunksPerTick) ? 0.01f : Math.clamp(desiredChunksPerTick, 0.01f, 64f);
        if (this.unacknowledgedBatches == 0) {
            this.batchQuota = 1f;
        }
        this.maxUnacknowledgedBatches = MAX_UNACKNOWLEDGED_BATCHES;
    }

    void tick() {
        long now = System.nanoTime();
        if (this.session.logger().isDebugEnabled() && this.session.tickCount() % 100 == 0) {
            int[] stages = new int[ChunkColumn.Stage.values().length];
            for (ChunkColumn column : this.columns.values()) {
                stages[column.stage.ordinal()]++;
            }
            this.session.logger().debug("[{}] Chunks by stage {}: {}, unacknowledged batches {}/{}, {} chunks/tick",
                    this.session.getName(), Arrays.toString(ChunkColumn.Stage.values()), Arrays.toString(stages),
                    this.unacknowledgedBatches, this.maxUnacknowledgedBatches, this.desiredChunksPerTick);
        }
        if (!this.waiting.isEmpty()) {
            for (ChunkColumn column : this.waiting.toArray(new ChunkColumn[0])) {
                if (column.stage == ChunkColumn.Stage.WAITING && isReady(column, now)) {
                    build(column);
                }
            }
        }
        List<ChunkColumn> late = null;
        for (ChunkColumn column : this.columns.values()) {
            if (column.stage == ChunkColumn.Stage.REQUESTING && now - column.requestedAt > SUB_CHUNK_TIMEOUT_NANOS) {
                if (late == null) {
                    late = new ArrayList<>();
                }
                late.add(column);
            }
        }
        if (late != null) {
            for (ChunkColumn column : late) {
                column.requestedSections.clear();
                loaded(column);
            }
        }
        this.worker.flushLight();
        if (this.session.javaPhase() != ProxySession.JavaPhase.GAME) {
            return;
        }
        followPlayer();
        sendBatch();
    }

    /** Keeps the client's chunk cache centred on the player as it walks. */
    private void followPlayer() {
        PlayerState player = this.session.get(PlayerState.KEY);
        int x = (int) Math.floor(player.position().getX()) >> 4;
        int z = (int) Math.floor(player.position().getZ()) >> 4;
        if (x != this.playerChunkX || z != this.playerChunkZ) {
            this.playerChunkX = x;
            this.playerChunkZ = z;
            if (this.cacheCenterX != Integer.MIN_VALUE) {
                setCacheCenter(x, z);
            }
        }
    }

    private void sendBatch() {
        if (this.queued.isEmpty() || this.unacknowledgedBatches >= this.maxUnacknowledgedBatches) {
            return;
        }
        float max = Math.max(1f, this.desiredChunksPerTick);
        this.batchQuota = Math.min(this.batchQuota + this.desiredChunksPerTick, max);
        if (this.batchQuota < 1f) {
            return;
        }
        int count = Math.min((int) this.batchQuota, this.queued.size());
        int px = this.playerChunkX == Integer.MIN_VALUE ? this.viewX : this.playerChunkX;
        int pz = this.playerChunkZ == Integer.MIN_VALUE ? this.viewZ : this.playerChunkZ;
        this.queued.sort((a, b) -> Integer.compare(distance(a, px, pz), distance(b, px, pz)));
        this.session.sendJava(new ClientboundChunkBatchStartPacket());
        for (int i = 0; i < count; i++) {
            send(this.queued.get(i));
        }
        this.queued.removeElements(0, count);
        this.session.sendJava(new ClientboundChunkBatchFinishedPacket(count));
        this.unacknowledgedBatches++;
        this.batchQuota -= count;
        if (this.stats.shouldReport(this.queued.isEmpty() && this.waiting.isEmpty())) {
            this.session.logger().info("[{}] {}", this.session.getName(), this.stats.report());
        }
    }

    private static int distance(ChunkColumn column, int x, int z) {
        int dx = column.x - x;
        int dz = column.z - z;
        return dx * dx + dz * dz;
    }

    private void send(ChunkColumn column) {
        ClientboundLevelChunkWithLightPacket packet = column.packet;
        column.packet = null;
        column.stage = ChunkColumn.Stage.SENT;
        if (packet == null) {
            return;
        }
        this.session.sendJava(packet);
        this.stats.sent();
        for (Packet after : column.after) {
            this.session.sendJava(after);
        }
        column.after.clear();
        if (!column.dirty.isEmpty()) {
            resendDirty(column);
        }
    }

    private void resendDirty(ChunkColumn column) {
        ChangeSet changes = new ChangeSet();
        IntArrayList entities = new IntArrayList();
        int minY = this.layout.javaMinY();
        for (int packed : column.dirty) {
            int x = (column.x << 4) | (packed & 15);
            int z = (column.z << 4) | ((packed >> 4) & 15);
            int y = (packed >> 8) + minY;
            int state = javaBlockAt(x, y, z);
            changes.add(new BlockChangeEntry(Vector3i.from(x, y, z), state));
            if (JavaBlockStates.hasBlockEntity(state) && column.bedrockBlockEntities.containsKey(packed)) {
                entities.add(packed);
            }
        }
        column.dirty.clear();
        flushChanges(changes);
        for (int packed : entities) {
            int x = (column.x << 4) | (packed & 15);
            int z = (column.z << 4) | ((packed >> 4) & 15);
            int y = (packed >> 8) + minY;
            int state = javaBlockAt(x, y, z);
            BlockEntityType type = JavaBlockStates.blockEntityType(state);
            if (type != null) {
                this.session.sendJava(new ClientboundBlockEntityDataPacket(Vector3i.from(x, y, z), type,
                        BlockEntityTranslator.toJava(type, state, column.bedrockBlockEntities.get(packed))));
            }
        }
    }

    void close() {
        this.closed = true;
        this.worker.close();
        this.columns.clear();
        this.waiting.clear();
        this.queued.clear();
    }
}
