package io.github.wyze3306.jtb.resourcepack.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.wyze3306.jtb.resourcepack.JavaAssetNames;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sounds of the server's packs.
 * <ul>
 *     <li>Events of {@code sounds/sound_definitions.json} whose files a server pack ships become Java
 *     custom sound events {@code jtb:<event>} ({@link JavaAssetNames#soundEvent}) in
 *     {@code assets/jtb/sounds.json}, their {@code .ogg} files copied to {@code assets/jtb/sounds/}.
 *     Vanilla events re-defined with new files are included too: the sound translator plays the
 *     custom event when {@code ConversionArtifacts.soundEvent(name)} has it.</li>
 *     <li>A server pack shipping a vanilla Bedrock sound file ({@code sounds/mob/zombie/say1.ogg})
 *     replaces the Java vanilla file(s) it corresponds to ({@code mappings/resourcepack/sound_files.json}).</li>
 * </ul>
 * Only Ogg Vorbis plays on Java: {@code .fsb} (FMOD), {@code .wav} and Ogg Opus files are skipped.
 */
final class SoundConverter {

    private static final List<String> EXTENSIONS = List.of(".ogg", ".fsb", ".wav");

    private SoundConverter() {
    }

    static void convert(ConversionContext context) {
        JsonObject sounds = new JsonObject();
        for (Map.Entry<String, JsonObject> event : context.definitions().soundDefinitions().entrySet()) {
            JsonObject javaEvent = event(context, event.getValue());
            if (javaEvent != null) {
                String key = JavaAssetNames.soundEventPath(event.getKey());
                sounds.add(key, javaEvent);
                context.artifacts().putSoundEvent(event.getKey(), JavaAssetNames.NAMESPACE + ":" + key);
                context.count("sound events");
            }
        }
        if (!sounds.isEmpty()) {
            context.writer().putJson("assets/" + JavaAssetNames.NAMESPACE + "/sounds.json", sounds);
        }
        vanillaFiles(context);
    }

    private static @Nullable JsonObject event(ConversionContext context, JsonObject definition) {
        JsonElement list = definition.get("sounds");
        JsonArray entries = list instanceof JsonArray array ? array : new JsonArray();
        if (list != null && !list.isJsonArray()) {
            entries.add(list);
        }
        Integer attenuation = null;
        if (definition.get("max_distance") instanceof JsonPrimitive max && max.isNumber() && max.getAsFloat() > 0) {
            attenuation = Math.max(1, Math.round(max.getAsFloat()));
        }
        JsonArray javaSounds = new JsonArray();
        for (JsonElement entry : entries) {
            JsonObject sound = entry instanceof JsonObject object ? object : new JsonObject();
            String name = entry.isJsonPrimitive() ? entry.getAsString() : string(sound, "name");
            if (name.isEmpty()) {
                continue;
            }
            String file = copySound(context, name);
            if (file == null) {
                continue;
            }
            JsonObject javaSound = new JsonObject();
            javaSound.addProperty("name", JavaAssetNames.NAMESPACE + ":" + file);
            float volume = number(sound, "volume", 1);
            if (volume > 0 && volume != 1) {
                javaSound.addProperty("volume", Math.min(1, volume));
            }
            float pitch = number(sound, "pitch", 1);
            if (pitch > 0 && pitch != 1) {
                javaSound.addProperty("pitch", pitch);
            }
            int weight = Math.round(number(sound, "weight", 1));
            if (weight > 1) {
                javaSound.addProperty("weight", weight);
            }
            if (sound.has("stream") && sound.get("stream").isJsonPrimitive() && sound.get("stream").getAsBoolean()) {
                javaSound.addProperty("stream", true);
            }
            if (attenuation != null) {
                javaSound.addProperty("attenuation_distance", attenuation);
            }
            javaSounds.add(javaSound);
        }
        if (javaSounds.isEmpty()) {
            return null;
        }
        JsonObject event = new JsonObject();
        event.add("sounds", javaSounds);
        String subtitle = string(definition, "subtitle");
        if (!subtitle.isEmpty()) {
            event.addProperty("subtitle", subtitle);
        }
        return event;
    }

    /**
     * Copies the sound file {@code name} ({@code sounds/x/y}, extension optional) from the server's
     * packs into {@code assets/jtb/sounds/}.
     *
     * @return the Java sound path ({@code x/y}), or null when no pack ships a playable file
     */
    private static @Nullable String copySound(ConversionContext context, String name) {
        String base = name.toLowerCase(Locale.ROOT).replace('\\', '/');
        for (String extension : EXTENSIONS) {
            if (base.endsWith(extension)) {
                base = base.substring(0, base.length() - extension.length());
                break;
            }
        }
        for (String extension : EXTENSIONS) {
            PackFiles.PackFile file = context.stack().file(base + extension);
            if (file == null) {
                continue;
            }
            if (!isVorbis(file.data())) {
                context.count("sound files skipped (not Ogg Vorbis)");
                return null;
            }
            String relative = base.startsWith("sounds/") ? base.substring("sounds/".length()) : base;
            String path = JavaAssetNames.path(relative);
            context.writer().put("assets/" + JavaAssetNames.NAMESPACE + "/sounds/" + path + ".ogg", file.data());
            return path;
        }
        return null;
    }

    /** Ogg container ({@code OggS}) whose first packet is a Vorbis identification header. */
    static boolean isVorbis(byte[] data) {
        if (data.length < 35 || data[0] != 'O' || data[1] != 'g' || data[2] != 'g' || data[3] != 'S') {
            return false;
        }
        int segments = data[26] & 0xFF;
        int packet = 27 + segments;
        return data.length >= packet + 7 && data[packet] == 1
                && "vorbis".equals(new String(data, packet + 1, 6, StandardCharsets.US_ASCII));
    }

    private static void vanillaFiles(ConversionContext context) {
        for (PackFiles pack : context.stack().bottomToTop()) {
            for (String path : pack.list("sounds/")) {
                if (!path.endsWith(".ogg")) {
                    continue;
                }
                String bedrock = path.substring("sounds/".length(), path.length() - ".ogg".length());
                List<String> targets = context.tables().javaSoundFiles(bedrock);
                if (targets.isEmpty()) {
                    continue;
                }
                byte[] data = pack.get(path);
                if (data == null || !isVorbis(data)) {
                    continue;
                }
                for (String target : targets) {
                    context.writer().put("assets/minecraft/sounds/" + target + ".ogg", data);
                    context.count("vanilla sound overrides");
                }
            }
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static float number(JsonObject object, String key, float fallback) {
        JsonElement element = object.get(key);
        try {
            return element != null && element.isJsonPrimitive() ? element.getAsFloat() : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
