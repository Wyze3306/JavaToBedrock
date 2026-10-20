package io.github.wyze3306.jtb.text;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockTranslationTest {

    private static final Map<String, String> EN = Map.ofEntries(
            Map.entry("multiplayer.player.joined", "%s joined the game"),
            Map.entry("commands.players.list", "There are %1$d/%2$d players online:"),
            Map.entry("death.attack.mob", "%1$s was slain by %2$s"),
            Map.entry("entity.zombie.name", "Zombie"),
            Map.entry("item.diamond.name", "Diamond"),
            Map.entry("commands.give.success", "Gave %1$s * %2$d to %3$s"),
            Map.entry("chat.type.announcement", "[%s] %s"),
            Map.entry("percent.test", "100%% sure"),
            Map.entry("commands.generic.unknown", "Unknown command: %s."));
    private static final BedrockTranslation.Lookup LANG = EN::get;

    @Test
    void plainTextIsReturnedAsIs() {
        String message = "§aHello world";
        assertSame(message, BedrockTranslation.translate(LANG, message, List.of()));
        assertSame(message, BedrockTranslation.translate(LANG, message, List.of("ignored")));
    }

    @Test
    void wholeKeyWithPositionalParameters() {
        assertEquals("There are 1/20 players online:",
                BedrockTranslation.translate(LANG, "commands.players.list", List.of("1", "20")));
    }

    @Test
    void referencesInsideTextAreExpandedBeforePlaceholders() {
        assertEquals("§eSteve joined the game",
                BedrockTranslation.translate(LANG, "§e%multiplayer.player.joined", List.of("Steve")));
        // PowerNukkitX command output: colour + reference, parameters fill the referenced pattern.
        assertEquals("§fThere are 2/10 players online:",
                BedrockTranslation.translate(LANG, "§f%commands.players.list", List.of("2", "10")));
    }

    @Test
    void parametersMayBeKeys() {
        assertEquals("Steve was slain by Zombie",
                BedrockTranslation.translate(LANG, "death.attack.mob", List.of("Steve", "%entity.zombie.name")));
        assertEquals("Gave Diamond * 3 to Alex",
                BedrockTranslation.translate(LANG, "commands.give.success", List.of("item.diamond.name", "3", "Alex")));
        // Bare words are never looked up, only dotted keys that exist.
        assertEquals("Gave Mr.Bean * 1 to stone",
                BedrockTranslation.translate(LANG, "commands.give.success", List.of("Mr.Bean", "1", "stone")));
    }

    @Test
    void unknownReferencesAndPercentSignsStayLiteral() {
        assertEquals("50%off %unknown.key", BedrockTranslation.translate(LANG, "50%off %unknown.key", List.of()));
        assertEquals("100% sure", BedrockTranslation.translate(LANG, "percent.test", List.of()));
        assertEquals("100% done", BedrockTranslation.translate(LANG, "100% done", List.of()));
    }

    @Test
    void trailingDotIsNotPartOfTheKey() {
        assertEquals("I found a Diamond.", BedrockTranslation.expandReferences(LANG, "I found a %item.diamond.name."));
    }

    @Test
    void missingParametersBecomeEmpty() {
        assertEquals("Unknown command: .", BedrockTranslation.translate(LANG, "commands.generic.unknown", List.of()));
    }

    @Test
    void formatKeyInsertsArgumentsVerbatim() {
        assertEquals("[Steve] %item.diamond.name",
                BedrockTranslation.formatKey(LANG, "chat.type.announcement", "[%s] %s", "Steve", "%item.diamond.name"));
        assertEquals("<Steve> hi", BedrockTranslation.formatKey(LANG, "chat.type.text", "<%s> %s", "Steve", "hi"));
    }

    @Test
    void rawTextElements() {
        String json = """
                {"rawtext":[{"text":"§aHello "},{"selector":"@p"},{"text":", "},
                {"translate":"death.attack.mob","with":["Steve","%entity.zombie.name"]},
                {"text":" score="},{"score":{"name":"Steve","objective":"kills","value":7}},
                {"rawtext":[{"text":" nested"}]}]}""";
        assertTrue(BedrockTranslation.isRawText(json));
        assertEquals("§aHello @p, Steve was slain by Zombie score=7 nested", BedrockTranslation.rawText(LANG, json));
    }

    @Test
    void rawTextTranslateWithRawTextParameters() {
        String json = "{\"rawtext\":[{\"translate\":\"death.attack.mob\",\"with\":{\"rawtext\":[{\"text\":\"§cAlex\"},"
                + "{\"translate\":\"entity.zombie.name\"}]}}]}";
        assertEquals("§cAlex was slain by Zombie", BedrockTranslation.rawText(LANG, json));
    }

    @Test
    void invalidRawTextIsRejected() {
        assertNull(BedrockTranslation.rawText(LANG, "{\"rawtext\": [ oops"));
        assertNull(BedrockTranslation.rawText(LANG, "{\"text\":\"no rawtext\"}"));
        assertFalse(BedrockTranslation.isRawText("hello {\"rawtext\"}"));
        assertEquals("{\"rawtext\": [ oops", TextTranslator.translateToLegacy(LANG, "{\"rawtext\": [ oops", List.of()));
    }

    @Test
    void textTranslatorDetectsRawText() {
        assertEquals("Zombie", TextTranslator.translateToLegacy(LANG, "{\"rawtext\":[{\"translate\":\"entity.zombie.name\"}]}", List.of()));
    }

    @Test
    void formatHandlesAllPlaceholderForms() {
        assertEquals("a b a %", BedrockLang.format("%s %2$s %1 %%", "a", "b"));
        assertEquals("x 5", BedrockLang.format("%s %d", "x", "5"));
    }
}
