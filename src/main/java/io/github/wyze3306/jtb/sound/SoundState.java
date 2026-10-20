package io.github.wyze3306.jtb.sound;

import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.mapping.JavaRegistries;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.protocol.data.game.level.event.LevelEventType;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.RainStrengthValue;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.ThunderStrengthValue;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.SoundCategory;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundStopSoundPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Per-session state of the sound subsystem: sounds started with a server sound handle (jukeboxes), weather
 * levels, the server music track, resource pack sounds and the local-prediction filter.
 * <p>
 * <b>Contract for other subsystems</b> (all on the session event loop):
 * <ul>
 *   <li>player: {@link #onLocalBlockPlaced}, {@link #onLocalBlockBroken} and {@link #suppressSoundsAt} when the
 *       Java client already played the effect itself (it predicts place sounds, break particles+sound, door,
 *       trapdoor, fence gate and button sounds, axe stripping...), so the copy the server echoes back is not
 *       played twice. Without these calls the local player hears those sounds doubled.</li>
 *   <li>world: {@link #resendWeather()} after a Java respawn / dimension change (the client forgets the weather).</li>
 *   <li>resourcepack: {@link #registerPackSound} and {@link #registerPackEntitySound} for the server packs'
 *       {@code sound_definitions.json} categories and {@code sounds.json} entity events.</li>
 *   <li>entity: {@link SoundTranslators#playLevelSound} to synthesise the mob sounds a Bedrock client plays on
 *       its own (ambient, steps), which Java clients expect from the server.</li>
 * </ul>
 */
public final class SoundState implements SessionState {

    public static final StateKey<SoundState> KEY = StateKey.of("sound", SoundState::new);

    static final int SUPPRESS_PLACE = 1;
    static final int SUPPRESS_BREAK = 2;
    static final int SUPPRESS_SOUND = 4;

    /** How long a local prediction filters the server's echo: generous for laggy connections. */
    private static final int SUPPRESS_TICKS = 30;
    private static final int SUPPRESS_SLOTS = 16;
    private static final int MAX_HANDLES = 256;
    /** Vanilla Java servers move the rain and thunder levels by 0.01 per tick. */
    private static final float WEATHER_STEP = 0.01f;

    /** A sound started with a server sound handle, kept so it can be stopped or resumed. */
    record PlayingSound(JavaSound sound, @Nullable Vector3f position, float volume, float pitch, @Nullable Vector3i jukebox,
                        boolean paused) {

        PlayingSound withPaused(boolean paused) {
            return new PlayingSound(this.sound, this.position, this.volume, this.pitch, this.jukebox, paused);
        }

        PlayingSound withVolume(float volume) {
            return new PlayingSound(this.sound, this.position, volume, this.pitch, this.jukebox, this.paused);
        }

        PlayingSound withPitch(float pitch) {
            return new PlayingSound(this.sound, this.position, this.volume, pitch, this.jukebox, this.paused);
        }
    }

    /** A disc sound waiting one tick for its {@code RecordStarted} (then it plays as a Java jukebox song). */
    private record PendingRecord(long handle, PlayingSound sound, long tick) {
    }

    private final ProxySession session;
    private final Map<Long, PlayingSound> handles = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, PlayingSound> eldest) {
            return size() > MAX_HANDLES;
        }
    };
    private final ArrayDeque<PendingRecord> pendingRecords = new ArrayDeque<>(2);

    private final long[] suppressPositions = new long[SUPPRESS_SLOTS];
    private final long[] suppressUntil = new long[SUPPRESS_SLOTS];
    private final int[] suppressKinds = new int[SUPPRESS_SLOTS];
    private int nextSuppressSlot;
    private long suppressAnyUntil = -1;

    private boolean raining;
    private float rainLevel;
    private float rainTarget;
    private float thunderLevel;
    private float thunderTarget;

    private @Nullable JavaSound music;
    private int sleeping;

    private final Map<String, SoundCategory> packCategories = new HashMap<>();
    private final Map<String, Map<String, SoundMappings.Variant>> packEntitySounds = new HashMap<>();
    private final Map<String, JavaSound> customSounds = new HashMap<>();
    private final Int2ObjectMap<String> blockSoundTypes = new Int2ObjectOpenHashMap<>();
    private final Set<String> reportedMissing = new HashSet<>();

    private SoundState(ProxySession session) {
        this.session = session;
    }

    // ------------------------------------------------------------------ local predictions

    /** The Java player placed a block at this position (its client already played the place sound). */
    public void onLocalBlockPlaced(int x, int y, int z) {
        suppress(SUPPRESS_PLACE, x, y, z);
    }

    /** The Java player broke this block (its client already showed the break particles and played the sound). */
    public void onLocalBlockBroken(int x, int y, int z) {
        suppress(SUPPRESS_BREAK, x, y, z);
    }

    /** The Java client already played the sound of an interaction with this block (door, button, stripping...). */
    public void suppressSoundsAt(int x, int y, int z) {
        suppress(SUPPRESS_SOUND, x, y, z);
    }

    private void suppress(int kind, int x, int y, int z) {
        int slot = this.nextSuppressSlot;
        this.nextSuppressSlot = (slot + 1) % SUPPRESS_SLOTS;
        this.suppressPositions[slot] = pack(x, y, z);
        this.suppressKinds[slot] = kind;
        this.suppressUntil[slot] = this.session.tickCount() + SUPPRESS_TICKS;
        this.suppressAnyUntil = this.suppressUntil[slot];
    }

    /** True when an effect of {@code kind} at this block was predicted by the Java client. */
    boolean isSuppressed(int kind, Vector3f position) {
        long now = this.session.tickCount();
        if (now > this.suppressAnyUntil) {
            return false;
        }
        long packed = pack(floor(position.getX()), floor(position.getY()), floor(position.getZ()));
        for (int i = 0; i < SUPPRESS_SLOTS; i++) {
            if ((this.suppressKinds[i] & kind) != 0 && this.suppressPositions[i] == packed && this.suppressUntil[i] >= now) {
                return true;
            }
        }
        return false;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    static int floor(float value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    // ------------------------------------------------------------------ resource pack sounds

    /**
     * Declares a sound of the server's resource packs and its Bedrock category ({@code sound_definitions.json}
     * {@code category}: ambient, block, hostile...). Unregistered pack sounds play in the master category.
     */
    public void registerPackSound(String bedrockName, @Nullable String bedrockCategory) {
        SoundCategory category = javaCategory(bedrockCategory);
        this.packCategories.put(bedrockName.toLowerCase(Locale.ROOT), category);
        this.customSounds.clear();
    }

    /**
     * Declares the sound a resource pack gives an entity event ({@code sounds.json}
     * {@code entity_sounds.entities.<entity>.events.<event>}), for custom entities.
     */
    public void registerPackEntitySound(String entityId, String event, String bedrockSound, float minVolume, float maxVolume,
                                        float minPitch, float maxPitch) {
        this.packEntitySounds.computeIfAbsent(event, e -> new HashMap<>())
                .put(entityId, new SoundMappings.Variant(javaSound(bedrockSound), minVolume, maxVolume, minPitch, maxPitch));
    }

    /** Java category of a Bedrock sound category name, master when unknown. */
    public static SoundCategory javaCategory(@Nullable String bedrockCategory) {
        if (bedrockCategory == null) {
            return SoundCategory.MASTER;
        }
        return switch (bedrockCategory.toLowerCase(Locale.ROOT)) {
            case "ambient" -> SoundCategory.AMBIENT;
            case "block" -> SoundCategory.BLOCK;
            case "bottle", "neutral", "sparkler" -> SoundCategory.NEUTRAL;
            case "hostile" -> SoundCategory.HOSTILE;
            case "music" -> SoundCategory.MUSIC;
            case "player" -> SoundCategory.PLAYER;
            case "record" -> SoundCategory.RECORD;
            case "ui" -> SoundCategory.UI;
            case "voice" -> SoundCategory.VOICE;
            case "weather" -> SoundCategory.WEATHER;
            default -> SoundCategory.MASTER;
        };
    }

    /**
     * The Java sound of any Bedrock sound name: the vanilla mapping, else the converted pack's
     * {@code jtb:<name>} event (see {@link CustomSounds}).
     */
    public JavaSound javaSound(String bedrockName) {
        JavaSound vanilla = SoundMappings.vanillaSound(bedrockName);
        if (vanilla != null) {
            return vanilla;
        }
        JavaSound custom = this.customSounds.get(bedrockName);
        if (custom == null) {
            SoundCategory category = this.packCategories.getOrDefault(bedrockName.toLowerCase(Locale.ROOT), SoundCategory.MASTER);
            custom = JavaSound.custom(bedrockName, category);
            if (this.customSounds.size() < 4096) {
                this.customSounds.put(bedrockName, custom);
            }
        }
        return custom;
    }

    /** Variant a resource pack gave to an entity event, or null. */
    SoundMappings.@Nullable Variant packEntitySound(String event, String entityId) {
        Map<String, SoundMappings.Variant> byEntity = this.packEntitySounds.get(event);
        return byEntity == null ? null : byEntity.get(entityId);
    }

    /** Bedrock block sound type of a block runtime id (cached; "stone" when unknown, like the Bedrock client). */
    String blockSoundType(int runtimeId) {
        String type = this.blockSoundTypes.get(runtimeId);
        if (type == null) {
            NbtMap state = this.session.get(BlockMapper.KEY).bedrockState(runtimeId);
            type = state == null ? null : SoundMappings.blockSoundType(state.getString("name"));
            if (type == null) {
                type = "stone";
            }
            this.blockSoundTypes.put(runtimeId, type);
        }
        return type;
    }

    /** Logs a missing mapping once per session at debug level. */
    void reportMissing(String what) {
        if (this.reportedMissing.size() < 512 && this.reportedMissing.add(what)) {
            this.session.logger().debug("[{}] No Java mapping for {}", this.session.getName(), what);
        }
    }

    // ------------------------------------------------------------------ handles and jukeboxes

    /** Starts (or defers, for discs) a sound the server identified with a handle. */
    void startHandleSound(long handle, PlayingSound sound) {
        this.handles.put(handle, sound);
        if (sound.sound().jukeboxSong() != null) {
            this.pendingRecords.add(new PendingRecord(handle, sound, this.session.tickCount()));
        } else {
            play(sound);
        }
    }

    /** {@code RecordStarted}: the handle's disc plays in the jukebox at {@code jukebox} (Java "now playing"). */
    void onRecordStarted(long handle, Vector3i jukebox) {
        PlayingSound sound = this.handles.get(handle);
        Iterator<PendingRecord> pending = this.pendingRecords.iterator();
        boolean wasPending = false;
        while (pending.hasNext()) {
            if (pending.next().handle() == handle) {
                pending.remove();
                wasPending = true;
            }
        }
        if (sound == null) {
            return;
        }
        int songId = jukeboxSongId(sound.sound());
        if (songId < 0) {
            if (wasPending) {
                play(sound);
            }
            return;
        }
        PlayingSound record = new PlayingSound(sound.sound(), sound.position(), sound.volume(), sound.pitch(), jukebox, false);
        this.handles.put(handle, record);
        if (!wasPending) {
            // Already playing as a plain sound: replace it by the jukebox song.
            this.session.sendJava(new ClientboundStopSoundPacket(sound.sound().category(), sound.sound().key()));
        }
        JavaEffects.levelEvent(this.session, LevelEventType.SOUND_PLAY_JUKEBOX_SONG.getId(), jukebox, songId);
    }

    /** Stops a handle's sound. */
    void stopHandle(long handle) {
        PlayingSound sound = this.handles.remove(handle);
        this.pendingRecords.removeIf(record -> record.handle() == handle);
        if (sound != null) {
            stop(sound);
        }
    }

    void pauseHandle(long handle) {
        PlayingSound sound = this.handles.get(handle);
        if (sound != null && !sound.paused()) {
            stop(sound);
            this.handles.put(handle, sound.withPaused(true));
        }
    }

    /** Java cannot resume a sound: a paused handle restarts from the beginning. */
    void resumeHandle(long handle) {
        PlayingSound sound = this.handles.get(handle);
        if (sound != null && sound.paused()) {
            this.handles.put(handle, sound.withPaused(false));
            if (sound.jukebox() != null) {
                int songId = jukeboxSongId(sound.sound());
                JavaEffects.levelEvent(this.session, LevelEventType.SOUND_PLAY_JUKEBOX_SONG.getId(), sound.jukebox(), songId);
            } else {
                play(sound);
            }
        }
    }

    /** Records volume / pitch updates (Java cannot change a playing sound; they apply on resume). */
    void updateHandle(long handle, @Nullable Float volume, @Nullable Float pitch) {
        PlayingSound sound = this.handles.get(handle);
        if (sound != null) {
            if (volume != null) {
                sound = sound.withVolume(volume);
            }
            if (pitch != null) {
                sound = sound.withPitch(pitch);
            }
            this.handles.put(handle, sound);
        }
    }

    private void play(PlayingSound sound) {
        Vector3f position = sound.position();
        if (position == null || !SoundTranslators.isFinite(position)) {
            JavaEffects.soundAtPlayer(this.session, sound.sound(), sound.volume(), sound.pitch());
        } else {
            JavaEffects.sound(this.session, sound.sound(), position.getX(), position.getY(), position.getZ(), sound.volume(), sound.pitch());
        }
    }

    private void stop(PlayingSound sound) {
        if (sound.jukebox() != null) {
            JavaEffects.levelEvent(this.session, LevelEventType.SOUND_STOP_JUKEBOX_SONG.getId(), sound.jukebox(), 0);
        } else {
            this.session.sendJava(new ClientboundStopSoundPacket(sound.sound().category(), sound.sound().key()));
        }
    }

    private static int jukeboxSongId(JavaSound sound) {
        String song = sound.jukeboxSong();
        if (song == null) {
            return -1;
        }
        try {
            return JavaRegistries.registry("minecraft:jukebox_song").id(song);
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ music

    /** Plays the server's music track (Bedrock custom music) at the player, replacing the client's own music. */
    void playMusic(JavaSound track, float volume) {
        this.session.sendJava(new ClientboundStopSoundPacket(SoundCategory.MUSIC, null));
        this.music = track;
        JavaEffects.soundAtPlayer(this.session, track, volume, 1f);
    }

    boolean isMusicPlaying() {
        return this.music != null;
    }

    void stopMusic() {
        if (this.music != null) {
            this.session.sendJava(new ClientboundStopSoundPacket(this.music.category(), this.music.key()));
            this.music = null;
        }
    }

    // ------------------------------------------------------------------ sleeping

    /** Records the sleeping players count; true when the change is worth showing (someone sleeps or slept). */
    boolean updateSleeping(int sleeping) {
        boolean show = sleeping > 0 || this.sleeping > 0;
        this.sleeping = sleeping;
        return show;
    }

    // ------------------------------------------------------------------ weather

    void startRain() {
        if (!this.raining) {
            this.raining = true;
            this.rainLevel = 0;
            this.session.sendJava(new ClientboundGameEventPacket(GameEvent.START_RAINING, null));
        }
        this.rainTarget = 1;
    }

    void stopRain() {
        if (this.raining) {
            this.raining = false;
            this.session.sendJava(new ClientboundGameEventPacket(GameEvent.STOP_RAINING, null));
            // The client sets the level to 1 on STOP_RAINING and expects it to fall from there.
            this.rainLevel = 1;
        }
        this.rainTarget = 0;
        this.thunderTarget = 0;
    }

    void startThunder() {
        startRain();
        this.thunderTarget = 1;
    }

    void stopThunder() {
        this.thunderTarget = 0;
    }

    public boolean isRaining() {
        return this.raining;
    }

    public boolean isThundering() {
        return this.raining && this.thunderTarget > 0;
    }

    /** Sends the current weather again (the Java client resets it when it respawns or changes dimension). */
    public void resendWeather() {
        if (this.raining) {
            this.session.sendJava(new ClientboundGameEventPacket(GameEvent.START_RAINING, null));
            this.session.sendJava(new ClientboundGameEventPacket(GameEvent.RAIN_LEVEL_CHANGE, new RainStrengthValue(this.rainLevel)));
            this.session.sendJava(new ClientboundGameEventPacket(GameEvent.THUNDER_LEVEL_CHANGE, new ThunderStrengthValue(this.thunderLevel)));
        }
    }

    @Override
    public void tick() {
        if (this.rainLevel != this.rainTarget) {
            this.rainLevel = approach(this.rainLevel, this.rainTarget);
            this.session.sendJava(new ClientboundGameEventPacket(GameEvent.RAIN_LEVEL_CHANGE, new RainStrengthValue(this.rainLevel)));
        }
        if (this.thunderLevel != this.thunderTarget) {
            this.thunderLevel = approach(this.thunderLevel, this.thunderTarget);
            this.session.sendJava(new ClientboundGameEventPacket(GameEvent.THUNDER_LEVEL_CHANGE, new ThunderStrengthValue(this.thunderLevel)));
        }
        if (!this.pendingRecords.isEmpty()) {
            long now = this.session.tickCount();
            while (!this.pendingRecords.isEmpty() && this.pendingRecords.peekFirst().tick() < now) {
                // No RecordStarted came: a disc played by /playsound or by an older server.
                play(this.pendingRecords.pollFirst().sound());
            }
        }
    }

    private static float approach(float value, float target) {
        return value < target ? Math.min(target, value + WEATHER_STEP) : Math.max(target, value - WEATHER_STEP);
    }
}
