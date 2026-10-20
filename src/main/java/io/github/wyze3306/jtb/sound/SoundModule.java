package io.github.wyze3306.jtb.sound;

import io.github.wyze3306.jtb.translator.TranslatorModule;
import io.github.wyze3306.jtb.translator.TranslatorRegistry;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundUpdateSoundDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelEventGenericPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelSoundEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlaySoundPacket;
import org.cloudburstmc.protocol.bedrock.packet.RecordStartedPacket;
import org.cloudburstmc.protocol.bedrock.packet.SpawnParticleEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.StopSoundPacket;

/**
 * Translators of the sound subsystem: sounds, particles and level events. See docs/DEVELOPING.md for the
 * packets it owns. Registering also loads {@link SoundMappings}, at proxy start.
 */
public final class SoundModule implements TranslatorModule {

    @Override
    public void register(TranslatorRegistry registry) {
        SoundMappings.load();
        registry.bedrock(PlaySoundPacket.class, SoundTranslators::playSound);
        registry.bedrock(StopSoundPacket.class, SoundTranslators::stopSound);
        registry.bedrock(ClientboundUpdateSoundDataPacket.class, SoundTranslators::updateSoundData);
        registry.bedrock(RecordStartedPacket.class, SoundTranslators::recordStarted);
        registry.bedrock(LevelSoundEventPacket.class, SoundTranslators::levelSoundEvent);
        registry.bedrock(LevelEventPacket.class, LevelEventTranslators::levelEvent);
        registry.bedrock(LevelEventGenericPacket.class, LevelEventTranslators::levelEventGeneric);
        registry.bedrock(SpawnParticleEffectPacket.class, LevelEventTranslators::spawnParticleEffect);
    }
}
