package io.github.wyze3306.jtb.sound;

import io.github.wyze3306.jtb.player.PlayerState;
import io.github.wyze3306.jtb.session.ProxySession;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.level.event.LevelEventType;
import org.geysermc.mcprotocollib.protocol.data.game.level.event.UnknownLevelEventData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.Particle;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ParticleData;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSoundEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelParticlesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSoundPacket;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Sends Java sounds, particles and level events. Every particle goes through {@link #particle}, which refuses
 * emissions MCProtocolLib cannot write (see {@link ParticleSpec#UNWRITABLE}): one such packet would kick the
 * player.
 */
public final class JavaEffects {

    private static final Logger LOGGER = LoggerFactory.getLogger(JavaEffects.class);

    private JavaEffects() {
    }

    /** Plays a sound at a position. Volume above 1 widens the range (16 blocks per unit), like on both editions. */
    public static void sound(ProxySession session, JavaSound sound, double x, double y, double z, float volume, float pitch) {
        session.sendJava(new ClientboundSoundPacket(sound.sound(), sound.category(), x, y, z, volume, pitch,
                ThreadLocalRandom.current().nextLong()));
    }

    /**
     * Plays a sound attached to the local player: it follows the camera and is never out of range. Used for
     * Bedrock "global" sounds, music and sounds without a position.
     */
    public static void soundAtPlayer(ProxySession session, JavaSound sound, float volume, float pitch) {
        session.sendJava(new ClientboundSoundEntityPacket(sound.sound(), sound.category(),
                session.get(PlayerState.KEY).javaEntityId(), volume, pitch, ThreadLocalRandom.current().nextLong()));
    }

    /** Sends a Java level event (block position) with raw data. */
    public static void levelEvent(ProxySession session, int javaEvent, Vector3i position, int data) {
        LevelEventType type = LevelEventType.from(javaEvent);
        if (type == null) {
            LOGGER.debug("[{}] Unknown Java level event {}", session.getName(), javaEvent);
            return;
        }
        session.sendJava(new ClientboundLevelEventPacket(type, position, new UnknownLevelEventData(data), false));
    }

    /** Emits a particle mapping as is. */
    public static void particle(ProxySession session, ParticleSpec spec, double x, double y, double z) {
        particle(session, spec, spec.data(), x, y, z);
    }

    /**
     * Emits a particle mapping with {@code data} (the mapping's own, or data derived from the Bedrock event).
     * Emissions that cannot be serialised are dropped.
     */
    public static void particle(ProxySession session, ParticleSpec spec, @Nullable ParticleData data, double x, double y, double z) {
        if (!ParticleSpec.isWritable(spec.type(), data)) {
            LOGGER.debug("[{}] Dropping unwritable particle {} {}", session.getName(), spec.type(), data);
            return;
        }
        session.sendJava(new ClientboundLevelParticlesPacket(new Particle(spec.type(), data), false, false, x, y, z,
                spec.offsetX(), spec.offsetY(), spec.offsetZ(), spec.speed(), spec.speed(), spec.speed(), spec.count(),
                ClientboundLevelParticlesPacket.RandomizationType.DEFAULT));
    }
}
