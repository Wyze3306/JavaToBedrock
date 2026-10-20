package io.github.wyze3306.jtb.text;

import io.github.wyze3306.jtb.session.ProxySession;
import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * CONTRACT (owner: chat/text subsystem). Bedrock text to Java components, localised in the Java
 * player's language ({@link BedrockLang#forJavaLocale}), exactly as a Bedrock client would show it.
 * <p>
 * Accepted input (see {@link BedrockTranslation} for the rules):
 * <ul>
 *   <li>plain {@code §}-formatted text (fast path: no lookup beyond one hash probe, no copy);</li>
 *   <li>a translation key, with parameters that may be keys themselves;</li>
 *   <li>text with {@code %key} references ({@code "§e%multiplayer.player.joined"});</li>
 *   <li>rawtext JSON ({@code {"rawtext":[...]}}), as sent by {@code /tellraw}, {@code /titleraw} and
 *       some plugins.</li>
 * </ul>
 * Use {@link #literal} for text that must not be localised (player chat, sign lines, item names
 * typed by players). Every method is safe from any thread.
 */
public final class TextTranslator {

    private TextTranslator() {
    }

    /** The Bedrock language of this player. */
    public static BedrockLang lang(ProxySession session) {
        return BedrockLang.forJavaLocale(session.getLocale());
    }

    /**
     * @param message    Bedrock text; may be a translation key, contain {@code %key} references, or be
     *                   rawtext JSON
     * @param parameters translation parameters (may themselves be keys)
     */
    public static Component translate(ProxySession session, String message, List<String> parameters) {
        return BedrockText.legacyToComponent(translateToLegacy(lang(session), message, parameters));
    }

    public static Component translate(ProxySession session, String message) {
        return translate(session, message, List.of());
    }

    /** Like {@link #translate}, as a {@code §}-formatted string (for places that need one). */
    public static String translateToLegacy(ProxySession session, String message, List<String> parameters) {
        return translateToLegacy(lang(session), message, parameters);
    }

    /** Localises with an explicit language (no session needed). */
    public static String translateToLegacy(BedrockTranslation.Lookup lang, String message, List<String> parameters) {
        if (message.isEmpty()) {
            return message;
        }
        if (BedrockTranslation.isRawText(message)) {
            String text = BedrockTranslation.rawText(lang, message);
            if (text != null) {
                return text;
            }
        }
        return BedrockTranslation.translate(lang, message, parameters);
    }

    /** Rawtext JSON; text that is not valid rawtext is shown as typed. */
    public static Component rawText(ProxySession session, String json) {
        String text = BedrockTranslation.rawText(lang(session), json);
        return BedrockText.legacyToComponent(text == null ? json : text);
    }

    /** Formatting only, no localisation: text typed by players or already localised by the server. */
    public static Component literal(String text) {
        return BedrockText.legacyToComponent(text);
    }

    /**
     * A death message as carried by {@code DeathInfoPacket} or a {@code TRANSLATE} text packet:
     * {@code death.attack.mob} with {@code [victim, "%entity.zombie.name"]}.
     */
    public static Component deathMessage(ProxySession session, String cause, List<String> parameters) {
        return translate(session, cause, parameters);
    }

    /**
     * Localised text split into lines, the formatting of one line continuing on the next like on
     * Bedrock. For multi-line name tags, item lore, scoreboard and sign lines.
     */
    public static List<Component> lines(ProxySession session, String text) {
        return BedrockText.legacyToLines(translateToLegacy(lang(session), text, List.of()));
    }

    /**
     * An entity name tag. Name tags are shown verbatim by Bedrock clients (no key lookup), but servers
     * sometimes put a {@code %key} reference in them; lines are joined with spaces because Java
     * renders a single-line name.
     */
    public static Component nameTag(ProxySession session, String nameTag) {
        String text = nameTag.indexOf('%') < 0 ? nameTag : BedrockTranslation.expandReferences(lang(session), nameTag);
        return BedrockText.legacyToComponent(text.indexOf('\n') < 0 ? text : text.replace('\n', ' '));
    }
}
