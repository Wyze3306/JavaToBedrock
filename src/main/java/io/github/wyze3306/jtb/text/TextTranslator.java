package io.github.wyze3306.jtb.text;

import io.github.wyze3306.jtb.session.ProxySession;
import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * CONTRACT (owner: chat/text subsystem). Bedrock text (formatting codes, {@code %key}
 * translations with parameters, rawtext JSON) to Java components, in the Java player's language.
 */
public final class TextTranslator {

    private TextTranslator() {
    }

    /**
     * @param message    Bedrock text; may be a translation key or contain {@code %key} references
     * @param parameters translation parameters (may themselves be keys)
     */
    public static Component translate(ProxySession session, String message, List<String> parameters) {
        return BedrockText.legacyToComponent(message);
    }

    public static Component translate(ProxySession session, String message) {
        return translate(session, message, List.of());
    }
}
