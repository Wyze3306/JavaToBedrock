package io.github.wyze3306.jtb.world;

import io.github.wyze3306.jtb.world.chunk.ChunkSection;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A chunk column as the session loop tracks it, from the Bedrock packet to the Java client. Only
 * touched on the session loop. Block entity positions are packed with
 * {@code BlockEntityTranslator.packLocal}.
 */
final class ChunkColumn {

    enum Stage {
        /** Payload being decoded on a worker; block updates are queued. */
        DECODING,
        /** Waiting for its sub-chunks (sub-chunk request mode). */
        REQUESTING,
        /** Decoded and lit, waiting for its neighbours before being sent. */
        WAITING,
        /** Packet being built by the chunk worker. */
        BUILDING,
        /** Packet built, waiting for the batch sender. */
        QUEUED,
        /** The client has it. */
        SENT
    }

    final int x;
    final int z;
    /** Identifies this load of the column: results for a replaced or unloaded column are dropped. */
    final long token;
    Stage stage = Stage.DECODING;
    long readyAt;
    @Nullable ChunkSection[] sections;
    int[][] biomes;
    /** Bedrock block entity NBT by position. */
    final Int2ObjectOpenHashMap<NbtMap> bedrockBlockEntities = new Int2ObjectOpenHashMap<>();
    /** Positions holding a Java state with a block entity type. */
    final IntOpenHashSet javaBlockEntities = new IntOpenHashSet();
    /** Item frame blocks: position -> {@code BlockMapper#itemFrame} value (shown as entities once the column is sent). */
    final Int2IntOpenHashMap frames = new Int2IntOpenHashMap(0);
    /** Custom blocks shown by display entities: position -> {@code BlockMapper#blockDisplay} value. */
    final Int2IntOpenHashMap displays = new Int2IntOpenHashMap(0);
    /** Block changes received while decoding: x, y, z, layer, runtime id. */
    final List<int[]> pendingBlocks = new ArrayList<>(0);
    /** Block entities received while decoding. */
    final List<NbtMap> pendingBlockEntities = new ArrayList<>(0);
    /** Positions changed after the packet was built, to resend once it is sent. */
    final IntOpenHashSet dirty = new IntOpenHashSet();
    /** Packets for this column that must follow the chunk packet (light updates). */
    final List<Packet> after = new ArrayList<>(0);
    @Nullable ClientboundLevelChunkWithLightPacket packet;
    /** Sub-chunk request mode: Bedrock section y values still expected. */
    final IntOpenHashSet requestedSections = new IntOpenHashSet();
    long requestedAt;

    ChunkColumn(int x, int z, long token) {
        this.x = x;
        this.z = z;
        this.token = token;
    }

    boolean isDecoded() {
        return this.stage.ordinal() >= Stage.WAITING.ordinal();
    }

    /** Whether the packet already describes the column, so changes must be resent after it. */
    boolean isBuilt() {
        return this.stage == Stage.BUILDING || this.stage == Stage.QUEUED;
    }

    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
