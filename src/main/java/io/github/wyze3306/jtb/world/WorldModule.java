package io.github.wyze3306.jtb.world;

import io.github.wyze3306.jtb.translator.TranslatorModule;
import io.github.wyze3306.jtb.translator.TranslatorRegistry;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;

/**
 * Translators of the world subsystem. See docs/DEVELOPING.md for the packets it owns.
 */
public final class WorldModule implements TranslatorModule {

    @Override
    public void register(TranslatorRegistry registry) {
        registry.bedrock(StartGamePacket.class, new StartGameTranslator());
    }
}
