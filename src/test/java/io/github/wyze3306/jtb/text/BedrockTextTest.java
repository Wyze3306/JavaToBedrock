package io.github.wyze3306.jtb.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockTextTest {

    @Test
    void plainTextStaysPlain() {
        assertEquals(Component.text("hello"), BedrockText.legacyToComponent("hello"));
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
}
