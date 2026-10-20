package io.github.wyze3306.jtb.text;

import io.github.wyze3306.jtb.session.ProxySession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;

import java.util.List;

/**
 * Bedrock disconnect reasons to the Java kick screen.
 */
public final class BedrockDisconnects {

    private BedrockDisconnects() {
    }

    public static Component message(ProxySession session, DisconnectPacket packet) {
        String message = packet.getMessages() == null ? "" : packet.getMessages().getMessage();
        if (message == null || message.isEmpty()) {
            return message(session, "disconnectionScreen." + toCamel(String.valueOf(packet.getReason())));
        }
        return message(session, message);
    }

    public static Component message(ProxySession session, String reason) {
        if (reason == null || reason.isEmpty()) {
            return Component.text("Déconnecté du serveur Bedrock", NamedTextColor.RED);
        }
        String key = reason.startsWith("%") ? reason.substring(1) : reason;
        BedrockLang lang = BedrockLang.forJavaLocale(session.getLocale());
        if (lang.has(key)) {
            return BedrockText.legacyToComponent(lang.translate(key));
        }
        if (key.startsWith("disconnectionScreen.") || key.startsWith("disconnect.")) {
            return Component.text("Déconnecté du serveur Bedrock (" + key + ")", NamedTextColor.RED);
        }
        // The Bedrock client localises kick messages too ("§c%disconnectionScreen.kicked: ...").
        return BedrockText.legacyToComponent(BedrockTranslation.translate(lang, reason, List.of()));
    }

    private static String toCamel(String enumName) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : enumName.toLowerCase().toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }
}
