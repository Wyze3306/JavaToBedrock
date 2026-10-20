package io.github.wyze3306.jtb.resourcepack.convert;

import com.google.gson.JsonObject;
import io.github.wyze3306.jtb.resourcepack.JavaAssetNames;

import java.util.Locale;
import java.util.Map;

/**
 * Translations of the server's packs ({@code texts/<code>.lang}): written as Java lang files
 * ({@code assets/jtb/lang/<locale>.json}, merged by the client with the vanilla ones) and published
 * as artifacts so the proxy's {@code BedrockLang} translates the server's own keys.
 * <p>
 * Only keys the packs define are written, not the vanilla Bedrock ones. Bedrock placeholders are
 * rewritten to the only forms Java understands ({@code %s}, {@code %n$s}, {@code %%}):
 * {@code %d}/{@code %f} -> {@code %s}, {@code %n$d} -> {@code %n$s}, bare {@code %n} -> {@code %n$s};
 * {@code ~LINEBREAK~} becomes a new line; any other {@code %} is escaped.
 */
final class LangConverter {

    private LangConverter() {
    }

    static void convert(ConversionContext context) {
        for (Map.Entry<String, Map<String, String>> language : context.definitions().languages().entrySet()) {
            String code = language.getKey();
            JsonObject json = new JsonObject();
            language.getValue().forEach((key, value) -> json.addProperty(key, toJava(value)));
            context.writer().putJson("assets/" + JavaAssetNames.NAMESPACE + "/lang/" + javaLocale(code) + ".json", json);
            context.artifacts().putLanguage(code, language.getValue());
            context.count("translations", language.getValue().size());
        }
    }

    /** Java locale of a Bedrock language code: lower case, except Norwegian ({@code nb_NO} -> {@code no_no}). */
    static String javaLocale(String bedrockCode) {
        String lower = bedrockCode.toLowerCase(Locale.ROOT);
        return lower.equals("nb_no") ? "no_no" : lower;
    }

    static String toJava(String value) {
        String text = value.replace("~LINEBREAK~", "\n");
        if (text.indexOf('%') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c != '%') {
                out.append(c);
                continue;
            }
            char next = i + 1 < length ? text.charAt(i + 1) : 0;
            if (next == '%') {
                out.append("%%");
                i++;
            } else if (next == 's' || next == 'd' || next == 'f' || next == 'i') {
                out.append("%s");
                i++;
            } else if (Character.isDigit(next)) {
                int j = i + 1;
                while (j < length && Character.isDigit(text.charAt(j))) {
                    j++;
                }
                out.append('%').append(text, i + 1, j).append("$s");
                if (j + 1 < length && text.charAt(j) == '$' && "sdfi".indexOf(text.charAt(j + 1)) >= 0) {
                    j += 2;
                }
                i = j - 1;
            } else if (next == '.' && i + 3 < length && Character.isDigit(text.charAt(i + 2)) && text.charAt(i + 3) == 'f') {
                out.append("%s");
                i += 3;
            } else {
                out.append("%%");
            }
        }
        return out.toString();
    }
}
