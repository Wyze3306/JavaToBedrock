package io.github.wyze3306.jtb.chat;

import io.github.wyze3306.jtb.chat.CommandParameters.Argument;
import io.github.wyze3306.jtb.chat.CommandParameters.Literals;
import io.github.wyze3306.jtb.chat.CommandParameters.Mapping;
import io.github.wyze3306.jtb.chat.CommandParameters.RedirectToRoot;
import io.github.wyze3306.jtb.chat.CommandTree.Node;
import org.cloudburstmc.protocol.bedrock.data.command.CommandData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOverloadData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamOption;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandParser;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandType;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.IntegerProperties;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.StringProperties;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the Java command tree from {@code AvailableCommandsPacket} data (ViaBedrock's
 * {@code CommandsStorage} does the same with Brigadier, which the proxy does not ship).
 * <ul>
 *   <li>One literal per command, plus one redirecting literal per alias.</li>
 *   <li>Overloads are merged: identical prefixes share nodes, sibling arguments that the Java client
 *       reads the same way become one node.</li>
 *   <li>A node is executable when the command may end there (next parameter optional, or none).</li>
 *   <li>A command parameter ({@code execute ... run <command>}) redirects to the root; an enum of the
 *       command's own subcommands at the end of an overload ({@code execute as <origin>
 *       <chainedCommand>}) redirects to the command, like Java's {@code execute}.</li>
 * </ul>
 */
final class CommandTreeBuilder {

    private final Node root = Node.root();
    private final Map<String, List<String>> enumValues = new HashMap<>();

    /**
     * @param commands every command of the packet; their enums are indexed first so that empty
     *                 client-populated enums can borrow the values of a sibling enum
     */
    CommandTreeBuilder(List<CommandData> commands) {
        for (CommandData command : commands) {
            for (CommandOverloadData overload : command.getOverloads()) {
                for (CommandParamData param : overload.getOverloads()) {
                    CommandEnumData enumData = param.getEnumData();
                    if (enumData != null && !enumData.isSoft() && !enumData.getValues().isEmpty()) {
                        this.enumValues.putIfAbsent(enumData.getName(), List.copyOf(enumData.getValues().keySet()));
                    }
                }
            }
        }
    }

    /**
     * Adds every command the player may see: hidden ones are left out, and when commands are disabled
     * (cheats off) only those flagged {@code NOT_CHEAT} remain, like on the Bedrock client.
     */
    CommandTreeBuilder addAll(List<CommandData> commands, boolean commandsEnabled) {
        List<CommandData> visible = commands.stream()
                .filter(command -> !command.getFlags().contains(CommandData.Flag.HIDDEN_FROM_PLAYER))
                .filter(command -> commandsEnabled || command.getFlags().contains(CommandData.Flag.NOT_CHEAT))
                .toList();
        for (CommandData command : visible) {
            add(command);
        }
        // Aliases last, so that a real command name always wins over another command's alias.
        for (CommandData command : visible) {
            addAliases(command);
        }
        return this;
    }

    boolean has(String name) {
        return this.root.literalChild(name) != null;
    }

    /** Adds a proxy-side command node (already built) at the top level, unless the name is taken. */
    void addTopLevel(Node literal) {
        this.root.children.putIfAbsent(Node.literalKey(literal.name), literal);
    }

    /**
     * PowerNukkitX runs {@code /help} but keeps it out of the command list (server-side only), which
     * would make the Java client flag it as unknown: a {@code help [page] | help <command>} branch is
     * added when the server lists no help. It is still the server that answers.
     */
    void addHelpIfMissing() {
        if (has("help")) {
            return;
        }
        Node help = Node.literal("help");
        help.executable = true;
        Set<Node> parent = Set.of(help);
        attachArgument(parent, "page", new CommandArgument(CommandParser.INTEGER, new IntegerProperties(1, Integer.MAX_VALUE),
                CommandArgument.Syntax.WORD, "int", List.of())).forEach(node -> node.executable = true);
        List<String> commands = this.root.children.values().stream()
                .filter(node -> node.type == CommandType.LITERAL)
                .map(node -> node.name)
                .toList();
        attachArgument(parent, "command", new CommandArgument(CommandParser.STRING, StringProperties.GREEDY_PHRASE,
                CommandArgument.Syntax.GREEDY, "command", List.of(new CommandArgument.Values(commands))))
                .forEach(node -> node.executable = true);
        this.root.children.put(Node.literalKey("help"), help);
    }

    CommandTree build() {
        return new CommandTree(this.root);
    }

    private void add(CommandData data) {
        String name = data.getName();
        if (name == null || !CommandParameters.isLiteralSafe(name) || has(name)) {
            return;
        }
        Node command = Node.literal(name);
        this.root.children.put(Node.literalKey(name), command);

        CommandOverloadData[] overloads = data.getOverloads();
        Set<String> subcommands = subcommands(overloads);
        if (overloads.length == 0) {
            command.executable = true;
        }
        for (CommandOverloadData overload : overloads) {
            addOverload(command, overload.getOverloads(), subcommands);
        }
    }

    /** One literal per alias, redirecting to the command (PowerNukkitX lists the name among its aliases). */
    private void addAliases(CommandData data) {
        CommandEnumData aliases = data.getAliases();
        Node command = data.getName() == null ? null : this.root.literalChild(data.getName());
        if (aliases == null || command == null) {
            return;
        }
        for (String alias : aliases.getValues().keySet()) {
            if (CommandParameters.isLiteralSafe(alias) && !has(alias)) {
                Node node = Node.literal(alias);
                node.redirect = command;
                node.executable = command.executable;
                this.root.children.put(Node.literalKey(alias), node);
            }
        }
    }

    /** The literals that can start an overload of this command (its subcommands). */
    private Set<String> subcommands(CommandOverloadData[] overloads) {
        Set<String> subcommands = new HashSet<>();
        for (CommandOverloadData overload : overloads) {
            CommandParamData[] params = overload.getOverloads();
            if (params.length > 0 && mapping(params[0], params.length == 1) instanceof Literals literals) {
                subcommands.addAll(literals.values());
            }
        }
        return subcommands;
    }

    private void addOverload(Node command, CommandParamData[] params, Set<String> subcommands) {
        Set<Node> current = Set.of(command);
        for (int i = 0; i < params.length; i++) {
            CommandParamData param = params[i];
            boolean last = i == params.length - 1;
            if (param.isOptional()) {
                for (Node node : current) {
                    node.executable = true;
                }
            }
            Mapping mapping = mapping(param, last);
            switch (mapping) {
                case Literals literals -> {
                    if (i > 0 && isChain(param, literals, subcommands, last)) {
                        redirect(current, command);
                        return;
                    }
                    current = attachLiterals(current, literals.values());
                }
                case Argument argument -> current = attachArgument(current, param.getName(), argument.argument());
                case RedirectToRoot ignored -> {
                    redirect(current, this.root);
                    return;
                }
            }
        }
        for (Node node : current) {
            node.executable = true;
        }
    }

    /** An enum listing the command's own subcommands: the rest of the input is another subcommand. */
    private static boolean isChain(CommandParamData param, Literals literals, Set<String> subcommands, boolean last) {
        return (last || param.getOptions().contains(CommandParamOption.ENUM_AS_CHAINED_COMMAND))
                && !subcommands.isEmpty() && subcommands.containsAll(literals.values());
    }

    private Mapping mapping(CommandParamData param, boolean last) {
        CommandEnumData enumData = param.getEnumData();
        if (enumData == null) {
            return CommandParameters.map(param, last);
        }
        List<String> values = List.copyOf(enumData.getValues().keySet());
        if (values.isEmpty() && !enumData.isSoft()) {
            List<String> borrowed = borrowedValues(enumData.getName());
            if (borrowed != null) {
                values = borrowed;
            }
        }
        return CommandParameters.mapEnum(enumData.getName(), values, enumData.isSoft());
    }

    /** PowerNukkitX sends the {@code Entity} enum empty (the Bedrock client fills it); {@code EntityType} has the ids. */
    private @Nullable List<String> borrowedValues(String enumName) {
        return "Entity".equalsIgnoreCase(enumName) ? this.enumValues.get("EntityType") : null;
    }

    private static void redirect(Set<Node> nodes, Node target) {
        for (Node node : nodes) {
            if (node.redirect == null) {
                node.redirect = target;
            }
        }
    }

    private static Set<Node> attachLiterals(Set<Node> parents, List<String> values) {
        Set<Node> next = new LinkedHashSet<>();
        for (String value : values) {
            String key = Node.literalKey(value);
            Node shared = null;
            for (Node parent : parents) {
                shared = parent.children.get(key);
                if (shared != null) {
                    break;
                }
            }
            if (shared == null) {
                shared = Node.literal(value);
            }
            for (Node parent : parents) {
                Node child = parent.children.putIfAbsent(key, shared);
                next.add(child == null ? shared : child);
            }
        }
        return next;
    }

    private static Set<Node> attachArgument(Set<Node> parents, String parameterName, CommandArgument argument) {
        String key = Node.argumentKey(argument);
        Node shared = null;
        for (Node parent : parents) {
            shared = parent.children.get(key);
            if (shared != null) {
                break;
            }
        }
        if (shared == null) {
            shared = Node.argument(parameterName == null ? "value" : parameterName, argument);
        }
        Set<Node> next = new LinkedHashSet<>();
        for (Node parent : parents) {
            Node child = parent.children.putIfAbsent(key, shared);
            Node actual = child == null ? shared : child;
            actual.merge(parameterName == null ? "value" : parameterName, argument);
            next.add(actual);
        }
        return next;
    }
}
