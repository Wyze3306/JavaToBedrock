package io.github.wyze3306.jtb.text;

import io.github.wyze3306.jtb.assets.BedrockAssets;
import io.github.wyze3306.jtb.auth.ClientDataBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bedrock vanilla translations ({@code texts/*.lang}), one instance per language, merged with the
 * translations of the server's resource packs. Server-side translation is needed because the Java
 * client only knows Java keys, which differ from Bedrock's.
 * <p>
 * Languages load in the background on first use; until then {@link #translate} falls back to
 * en_US, then to the key itself.
 */
public final class BedrockLang {

    private static final Logger LOGGER = LoggerFactory.getLogger(BedrockLang.class);
    private static final Map<String, BedrockLang> LANGUAGES = new ConcurrentHashMap<>();
    private static final List<String> AVAILABLE = List.of("bg_BG", "cs_CZ", "da_DK", "de_DE", "el_GR", "en_GB", "en_US",
            "es_ES", "es_MX", "fi_FI", "fr_CA", "fr_FR", "hu_HU", "id_ID", "it_IT", "ja_JP", "ko_KR", "nb_NO", "nl_NL",
            "pl_PL", "pt_BR", "pt_PT", "ru_RU", "sk_SK", "sv_SE", "tr_TR", "uk_UA", "zh_CN", "zh_TW");

    private final String code;
    private volatile Map<String, String> entries = Map.of();
    private final Map<String, String> packEntries = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    private BedrockLang(String code) {
        this.code = code;
    }

    /** The Bedrock language matching a Java locale ({@code fr_fr}), or en_US. */
    public static BedrockLang forJavaLocale(String javaLocale) {
        String code = ClientDataBuilder.bedrockLanguage(javaLocale, "en_US");
        if (!AVAILABLE.contains(code)) {
            code = "en_US";
        }
        return forCode(code);
    }

    public static BedrockLang forCode(String code) {
        BedrockLang lang = LANGUAGES.computeIfAbsent(code, BedrockLang::new);
        lang.ensureLoading();
        return lang;
    }

    /** Starts loading the most common languages so the first players do not see raw keys. */
    public static void preload(String... codes) {
        for (String code : codes) {
            forCode(code);
        }
    }

    private boolean loading;

    private synchronized void ensureLoading() {
        if (this.loading) {
            return;
        }
        this.loading = true;
        CompletableFuture.runAsync(() -> {
            Path file = BedrockAssets.get("resource_pack/texts/" + this.code + ".lang");
            if (file != null) {
                try {
                    this.entries = parse(Files.readAllLines(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    LOGGER.warn("Could not read Bedrock language {}", this.code, e);
                }
            }
            this.loaded = true;
        });
    }

    public String code() {
        return this.code;
    }

    public boolean isLoaded() {
        return this.loaded;
    }

    /**
     * Parses a {@code .lang} file: {@code key=value}, {@code ##} comments, and trailing
     * {@code \t#} comments after values.
     */
    public static Map<String, String> parse(List<String> lines) {
        Map<String, String> map = new HashMap<>(lines.size() * 2);
        for (String raw : lines) {
            String line = raw.startsWith("﻿") ? raw.substring(1) : raw;
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String value = line.substring(eq + 1);
            int comment = value.indexOf("\t#");
            if (comment >= 0) {
                value = value.substring(0, comment);
            }
            map.put(line.substring(0, eq).trim(), value.stripTrailing());
        }
        return map;
    }

    /** Adds translations coming from the server's resource packs (they override vanilla). */
    public void addPackEntries(Map<String, String> entries) {
        this.packEntries.putAll(entries);
    }

    public boolean has(String key) {
        return this.packEntries.containsKey(key) || this.entries.containsKey(key)
                || (!this.code.equals("en_US") && forCode("en_US").hasOwn(key));
    }

    private boolean hasOwn(String key) {
        return this.packEntries.containsKey(key) || this.entries.containsKey(key);
    }

    private String raw(String key) {
        String value = this.packEntries.get(key);
        if (value == null) {
            value = this.entries.get(key);
        }
        if (value == null && !this.code.equals("en_US")) {
            BedrockLang english = forCode("en_US");
            value = english.packEntries.get(key);
            if (value == null) {
                value = english.entries.get(key);
            }
        }
        return value;
    }

    /**
     * Translates {@code key} with Bedrock's placeholder syntax: {@code %s}, {@code %d},
     * {@code %1$s} / {@code %1} (positional) and {@code %%}. Unknown keys are returned unchanged.
     */
    public String translate(String key, String... args) {
        String pattern = raw(key);
        if (pattern == null) {
            return args.length == 0 ? key : key + " " + String.join(" ", args);
        }
        return format(pattern, args);
    }

    public static String format(String pattern, String... args) {
        if (pattern.indexOf('%') < 0) {
            return pattern;
        }
        StringBuilder out = new StringBuilder(pattern.length() + 16);
        int next = 0;
        int length = pattern.length();
        for (int i = 0; i < length; i++) {
            char c = pattern.charAt(i);
            if (c != '%' || i + 1 >= length) {
                out.append(c);
                continue;
            }
            char n = pattern.charAt(i + 1);
            if (n == '%') {
                out.append('%');
                i++;
            } else if (n == 's' || n == 'd' || n == 'f') {
                out.append(next < args.length ? args[next] : "");
                next++;
                i++;
            } else if (Character.isDigit(n)) {
                int j = i + 1;
                while (j < length && Character.isDigit(pattern.charAt(j))) {
                    j++;
                }
                int index = Integer.parseInt(pattern.substring(i + 1, j)) - 1;
                if (j + 1 < length && pattern.charAt(j) == '$' && "sdf".indexOf(pattern.charAt(j + 1)) >= 0) {
                    j += 2;
                }
                out.append(index >= 0 && index < args.length ? args[index] : "");
                i = j - 1;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
