package io.github.wyze3306.jtb.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockTextTest {

    @Test
    void plainTextStaysPlain() {
        assertEquals(Component.text("hello"), BedrockText.legacyToComponent("hello"));
    }

    @Test
    void unknownCodesAreHiddenLikeOnBedrock() {
        // UI packs use codes Bedrock does not know as invisible markers ("§w", "§x").
        assertEquals("Store", BedrockText.plain(BedrockText.legacyToComponent("§w§x§r§fStore")));
    }

    @Test
    void javaColoursAndDecorations() {
        Component component = BedrockText.legacyToComponent("§cred §lbold");
        assertEquals("red bold", BedrockText.plain(component));
        Component red = component.children().get(0);
        assertEquals(NamedTextColor.RED, red.color());
        Component bold = component.children().get(1);
        assertTrue(bold.hasDecoration(TextDecoration.BOLD));
        assertEquals(NamedTextColor.RED, bold.color());
    }

    @Test
    void bedrockMaterialColoursAreNotDecorations() {
        // §m and §n are colours on Bedrock (redstone and copper), not strikethrough and underline.
        Component component = BedrockText.legacyToComponent("§mredstone§ncopper");
        assertEquals(TextColor.color(0x971607), component.children().get(0).color());
        assertEquals(TextColor.color(0xB4684D), component.children().get(1).color());
        assertTrue(!component.children().get(0).hasDecoration(TextDecoration.STRIKETHROUGH));
    }

    @Test
    void stripFormatting() {
        assertEquals("Survie FR", BedrockText.stripFormatting("§l§aSurvie §r§fFR"));
    }

    @Test
    void colourKeepsBoldLikeBedrock() {
        Component component = BedrockText.legacyToComponent("§lBold §cRed");
        List<Component> parts = component.children();
        assertEquals(2, parts.size());
        assertEquals(TextDecoration.State.TRUE, parts.get(1).decoration(TextDecoration.BOLD));
        assertEquals(NamedTextColor.RED, parts.get(1).color());
    }

    @Test
    void resetClearsEverything() {
        Component component = BedrockText.legacyToComponent("§l§cA§rB");
        Component last = component.children().get(1);
        assertNull(last.color());
        assertEquals(TextDecoration.State.NOT_SET, last.decoration(TextDecoration.BOLD));
        assertEquals(TextDecoration.State.FALSE, last.decoration(TextDecoration.ITALIC));
    }

    @Test
    void materialColours() {
        Component component = BedrockText.legacyToComponent("§mredstone");
        assertEquals(TextColor.color(0x971607), component.children().get(0).color());
        assertEquals(TextDecoration.State.NOT_SET, component.children().get(0).decoration(TextDecoration.STRIKETHROUGH));
    }

    @Test
    void linesCarryTheirFormatting() {
        List<Component> lines = BedrockText.legacyToLines("§aFirst\nSecond\n§bThird");
        assertEquals(3, lines.size());
        assertEquals("Second", BedrockText.plain(lines.get(1)));
        assertEquals(NamedTextColor.GREEN, lines.get(1).children().get(0).color());
        assertEquals(NamedTextColor.AQUA, lines.get(2).children().get(0).color());
    }

    @Test
    void plainTextFastPath() {
        assertEquals(Component.text("hello"), BedrockText.legacyToComponent("hello"));
    }
}
