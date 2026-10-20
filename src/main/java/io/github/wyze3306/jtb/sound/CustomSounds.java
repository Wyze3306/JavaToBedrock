package io.github.wyze3306.jtb.sound;

import net.kyori.adventure.key.Key;

import java.util.Locale;

/**
 * Naming convention of the Java sound events that stand for Bedrock sounds Java does not have
 * (resource pack sounds): {@code jtb:<name>}, where {@code <name>} is the Bedrock sound name lower-cased with
 * every character outside {@code [a-z0-9_.-/]} replaced by {@code _}. The resource pack converter writes its
 * {@code assets/jtb/sounds.json} entries with exactly these ids; both sides must call {@link #path(String)}.
 */
public final class CustomSounds {

    /** Namespace of the converted resource pack's sound events. */
    public static final String NAMESPACE = "jtb";

    private CustomSounds() {
    }

    /** Sound event path (without namespace) for a Bedrock sound name, e.g. {@code Mob.Boss:Roar} -> {@code mob.boss_roar}. */
    public static String path(String bedrockName) {
        if (bedrockName.isEmpty()) {
            return "_";
        }
        String lower = bedrockName.toLowerCase(Locale.ROOT);
        StringBuilder builder = null;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-' || c == '/';
            if (!valid) {
                if (builder == null) {
                    builder = new StringBuilder(lower);
                }
                builder.setCharAt(i, '_');
            }
        }
        return builder == null ? lower : builder.toString();
    }

    /** Full Java sound event key ({@code jtb:<path>}) for a Bedrock sound name. */
    public static Key key(String bedrockName) {
        return Key.key(NAMESPACE, path(bedrockName));
    }
}
