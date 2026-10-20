package io.github.wyze3306.jtb.text;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockLangTest {

    @Test
    void parsesLangFiles() {
        Map<String, String> map = BedrockLang.parse(List.of(
                "﻿## comment",
                "death.attack.player=%1$s was slain by %2$s\t#",
                "chat.type.text=<%s> %s",
                "",
                "broken line"));
        assertEquals("%1$s was slain by %2$s", map.get("death.attack.player"));
        assertEquals("<%s> %s", map.get("chat.type.text"));
        assertEquals(2, map.size());
    }

    @Test
    void formatsSequentialAndPositionalPlaceholders() {
        assertEquals("<Steve> hi", BedrockLang.format("<%s> %s", "Steve", "hi"));
        assertEquals("Alex was slain by Steve", BedrockLang.format("%1$s was slain by %2$s", "Alex", "Steve"));
        assertEquals("b a", BedrockLang.format("%2 %1", "a", "b"));
        assertEquals("100%", BedrockLang.format("%d%%", "100"));
        assertEquals("missing: ", BedrockLang.format("missing: %s"));
    }
}
