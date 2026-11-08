package io.github.wyze3306.jtb.world;

import io.github.wyze3306.jtb.network.java.JavaWire;
import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.entity.EntityTracker;
import io.github.wyze3306.jtb.network.java.JavaConfiguration;
import io.github.wyze3306.jtb.player.GameModes;
import io.github.wyze3306.jtb.player.PlayerState;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.translator.BedrockTranslator;
import org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundLoadingScreenPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;

import java.util.concurrent.TimeUnit;

/**
 * Joins the Java client: StartGame becomes the Java login packet, and every subsystem learns the
 * initial state (runtime ids, position, game mode, block palette...).
 * <p>
 * The configuration phase is released ({@link JavaConfiguration#markBedrockReady()}) once the
 * biome definitions arrived (custom biomes become Java registry entries), which PowerNukkitX sends
 * right after StartGame; the first chunk or a short delay release it for servers that skip them.
 */
final class StartGameTranslator implements BedrockTranslator<StartGamePacket> {

    private static final long BIOMES_WAIT_SECONDS = 3;

    @Override
    public void translate(ProxySession session, StartGamePacket packet) {
        session.get(BlockMapper.KEY).onStartGame(packet);
        session.get(EntityTracker.KEY).onStartGame(packet);
        PlayerState player = session.get(PlayerState.KEY);
        player.onStartGame(packet);
        WorldState world = session.get(WorldState.KEY);
        world.onStartGame(packet);

        JavaConfiguration configuration = session.get(JavaConfiguration.KEY);
        ServerboundClientInformationPacket information = configuration.clientInformation();
        int maxRadius = Math.max(WorldTranslators.MIN_CHUNK_RADIUS, session.config().world().maxViewDistance());
        int clientView = information == null ? maxRadius : information.getRenderDistance();
        int viewDistance = Math.clamp(clientView, WorldTranslators.MIN_CHUNK_RADIUS, maxRadius);

        PlayerSpawnInfo spawnInfo = new PlayerSpawnInfo(JavaDimensions.typeId(world.dimension()), world.worldKey(),
                packet.getSettings().getSeed(), GameModes.toJava(player.gameType()), JavaWire.NO_PREVIOUS_GAME_MODE, false, false, null, 0, 63);
        session.sendJavaLogin(new ClientboundLoginPacket(player.javaEntityId(), packet.getSettings().isHardcore(),
                JavaDimensions.WORLD_NAMES, 0, viewDistance, viewDistance, world.reducedDebugInfo(),
                !world.gameRule(WorldState.RULE_IMMEDIATE_RESPAWN, false), world.gameRule(WorldState.RULE_LIMITED_CRAFTING, false),
                spawnInfo, session.config().java().onlineMode(), false));

        WorldTranslators.requestChunkRadius(session, viewDistance);
        ServerboundLoadingScreenPacket loading = new ServerboundLoadingScreenPacket();
        loading.setType(ServerboundLoadingScreenPacketType.START_LOADING_SCREEN);
        session.sendBedrock(loading);

        session.schedule(configuration::markBedrockReady, BIOMES_WAIT_SECONDS, TimeUnit.SECONDS);
    }
}
