package io.github.wyze3306.jtb.world;

import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.entity.EntityTracker;
import io.github.wyze3306.jtb.network.java.JavaConfiguration;
import io.github.wyze3306.jtb.player.GameModes;
import io.github.wyze3306.jtb.player.PlayerState;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.translator.BedrockTranslator;
import org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType;
import org.cloudburstmc.protocol.bedrock.packet.RequestChunkRadiusPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundLoadingScreenPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;

/**
 * Joins the Java client: StartGame becomes the Java login packet, and every subsystem learns the
 * initial state (runtime ids, position, game mode, block palette...).
 */
final class StartGameTranslator implements BedrockTranslator<StartGamePacket> {

    /** PowerNukkitX only spawns a player once 56 chunks are loaded: radius 5 is the minimum that gets there. */
    private static final int MIN_CHUNK_RADIUS = 5;

    @Override
    public void translate(ProxySession session, StartGamePacket packet) {
        session.get(BlockMapper.KEY).onStartGame(packet);
        session.get(EntityTracker.KEY).onStartGame(packet);
        PlayerState player = session.get(PlayerState.KEY);
        player.onStartGame(packet);
        WorldState world = session.get(WorldState.KEY);
        world.onStartGame(packet);

        int dimension = world.dimension();
        int viewDistance = Math.max(MIN_CHUNK_RADIUS, session.config().world().maxViewDistance());
        PlayerSpawnInfo spawnInfo = new PlayerSpawnInfo(JavaDimensions.typeId(dimension), JavaDimensions.key(dimension),
                packet.getSettings().getSeed(), GameModes.toJava(player.gameType()), null, false, false, null, 0, 63);
        session.sendJavaLogin(new ClientboundLoginPacket(player.javaEntityId(), packet.getSettings().isHardcore(),
                JavaDimensions.WORLD_NAMES, 0, viewDistance, viewDistance, false, true, false, spawnInfo,
                session.config().java().onlineMode(), false));

        RequestChunkRadiusPacket radius = new RequestChunkRadiusPacket();
        radius.setChunkRadius(viewDistance);
        radius.setMaxChunkRadius(viewDistance);
        session.sendBedrock(radius);
        ServerboundLoadingScreenPacket loading = new ServerboundLoadingScreenPacket();
        loading.setType(ServerboundLoadingScreenPacketType.START_LOADING_SCREEN);
        session.sendBedrock(loading);

        session.get(JavaConfiguration.KEY).markBedrockReady();
    }
}
