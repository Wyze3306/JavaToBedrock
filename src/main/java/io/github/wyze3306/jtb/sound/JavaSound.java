package io.github.wyze3306.jtb.sound;

import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.BuiltinSound;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.CustomSound;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.Sound;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.SoundCategory;
import org.jspecify.annotations.Nullable;

/**
 * A Java sound event as the proxy plays it: the event (built-in registry entry or inline custom event), its
 * category, its key (what {@code StopSound} names) and the Bedrock hearing distances.
 * <p>
 * Both editions hear a sound up to 16 blocks per unit of volume (at least 16) by default. Bedrock sound
 * definitions can override that with a {@code max_distance} (fixed range: note blocks 48, discs 64, warden
 * 80-160...) and a {@code min_distance} (full volume radius: thunder, rain 100); {@link #wireSound(float)}
 * then sends the event inline with a fixed Java range.
 *
 * @param maxDistance Bedrock fixed hearing range, 0 for the volume rule
 * @param minDistance Bedrock full-volume radius, 0 for none
 */
public record JavaSound(Sound sound, SoundCategory category, Key key, float maxDistance, float minDistance) {

    /** A vanilla sound event with the default (volume based) range. */
    public static JavaSound builtin(BuiltinSound sound, SoundCategory category) {
        return builtin(sound, category, 0, 0);
    }

    /** A vanilla sound event with Bedrock hearing distances. */
    public static JavaSound builtin(BuiltinSound sound, SoundCategory category, float maxDistance, float minDistance) {
        return new JavaSound(sound, category, Key.key(Key.MINECRAFT_NAMESPACE, sound.getName()), maxDistance, minDistance);
    }

    /**
     * A Bedrock sound Java does not know, served by the converted resource pack as {@code jtb:<name>} (see
     * {@link CustomSounds}).
     */
    public static JavaSound custom(String bedrockName, SoundCategory category) {
        return custom(bedrockName, category, 0, 0);
    }

    /** A resource pack sound with its {@code sound_definitions.json} distances. */
    public static JavaSound custom(String bedrockName, SoundCategory category, float maxDistance, float minDistance) {
        Key key = CustomSounds.key(bedrockName);
        return new JavaSound(new CustomSound(key.asString(), false, 16f), category, key, maxDistance, minDistance);
    }

    /**
     * The event to put in a sound packet played at {@code volume}: the plain event when the Java default range
     * ({@code 16 * max(1, volume)}) is right, else an inline event with Bedrock's range. Java fades a sound
     * linearly to silence at its range, so a full-volume radius becomes a range twice as large.
     */
    public Sound wireSound(float volume) {
        if (this.maxDistance <= 0 && this.minDistance <= 0) {
            return this.sound;
        }
        float legacy = 16f * Math.max(1f, volume);
        float range = this.maxDistance > 0 ? this.maxDistance : legacy;
        if (this.minDistance > 0) {
            range = Math.max(range, this.minDistance * 2);
        }
        return range == legacy ? this.sound : new CustomSound(this.key.asString(), true, range);
    }

    /** A Jukebox song id for disc sounds ({@code music_disc.cat} -> {@code minecraft:cat}), or null. */
    public @Nullable String jukeboxSong() {
        String value = this.key.value();
        return this.sound instanceof BuiltinSound && value.startsWith("music_disc.")
                ? Key.MINECRAFT_NAMESPACE + ":" + value.substring("music_disc.".length())
                : null;
    }
}
