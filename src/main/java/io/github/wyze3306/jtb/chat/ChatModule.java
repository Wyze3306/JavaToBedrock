package io.github.wyze3306.jtb.chat;

import io.github.wyze3306.jtb.text.BedrockLang;
import io.github.wyze3306.jtb.translator.TranslatorModule;
import io.github.wyze3306.jtb.translator.TranslatorRegistry;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import org.cloudburstmc.protocol.bedrock.packet.CommandOutputPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetCommandsEnabledPacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateSoftEnumPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatAckPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandSignedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatSessionUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundCommandSuggestionPacket;

/**
 * Translators of the chat subsystem. See docs/DEVELOPING.md for the packets it owns.
 * <p>
 * Secure chat: the proxy announces {@code enforcesSecureChat=false} and only ever sends system chat,
 * so the client's signatures, chat sessions and acknowledgements are accepted and ignored; nothing
 * can make the client disconnect for chat validation.
 */
public final class ChatModule implements TranslatorModule {

    @Override
    public void register(TranslatorRegistry registry) {
        // The fallback language of every other one: start downloading it with the proxy.
        BedrockLang.preload("en_US");

        registry.bedrock(TextPacket.class, ChatTranslators::text);
        registry.bedrock(CommandOutputPacket.class, ChatTranslators::commandOutput);
        registry.bedrock(AvailableCommandsPacket.class, (session, packet) -> session.get(ChatState.KEY).onAvailableCommands(packet));
        registry.bedrock(UpdateSoftEnumPacket.class, (session, packet) -> session.get(ChatState.KEY).onSoftEnum(packet));
        registry.bedrock(SetCommandsEnabledPacket.class,
                (session, packet) -> session.get(ChatState.KEY).onCommandsEnabled(packet.isCommandsEnabled()));

        registry.java(ServerboundChatPacket.class, (session, packet) -> session.get(ChatState.KEY).sendChat(packet.getMessage()));
        registry.java(ServerboundChatCommandPacket.class,
                (session, packet) -> session.get(ChatState.KEY).sendCommand(packet.getCommand()));
        registry.java(ServerboundChatCommandSignedPacket.class,
                (session, packet) -> session.get(ChatState.KEY).sendCommand(packet.getCommand()));
        registry.java(ServerboundCommandSuggestionPacket.class,
                (session, packet) -> session.get(ChatState.KEY).suggest(packet.getTransactionId(), packet.getText()));
        registry.java(ServerboundChatAckPacket.class, (session, packet) -> {
        });
        registry.java(ServerboundChatSessionUpdatePacket.class, (session, packet) -> {
        });
    }
}
