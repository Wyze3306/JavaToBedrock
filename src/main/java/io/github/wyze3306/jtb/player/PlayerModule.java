package io.github.wyze3306.jtb.player;

import io.github.wyze3306.jtb.translator.TranslatorModule;
import io.github.wyze3306.jtb.translator.TranslatorRegistry;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket;

/**
 * Translators of the player subsystem. See docs/DEVELOPING.md for the packets it owns.
 */
public final class PlayerModule implements TranslatorModule {

    @Override
    public void register(TranslatorRegistry registry) {
        registry.bedrock(PlayStatusPacket.class, SpawnTranslators::playStatus);
        registry.bedrock(RespawnPacket.class, SpawnTranslators::respawn);
        registry.bedrock(NetworkStackLatencyPacket.class, SpawnTranslators::networkStackLatency);
        registry.bedrock(MovePlayerPacket.class, SpawnTranslators::movePlayer);
    }
}
