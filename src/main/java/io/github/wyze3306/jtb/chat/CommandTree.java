package io.github.wyze3306.jtb.chat;

import io.github.wyze3306.jtb.chat.CommandArgument.Blocks;
import io.github.wyze3306.jtb.chat.CommandArgument.Items;
import io.github.wyze3306.jtb.chat.CommandArgument.Players;
import io.github.wyze3306.jtb.chat.CommandArgument.SoftEnum;
import io.github.wyze3306.jtb.chat.CommandArgument.Source;
import io.github.wyze3306.jtb.chat.CommandArgument.Values;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandNode;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandType;
import org.geysermc.mcprotocollib.protocol.data.game.command.SuggestionType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCommandsPacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * The Java command tree of a session, built from the Bedrock command data by
 * {@link CommandTreeBuilder}. It serialises to {@link ClientboundCommandsPacket} and answers the
 * client's {@code ask_server} suggestion requests by walking the same tree over the typed text.
 * <p>
 * Nodes form a DAG: when a parameter follows the literals of an enum, all those literals share the
 * same next node, which keeps the tree linear in the size of the Bedrock data.
 */
final class CommandTree {

    /** Selectors offered for target arguments, like the Bedrock client. */
    static final List<String> SELECTORS = List.of("@a", "@e", "@p", "@r", "@s", "@initiator");
    private static final int MAX_SUGGESTIONS = 5000;
    private static final int MAX_DEPTH = 128;

    /** Where dynamic suggestions come from. */
    interface SuggestionData {

        Collection<String> softEnum(String name);

        Collection<String> playerNames();

        Collection<String> itemIdentifiers();

        Collection<String> blockIdentifiers();
    }

    /** A suggestion answer: replace {@code [start, start + length)} of the text with one of {@code matches}. */
    record Completion(int start, int length, List<String> matches) {
    }

    static final class Node {
        final CommandType type;
        final String name;
        final @Nullable CommandArgument argument;
        /** Bedrock parameter names and type labels merged into this argument node. */
        final Set<String> parameterNames = new LinkedHashSet<>(2);
        final Set<String> labels = new LinkedHashSet<>(2);
        final List<Source> sources = new ArrayList<>(1);
        final Map<String, Node> children = new LinkedHashMap<>();
        boolean executable;
        @Nullable Node redirect;
        /** Set when a sibling already asks the server: the client cancels all but the last request. */
        boolean suggestionsSuppressed;

        private Node(CommandType type, String name, @Nullable CommandArgument argument) {
            this.type = type;
            this.name = name;
            this.argument = argument;
        }

        static Node root() {
            return new Node(CommandType.ROOT, "", null);
        }

        static Node literal(String name) {
            return new Node(CommandType.LITERAL, name, null);
        }

        static Node argument(String parameterName, CommandArgument argument) {
            Node node = new Node(CommandType.ARGUMENT, parameterName, argument);
            node.merge(parameterName, argument);
            return node;
        }

        /** Adds another Bedrock parameter that reads the same way to this argument node. */
        void merge(String parameterName, CommandArgument argument) {
            this.parameterNames.add(parameterName);
            this.labels.add(argument.label());
            for (Source source : argument.sources()) {
                if (!this.sources.contains(source)) {
                    this.sources.add(source);
                }
            }
        }

        static String literalKey(String literal) {
            return "l:" + literal;
        }

        static String argumentKey(CommandArgument argument) {
            return "a:" + argument.mergeKey();
        }

        @Nullable Node literalChild(String literal) {
            return this.children.get(literalKey(literal));
        }

        boolean asksServer() {
            return !this.sources.isEmpty() && !this.suggestionsSuppressed;
        }

        /** {@code <player|victim: target>}, as shown in the client's usage hint. */
        String displayName() {
            if (this.type != CommandType.ARGUMENT) {
                return this.name;
            }
            return String.join("|", this.parameterNames) + ": " + String.join("|", this.labels);
        }
    }

    private final Node root;

    CommandTree(Node root) {
        this.root = root;
    }

    Node root() {
        return this.root;
    }

    /** Whether the tree has a top-level command (or alias) with this name. */
    boolean hasCommand(String name) {
        return this.root.literalChild(name) != null;
    }

    // ------------------------------------------------------------------ serialisation

    /** The packet describing this tree; nodes are numbered breadth first from the root (index 0). */
    ClientboundCommandsPacket toPacket() {
        Map<Node, Integer> indices = new IdentityHashMap<>();
        Map<Node, String> names = new IdentityHashMap<>();
        List<Node> order = new ArrayList<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(this.root);
        indices.put(this.root, 0);
        order.add(this.root);
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            suppressConcurrentSuggestions(node);
            nameChildren(node, names);
            for (Node child : node.children.values()) {
                if (indices.putIfAbsent(child, order.size()) == null) {
                    order.add(child);
                    queue.add(child);
                }
            }
            Node redirect = effectiveRedirect(node);
            if (redirect != null && indices.putIfAbsent(redirect, order.size()) == null) {
                order.add(redirect);
                queue.add(redirect);
            }
        }

        CommandNode[] nodes = new CommandNode[order.size()];
        for (int i = 0; i < nodes.length; i++) {
            Node node = order.get(i);
            int[] children = new int[node.children.size()];
            int c = 0;
            for (Node child : node.children.values()) {
                children[c++] = indices.get(child);
            }
            Node redirect = effectiveRedirect(node);
            OptionalInt redirectIndex = redirect == null ? OptionalInt.empty() : OptionalInt.of(indices.get(redirect));
            CommandArgument argument = node.argument;
            nodes[i] = switch (node.type) {
                case ROOT -> new CommandNode(CommandType.ROOT, false, false, children, OptionalInt.empty(), null, null, null, null);
                case LITERAL -> new CommandNode(CommandType.LITERAL, node.executable, false, children, redirectIndex,
                        node.name, null, null, null);
                case ARGUMENT -> new CommandNode(CommandType.ARGUMENT, node.executable, false, children, redirectIndex,
                        names.getOrDefault(node, node.displayName()), argument.parser(), argument.properties(),
                        node.asksServer() ? SuggestionType.ASK_SERVER.getResourceLocation() : null);
            };
        }
        return new ClientboundCommandsPacket(nodes, 0);
    }

    /**
     * Sibling nodes with the same name are merged by the client: argument names are made distinct
     * among the children of the first parent that reaches them (shared nodes come from identical
     * parameters, so other parents see the same siblings).
     */
    private static void nameChildren(Node parent, Map<Node, String> names) {
        Set<String> taken = null;
        for (Node child : parent.children.values()) {
            if (taken == null) {
                taken = new HashSet<>();
            }
            String name = names.get(child);
            if (name == null) {
                name = child.displayName();
                for (int n = 2; taken.contains(name); n++) {
                    name = child.displayName() + " #" + n;
                }
                if (child.type == CommandType.ARGUMENT) {
                    names.put(child, name);
                }
            }
            taken.add(name);
        }
    }

    /** A node with children ignores its redirect (Brigadier parses one or the other). */
    private static @Nullable Node effectiveRedirect(Node node) {
        return node.children.isEmpty() ? node.redirect : null;
    }

    /**
     * The vanilla client keeps a single pending {@code ask_server} request and cancels the previous
     * one, which fails the whole completion when two sibling arguments ask: only the first may.
     */
    private static void suppressConcurrentSuggestions(Node parent) {
        boolean asking = false;
        for (Node child : parent.children.values()) {
            if (child.type == CommandType.ARGUMENT && child.asksServer()) {
                if (asking) {
                    child.suggestionsSuppressed = true;
                }
                asking = true;
            }
        }
    }

    // ------------------------------------------------------------------ suggestions

    /**
     * Suggestions for the argument under the cursor (the end of {@code input}, which includes the
     * leading {@code /}), from every argument node the text can reach. Siblings whose own request was
     * suppressed contribute too: the client's single request answers for all of them.
     */
    Completion complete(String input, SuggestionData data) {
        int start = !input.isEmpty() && input.charAt(0) == '/' ? 1 : 0;
        List<Node> targets = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        walk(this.root, input, start, targets, starts, new HashSet<>(), 0);
        int from = input.length();
        Set<String> matches = new LinkedHashSet<>();
        for (int i = 0; i < targets.size(); i++) {
            Node target = targets.get(i);
            int position = starts.get(i);
            if (target.sources.isEmpty()) {
                continue;
            }
            if (matches.isEmpty() || position == from) {
                from = position;
                suggest(target, input.substring(position), data, matches);
            }
        }
        return new Completion(from, input.length() - from, List.copyOf(matches));
    }

    /** A node reached at a text position: the walk from there is the same whatever the path, so it runs once. */
    private record Visit(Node node, int position) {
    }

    private void walk(Node node, String input, int position, List<Node> targets, List<Integer> starts, Set<Visit> visited,
                      int depth) {
        if (depth > MAX_DEPTH || !visited.add(new Visit(node, position))) {
            return;
        }
        Node from = node.children.isEmpty() && node.redirect != null ? node.redirect : node;
        for (Node child : from.children.values()) {
            if (child.type == CommandType.LITERAL) {
                int end = position + child.name.length();
                if (end < input.length() && input.charAt(end) == ' '
                        && input.regionMatches(true, position, child.name, 0, child.name.length())) {
                    walk(child, input, end + 1, targets, starts, visited, depth + 1);
                }
                continue;
            }
            int end = consume(child.argument.syntax(), input, position);
            if (end < 0) {
                continue;
            }
            if (end >= input.length()) {
                targets.add(child);
                starts.add(position);
            } else if (end > position && input.charAt(end) == ' ') {
                walk(child, input, end + 1, targets, starts, visited, depth + 1);
            }
        }
    }

    /**
     * Where an argument starting at {@code position} ends (exclusive), or -1 when the text there
     * cannot start it. Reaching the end of the text means the cursor is inside the argument.
     */
    static int consume(CommandArgument.Syntax syntax, String input, int position) {
        int length = input.length();
        if (position > length) {
            return -1;
        }
        return switch (syntax) {
            case GREEDY -> length;
            case WORD -> wordEnd(input, position);
            case QUOTABLE -> quotableEnd(input, position);
            case ENTITY -> {
                if (position < length && input.charAt(position) == '@') {
                    int end = position + 1;
                    while (end < length && Character.isLetter(input.charAt(end))) {
                        end++;
                    }
                    if (end < length && input.charAt(end) == '[') {
                        end = balancedEnd(input, end);
                    }
                    yield end;
                }
                yield quotableEnd(input, position);
            }
            case POSITION -> {
                int end = position;
                for (int i = 0; i < 3; i++) {
                    end = wordEnd(input, end);
                    if (i < 2) {
                        if (end >= length) {
                            yield length;
                        }
                        end++;
                    }
                }
                yield end;
            }
            case STRUCTURED -> position < length && (input.charAt(position) == '{' || input.charAt(position) == '[')
                    ? balancedEnd(input, position) : wordEnd(input, position);
        };
    }

    private static int wordEnd(String input, int position) {
        int end = position;
        while (end < input.length() && input.charAt(end) != ' ') {
            end++;
        }
        return end;
    }

    private static int quotableEnd(String input, int position) {
        if (position < input.length() && (input.charAt(position) == '"' || input.charAt(position) == '\'')) {
            char quote = input.charAt(position);
            for (int i = position + 1; i < input.length(); i++) {
                char c = input.charAt(i);
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    return i + 1;
                }
            }
            return input.length();
        }
        return wordEnd(input, position);
    }

    /** End of a bracketed value starting at {@code position}, skipping quoted strings. */
    private static int balancedEnd(String input, int position) {
        int depth = 0;
        boolean quoted = false;
        for (int i = position; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quoted) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    quoted = false;
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return input.length();
    }

    private static void suggest(Node target, String typed, SuggestionData data, Set<String> out) {
        boolean entity = target.argument.syntax() == CommandArgument.Syntax.ENTITY;
        if (entity && typed.startsWith("@") && typed.indexOf('[') >= 0) {
            return; // inside selector arguments
        }
        String prefix = typed.startsWith("\"") ? typed.substring(1) : typed;
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        for (Source source : target.sources) {
            switch (source) {
                case Values values -> addMatching(values.values(), lowerPrefix, out, false);
                case SoftEnum softEnum -> addMatching(data.softEnum(softEnum.name()), lowerPrefix, out, true);
                case Players players -> {
                    addMatching(SELECTORS, lowerPrefix, out, false);
                    addMatching(data.playerNames(), lowerPrefix, out, true);
                }
                case Items items -> addMatching(data.itemIdentifiers(), lowerPrefix, out, false);
                case Blocks blocks -> addMatching(data.blockIdentifiers(), lowerPrefix, out, false);
            }
        }
    }

    private static void addMatching(Collection<String> values, String lowerPrefix, Set<String> out, boolean quoteSpaces) {
        for (String value : values) {
            if (out.size() >= MAX_SUGGESTIONS) {
                return;
            }
            if (matches(value, lowerPrefix)) {
                out.add(quoteSpaces && value.indexOf(' ') >= 0 ? '"' + value + '"' : value);
            }
        }
    }

    /** Case-insensitive prefix match, also on the path of a namespaced id ({@code dia} matches {@code minecraft:diamond}). */
    static boolean matches(String value, String lowerPrefix) {
        if (lowerPrefix.isEmpty() || value.regionMatches(true, 0, lowerPrefix, 0, lowerPrefix.length())) {
            return true;
        }
        int colon = value.indexOf(':');
        return colon >= 0 && value.regionMatches(true, colon + 1, lowerPrefix, 0, lowerPrefix.length());
    }
}
