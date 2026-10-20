package io.github.wyze3306.jtb.world;

import io.github.wyze3306.jtb.block.JavaBlockStates;
import io.github.wyze3306.jtb.network.java.JavaConfiguration;
import io.github.wyze3306.jtb.player.GameModes;
import io.github.wyze3306.jtb.player.PlayerState;
import io.github.wyze3306.jtb.session.ProxySession;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.BlockChangeEntry;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType;
import org.cloudburstmc.protocol.bedrock.data.SpawnPositionType;
import org.cloudburstmc.protocol.bedrock.data.payload.clock.InitializeRegistryData;
import org.cloudburstmc.protocol.bedrock.data.payload.clock.SyncStateData;
import org.cloudburstmc.protocol.bedrock.data.payload.clock.SyncWorldClockStateData;
import org.cloudburstmc.protocol.bedrock.data.payload.clock.WorldClockData;
import org.cloudburstmc.protocol.bedrock.packet.BiomeDefinitionListPacket;
import org.cloudburstmc.protocol.bedrock.packet.BlockActorDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.BlockEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChunkRadiusUpdatedPacket;
import org.cloudburstmc.protocol.bedrock.packet.DimensionDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.GameRulesChangedPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkChunkPublisherUpdatePacket;
import org.cloudburstmc.protocol.bedrock.packet.OpenSignPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundLoadingScreenPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetDifficultyPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetSpawnPositionPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetTimePacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.SyncWorldClocksPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateSubChunkBlocksPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.GlobalPos;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.geysermc.mcprotocollib.protocol.data.game.setting.Difficulty;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundChangeDifficultyPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundOpenSignEditorPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSetDefaultSpawnPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundSignUpdatePacket;

import java.util.List;
import java.util.Set;

/**
 * The world subsystem's packet translators: chunks, blocks, block entities, time, rules,
 * dimensions. State lives in {@link WorldState} and its {@link ChunkManager}.
 */
final class WorldTranslators {

    /** PowerNukkitX only spawns a player once 56 chunks are loaded: radius 5 is the minimum that gets there. */
    static final int MIN_CHUNK_RADIUS = 5;
    /** Bedrock "position not known yet" height (dimension changes send the real position later). */
    private static final float UNKNOWN_Y = 32000f;
    /**
     * Java blocks whose block events Java understands from a Bedrock server (chests, spawners,
     * gateways). Not note blocks: the sound subsystem plays their note with the exact instrument from
     * the {@code note} level sound event, a Java block event would play it a second time.
     */
    private static final Set<String> EVENT_BLOCKS = Set.of("chest", "trapped_chest", "ender_chest", "spawner",
            "end_gateway", "bell", "decorated_pot");

    private WorldTranslators() {
    }

    // ------------------------------------------------------------------ chunks

    static void levelChunk(ProxySession session, LevelChunkPacket packet) {
        WorldState world = session.get(WorldState.KEY);
        ChunkManager chunks = world.chunks();
        // Some servers skip the biome definitions: the first chunk proves the join went past them.
        session.get(JavaConfiguration.KEY).markBedrockReady();
        if (chunks == null || packet.getDimension() != null && packet.getDimension().getValue() != world.dimension()) {
            return;
        }
        chunks.onLevelChunk(packet);
    }

    static void subChunk(ProxySession session, SubChunkPacket packet) {
        ChunkManager chunks = session.get(WorldState.KEY).chunks();
        if (chunks != null) {
            chunks.onSubChunk(packet);
        }
    }

    static void publisherUpdate(ProxySession session, NetworkChunkPublisherUpdatePacket packet) {
        ChunkManager chunks = session.get(WorldState.KEY).chunks();
        if (chunks != null && packet.getNewPositionForView() != null) {
            chunks.onPublisherUpdate(packet.getNewPositionForView().getX(), packet.getNewPositionForView().getZ(), packet.getNewRadiusForView());
        }
    }

    static void radiusUpdated(ProxySession session, ChunkRadiusUpdatedPacket packet) {
        ChunkManager chunks = session.get(WorldState.KEY).chunks();
        if (chunks != null) {
            chunks.onRadiusUpdated(packet.getChunkRadius());
        }
    }

    /** Asks the Bedrock server for the client's view distance (at least what PowerNukkitX needs to spawn us). */
    static void requestChunkRadius(ProxySession session, int javaViewDistance) {
        int radius = Math.clamp(javaViewDistance, MIN_CHUNK_RADIUS, Math.max(MIN_CHUNK_RADIUS, session.config().world().maxViewDistance()));
        RequestChunkRadiusPacket request = new RequestChunkRadiusPacket();
        request.setChunkRadius(radius);
        request.setMaxChunkRadius(radius);
        session.sendBedrock(request);
    }

    static void biomeDefinitions(ProxySession session, BiomeDefinitionListPacket packet) {
        session.get(WorldState.KEY).onBiomeDefinitions(packet);
        session.get(JavaConfiguration.KEY).markBedrockReady();
    }

    static void dimensionData(ProxySession session, DimensionDataPacket packet) {
        session.get(WorldState.KEY).onDimensionData(packet.getDefinitions());
    }

    // ------------------------------------------------------------------ blocks

    static void updateBlock(ProxySession session, UpdateBlockPacket packet) {
        ChunkManager chunks = session.get(WorldState.KEY).chunks();
        Vector3i position = packet.getBlockPosition();
        if (chunks != null && position != null && packet.getDefinition() != null) {
            chunks.setBlock(position.getX(), position.getY(), position.getZ(), packet.getLayer(), packet.getDefinition().getRuntimeId());
        }
    }

    static void updateSubChunkBlocks(ProxySession session, UpdateSubChunkBlocksPacket packet) {
        ChunkManager chunks = session.get(WorldState.KEY).chunks();
        if (chunks == null) {
            return;
        }
        ChunkManager.BlockBatch batch = new ChunkManager.BlockBatch();
        add(batch, packet.getStandardBlocks(), 0);
        add(batch, packet.getExtraBlocks(), 1);
        ChunkManager.ChangeSet changes = new ChunkManager.ChangeSet();
        chunks.setBlocks(batch, changes);
        chunks.flushChanges(changes);
    }

    private static void add(ChunkManager.BlockBatch batch, List<BlockChangeEntry> entries, int layer) {
        for (BlockChangeEntry entry : entries) {
            Vector3i position = entry.getPos();
            if (position != null && entry.getDefinition() != null) {
                batch.add(position.getX(), position.getY(), position.getZ(), layer, entry.getDefinition().getRuntimeId());
            }
        }
    }

    static void blockActorData(ProxySession session, BlockActorDataPacket packet) {
        ChunkManager chunks = session.get(WorldState.KEY).chunks();
        if (chunks != null && packet.getBlockPosition() != null && packet.getActorDataTags() != null) {
            chunks.onBlockEntity(packet.getBlockPosition(), packet.getActorDataTags());
        }
    }

    static void blockEvent(ProxySession session, BlockEventPacket packet) {
        WorldState world = session.get(WorldState.KEY);
        Vector3i position = packet.getBlockPosition();
        int state = world.javaBlockAt(position.getX(), position.getY(), position.getZ());
        int block = JavaBlockStates.blockId(state);
        String name = JavaBlockStates.blockName(block);
        if (!EVENT_BLOCKS.contains(name) && !name.endsWith("shulker_box") && !name.endsWith("copper_chest")) {
            return;
        }
        session.sendJava(new ClientboundBlockEventPacket(position, packet.getEventType() & 0xFF,
                Math.clamp(packet.getEventValue(), 0, 255), null, null, block));
    }

    static void openSign(ProxySession session, OpenSignPacket packet) {
        session.get(WorldState.KEY).setOpenSign(packet.getPos(), packet.isFrontSide());
        session.sendJava(new ClientboundOpenSignEditorPacket(packet.getPos(), packet.isFrontSide()));
    }

    static void signUpdate(ProxySession session, ServerboundSignUpdatePacket packet) {
        WorldState world = session.get(WorldState.KEY);
        Vector3i position = packet.getPosition();
        if (!world.takeOpenSign(position)) {
            return;
        }
        BlockActorDataPacket update = new BlockActorDataPacket();
        update.setBlockPosition(position);
        update.setActorDataTags(world.signUpdate(position, packet.isFrontText(), packet.getLines()));
        session.sendBedrock(update);
    }

    // ------------------------------------------------------------------ time, rules, misc

    static void setTime(ProxySession session, SetTimePacket packet) {
        WorldState world = session.get(WorldState.KEY);
        world.onTime(packet.getTime(), false);
    }

    static void syncWorldClocks(ProxySession session, SyncWorldClocksPacket packet) {
        WorldState world = session.get(WorldState.KEY);
        if (packet.getData() instanceof SyncStateData sync) {
            for (SyncWorldClockStateData clock : sync.getClockData()) {
                if (world.isOverworldClock(clock.getClockId())) {
                    world.onTime(clock.getTime(), clock.isPaused());
                }
            }
        } else if (packet.getData() instanceof InitializeRegistryData registry) {
            for (WorldClockData clock : registry.getClockData()) {
                if ("minecraft:overworld".equals(clock.getName())) {
                    world.onClockRegistry(clock.getId());
                    world.onTime(clock.getTime(), clock.isPaused());
                }
            }
        }
    }

    static void gameRules(ProxySession session, GameRulesChangedPacket packet) {
        session.get(WorldState.KEY).onGameRules(packet.getRulesData().getRulesList());
        // Immediate respawn, coordinates, sleeping percentage...
        session.get(PlayerState.KEY).onGameRules(packet.getRulesData().getRulesList());
    }

    static void spawnPosition(ProxySession session, SetSpawnPositionPacket packet) {
        if (packet.getSpawnPositionType() != SpawnPositionType.WORLD_SPAWN || packet.getBlockPosition() == null) {
            return;
        }
        WorldState world = session.get(WorldState.KEY);
        int dimension = packet.getDimensionType() == null ? world.dimension() : packet.getDimensionType().getValue();
        Key key = dimension == world.dimension() ? world.worldKey() : JavaDimensions.key(dimension);
        session.sendJava(new ClientboundSetDefaultSpawnPositionPacket(new GlobalPos(key, packet.getBlockPosition()), 0f, 0f));
    }

    static void difficulty(ProxySession session, SetDifficultyPacket packet) {
        int difficulty = Math.clamp(packet.getDifficulty(), 0, 3);
        session.sendJava(new ClientboundChangeDifficultyPacket(Difficulty.from(difficulty), false));
    }

    // ------------------------------------------------------------------ dimensions

    /**
     * Bedrock dimension change: the Java client respawns into the new world and waits for its chunks;
     * the server confirms with {@code PlayerAction(CHANGE_DIMENSION_ACK)} once it sent them.
     */
    static void changeDimension(ProxySession session, ChangeDimensionPacket packet) {
        WorldState world = session.get(WorldState.KEY);
        PlayerState player = session.get(PlayerState.KEY);
        int dimension = packet.getDimension().getValue();
        session.logger().debug("[{}] Dimension change to {} at {} (loading screen {})", session.getName(), dimension,
                packet.getPosition(), packet.getLoadingScreenId());
        Key worldKey = world.changeDimension(dimension, packet.getLoadingScreenId());
        // Java movements mean nothing until the server confirmed the change.
        player.setDimensionChanging(true);
        PlayerSpawnInfo info = new PlayerSpawnInfo(JavaDimensions.typeId(dimension), worldKey, 0L,
                GameModes.toJava(player.gameType()), null, false, false, null, 0, 63);
        session.sendJava(new ClientboundRespawnPacket(info, true, true));
        Vector3f position = packet.getPosition();
        if (position != null && position.getY() < UNKNOWN_Y) {
            player.teleport(position, player.yaw(), player.pitch());
        }
        session.sendJava(new ClientboundGameEventPacket(GameEvent.LEVEL_CHUNKS_LOAD_START, null));
        ServerboundLoadingScreenPacket loading = new ServerboundLoadingScreenPacket();
        loading.setType(ServerboundLoadingScreenPacketType.START_LOADING_SCREEN);
        loading.setLoadingScreenId(packet.getLoadingScreenId());
        session.sendBedrock(loading);
        world.sendTime();
        world.runDimensionListeners();
    }

    /** The server's half of the dimension change handshake; a vanilla client answers the same way. */
    static void playerAction(ProxySession session, PlayerActionPacket packet) {
        if (packet.getAction() != PlayerActionType.CHANGE_DIMENSION_ACK) {
            return;
        }
        WorldState world = session.get(WorldState.KEY);
        Integer loadingScreen = world.finishDimensionChange();
        session.get(PlayerState.KEY).setDimensionChanging(false);
        session.logger().debug("[{}] Dimension change acknowledged", session.getName());
        PlayerActionPacket ack = new PlayerActionPacket();
        ack.setPlayerRuntimeID(session.get(PlayerState.KEY).runtimeId());
        ack.setAction(PlayerActionType.CHANGE_DIMENSION_ACK);
        ack.setBlockPosition(Vector3i.ZERO);
        ack.setResultPos(Vector3i.ZERO);
        ack.setFace(0);
        session.sendBedrock(ack);
        ServerboundLoadingScreenPacket loading = new ServerboundLoadingScreenPacket();
        loading.setType(ServerboundLoadingScreenPacketType.END_LOADING_SCREEN);
        loading.setLoadingScreenId(loadingScreen);
        session.sendBedrock(loading);
    }

    // ------------------------------------------------------------------ Java -> Bedrock

    static void clientInformation(ProxySession session, ServerboundClientInformationPacket packet) {
        session.setLocale(packet.getLocale());
        requestChunkRadius(session, packet.getRenderDistance());
    }

    static void chunkBatchReceived(ProxySession session, ServerboundChunkBatchReceivedPacket packet) {
        ChunkManager chunks = session.get(WorldState.KEY).chunks();
        if (chunks != null) {
            chunks.onBatchReceived(packet.getDesiredChunksPerTick());
        }
    }
}
