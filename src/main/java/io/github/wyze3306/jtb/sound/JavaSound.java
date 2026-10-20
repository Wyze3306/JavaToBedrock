package io.github.wyze3306.jtb.sound;

import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.BuiltinSound;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.CustomSound;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.Sound;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.SoundCategory;
import org.jspecify.annotations.Nullable;

/**
 * A Java sound event as the proxy plays it: the event (built-in registry entry or inline custom event), its
 * category and its key (what {@code StopSound} names).
 */
public record JavaSound(Sound sound, SoundCategory category, Key key) {

    /** A vanilla sound event. */
    public static JavaSound builtin(BuiltinSound sound, SoundCategory category) {
        return new JavaSound(sound, category, Key.key(Key.MINECRAFT_NAMESPACE, sound.getName()));
    }

    /**
     * A Bedrock sound Java does not know, served by the converted resource pack as {@code jtb:<name>} (see
     * {@link CustomSounds}). Its range follows the volume like vanilla events (16 blocks per unit of volume).
     */
    public static JavaSound custom(String bedrockName, SoundCategory category) {
        Key key = CustomSounds.key(bedrockName);
        return new JavaSound(new CustomSound(key.asString(), false, 16f), category, key);
    }

    /** A Jukebox song id for disc sounds ({@code music_disc.cat} -> {@code minecraft:cat}), or null. */
    public @Nullable String jukeboxSong() {
        String value = this.key.value();
        return this.sound instanceof BuiltinSound && value.startsWith("music_disc.")
                ? Key.MINECRAFT_NAMESPACE + ":" + value.substring("music_disc.".length())
                : null;
    }
}
