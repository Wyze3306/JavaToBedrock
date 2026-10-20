package io.github.wyze3306.jtb.player;

import io.github.wyze3306.jtb.entity.EntityTracker;
import io.github.wyze3306.jtb.session.ProxySession;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.PlayStatus;
import org.cloudburstmc.protocol.bedrock.data.PlayerRespawnState;
import org.cloudburstmc.protocol.bedrock.data.ServerboundLoadingScreenPacketType;
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundLoadingScreenPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLocalPlayerAsInitializedPacket;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;

/**
 * The spawn sequence and the always-on bookkeeping packets of the local player.
 */
final class SpawnTranslators {

    /** First time players are staged at this height until the server found their spawn. */
    private static final float STAGING_Y = 30000;

    private SpawnTranslators() {
    }

    /** PLAYER_SPAWN: the player is in the world for the server; tell it we are ready like a vanilla client. */
    static void playStatus(ProxySession session, PlayStatusPacket packet) {
        if (packet.getStatus() != PlayStatus.PLAYER_SPAWN) {
            return;
        }
        PlayerState player = session.get(PlayerState.KEY);
        if (player.isSpawned()) {
            return;
        }
        player.setSpawned(true);

        InteractPacket interact = new InteractPacket();
        interact.setAction(InteractPacket.Action.INTERACT_UPDATE);
        interact.setTargetRuntimeID(0);
        session.sendBedrock(interact);
        ServerboundLoadingScreenPacket loading = new ServerboundLoadingScreenPacket();
        loading.setType(ServerboundLoadingScreenPacketType.END_LOADING_SCREEN);
        session.sendBedrock(loading);
        SetLocalPlayerAsInitializedPacket initialized = new SetLocalPlayerAsInitializedPacket();
        initialized.setPlayerID(player.runtimeId());
        session.sendBedrock(initialized);

        if (player.position().getY() < STAGING_Y) {
            player.teleport(player.position(), player.yaw(), player.pitch());
        }
        session.sendJava(new ClientboundGameEventPacket(GameEvent.LEVEL_CHUNKS_LOAD_START, null));
    }

    /** The server found where the player (re)spawns: eye position. */
    static void respawn(ProxySession session, RespawnPacket packet) {
        if (packet.getState() != PlayerRespawnState.READY_TO_SPAWN) {
            return;
        }
        PlayerState player = session.get(PlayerState.KEY);
        Vector3f feet = packet.getPosition().sub(0, PlayerState.EYE_HEIGHT, 0);
        player.teleport(feet, player.yaw(), player.pitch());
    }

    /**
     * PowerNukkitX measures latency every tick and matches the answer by its exact timestamp; a vanilla
     * client answers with the same value.
     */
    static void networkStackLatency(ProxySession session, NetworkStackLatencyPacket packet) {
        if (!packet.isFromServer()) {
            return;
        }
        NetworkStackLatencyPacket reply = new NetworkStackLatencyPacket();
        reply.setCreationTime(packet.getCreationTime());
        reply.setFromServer(false);
        session.sendBedrock(reply);
    }

    /** Server-side moves of the local player (teleports, corrections). */
    static void movePlayer(ProxySession session, MovePlayerPacket packet) {
        PlayerState player = session.get(PlayerState.KEY);
        if (!player.isLocal(packet.getPlayerRuntimeID())) {
            session.get(EntityTracker.KEY).onRemotePlayerMove(packet);
            return;
        }
        Vector3f feet = packet.getPosition().sub(0, PlayerState.EYE_HEIGHT, 0);
        player.teleport(feet, packet.getRotation().getY(), packet.getRotation().getX());
    }
}
