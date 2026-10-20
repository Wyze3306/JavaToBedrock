package io.github.wyze3306.jtb.resourcepack.convert;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads the JSON dialect of Bedrock packs, which is not JSON: files start with a UTF-8 BOM, carry
 * {@code //}, {@code #} and {@code /* *}{@code /} comments and trailing commas ({@code [1,2,]},
 * {@code {"a":1,}}).
 * <p>
 * Gson's lenient mode accepts comments but turns {@code [1,]} into {@code [1,null]} and rejects
 * {@code {"a":1,}}, so a string-aware pre-pass removes the BOM, comments and trailing commas first;
 * the rest (unquoted names, single quotes) is left to the lenient reader.
 */
public final class LenientJson {

    private LenientJson() {
    }

    /**
     * @throws JsonParseException when the text is not JSON even after clean-up
     */
    public static JsonElement parse(byte[] data) {
        return parse(new String(data, StandardCharsets.UTF_8));
    }

    /**
     * @throws JsonParseException when the text is not JSON even after clean-up
     */
    public static JsonElement parse(String text) {
        try (JsonReader reader = new JsonReader(new StringReader(clean(text)))) {
            reader.setStrictness(Strictness.LENIENT);
            return JsonParser.parseReader(reader);
        } catch (IOException e) {
            throw new JsonParseException(e);
        }
    }

    /** The JSON object in {@code data}, or null when it is missing, not an object or unreadable. */
    public static @Nullable JsonObject object(byte @Nullable [] data) {
        if (data == null) {
            return null;
        }
        try {
            JsonElement element = parse(data);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Removes a leading BOM, comments and trailing commas, leaving string contents untouched. */
    static String clean(String text) {
        int length = text.length();
        StringBuilder out = new StringBuilder(length);
        int i = text.startsWith("﻿") ? 1 : 0;
        boolean inString = false;
        char quote = 0;
        int pendingComma = -1;
        while (i < length) {
            char c = text.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < length) {
                    out.append(text.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    inString = false;
                }
                i++;
                continue;
            }
            if (c == '#' || c == '/' && i + 1 < length && text.charAt(i + 1) == '/') {
                int end = text.indexOf('\n', i);
                i = end < 0 ? length : end;
                continue;
            }
            if (c == '/' && i + 1 < length && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
                continue;
            }
            if (c == '}' || c == ']') {
                if (pendingComma >= 0) {
                    out.setCharAt(pendingComma, ' ');
                }
                pendingComma = -1;
            } else if (c == ',') {
                pendingComma = out.length();
            } else if (!Character.isWhitespace(c)) {
                pendingComma = -1;
                if (c == '"' || c == '\'') {
                    inString = true;
                    quote = c;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
