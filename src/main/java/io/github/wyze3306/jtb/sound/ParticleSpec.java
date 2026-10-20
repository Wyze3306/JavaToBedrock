package io.github.wyze3306.jtb.sound;

import org.geysermc.mcprotocollib.protocol.data.game.level.particle.BlockParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ColorParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.DustColorTransitionParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.DustParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ItemParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ParticleType;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.SculkChargeParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ShriekParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.TrailParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.VibrationParticleData;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;

/**
 * A Java particle emission: type, data and the {@code LevelParticles} spread/speed/count. {@code data} is null
 * for data-less types, and for types whose data comes from the Bedrock event at run time (block of a terrain
 * particle, colour of a spell, item of an item crack): {@link #isComplete()} tells which.
 * <p>
 * <b>MCProtocolLib 26.3 writer bug:</b> it has no write branch for {@link #UNWRITABLE} types, so sending one
 * leaves its data out of the stream and the client disconnects. The mapping generator never emits them and
 * {@link JavaEffects} refuses them as a last guard.
 */
public record ParticleSpec(ParticleType type, @Nullable ParticleData data, float offsetX, float offsetY, float offsetZ,
                           float speed, int count) {

    /** Particle types MCProtocolLib cannot serialise. */
    public static final Set<ParticleType> UNWRITABLE = EnumSet.of(ParticleType.GEYSER, ParticleType.GEYSER_BASE,
            ParticleType.GEYSER_POOF, ParticleType.GEYSER_PLUME, ParticleType.DRAGON_BREATH, ParticleType.EFFECT,
            ParticleType.INSTANT_EFFECT, ParticleType.TINTED_LEAVES, ParticleType.FLASH);

    public static ParticleSpec of(ParticleType type) {
        return new ParticleSpec(type, null, 0, 0, 0, 0, 0);
    }

    public static ParticleSpec of(ParticleType type, @Nullable ParticleData data) {
        return new ParticleSpec(type, data, 0, 0, 0, 0, 0);
    }

    public ParticleSpec withData(@Nullable ParticleData data) {
        return new ParticleSpec(this.type, data, this.offsetX, this.offsetY, this.offsetZ, this.speed, this.count);
    }

    public ParticleSpec withCount(int count) {
        return new ParticleSpec(this.type, this.data, this.offsetX, this.offsetY, this.offsetZ, this.speed, count);
    }

    /** True when the type carries data and none is set yet (it must come from the event). */
    public boolean needsData() {
        return this.data == null && dataClass(this.type) != null;
    }

    /** True when this emission can be written as is. */
    public boolean isComplete() {
        return isWritable(this.type, this.data);
    }

    /**
     * Whether MCProtocolLib can write {@code type} with {@code data}: the type has a writer and the data is
     * present exactly when the type needs it, with the class the writer casts to.
     */
    public static boolean isWritable(ParticleType type, @Nullable ParticleData data) {
        if (UNWRITABLE.contains(type)) {
            return false;
        }
        Class<? extends ParticleData> expected = dataClass(type);
        if (expected == null) {
            return data == null;
        }
        return expected.isInstance(data);
    }

    /** The data class MCProtocolLib's writer expects for a type, or null for data-less types. */
    static @Nullable Class<? extends ParticleData> dataClass(ParticleType type) {
        return switch (type) {
            case BLOCK, BLOCK_MARKER, FALLING_DUST, DUST_PILLAR, BLOCK_CRUMBLE -> BlockParticleData.class;
            case DUST -> DustParticleData.class;
            case DUST_COLOR_TRANSITION -> DustColorTransitionParticleData.class;
            case ENTITY_EFFECT -> ColorParticleData.class;
            case ITEM -> ItemParticleData.class;
            case SCULK_CHARGE -> SculkChargeParticleData.class;
            case SHRIEK -> ShriekParticleData.class;
            case TRAIL -> TrailParticleData.class;
            case VIBRATION -> VibrationParticleData.class;
            default -> null;
        };
    }
}
