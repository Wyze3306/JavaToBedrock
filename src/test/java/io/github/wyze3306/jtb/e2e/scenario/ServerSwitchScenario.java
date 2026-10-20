package io.github.wyze3306.jtb.e2e.scenario;

import io.github.wyze3306.jtb.e2e.ClientContext;
import io.github.wyze3306.jtb.e2e.E2eScenario;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;

/**
 * {@code /jtb server}: lists the Bedrock servers, then moves the player to the one named {@code bis}
 * (run the proxy with {@code JTB_BEDROCK_SERVERS=bis=<host:port>}). The player must join the world
 * again without being disconnected.
 */
public final class ServerSwitchScenario implements E2eScenario {

    @Override
    public void tick(ClientContext context, int tick) {
        if (tick == 60) {
            context.send(new ServerboundChatCommandPacket("jtb server"));
        }
        if (tick == 80) {
            context.send(new ServerboundChatCommandPacket("jtb server bis"));
        }
    }

    @Override
    public void finish(ClientContext context) {
        context.check("server list shown", context.chat().stream().anyMatch(line -> line.startsWith("Serveurs : ") && line.contains("bis")));
        context.check("switch announced", context.chat().stream().anyMatch(line -> line.contains("Connexion à bis")));
        context.check("joined the other server", context.count("ClientboundLoginPacket") >= 2);
    }
}
