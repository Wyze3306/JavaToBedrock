package io.github.wyze3306.jtb.e2e.scenario;

import io.github.wyze3306.jtb.e2e.ClientContext;
import io.github.wyze3306.jtb.e2e.E2eScenario;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;

import java.time.Instant;
import java.util.BitSet;

/**
 * Joins, walks a few blocks along +X, says hello and runs {@code /list}; expects chunks and chat.
 */
public final class DefaultScenario implements E2eScenario {

    @Override
    public void tick(ClientContext context, int tick) {
        if (tick < 40) {
            context.setPosition(context.x() + 0.2, context.y(), context.z());
            context.send(new ServerboundMovePlayerPosRotPacket(true, false, context.x(), context.y(), context.z(),
                    context.yaw(), context.pitch()));
        }
        if (tick == 20) {
            context.send(new ServerboundChatPacket("Bonjour depuis Java !", Instant.now().toEpochMilli(), 0L, null, 0, new BitSet(), 0));
        }
        if (tick == 40) {
            context.send(new ServerboundChatCommandPacket("list"));
        }
    }

    @Override
    public void finish(ClientContext context) {
        context.check("received chunks", context.count("ClientboundLevelChunkWithLightPacket") > 0);
        context.check("received chat", !context.chat().isEmpty());
    }
}
