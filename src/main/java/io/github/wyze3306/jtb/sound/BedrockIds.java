package io.github.wyze3306.jtb.sound;

import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.data.LevelEventType;
import org.cloudburstmc.protocol.bedrock.data.ParticleType;
import org.cloudburstmc.protocol.common.util.TypeMap;

/**
 * Wire ids of the Bedrock 1.26.60 level events and particle types. The protocol library decodes them into
 * enums whose names differ from the ones in our data (ViaBedrock names), so the mapping tables are keyed by
 * these numeric ids and joined to the enums through the codec's own type maps. The maps are protected in the
 * codec classes; this subclass only exposes them.
 */
final class BedrockIds extends Bedrock_v2225 {

    /** {@code LevelEvent} and {@code ParticleType} (at {@code 0x4000 | id}) by wire id. */
    static TypeMap<LevelEventType> levelEvents() {
        return LEVEL_EVENTS;
    }

    /** {@code ParticleType} by its own id ({@code ParticleGenericSpawn} data, {@code LEVEL_EVENTS} minus 0x4000). */
    static TypeMap<ParticleType> particleTypes() {
        return PARTICLE_TYPES;
    }

    private BedrockIds() {
    }
}
