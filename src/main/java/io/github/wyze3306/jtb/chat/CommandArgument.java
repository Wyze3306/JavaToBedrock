package io.github.wyze3306.jtb.chat;

import org.geysermc.mcprotocollib.protocol.data.game.command.CommandParser;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.CommandProperties;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.EntityProperties;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.FloatProperties;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.IntegerProperties;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.ScoreHolderProperties;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.StringProperties;

import java.util.List;

/**
 * How one Bedrock command parameter appears in the Java command tree: the Java parser (with its
 * mandatory properties), how the proxy itself reads it when answering suggestion requests, a label
 * for the usage hint, and where its suggestions come from.
 *
 * @param parser     Java argument parser
 * @param properties parser properties; never null, MCProtocolLib requires them for every argument
 * @param syntax     how far the argument extends in typed text
 * @param label      type shown in the hint, like Bedrock ({@code <player: target>})
 * @param sources    suggestion sources; non-empty means {@code minecraft:ask_server}
 */
record CommandArgument(CommandParser parser, CommandProperties properties, Syntax syntax, String label,
                       List<Source> sources) {

    /** For parsers without properties (bool, vec3, message...): MCProtocolLib still needs an instance. */
    static final CommandProperties NO_PROPERTIES = new CommandProperties() {
        @Override
        public String toString() {
            return "none";
        }
    };

    /** How far an argument extends in a typed command. */
    enum Syntax {
        /** Up to the next space. */
        WORD,
        /** A word or a quoted string. */
        QUOTABLE,
        /** The rest of the command. */
        GREEDY,
        /** {@code @x[...]} (brackets may contain spaces) or a possibly quoted name. */
        ENTITY,
        /** Three space-separated coordinates. */
        POSITION,
        /** A balanced {@code {...}} or {@code [...]} (JSON, block states), else a word. */
        STRUCTURED
    }

    /** Where suggestions for an {@code ask_server} argument come from. */
    sealed interface Source {
    }

    /** Fixed values (a hard enum). */
    record Values(List<String> values) implements Source {
    }

    /** A soft enum, updated at run time by {@code UpdateSoftEnumPacket}. */
    record SoftEnum(String name) implements Source {
    }

    /** Selectors and the names of the players on the server. */
    record Players() implements Source {
    }

    /** Item identifiers known by the server, for the client-populated {@code Item} enum. */
    record Items() implements Source {
    }

    /** Block identifiers known by the server, for the client-populated {@code Block} enum. */
    record Blocks() implements Source {
    }

    /**
     * Arguments with the same key are merged when they are siblings: one node, the union of their
     * suggestions and children.
     */
    String mergeKey() {
        return this.parser.name() + '/' + propertiesKey(this.properties);
    }

    private static String propertiesKey(CommandProperties properties) {
        if (properties instanceof StringProperties string) {
            return string.name();
        }
        if (properties instanceof EntityProperties entity) {
            return entity.isSingleTarget() + "," + entity.isPlayersOnly();
        }
        if (properties instanceof IntegerProperties integer) {
            return integer.getMin() + "," + integer.getMax();
        }
        if (properties instanceof FloatProperties floating) {
            return floating.getMin() + "," + floating.getMax();
        }
        if (properties instanceof ScoreHolderProperties scoreHolder) {
            return String.valueOf(scoreHolder.isAllowMultiple());
        }
        return "";
    }
}
