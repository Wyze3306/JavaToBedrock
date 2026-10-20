package io.github.wyze3306.jtb.chat;

import io.github.wyze3306.jtb.text.BedrockTranslation;
import org.cloudburstmc.protocol.bedrock.data.TextPacketType;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOutputMessage;
import org.cloudburstmc.protocol.bedrock.data.payload.text.AuthorAndMessage;
import org.cloudburstmc.protocol.bedrock.data.payload.text.MessageAndParams;
import org.cloudburstmc.protocol.bedrock.data.payload.text.MessageOnly;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatTranslatorsTest {

    private static final Map<String, String> EN = Map.of(
            "chat.type.text", "<%s> %s",
            "chat.type.announcement", "[%s] %s",
            "commands.message.display.incoming", "%1$s whispers to you: %2$s",
            "commands.players.list", "There are %1$d/%2$d players online:",
            "commands.generic.unknown", "Unknown command: %s. Please check that the command exists.",
            "record.nowPlaying", "Now playing: %s",
            "item.record_cat.desc", "C418 - cat");
    private static final BedrockTranslation.Lookup LANG = EN::get;

    private static AuthorAndMessage author(String name, String message) {
        AuthorAndMessage body = new AuthorAndMessage();
        body.setPlayerName(name);
        body.setMessage(message);
        return body;
    }

    private static MessageOnly only(String message) {
        MessageOnly body = new MessageOnly();
        body.setMessage(message);
        return body;
    }

    @Test
    void chatLinesLikeBedrock() {
        assertEquals("<Steve> hello %s", ChatTranslators.render(LANG, TextPacketType.CHAT, false, author("Steve", "hello %s")));
        assertEquals("hello", ChatTranslators.render(LANG, TextPacketType.CHAT, false, author("", "hello")));
        assertEquals("[Server] restart soon", ChatTranslators.render(LANG, TextPacketType.ANNOUNCEMENT, false, author("Server", "restart soon")));
        assertEquals("<Alex> §7§oAlex whispers to you: psst",
                ChatTranslators.render(LANG, TextPacketType.WHISPER, false, author("Alex", "psst")));
    }

    @Test
    void localizeFlagControlsLookups() {
        assertEquals("§e%commands.players.list", ChatTranslators.render(LANG, TextPacketType.RAW, false, only("§e%commands.players.list")));
        assertEquals("§eThere are / players online:",
                ChatTranslators.render(LANG, TextPacketType.RAW, true, only("§e%commands.players.list")));
    }

    @Test
    void translateTypesAlwaysTranslate() {
        MessageAndParams body = new MessageAndParams();
        body.setMessage("record.nowPlaying");
        body.getParameterList().add("%item.record_cat.desc");
        assertEquals("Now playing: C418 - cat", ChatTranslators.render(LANG, TextPacketType.JUKEBOX_POPUP, false, body));
    }

    @Test
    void textObjects() {
        assertEquals("§aHi", ChatTranslators.render(LANG, TextPacketType.TEXT_OBJECT, false, only("{\"rawtext\":[{\"text\":\"§aHi\"}]}")));
        assertEquals("not json", ChatTranslators.render(LANG, TextPacketType.TEXT_OBJECT, false, only("not json")));
    }

    @Test
    void commandOutputColours() {
        String output = ChatTranslators.renderOutput(LANG, List.of(
                new CommandOutputMessage(false, "§f%commands.players.list", new String[]{"1", "20"}),
                new CommandOutputMessage(false, "commands.generic.unknown", new String[]{"foo"}),
                new CommandOutputMessage(true, "Tester5", new String[0])));
        assertEquals("§c§fThere are 1/20 players online:\n§cUnknown command: foo. Please check that the command exists.\n§rTester5", output);
    }
}
