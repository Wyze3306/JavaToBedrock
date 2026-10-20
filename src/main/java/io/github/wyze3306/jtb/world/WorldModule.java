package io.github.wyze3306.jtb.world;

import io.github.wyze3306.jtb.translator.TranslatorModule;
import io.github.wyze3306.jtb.translator.TranslatorRegistry;
import org.cloudburstmc.protocol.bedrock.packet.BiomeDefinitionListPacket;
import org.cloudburstmc.protocol.bedrock.packet.BlockActorDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.BlockEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket;
import org.cloudburstmc.protocol.bedrock.packet.ChunkRadiusUpdatedPacket;
import org.cloudburstmc.protocol.bedrock.packet.DimensionDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.GameRulesChangedPacket;
import org.cloudburstmc.protocol.bedrock.packet.JigsawStructureDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkChunkPublisherUpdatePacket;
import org.cloudburstmc.protocol.bedrock.packet.OpenSignPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerFogPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetDifficultyPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetSpawnPositionPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetTimePacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket;
import org.cloudburstmc.protocol.bedrock.packet.SyncWorldClocksPacket;
import org.cloudburstmc.protocol.bedrock.packet.TickingAreasLoadStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPropertiesPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockSyncedPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateSubChunkBlocksPacket;
import org.cloudburstmc.protocol.bedrock.packet.VoxelShapesPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundSignUpdatePacket;

/**
 * Translators of the world subsystem. See docs/DEVELOPING.md for the packets it owns.
 */
public final class WorldModule implements TranslatorModule {

    @Override
    public void register(TranslatorRegistry registry) {
        registry.bedrock(StartGamePacket.class, new StartGameTranslator());
        registry.bedrock(BiomeDefinitionListPacket.class, WorldTranslators::biomeDefinitions);
        registry.bedrock(DimensionDataPacket.class, WorldTranslators::dimensionData);
        registry.bedrock(LevelChunkPacket.class, WorldTranslators::levelChunk);
        registry.bedrock(SubChunkPacket.class, WorldTranslators::subChunk);
        registry.bedrock(NetworkChunkPublisherUpdatePacket.class, WorldTranslators::publisherUpdate);
        registry.bedrock(ChunkRadiusUpdatedPacket.class, WorldTranslators::radiusUpdated);
        registry.bedrock(UpdateBlockPacket.class, WorldTranslators::updateBlock);
        registry.bedrock(UpdateBlockSyncedPacket.class, WorldTranslators::updateBlock);
        registry.bedrock(UpdateSubChunkBlocksPacket.class, WorldTranslators::updateSubChunkBlocks);
        registry.bedrock(BlockActorDataPacket.class, WorldTranslators::blockActorData);
        registry.bedrock(BlockEventPacket.class, WorldTranslators::blockEvent);
        registry.bedrock(OpenSignPacket.class, WorldTranslators::openSign);
        registry.bedrock(SetTimePacket.class, WorldTranslators::setTime);
        registry.bedrock(SyncWorldClocksPacket.class, WorldTranslators::syncWorldClocks);
        registry.bedrock(GameRulesChangedPacket.class, WorldTranslators::gameRules);
        registry.bedrock(SetSpawnPositionPacket.class, WorldTranslators::spawnPosition);
        registry.bedrock(SetDifficultyPacket.class, WorldTranslators::difficulty);
        registry.bedrock(ChangeDimensionPacket.class, WorldTranslators::changeDimension);
        // Servers only send this one to acknowledge a dimension change.
        registry.bedrock(PlayerActionPacket.class, WorldTranslators::playerAction);
        // Nothing to show on Java: fog stacks, block shapes for client prediction, jigsaw data,
        // ticking areas preload state, runtime block property changes.
        registry.bedrock(PlayerFogPacket.class, (session, packet) -> {
        });
        registry.bedrock(VoxelShapesPacket.class, (session, packet) -> {
        });
        registry.bedrock(JigsawStructureDataPacket.class, (session, packet) -> {
        });
        registry.bedrock(TickingAreasLoadStatusPacket.class, (session, packet) -> {
        });
        registry.bedrock(UpdateBlockPropertiesPacket.class, (session, packet) -> {
        });

        registry.java(ServerboundClientInformationPacket.class, WorldTranslators::clientInformation);
        registry.java(ServerboundChunkBatchReceivedPacket.class, WorldTranslators::chunkBatchReceived);
        registry.java(ServerboundSignUpdatePacket.class, WorldTranslators::signUpdate);
    }
}
