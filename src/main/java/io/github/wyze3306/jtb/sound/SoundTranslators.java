package io.github.wyze3306.jtb.sound;

import io.github.wyze3306.jtb.session.ProxySession;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.ServerSoundHandle;
import org.cloudburstmc.protocol.bedrock.data.SoundEvent;
import org.cloudburstmc.protocol.bedrock.data.payload.sound.Fade;
import org.cloudburstmc.protocol.bedrock.data.payload.sound.SetPitch;
import org.cloudburstmc.protocol.bedrock.data.payload.sound.SetVolume;
import org.cloudburstmc.protocol.bedrock.data.payload.sound.SoundData;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundUpdateSoundDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelSoundEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlaySoundPacket;
import org.cloudburstmc.protocol.bedrock.packet.RecordStartedPacket;
import org.cloudburstmc.protocol.bedrock.packet.StopSoundPacket;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ParticleType;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.SoundCategory;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundStopSoundPacket;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * PlaySound, StopSound, ClientboundUpdateSoundData, RecordStarted and LevelSoundEvent.
 * <p>
 * Positions: the protocol library already divides the PlaySound block position by 8 (it is sent ×8 on the
 * wire), so it is a world position here; MCProtocolLib multiplies by 8 again when writing the Java packet.
 * (ViaBedrock reads the raw ×8 value and multiplies it a second time.)
 */
public final class SoundTranslators {

    /** Java pitch for a note block key 0-24 (also what Bedrock uses). */
    private static final float[] NOTE_PITCH = new float[25];

    static {
        for (int i = 0; i < NOTE_PITCH.length; i++) {
            NOTE_PITCH[i] = (float) Math.pow(2, (i - 12) / 12.0);
        }
    }

    /** Tunable note block instruments (Bedrock ids 0-19); mob heads play at their own pitch. */
    private static final int LAST_TUNABLE_INSTRUMENT = 19;

    private SoundTranslators() {
    }

    static void playSound(ProxySession session, PlaySoundPacket packet) {
        String name = packet.getName();
        if (name == null || name.isEmpty()) {
            return;
        }
        SoundState state = session.get(SoundState.KEY);
        JavaSound sound = state.javaSound(name);
        Vector3f position = packet.getPosition();
        ServerSoundHandle handle = packet.getServerSoundHandle();
        if (handle != null) {
            state.startHandleSound(handle.getServerSoundHandle(),
                    new SoundState.PlayingSound(sound, position, packet.getVolume(), packet.getPitch(), null, false));
            return;
        }
        if (position == null || !isFinite(position)) {
            JavaEffects.soundAtPlayer(session, sound, packet.getVolume(), packet.getPitch());
        } else if (!state.isSuppressed(SoundState.SUPPRESS_SOUND, position)) {
            JavaEffects.sound(session, sound, position.getX(), position.getY(), position.getZ(), packet.getVolume(), packet.getPitch());
        }
    }

    static void stopSound(ProxySession session, StopSoundPacket packet) {
        String name = packet.getSoundName();
        if (packet.isStopAllSounds() || name == null || name.isEmpty()) {
            session.sendJava(new ClientboundStopSoundPacket(null, null));
            return;
        }
        session.sendJava(new ClientboundStopSoundPacket(null, session.get(SoundState.KEY).javaSound(name).key()));
        if (packet.isStopMusicLegacy()) {
            session.sendJava(new ClientboundStopSoundPacket(SoundCategory.MUSIC, null));
        }
    }

    static void recordStarted(ProxySession session, RecordStartedPacket packet) {
        if (packet.getServerSoundHandle() != null && packet.getBlockPosition() != null) {
            session.get(SoundState.KEY).onRecordStarted(packet.getServerSoundHandle().getServerSoundHandle(), packet.getBlockPosition());
        }
    }

    /**
     * Server-controlled sounds. Java cannot change a playing sound: stop works, pause stops (resume restarts),
     * a fade to silence stops at the end of the fade, volume and pitch changes are remembered for a resume.
     */
    static void updateSoundData(ProxySession session, ClientboundUpdateSoundDataPacket packet) {
        if (packet.getServerSoundHandle() == null) {
            return;
        }
        long handle = packet.getServerSoundHandle().getServerSoundHandle();
        SoundState state = session.get(SoundState.KEY);
        // The protocol library carries seven slots, each holding any kind of update; servers fill unused slots
        // with a copy of the one that matters (PowerNukkitX sends seven STOP).
        SoundData[] updates = {packet.getStop(), packet.getSetVolume(), packet.getSetPitch(), packet.getFade(),
                packet.getSeekTo(), packet.getPause(), packet.getResume()};
        Float volume = null;
        Float pitch = null;
        for (SoundData update : updates) {
            if (update == null) {
                continue;
            }
            switch (update.getType()) {
                case STOP -> {
                    state.stopHandle(handle);
                    return;
                }
                case SET_VOLUME -> volume = ((SetVolume) update).getVolume();
                case SET_PITCH -> pitch = ((SetPitch) update).getPitch();
                case FADE -> {
                    Fade fade = (Fade) update;
                    if (fade.getTargetVolume() <= 0.01f) {
                        long delay = Math.max(0, (long) (fade.getDuration() * 1000));
                        session.schedule(() -> session.get(SoundState.KEY).stopHandle(handle), delay, TimeUnit.MILLISECONDS);
                    } else {
                        volume = fade.getTargetVolume();
                    }
                }
                case PAUSE -> state.pauseHandle(handle);
                case RESUME -> state.resumeHandle(handle);
                case SEEK_TO -> {
                    // Java sounds cannot seek.
                }
            }
        }
        state.updateHandle(handle, volume, pitch);
    }

    static void levelSoundEvent(ProxySession session, LevelSoundEventPacket packet) {
        SoundEvent event = packet.getSoundEvent();
        if (event == null || event == SoundEvent.UNDEFINED) {
            return;
        }
        Vector3f position = packet.getPosition();
        SoundState state = session.get(SoundState.KEY);
        switch (event) {
            case RECORD_NULL -> {
                session.sendJava(new ClientboundStopSoundPacket(SoundCategory.RECORD, null));
                return;
            }
            case NOTE -> {
                noteBlock(session, position, packet.getData());
                return;
            }
            case PLACE -> {
                if (position != null && state.isSuppressed(SoundState.SUPPRESS_PLACE | SoundState.SUPPRESS_SOUND, position)) {
                    return;
                }
            }
            case BREAK -> {
                if (position != null && state.isSuppressed(SoundState.SUPPRESS_BREAK | SoundState.SUPPRESS_SOUND, position)) {
                    return;
                }
            }
            default -> {
                if (position != null && state.isSuppressed(SoundState.SUPPRESS_SOUND, position)) {
                    return;
                }
            }
        }
        boolean global = packet.isGlobal() || position == null || !isFinite(position);
        playLevelSound(session, event, position, packet.getData(), packet.getActorIdentifier(), packet.isBaby(), global);
    }

    /**
     * Plays a LevelSoundEvent the way the Bedrock client resolves it: the entity's own sound, else the sound of
     * the block whose runtime id is {@code data}, else the event's generic sound. Public for the entity module,
     * which synthesises the mob sounds Bedrock clients make on their own (ambient, steps).
     *
     * @param entityIdentifier Bedrock entity identifier ({@code minecraft:zombie}) or empty
     * @param global           heard everywhere: played on the local player
     * @return false when the event has no sound for this entity / block
     */
    public static boolean playLevelSound(ProxySession session, SoundEvent event, @Nullable Vector3f position, int data,
                                         @Nullable String entityIdentifier, boolean baby, boolean global) {
        SoundState state = session.get(SoundState.KEY);
        String entity = normalizeEntity(entityIdentifier);
        SoundMappings.Variant variant = resolve(SoundMappings.levelSound(event),
                entity == null ? null : state.packEntitySound(event.getId(), entity), entity, data, baby, state::blockSoundType);
        if (variant == null) {
            state.reportMissing("level sound event " + event.getId() + (entity == null ? "" : " for " + entity));
            return false;
        }
        if (global || position == null) {
            JavaEffects.soundAtPlayer(session, variant.sound(), Math.min(1f, variant.volume()), variant.pitch());
        } else {
            JavaEffects.sound(session, variant.sound(), position.getX(), position.getY(), position.getZ(), variant.volume(), variant.pitch());
        }
        return true;
    }

    /**
     * Bedrock's lookup order (as in ViaBedrock's {@code tryFindSound}): the entity's sound (a resource pack's
     * first, babies +0.5 pitch), then the sound of the block whose runtime id is {@code data} (blocks without a
     * known sound type sound like stone), then the generic sound.
     */
    static SoundMappings.@Nullable Variant resolve(SoundMappings.@Nullable LevelSound sounds, SoundMappings.@Nullable Variant packVariant,
                                                  @Nullable String entity, int data, boolean baby, IntFunction<String> blockSoundType) {
        SoundMappings.Variant variant = packVariant;
        if (variant == null && entity != null && sounds != null) {
            variant = sounds.entities().get(entity);
        }
        if (variant != null) {
            return baby ? variant.baby() : variant;
        }
        if (sounds == null) {
            return null;
        }
        if (data != -1 && !sounds.blocks().isEmpty()) {
            variant = sounds.blocks().get(blockSoundType.apply(data));
            if (variant == null) {
                variant = sounds.blocks().get("stone");
            }
        }
        return variant != null ? variant : sounds.generic();
    }

    /** {@code note}: data = instrument << 8 | key (0-24). Plays the instrument and shows the coloured note. */
    private static void noteBlock(ProxySession session, @Nullable Vector3f position, int data) {
        int instrument = data >> 8;
        int key = Math.max(0, Math.min(24, data & 0xFF));
        JavaSound sound = SoundMappings.noteInstrument(instrument);
        if (sound == null) {
            sound = SoundMappings.noteInstrument(0);
        }
        if (sound == null || position == null) {
            return;
        }
        boolean tunable = instrument <= LAST_TUNABLE_INSTRUMENT;
        int x = SoundState.floor(position.getX());
        int y = SoundState.floor(position.getY());
        int z = SoundState.floor(position.getZ());
        // Java plays note blocks at volume 3 (48 blocks), from the block centre.
        JavaEffects.sound(session, sound, x + 0.5, y + 0.5, z + 0.5, 3f, tunable ? NOTE_PITCH[key] : 1f);
        if (tunable) {
            // Count 0: the offset is the "speed", which the note particle reads as its colour (key / 24).
            JavaEffects.particle(session, new ParticleSpec(ParticleType.NOTE, null, key / 24f, 0, 0, 1, 0), x + 0.5, y + 1.2, z + 0.5);
        }
    }

    private static @Nullable String normalizeEntity(@Nullable String identifier) {
        if (identifier == null || identifier.isEmpty() || identifier.equals(":")) {
            return null;
        }
        return identifier.indexOf(':') < 0 ? "minecraft:" + identifier.toLowerCase(Locale.ROOT) : identifier;
    }

    static boolean isFinite(Vector3f position) {
        return Float.isFinite(position.getX()) && Float.isFinite(position.getY()) && Float.isFinite(position.getZ());
    }
}
