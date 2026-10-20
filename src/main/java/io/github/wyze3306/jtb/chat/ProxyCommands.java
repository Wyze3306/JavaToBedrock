package io.github.wyze3306.jtb.chat;

import io.github.wyze3306.jtb.JavaToBedrock;
import io.github.wyze3306.jtb.chat.CommandTree.Node;
import io.github.wyze3306.jtb.network.java.JavaKeepAlive;
import io.github.wyze3306.jtb.network.java.JavaServer;
import io.github.wyze3306.jtb.session.ProxySession;
import io.netty.channel.Channel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.cloudburstmc.netty.channel.raknet.RakChannel;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;

import java.util.List;
import java.util.Locale;

/**
 * {@code /jtb [info|version|ping]}, answered by the proxy itself. Only offered when the Bedrock
 * server has no command of that name, in which case the server's command wins.
 */
final class ProxyCommands {

    static final String NAME = "jtb";
    private static final List<String> SUBCOMMANDS = List.of("info", "version", "ping");

    private ProxyCommands() {
    }

    /** The {@code /jtb} branch of the Java command tree. */
    static Node node() {
        Node command = Node.literal(NAME);
        command.executable = true;
        for (String subcommand : SUBCOMMANDS) {
            Node node = Node.literal(subcommand);
            node.executable = true;
            command.children.put(Node.literalKey(subcommand), node);
        }
        return command;
    }

    static void execute(ProxySession session, String arguments) {
        switch (arguments.strip().toLowerCase(Locale.ROOT)) {
            case "", "info" -> {
                // The server address is deliberately not shown: it is often an internal one.
                send(session, version(session));
                send(session, line("Nom Bedrock : ", session.getBedrockName() + " (authentification "
                        + session.config().bedrock().auth().name().toLowerCase(Locale.ROOT) + ")"));
                send(session, ping(session));
            }
            case "version" -> send(session, version(session));
            case "ping" -> send(session, ping(session));
            default -> send(session, Component.text("Usage : /jtb [info|version|ping]", NamedTextColor.RED));
        }
    }

    private static Component version(ProxySession session) {
        BedrockClientSession bedrock = session.bedrockSession();
        BedrockCodec codec = bedrock == null ? null : bedrock.getCodec();
        String bedrockVersion = codec == null ? "?" : codec.getMinecraftVersion() + " (protocole " + codec.getProtocolVersion() + ")";
        return line(JavaToBedrock.NAME + " " + JavaToBedrock.VERSION + " : ",
                "Java " + JavaServer.javaVersionName() + " ⇄ Bedrock " + bedrockVersion);
    }

    private static Component ping(ProxySession session) {
        int java = session.get(JavaKeepAlive.KEY).latencyMs();
        long bedrock = bedrockPing(session);
        return line("Latence : ", "Java " + java + " ms, Bedrock " + (bedrock < 0 ? "?" : bedrock + " ms"));
    }

    /** RakNet round trip of the Bedrock connection, or -1 when unknown. */
    private static long bedrockPing(ProxySession session) {
        BedrockClientSession bedrock = session.bedrockSession();
        if (bedrock == null) {
            return -1;
        }
        Channel channel = bedrock.getPeer().getChannel();
        if (channel instanceof RakChannel rak) {
            RakSessionCodec codec = rak.rakPipeline().get(RakSessionCodec.class);
            if (codec != null) {
                return Math.round(codec.getRTT());
            }
        }
        return -1;
    }

    private static Component line(String label, String value) {
        return Component.text(label, NamedTextColor.GOLD).append(Component.text(value, NamedTextColor.WHITE));
    }

    private static void send(ProxySession session, Component message) {
        session.sendJava(new ClientboundSystemChatPacket(message, false));
    }
}
