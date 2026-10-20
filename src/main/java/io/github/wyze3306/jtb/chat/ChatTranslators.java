package io.github.wyze3306.jtb.chat;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.text.BedrockText;
import io.github.wyze3306.jtb.text.BedrockTranslation;
import io.github.wyze3306.jtb.text.TextTranslator;
import org.cloudburstmc.protocol.bedrock.data.TextPacketType;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginType;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOutputMessage;
import org.cloudburstmc.protocol.bedrock.data.payload.text.AuthorAndMessage;
import org.cloudburstmc.protocol.bedrock.data.payload.text.MessageAndParams;
import org.cloudburstmc.protocol.bedrock.data.payload.text.TextPacketBody;
import org.cloudburstmc.protocol.bedrock.packet.CommandOutputPacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * Bedrock text and command output to Java system chat, rendered the way the Bedrock client renders
 * it (ViaBedrock's {@code ChatPackets} documents the client behaviour). Everything goes through
 * {@code ClientboundSystemChatPacket}: no signed player chat, so the Java client never validates
 * signatures.
 */
final class ChatTranslators {

    private ChatTranslators() {
    }

    static void text(ProxySession session, TextPacket packet) {
        TextPacketBody body = packet.getBody();
        TextPacketType type = packet.getMessageType();
        if (body == null || type == null) {
            return;
        }
        String text = render(TextTranslator.lang(session), type, packet.isLocalize(), body);
        if (text == null || text.isEmpty()) {
            return;
        }
        boolean overlay = type == TextPacketType.TIP || type == TextPacketType.POPUP || type == TextPacketType.JUKEBOX_POPUP;
        session.sendJava(new ClientboundSystemChatPacket(BedrockText.legacyToComponent(text), overlay));
    }

    /**
     * The {@code §} text a Bedrock client would show for this packet.
     *
     * @param localize the packet's flag: player-typed text must not be looked up in the language
     */
    static @Nullable String render(BedrockTranslation.Lookup lang, TextPacketType type, boolean localize, TextPacketBody body) {
        String message = body.getMessage() == null ? "" : body.getMessage();
        return switch (type) {
            case CHAT, WHISPER, ANNOUNCEMENT -> {
                String source = body instanceof AuthorAndMessage author && author.getPlayerName() != null
                        ? author.getPlayerName() : "";
                String text = localize ? BedrockTranslation.translate(lang, message, List.of()) : message;
                if (source.isEmpty()) {
                    yield type == TextPacketType.WHISPER ? "§7§o" + text : text;
                }
                yield switch (type) {
                    case WHISPER -> BedrockTranslation.formatKey(lang, "chat.type.text", "<%s> %s", source,
                            "§7§o" + BedrockTranslation.formatKey(lang, "commands.message.display.incoming",
                                    "%1$s whispers to you: %2$s", source, text));
                    case ANNOUNCEMENT -> BedrockTranslation.formatKey(lang, "chat.type.announcement", "[%s] %s", source, text);
                    default -> BedrockTranslation.formatKey(lang, "chat.type.text", "<%s> %s", source, text);
                };
            }
            case RAW, SYSTEM_MESSAGE, TIP -> localize ? TextTranslator.translateToLegacy(lang, message, List.of()) : message;
            case TRANSLATE, POPUP, JUKEBOX_POPUP -> {
                List<String> parameters = body instanceof MessageAndParams params ? params.getParameterList() : List.of();
                // The type itself asks for a translation, whatever the localize flag says.
                yield TextTranslator.translateToLegacy(lang, message, parameters);
            }
            case TEXT_OBJECT, TEXT_OBJECT_WHISPER, TEXT_OBJECT_ANNOUNCEMENT -> {
                String text = BedrockTranslation.rawText(lang, message);
                yield text == null ? message : text;
            }
        };
    }

    /** Command feedback for the player: one chat line per message, errors in red like on Bedrock. */
    static void commandOutput(ProxySession session, CommandOutputPacket packet) {
        CommandOriginData origin = packet.getOriginData();
        if (origin != null && origin.getCommandType() != CommandOriginType.PLAYER) {
            return; // the Bedrock client ignores output of other origins
        }
        String text = renderOutput(TextTranslator.lang(session), packet.getOutputMessages());
        if (!text.isEmpty()) {
            session.sendJava(new ClientboundSystemChatPacket(BedrockText.legacyToComponent(text), false));
        }
    }

    static String renderOutput(BedrockTranslation.Lookup lang, List<CommandOutputMessage> messages) {
        StringBuilder out = new StringBuilder();
        for (CommandOutputMessage message : messages) {
            String line = BedrockTranslation.translate(lang, message.getMessageId(), Arrays.asList(message.getParameters()));
            if (line.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(message.isSuccessful() ? "§r" : "§c").append(line);
        }
        return out.toString();
    }
}
