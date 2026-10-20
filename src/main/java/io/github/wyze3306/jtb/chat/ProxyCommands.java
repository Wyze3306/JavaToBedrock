package io.github.wyze3306.jtb.chat;

import io.github.wyze3306.jtb.JavaToBedrock;
import io.github.wyze3306.jtb.chat.CommandTree.Node;
import io.github.wyze3306.jtb.form.FormState;
import io.github.wyze3306.jtb.network.bedrock.BedrockReconnect;
import io.github.wyze3306.jtb.network.bedrock.BedrockServers;
import io.github.wyze3306.jtb.network.java.JavaKeepAlive;
import io.github.wyze3306.jtb.network.java.JavaServer;
import io.github.wyze3306.jtb.session.ProxySession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;

/**
 * {@code /jtb [info|version|ping|settings|server [name]]}, answered by the proxy itself. Only offered when the
 * Bedrock server has no command of that name, in which case the server's command wins.
 * {@code server} lists the Bedrock servers of {@code bedrock.servers} and moves the player to one;
 * it exists only when such servers are configured.
 */
final class ProxyCommands {

    static final String NAME = "jtb";
    private static final List<String> SUBCOMMANDS = List.of("info", "version", "ping", "settings");

    private ProxyCommands() {
    }

    /** The {@code /jtb} branch of the Java command tree. */
    static Node node(ProxySession session) {
        Node command = Node.literal(NAME);
        command.executable = true;
        for (String subcommand : SUBCOMMANDS) {
            Node node = Node.literal(subcommand);
            node.executable = true;
            command.children.put(Node.literalKey(subcommand), node);
        }
        if (hasServers(session)) {
            Node server = Node.literal("server");
            server.executable = true;
            for (BedrockServers.Target target : session.proxy().bedrockConnector().servers().all()) {
                Node name = Node.literal(target.name());
                name.executable = true;
                server.children.put(Node.literalKey(target.name()), name);
            }
            command.children.put(Node.literalKey("server"), server);
        }
        return command;
    }

    private static boolean hasServers(ProxySession session) {
        return !session.config().bedrock().servers().isEmpty();
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
            // What the Bedrock client shows in its settings screen (server settings forms).
            case "settings" -> session.get(FormState.KEY).openServerSettings();
            default -> {
                String[] words = arguments.strip().split("\\s+");
                if (words[0].equalsIgnoreCase("server") && hasServers(session)) {
                    server(session, words.length > 1 ? words[1] : null);
                } else {
                    send(session, Component.text("Usage : /jtb [info|version|ping|settings" + (hasServers(session) ? "|server [nom]" : "") + "]",
                            NamedTextColor.RED));
                }
            }
        }
    }

    private static void server(ProxySession session, String name) {
        BedrockServers servers = session.proxy().bedrockConnector().servers();
        InetSocketAddress current = session.get(BedrockReconnect.KEY).server();
        if (name == null) {
            Component list = Component.text("Serveurs : ", NamedTextColor.GOLD);
            boolean first = true;
            for (BedrockServers.Target target : servers.all()) {
                boolean here = current != null && current.equals(servers.address(target));
                list = list.append(Component.text((first ? "" : ", ") + target.name() + (here ? " (ici)" : ""),
                        here ? NamedTextColor.GREEN : NamedTextColor.WHITE));
                first = false;
            }
            send(session, list);
            return;
        }
        BedrockServers.Target target = servers.byName(name);
        if (target == null) {
            send(session, Component.text("Serveur inconnu : " + name, NamedTextColor.RED));
            return;
        }
        InetSocketAddress address = servers.address(target);
        if (address.equals(current)) {
            send(session, Component.text("Vous êtes déjà sur " + target.name() + ".", NamedTextColor.GRAY));
            return;
        }
        send(session, Component.text("Connexion à " + target.name() + "…", NamedTextColor.GRAY));
        session.switchServer(address);
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
        long bedrock = session.bedrockPingMillis();
        return line("Latence : ", "Java " + java + " ms, Bedrock " + (bedrock < 0 ? "?" : bedrock + " ms"));
    }

    private static Component line(String label, String value) {
        return Component.text(label, NamedTextColor.GOLD).append(Component.text(value, NamedTextColor.WHITE));
    }

    private static void send(ProxySession session, Component message) {
        session.sendJava(new ClientboundSystemChatPacket(message, false));
    }
}
