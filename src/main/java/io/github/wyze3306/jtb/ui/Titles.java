package io.github.wyze3306.jtb.ui;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.translator.BedrockTranslator;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.protocol.bedrock.packet.SetTitlePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundClearTitlesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetActionBarTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetSubtitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitlesAnimationPacket;

/**
 * {@code SetTitle}: title, subtitle and action bar texts (plain Bedrock text or, for the
 * {@code *_TEXT_OBJECT} types, rawtext JSON), animation times in ticks, clear and reset. Both
 * editions show a subtitle only together with the next title.
 */
final class Titles implements BedrockTranslator<SetTitlePacket> {

    @Override
    public void translate(ProxySession session, SetTitlePacket packet) {
        if (packet.getTitleType() == null) {
            return;
        }
        switch (packet.getTitleType()) {
            case CLEAR -> session.sendJava(new ClientboundClearTitlesPacket(false));
            case RESET -> session.sendJava(new ClientboundClearTitlesPacket(true));
            case TITLE, TITLE_TEXT_OBJECT -> session.sendJava(new ClientboundSetTitleTextPacket(text(session, packet)));
            case SUBTITLE, SUBTITLE_TEXT_OBJECT -> session.sendJava(new ClientboundSetSubtitleTextPacket(text(session, packet)));
            case ACTIONBAR, ACTIONBAR_TEXT_OBJECT -> session.sendJava(new ClientboundSetActionBarTextPacket(text(session, packet)));
            case TIMES -> session.sendJava(new ClientboundSetTitlesAnimationPacket(Math.max(0, packet.getFadeInTime()),
                    Math.max(0, packet.getStayTime()), Math.max(0, packet.getFadeOutTime())));
        }
    }

    private static Component text(ProxySession session, SetTitlePacket packet) {
        String text = packet.getTitleText();
        return text == null || text.isEmpty() ? Component.empty() : RawText.translator(session).apply(text);
    }
}
