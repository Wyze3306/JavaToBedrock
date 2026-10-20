package io.github.wyze3306.jtb.chat;

import io.github.wyze3306.jtb.chat.CommandParameters.Argument;
import io.github.wyze3306.jtb.chat.CommandParameters.Literals;
import io.github.wyze3306.jtb.chat.CommandParameters.Mapping;
import io.github.wyze3306.jtb.chat.CommandParameters.RedirectToRoot;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParam;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandParamType;
import org.geysermc.mcprotocollib.protocol.data.game.command.CommandParser;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.EntityProperties;
import org.geysermc.mcprotocollib.protocol.data.game.command.properties.StringProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandParametersTest {

    private static CommandArgument argument(CommandParamType type, boolean last) {
        return assertInstanceOf(Argument.class, CommandParameters.mapType(type, last)).argument();
    }

    @Test
    void numbersAndCoordinates() {
        assertEquals(CommandParser.INTEGER, argument(CommandParamType.INT, false).parser());
        assertEquals(CommandParser.FLOAT, argument(CommandParamType.FLOAT, false).parser());
        assertEquals(CommandParser.FLOAT, argument(CommandParamType.VAL, false).parser());
        assertEquals(CommandParser.ANGLE, argument(CommandParamType.RVAL, false).parser());
        assertEquals(CommandParser.BLOCK_POS, argument(CommandParamType.POSITION, false).parser());
        assertEquals(CommandParser.VEC3, argument(CommandParamType.POSITION_FLOAT, false).parser());
        assertEquals(CommandParser.INT_RANGE, argument(CommandParamType.FULL_INTEGER_RANGE, false).parser());
        assertEquals(CommandParser.OPERATION, argument(CommandParamType.OPERATOR, false).parser());
    }

    @Test
    void selectorsAreEntitiesAnsweredByTheProxy() {
        CommandArgument target = argument(CommandParamType.SELECTION, false);
        assertEquals(CommandParser.ENTITY, target.parser());
        EntityProperties properties = assertInstanceOf(EntityProperties.class, target.properties());
        assertFalse(properties.isSingleTarget());
        assertFalse(properties.isPlayersOnly());
        assertFalse(target.sources().isEmpty());
        assertEquals(CommandParser.SCORE_HOLDER, argument(CommandParamType.WILDCARD_SELECTION, false).parser());
    }

    @Test
    void freeTextIsGreedyOnlyAtTheEnd() {
        CommandArgument message = argument(CommandParamType.MESSAGE_ROOT, true);
        assertEquals(CommandParser.STRING, message.parser());
        assertEquals(StringProperties.GREEDY_PHRASE, message.properties());
        assertEquals(StringProperties.QUOTABLE_PHRASE, argument(CommandParamType.RAW_TEXT, false).properties());
        assertEquals(StringProperties.GREEDY_PHRASE, argument(CommandParamType.JSON_OBJECT, true).properties());
        assertEquals(CommandParser.NBT_TAG, argument(CommandParamType.JSON_OBJECT, false).parser());
    }

    @Test
    void commandsRedirectAndComparisonsAreLiterals() {
        assertInstanceOf(RedirectToRoot.class, CommandParameters.mapType(CommandParamType.COMMAND, true));
        assertInstanceOf(RedirectToRoot.class, CommandParameters.mapType(CommandParamType.SLASH_COMMAND, true));
        assertEquals(CommandParameters.COMPARE_OPERATORS,
                assertInstanceOf(Literals.class, CommandParameters.mapType(CommandParamType.COMPARE_OPERATOR, false)).values());
    }

    @Test
    void everyTypeMapsToANodeWithProperties() {
        for (CommandParamType type : CommandParamType.values()) {
            for (boolean last : new boolean[]{false, true}) {
                Mapping mapping = CommandParameters.mapType(type, last);
                assertNotNull(mapping, type.name());
                if (mapping instanceof Argument argument) {
                    assertNotNull(argument.argument().properties(), type.name());
                    assertNotNull(argument.argument().parser(), type.name());
                }
            }
        }
    }

    @Test
    void enums() {
        assertEquals(List.of("survival", "creative"),
                assertInstanceOf(Literals.class, CommandParameters.mapEnum("GameMode", List.of("survival", "creative"), false)).values());

        List<String> many = new ArrayList<>();
        for (int i = 0; i < CommandParameters.MAX_LITERAL_ENUM + 1; i++) {
            many.add("minecraft:value_" + i);
        }
        CommandArgument large = assertInstanceOf(Argument.class, CommandParameters.mapEnum("Big", many, false)).argument();
        assertEquals(CommandParser.RESOURCE_LOCATION, large.parser());
        assertFalse(large.sources().isEmpty());

        CommandArgument items = assertInstanceOf(Argument.class, CommandParameters.mapEnum("Item", List.of(), false)).argument();
        assertEquals(CommandParser.RESOURCE_LOCATION, items.parser());
        assertInstanceOf(CommandArgument.Items.class, items.sources().getFirst());

        CommandArgument soft = assertInstanceOf(Argument.class, CommandParameters.mapEnum("Tags", List.of("a b"), true)).argument();
        assertEquals(StringProperties.QUOTABLE_PHRASE, soft.properties());
        assertInstanceOf(CommandArgument.SoftEnum.class, soft.sources().getFirst());

        // Values with spaces cannot be literals.
        assertInstanceOf(Argument.class, CommandParameters.mapEnum("Spaced", List.of("a b", "c"), false));
    }

    @Test
    void postfixAndUnknownTypes() {
        CommandParamData xp = new CommandParamData();
        xp.setName("amount");
        xp.setPostfix("L");
        CommandArgument postfix = assertInstanceOf(Argument.class, CommandParameters.map(xp, false)).argument();
        assertEquals(StringProperties.SINGLE_WORD, postfix.properties());
        assertEquals("intL", postfix.label());

        CommandParamData unknown = new CommandParamData();
        unknown.setName("x");
        unknown.setType(new CommandParam(12345));
        assertInstanceOf(Argument.class, CommandParameters.map(unknown, true));
    }

    @Test
    void wordCharacterClasses() {
        assertTrue(CommandParameters.isResourceLocation("minecraft:diamond_sword"));
        assertFalse(CommandParameters.isResourceLocation("Minecraft:Stone"));
        assertTrue(CommandParameters.isUnquotedWord("slot.weapon.mainhand"));
        assertFalse(CommandParameters.isUnquotedWord("minecraft:stone"));
        assertFalse(CommandParameters.isLiteralSafe("two words"));
    }
}
