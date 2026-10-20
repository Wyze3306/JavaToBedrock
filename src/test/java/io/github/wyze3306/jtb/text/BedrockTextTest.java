package io.github.wyze3306.jtb.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BedrockTextTest {

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
