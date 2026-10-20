package io.github.wyze3306.jtb.e2e.scenario;

import io.github.wyze3306.jtb.e2e.ClientContext;
import io.github.wyze3306.jtb.e2e.E2eScenario;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandParser;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandSuggestionsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundCommandSuggestionPacket;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Chat and commands end to end (needs the test player in PowerNukkitX's {@code ops.txt} for
 * {@code /give} and {@code /tp}): chat round trip through the server, {@code /help} and
 * {@code /list} output, command output translation, the command tree ({@code list}, {@code tp},
 * {@code give} with arguments), tab completion of player names and items, proxy commands, and the
 * chat/command rate limits (bursts must arrive complete, without a kick).
 */
public final class ChatScenario implements E2eScenario {

    private static final int CHAT_FLOOD = 5;
    private static final int COMMAND_FLOOD = 12;

    private final String nonce = Integer.toString(ThreadLocalRandom.current().nextInt(100_000, 999_999));
    private final List<Line> lines = new CopyOnWriteArrayList<>();
    private final Map<Integer, ClientboundCommandSuggestionsPacket> suggestions = new ConcurrentHashMap<>();
    private volatile int helpFrom = -1;
    private volatile int listFrom = -1;
    private volatile int giveFrom = -1;
    private volatile int errorFrom = -1;
    private volatile int floodFrom = -1;

    private record Line(String text, Component component) {
    }

    @Override
    public void onPacket(ClientContext context, Packet packet) {
        if (packet instanceof ClientboundSystemChatPacket chat && !chat.isOverlay()) {
            this.lines.add(new Line(PlainTextComponentSerializer.plainText().serialize(chat.getContent()), chat.getContent()));
        } else if (packet instanceof ClientboundCommandSuggestionsPacket answer) {
            this.suggestions.put(answer.getTransactionId(), answer);
            context.log("Suggestions #" + answer.getTransactionId() + " [" + answer.getStart() + "+" + answer.getLength() + "] "
                    + Arrays.toString(answer.getMatches()));
        }
    }

    @Override
    public void tick(ClientContext context, int tick) {
        switch (tick) {
            case 40 -> chat(context, "Bonjour JTB " + this.nonce);
            case 60 -> {
                this.helpFrom = this.lines.size();
                command(context, "help");
            }
            case 80 -> {
                this.listFrom = this.lines.size();
                command(context, "list");
            }
            case 90 -> {
                context.send(new ServerboundCommandSuggestionPacket(42, "/tp Tes"));
                context.send(new ServerboundCommandSuggestionPacket(43, "/give Tester5 minecraft:diamon"));
                context.send(new ServerboundCommandSuggestionPacket(44, "/gamemode creative "));
            }
            case 100 -> {
                this.giveFrom = this.lines.size();
                command(context, "give @s minecraft:diamond 3");
            }
            case 110 -> {
                this.errorFrom = this.lines.size();
                command(context, "tp NobodyHere" + this.nonce);
            }
            case 120 -> command(context, "jtb version");
            case 130 -> {
                this.floodFrom = this.lines.size();
                for (int i = 0; i < CHAT_FLOOD; i++) {
                    chat(context, "flood " + this.nonce + " #" + i);
                }
                for (int i = 0; i < COMMAND_FLOOD; i++) {
                    command(context, "list");
                }
            }
            default -> {
            }
        }
    }

    private static void chat(ClientContext context, String message) {
        context.send(new ServerboundChatPacket(message, Instant.now().toEpochMilli(), 0L, null, 0, new BitSet(), 0));
    }

    private static void command(ClientContext context, String command) {
        context.send(new ServerboundChatCommandPacket(command));
    }

    private List<String> since(int from) {
        return between(from, Integer.MAX_VALUE);
    }

    /** Chat lines received in {@code [from, to)} (indices recorded when each step started). */
    private List<String> between(int from, int to) {
        List<String> texts = new ArrayList<>();
        int end = Math.min(this.lines.size(), to < 0 ? this.lines.size() : to);
        for (int i = Math.max(0, from); i < end; i++) {
            texts.add(this.lines.get(i).text());
        }
        return texts;
    }

    @Override
    public void finish(ClientContext context) {
        this.lines.forEach(line -> context.log("chat> " + line.text()));

        ClientboundCommandsPacket commands = context.last(ClientboundCommandsPacket.class);
        context.check("command tree received", commands != null);
        if (commands != null) {
            CommandNode[] nodes = commands.getNodes();
            CommandNode root = nodes[commands.getFirstNodeIndex()];
            context.log("command tree: " + nodes.length + " nodes, " + root.getChildIndices().length + " top-level commands");
            for (String name : List.of("list", "tp", "give", "help", "jtb")) {
                context.check("command tree has /" + name, child(nodes, root, name) != null);
            }
            CommandNode tp = child(nodes, root, "tp");
            context.check("/tp has an entity and a position argument", tp != null
                    && hasArgument(nodes, tp, CommandParser.ENTITY) && hasArgument(nodes, tp, CommandParser.VEC3));
            CommandNode give = child(nodes, root, "give");
            CommandNode player = give == null ? null : argument(nodes, give, CommandParser.ENTITY);
            context.check("/give <player> <item> arguments", player != null && hasArgument(nodes, player, CommandParser.RESOURCE_LOCATION));
            context.check("every argument node has a parser",
                    Arrays.stream(nodes).noneMatch(n -> n.getType() == CommandType.ARGUMENT && n.getParser() == null));
            for (String name : List.of("tp", "give", "gamemode", "effect", "execute", "scoreboard", "tag", "help")) {
                CommandNode command = child(nodes, root, name);
                if (command != null) {
                    List<String> usages = new ArrayList<>();
                    usage(nodes, command, "/" + name, usages, 0);
                    usages.stream().limit(12).forEach(line -> context.log("usage " + line));
                }
            }
        }

        List<String> all = since(0);
        context.check("chat round trip (<Tester5> message)", all.stream().anyMatch(l -> l.contains("Tester5") && l.contains("Bonjour JTB " + this.nonce)));
        context.check("no untranslated %key in chat", all.stream().noneMatch(l -> l.matches(".*%[a-z]+\\.[a-z.]+.*")));

        List<String> help = between(this.helpFrom, this.listFrom);
        context.check("/help output translated", help.stream().anyMatch(l -> l.contains("/help") && l.contains("---")));
        List<String> list = between(this.listFrom, this.giveFrom);
        context.check("/list output", list.stream().anyMatch(l -> l.matches(".*\\d+/\\d+.*")) && list.contains("Tester5"));
        List<String> give = between(this.giveFrom, this.errorFrom);
        context.check("/give command output (CommandOutput)", give.stream().anyMatch(l -> l.contains("Tester5") && l.contains("3")));
        boolean redError = false;
        for (int i = Math.max(0, this.errorFrom); i < Math.min(this.lines.size(), this.floodFrom < 0 ? this.lines.size() : this.floodFrom); i++) {
            Component component = this.lines.get(i).component();
            if (!this.lines.get(i).text().isEmpty() && isRed(component)) {
                redError = true;
            }
        }
        context.check("command error shown in red", redError);
        context.check("/jtb version answered by the proxy", all.stream().anyMatch(l -> l.startsWith("JavaToBedrock")));

        ClientboundCommandSuggestionsPacket players = this.suggestions.get(42);
        context.check("tab completion of a player name", players != null && Arrays.asList(players.getMatches()).contains("Tester5")
                && players.getStart() == 4);
        ClientboundCommandSuggestionsPacket items = this.suggestions.get(43);
        context.check("tab completion of an item", items != null && Arrays.asList(items.getMatches()).contains("minecraft:diamond"));
        context.check("suggestion request answered", this.suggestions.containsKey(44));

        List<String> flood = since(this.floodFrom);
        long chatEchoes = flood.stream().filter(l -> l.contains("flood " + this.nonce)).count();
        long listAnswers = flood.stream().filter(l -> l.matches(".*\\d+/\\d+.*")).count();
        context.log("flood: " + chatEchoes + "/" + CHAT_FLOOD + " chat echoes, " + listAnswers + "/" + COMMAND_FLOOD + " /list answers");
        context.check("chat burst fully delivered (2/s limit)", chatEchoes == CHAT_FLOOD);
        context.check("command burst fully delivered (10/s limit)", listAnswers == COMMAND_FLOOD);
    }

    /** Brigadier-like usage lines of a decoded tree, for the log. */
    private static void usage(CommandNode[] nodes, CommandNode node, String prefix, List<String> out, int depth) {
        if (out.size() > 40) {
            return;
        }
        if (node.isExecutable() || node.getChildIndices().length == 0 || depth > 12) {
            String redirect = node.getRedirectIndex().isPresent()
                    ? " -> " + (node.getRedirectIndex().getAsInt() == 0 ? "/" : nodes[node.getRedirectIndex().getAsInt()].getName())
                    : "";
            out.add(prefix + redirect);
        }
        if (depth > 12) {
            return;
        }
        for (int index : node.getChildIndices()) {
            CommandNode child = nodes[index];
            String part = child.getType() == CommandType.LITERAL ? child.getName()
                    : "<" + child.getName() + (child.getSuggestionType() != null ? "?" : "") + ">";
            usage(nodes, child, prefix + " " + part, out, depth + 1);
        }
    }

    private static boolean isRed(Component component) {
        TextColor color = component.color();
        if (NamedTextColor.RED.equals(color)) {
            return true;
        }
        for (Component child : component.children()) {
            if (isRed(child)) {
                return true;
            }
        }
        return false;
    }

    private static CommandNode child(CommandNode[] nodes, CommandNode parent, String name) {
        for (int index : parent.getChildIndices()) {
            if (name.equals(nodes[index].getName())) {
                return nodes[index];
            }
        }
        return null;
    }

    private static CommandNode argument(CommandNode[] nodes, CommandNode parent, CommandParser parser) {
        for (int index : parent.getChildIndices()) {
            if (nodes[index].getType() == CommandType.ARGUMENT && nodes[index].getParser() == parser) {
                return nodes[index];
            }
        }
        return null;
    }

    private static boolean hasArgument(CommandNode[] nodes, CommandNode parent, CommandParser parser) {
        return argument(nodes, parent, parser) != null;
    }
}
