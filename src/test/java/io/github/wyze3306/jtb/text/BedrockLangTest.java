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

    @Test
    void javaLocalesMapToTheClosestBedrockLanguage() {
        assertEquals("fr_FR", BedrockLang.codeForJavaLocale("fr_fr", "en_US"));
        assertEquals("fr_CA", BedrockLang.codeForJavaLocale("fr_ca", "en_US"));
        assertEquals("fr_FR", BedrockLang.codeForJavaLocale("fr_be", "en_US"));
        assertEquals("de_DE", BedrockLang.codeForJavaLocale("de_at", "en_US"));
        assertEquals("nb_NO", BedrockLang.codeForJavaLocale("no_no", "en_US"));
        assertEquals("nb_NO", BedrockLang.codeForJavaLocale("nn_no", "en_US"));
        assertEquals("en_GB", BedrockLang.codeForJavaLocale("en_au", "fr_FR"));
        assertEquals("en_US", BedrockLang.codeForJavaLocale("en_ca", "fr_FR"));
        assertEquals("es_ES", BedrockLang.codeForJavaLocale("es_es", "en_US"));
        assertEquals("es_MX", BedrockLang.codeForJavaLocale("es_ar", "en_US"));
        assertEquals("zh_TW", BedrockLang.codeForJavaLocale("zh_hk", "en_US"));
        assertEquals("zh_CN", BedrockLang.codeForJavaLocale("zh_cn", "en_US"));
        assertEquals("pt_PT", BedrockLang.codeForJavaLocale("pt_pt", "en_US"));
        assertEquals("fr_FR", BedrockLang.codeForJavaLocale("tlh_aa", "fr_FR"));
        assertEquals("fr_FR", BedrockLang.codeForJavaLocale("xx_yy", "fr_FR"));
        assertEquals("fr_FR", BedrockLang.codeForJavaLocale(null, "fr_FR"));
    }
}
