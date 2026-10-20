package io.github.wyze3306.jtb.chat;

import io.github.wyze3306.jtb.chat.CommandArgument.Items;
import io.github.wyze3306.jtb.chat.CommandArgument.Players;
import io.github.wyze3306.jtb.chat.CommandArgument.SoftEnum;
import io.github.wyze3306.jtb.chat.CommandArgument.Source;
import io.github.wyze3306.jtb.chat.CommandArgument.Syntax;
import io.github.wyze3306.jtb.chat.CommandArgument.Values;
import org.cloudburstmc.protocol.bedrock.data.command.CommandEnumData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParam;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamType;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandParser;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.CommandProperties;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.EntityProperties;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.FloatProperties;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.IntegerProperties;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.ScoreHolderProperties;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.StringProperties;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Maps Bedrock command parameters to Java command tree nodes. The inverse of Geyser's
 * {@code JavaCommandsTranslator.mapCommandType}, guided by what the Java client can parse:
 * <ul>
 *   <li>numbers, ranges, positions ({@code ~ ^}), operators and selectors use the matching Java
 *       parser so the client highlights and completes them;</li>
 *   <li>small enums become literal nodes (completed by the client itself), large ones, soft enums and
 *       the client-populated {@code Item}/{@code Block} enums a word answered by the proxy
 *       ({@code minecraft:ask_server});</li>
 *   <li>free text (messages, raw text, JSON, block states, commands) is a greedy string when it is the
 *       last parameter. {@code minecraft:message} is never used: the client would sign it.</li>
 * </ul>
 * Bedrock-only syntax the Java parsers reject ({@code @initiator}, {@code ["a"=1]}...) is shown in
 * red by the client but still sent as typed, so nothing is lost.
 */
final class CommandParameters {

    /** Enums up to this size become literal nodes. */
    static final int MAX_LITERAL_ENUM = 64;

    /** Bedrock's comparison operators ({@code /scoreboard players operation}, {@code /execute if score}). */
    static final List<String> COMPARE_OPERATORS = List.of("<", "<=", "=", ">=", ">");

    /** Bedrock equipment slot names ({@code /replaceitem}, {@code hasitem}). */
    static final List<String> EQUIPMENT_SLOTS = List.of("slot.armor", "slot.armor.body", "slot.armor.chest",
            "slot.armor.feet", "slot.armor.head", "slot.armor.legs", "slot.chest", "slot.enderchest", "slot.equippable",
            "slot.hotbar", "slot.inventory", "slot.saddle", "slot.weapon.mainhand", "slot.weapon.offhand");

    private static final List<Source> PLAYERS = List.of(new Players());
    private static final List<Source> ITEMS = List.of(new Items());

    private CommandParameters() {
    }

    /** What one parameter becomes. */
    sealed interface Mapping {
    }

    /** One literal child per value. */
    record Literals(List<String> values) implements Mapping {
    }

    /** One argument child. */
    record Argument(CommandArgument argument) implements Mapping {
    }

    /** A whole command follows: the previous node redirects to the root (like Java's {@code execute run}). */
    record RedirectToRoot() implements Mapping {
    }

    /**
     * @param param a parameter of an overload
     * @param last  whether no parameter follows it (free text then extends to the end)
     */
    static Mapping map(CommandParamData param, boolean last) {
        if (param.getPostfix() != null && param.getEnumData() == null) {
            // e.g. "/xp 10L": a number glued to a suffix.
            return argument(CommandParser.STRING, StringProperties.SINGLE_WORD, Syntax.WORD, "int" + param.getPostfix());
        }
        CommandEnumData enumData = param.getEnumData();
        if (enumData != null) {
            return mapEnum(enumData.getName(), List.copyOf(enumData.getValues().keySet()), enumData.isSoft());
        }
        CommandParam type = param.getType();
        CommandParamType paramType = type == null ? null : type.getParamType();
        return mapType(paramType == null ? CommandParamType.UNKNOWN : paramType, last);
    }

    /**
     * @param name   enum name (Bedrock shows it as the parameter type)
     * @param values its values; soft enum values are read at suggestion time instead
     */
    static Mapping mapEnum(String name, List<String> values, boolean soft) {
        if (soft) {
            return new Argument(new CommandArgument(CommandParser.STRING, StringProperties.QUOTABLE_PHRASE, Syntax.QUOTABLE,
                    name, List.of(new SoftEnum(name))));
        }
        if (values.isEmpty() && isItemEnum(name)) {
            // Filled by the vanilla client from its own registries; PowerNukkitX sends them empty.
            return new Argument(new CommandArgument(CommandParser.RESOURCE_LOCATION, CommandArgument.NO_PROPERTIES, Syntax.WORD,
                    name, ITEMS));
        }
        if (!values.isEmpty() && values.size() <= MAX_LITERAL_ENUM && values.stream().allMatch(CommandParameters::isLiteralSafe)) {
            return new Literals(values);
        }
        List<Source> sources = List.of(new Values(values));
        if (!values.isEmpty() && values.stream().allMatch(CommandParameters::isResourceLocation)) {
            return new Argument(new CommandArgument(CommandParser.RESOURCE_LOCATION, CommandArgument.NO_PROPERTIES, Syntax.WORD,
                    name, sources));
        }
        if (values.stream().allMatch(CommandParameters::isUnquotedWord)) {
            return new Argument(new CommandArgument(CommandParser.STRING, StringProperties.SINGLE_WORD, Syntax.WORD, name, sources));
        }
        return new Argument(new CommandArgument(CommandParser.STRING, StringProperties.QUOTABLE_PHRASE, Syntax.QUOTABLE, name, sources));
    }

    static Mapping mapType(CommandParamType type, boolean last) {
        return switch (type) {
            case INT -> argument(CommandParser.INTEGER, new IntegerProperties(), Syntax.WORD, "int");
            case WILDCARD_INT -> argument(CommandParser.INTEGER, new IntegerProperties(), Syntax.WORD, "wildcard int");
            case FLOAT, VAL -> argument(CommandParser.FLOAT, new FloatProperties(), Syntax.WORD, "float");
            case RVAL -> argument(CommandParser.ANGLE, CommandArgument.NO_PROPERTIES, Syntax.WORD, "value");
            case OPERATOR -> argument(CommandParser.OPERATION, CommandArgument.NO_PROPERTIES, Syntax.WORD, "operator");
            case COMPARE_OPERATOR -> new Literals(COMPARE_OPERATORS);
            case SELECTION, STANDALONE_SELECTION, NON_ID_SELECTOR -> new Argument(new CommandArgument(CommandParser.ENTITY,
                    new EntityProperties(false, false), Syntax.ENTITY, "target", PLAYERS));
            case WILDCARD_SELECTION -> new Argument(new CommandArgument(CommandParser.SCORE_HOLDER,
                    new ScoreHolderProperties(true), Syntax.ENTITY, "target", PLAYERS));
            case POSITION -> argument(CommandParser.BLOCK_POS, CommandArgument.NO_PROPERTIES, Syntax.POSITION, "x y z");
            case POSITION_FLOAT -> argument(CommandParser.VEC3, CommandArgument.NO_PROPERTIES, Syntax.POSITION, "x y z");
            case FULL_INTEGER_RANGE, INTEGER_RANGE, INTEGER_RANGE_VAL, INTEGER_RANGE_POST_VAL ->
                    argument(CommandParser.INT_RANGE, CommandArgument.NO_PROPERTIES, Syntax.WORD, "integer range");
            case FULL_RATIONAL_RANGE, RATIONAL_RANGE, RATIONAL_RANGE_VAL, RATIONAL_RANGE_POST_VAL ->
                    argument(CommandParser.FLOAT_RANGE, CommandArgument.NO_PROPERTIES, Syntax.WORD, "range");
            case MESSAGE, MESSAGE_ROOT, MESSAGE_EXP -> text(last, "message");
            case RAW_TEXT, RAW_TEXT_CONT -> text(last, "text");
            case JSON_VALUE, JSON_FIELD, JSON_OBJECT, JSON_OBJECT_FIELDS, JSON_OBJECT_CONT, JSON_ARRAY, JSON_ARRAY_VALUES,
                 JSON_ARRAY_CONT -> last
                    ? argument(CommandParser.STRING, StringProperties.GREEDY_PHRASE, Syntax.GREEDY, "json")
                    // SNBT accepts JSON objects and arrays, and lets the following arguments be completed.
                    : argument(CommandParser.NBT_TAG, CommandArgument.NO_PROPERTIES, Syntax.STRUCTURED, "json");
            case BLOCK_STATE, BLOCK_STATE_ARRAY, BLOCK_STATE_ARRAY_CONT, BLOCK_STATE_KEY, BLOCK_STATE_VALUE,
                 BLOCK_STATE_VALUES -> last
                    ? argument(CommandParser.STRING, StringProperties.GREEDY_PHRASE, Syntax.GREEDY, "block states")
                    : argument(CommandParser.STRING, StringProperties.QUOTABLE_PHRASE, Syntax.STRUCTURED, "block states");
            case ID, ID_CONT -> argument(CommandParser.STRING, StringProperties.QUOTABLE_PHRASE, Syntax.QUOTABLE, "string");
            case FILE_PATH, FILE_PATH_VAL, FILE_PATH_CONT -> last
                    ? argument(CommandParser.STRING, StringProperties.GREEDY_PHRASE, Syntax.GREEDY, "filepath")
                    : argument(CommandParser.STRING, StringProperties.QUOTABLE_PHRASE, Syntax.QUOTABLE, "filepath");
            case EQUIPMENT_SLOT_ENUM -> new Argument(new CommandArgument(CommandParser.STRING, StringProperties.SINGLE_WORD,
                    Syntax.WORD, "equipment slots", List.of(new Values(EQUIPMENT_SLOTS))));
            case COMMAND, SLASH_COMMAND, CHAINED_COMMAND -> new RedirectToRoot();
            default -> last
                    ? argument(CommandParser.STRING, StringProperties.GREEDY_PHRASE, Syntax.GREEDY, label(type))
                    : argument(CommandParser.STRING, StringProperties.QUOTABLE_PHRASE, Syntax.QUOTABLE, label(type));
        };
    }

    private static Mapping text(boolean last, String label) {
        return last
                ? argument(CommandParser.STRING, StringProperties.GREEDY_PHRASE, Syntax.GREEDY, label)
                : argument(CommandParser.STRING, StringProperties.QUOTABLE_PHRASE, Syntax.QUOTABLE, label);
    }

    private static Argument argument(CommandParser parser, CommandProperties properties, Syntax syntax, String label) {
        return new Argument(new CommandArgument(parser, properties, syntax, label, List.of()));
    }

    private static String label(CommandParamType type) {
        return type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** The enums the Bedrock client fills from its item registry. */
    static boolean isItemEnum(@Nullable String name) {
        return "Item".equalsIgnoreCase(name) || "Block".equalsIgnoreCase(name);
    }

    /** A literal node matches the exact text up to a space: anything without whitespace works. */
    static boolean isLiteralSafe(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** What Brigadier reads without quotes: {@code 0-9 A-Z a-z _ - . +}. */
    static boolean isUnquotedWord(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c == '_' || c == '-' || c == '.' || c == '+')) {
                return false;
            }
        }
        return true;
    }

    /** What Java's {@code resource_location} parser reads: {@code a-z 0-9 _ - . / :}. */
    static boolean isResourceLocation(String value) {
        if (value.isEmpty()) {
            return false;
        }
        int colons = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ':') {
                colons++;
            } else if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c == '_' || c == '-' || c == '.' || c == '/')) {
                return false;
            }
        }
        return colons <= 1;
    }
}
