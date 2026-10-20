package io.github.wyze3306.jtb.text;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Bedrock client does to localise text, as plain {@code §}-formatted strings. Pure
 * functions over a {@link Lookup} (usually a {@link BedrockLang}), usable from any thread.
 * <ul>
 *   <li>A message that is a whole translation key is replaced by its pattern.</li>
 *   <li>{@code %key} references inside text ({@code "§e%multiplayer.player.joined"}) are replaced by
 *       their translation; references to unknown keys stay as typed.</li>
 *   <li>Placeholders ({@code %s}, {@code %1$s}, {@code %d}, {@code %%}) are then filled with the
 *       parameters, which are themselves translated when they are keys ({@code %entity.zombie.name},
 *       or a bare known key such as {@code item.diamond.name}).</li>
 *   <li>Rawtext JSON ({@code {"rawtext":[{"text":..},{"translate":..,"with":..},{"score":..},{"selector":..}]}})
 *       is flattened like Bedrock does: formatting codes carry over from one element to the next.</li>
 * </ul>
 */
public final class BedrockTranslation {

    /** Translation patterns by key. */
    @FunctionalInterface
    public interface Lookup {

        /** The pattern of {@code key}, or null when unknown. */
        @Nullable String get(String key);
    }

    private static final int MAX_RAWTEXT_DEPTH = 16;

    private BedrockTranslation() {
    }

    /**
     * Localises {@code message} with {@code parameters}. Text without any {@code %} that is not a key
     * is returned as is (no allocation).
     */
    public static String translate(Lookup lang, String message, List<String> parameters) {
        if (message.isEmpty()) {
            return message;
        }
        String pattern = lang.get(message);
        if (pattern == null) {
            if (message.indexOf('%') < 0) {
                return message;
            }
            pattern = message;
        }
        pattern = expandReferences(lang, pattern);
        if (pattern.indexOf('%') < 0) {
            return pattern;
        }
        List<String> args;
        if (parameters.isEmpty()) {
            args = List.of();
        } else {
            args = new ArrayList<>(parameters.size());
            for (String parameter : parameters) {
                args.add(translateParameter(lang, parameter == null ? "" : parameter));
            }
        }
        return BedrockLang.format(pattern, args);
    }

    /**
     * Formats the pattern of {@code key} (or {@code fallback} when no language knows it) with
     * arguments inserted verbatim. Used for chat lines, whose arguments are player-typed text.
     */
    public static String formatKey(Lookup lang, String key, String fallback, String... arguments) {
        String pattern = lang.get(key);
        return BedrockLang.format(expandReferences(lang, pattern == null ? fallback : pattern), List.of(arguments));
    }

    /** A translation parameter: a {@code %key} reference, a bare known key, or literal text. */
    public static String translateParameter(Lookup lang, String parameter) {
        if (parameter.isEmpty()) {
            return parameter;
        }
        if (parameter.indexOf('.') > 0 && parameter.indexOf(' ') < 0 && parameter.charAt(0) != '%') {
            String value = lang.get(parameter);
            if (value != null) {
                return value;
            }
        }
        return parameter.indexOf('%') < 0 ? parameter : expandReferences(lang, parameter);
    }

    /**
     * Replaces every {@code %key} reference to a known key by its translation. Placeholders
     * ({@code %s}, {@code %1$s}, {@code %%}) and references to unknown keys are left in place. A
     * trailing dot is not part of a key ({@code "%item.diamond.name."}).
     */
    public static String expandReferences(Lookup lang, String text) {
        int percent = text.indexOf('%');
        if (percent < 0) {
            return text;
        }
        StringBuilder out = null;
        int copied = 0;
        int length = text.length();
        for (int i = percent; i < length; i++) {
            if (text.charAt(i) != '%') {
                continue;
            }
            if (i + 1 < length && text.charAt(i + 1) == '%') {
                i++;
                continue;
            }
            int end = i + 1;
            while (end < length && isKeyChar(text.charAt(end))) {
                end++;
            }
            if (end == i + 1) {
                continue;
            }
            String value = null;
            int keyEnd = end;
            while (keyEnd > i + 1) {
                value = lang.get(text.substring(i + 1, keyEnd));
                if (value != null || text.charAt(keyEnd - 1) != '.') {
                    break;
                }
                keyEnd--;
            }
            if (value == null) {
                i = end - 1;
                continue;
            }
            if (out == null) {
                out = new StringBuilder(length + 32);
            }
            out.append(text, copied, i).append(value);
            copied = keyEnd;
            i = keyEnd - 1;
        }
        if (out == null) {
            return text;
        }
        return out.append(text, copied, length).toString();
    }

    private static boolean isKeyChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
    }

    /** Whether {@code text} looks like a rawtext JSON object (checked cheaply, without parsing). */
    public static boolean isRawText(String text) {
        int length = text.length();
        int i = 0;
        while (i < length && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i < length && text.charAt(i) == '{' && text.contains("\"rawtext\"");
    }

    /**
     * Flattens rawtext JSON to Bedrock text.
     *
     * @return the text, or null when {@code json} is not a rawtext object (the caller then shows it as
     *         plain text)
     */
    public static @Nullable String rawText(Lookup lang, String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
        if (!root.isJsonObject()) {
            return null;
        }
        JsonElement elements = root.getAsJsonObject().get("rawtext");
        if (elements == null || !elements.isJsonArray()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        appendElements(lang, elements.getAsJsonArray(), out, 0);
        return out.toString();
    }

    private static void appendElements(Lookup lang, JsonArray elements, StringBuilder out, int depth) {
        if (depth > MAX_RAWTEXT_DEPTH) {
            return;
        }
        for (JsonElement element : elements) {
            if (element.isJsonObject()) {
                appendElement(lang, element.getAsJsonObject(), out, depth);
            } else if (element.isJsonPrimitive()) {
                out.append(element.getAsString());
            }
        }
    }

    private static void appendElement(Lookup lang, JsonObject element, StringBuilder out, int depth) {
        JsonElement value;
        if ((value = element.get("text")) != null) {
            out.append(asString(value));
        } else if ((value = element.get("translate")) != null) {
            out.append(translate(lang, asString(value), withParameters(lang, element.get("with"), depth)));
        } else if ((value = element.get("score")) != null) {
            if (value.isJsonObject()) {
                JsonElement score = value.getAsJsonObject().get("value");
                if (score != null && !score.isJsonNull()) {
                    out.append(asString(score));
                }
            }
        } else if ((value = element.get("selector")) != null) {
            // Servers resolve selectors before sending; an unresolved one is shown as typed.
            out.append(asString(value));
        } else if ((value = element.get("rawtext")) != null && value.isJsonArray()) {
            appendElements(lang, value.getAsJsonArray(), out, depth + 1);
        }
    }

    /** {@code "with"}: an array of strings (translated when keys) or a rawtext object, one parameter per element. */
    private static List<String> withParameters(Lookup lang, @Nullable JsonElement with, int depth) {
        if (with == null || with.isJsonNull()) {
            return List.of();
        }
        List<String> parameters = new ArrayList<>();
        if (with.isJsonArray()) {
            for (JsonElement parameter : with.getAsJsonArray()) {
                if (parameter.isJsonPrimitive()) {
                    parameters.add(translateParameter(lang, parameter.getAsString()));
                } else if (parameter.isJsonObject()) {
                    StringBuilder out = new StringBuilder();
                    appendElement(lang, parameter.getAsJsonObject(), out, depth + 1);
                    parameters.add(out.toString());
                }
            }
        } else if (with.isJsonObject()) {
            JsonElement elements = with.getAsJsonObject().get("rawtext");
            if (elements != null && elements.isJsonArray() && depth < MAX_RAWTEXT_DEPTH) {
                for (JsonElement element : elements.getAsJsonArray()) {
                    StringBuilder out = new StringBuilder();
                    if (element.isJsonObject()) {
                        appendElement(lang, element.getAsJsonObject(), out, depth + 1);
                    } else if (element.isJsonPrimitive()) {
                        out.append(element.getAsString());
                    }
                    parameters.add(out.toString());
                }
            }
        } else if (with instanceof JsonPrimitive primitive) {
            parameters.add(translateParameter(lang, primitive.getAsString()));
        }
        return parameters;
    }

    private static String asString(JsonElement value) {
        return value.isJsonPrimitive() ? value.getAsString() : value.isJsonNull() ? "" : value.toString();
    }
}
