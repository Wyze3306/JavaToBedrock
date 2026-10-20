package io.github.wyze3306.jtb.text;

import io.github.wyze3306.jtb.assets.BedrockAssets;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bedrock vanilla translations ({@code texts/*.lang}), one instance per language, merged with the
 * translations of the server's resource packs. Server-side translation is needed because the Java
 * client only knows Java keys, which differ from Bedrock's.
 * <p>
 * Languages load in the background on first use; until then lookups fall back to en_US, then to
 * the key itself. Instances are safe to read from any thread.
 */
public final class BedrockLang implements BedrockTranslation.Lookup {

    private static final Logger LOGGER = LoggerFactory.getLogger(BedrockLang.class);
    private static final String ENGLISH_CODE = "en_US";
    private static final Map<String, BedrockLang> LANGUAGES = new ConcurrentHashMap<>();
    /** Java locale ({@code fr_fr}) to language, so the per-message lookup allocates nothing. */
    private static final Map<String, BedrockLang> BY_JAVA_LOCALE = new ConcurrentHashMap<>();
    private static final List<String> AVAILABLE = List.of("bg_BG", "cs_CZ", "da_DK", "de_DE", "el_GR", "en_GB", "en_US",
            "es_ES", "es_MX", "fi_FI", "fr_CA", "fr_FR", "hu_HU", "id_ID", "it_IT", "ja_JP", "ko_KR", "nb_NO", "nl_NL",
            "pl_PL", "pt_BR", "pt_PT", "ru_RU", "sk_SK", "sv_SE", "tr_TR", "uk_UA", "zh_CN", "zh_TW");

    private final String code;
    private volatile Map<String, String> entries = Map.of();
    private final Map<String, String> packEntries = new ConcurrentHashMap<>();
    private volatile boolean loaded;
    private volatile boolean loading;
    private volatile @Nullable BedrockLang english;

    private BedrockLang(String code) {
        this.code = code;
    }

    /** The Bedrock language matching a Java locale ({@code fr_fr}), or en_US. */
    public static BedrockLang forJavaLocale(String javaLocale) {
        BedrockLang lang = BY_JAVA_LOCALE.get(javaLocale);
        if (lang == null) {
            lang = forCode(codeForJavaLocale(javaLocale, ENGLISH_CODE));
            BY_JAVA_LOCALE.put(javaLocale, lang);
        }
        return lang;
    }

    /**
     * The Bedrock language code closest to a Java locale: the same language and region when Bedrock
     * has it ({@code fr_fr} -> {@code fr_FR}), else the Bedrock variant of the language
     * ({@code fr_be} -> {@code fr_FR}, {@code es_ar} -> {@code es_MX}, {@code zh_hk} -> {@code zh_TW},
     * {@code no_no} -> {@code nb_NO}), else {@code fallback}.
     */
    public static String codeForJavaLocale(@Nullable String javaLocale, String fallback) {
        if (javaLocale == null || javaLocale.length() < 5 || javaLocale.charAt(2) != '_') {
            return fallback;
        }
        String language = javaLocale.substring(0, 2).toLowerCase(Locale.ROOT);
        String region = javaLocale.substring(3).toUpperCase(Locale.ROOT);
        if (language.equals("no") || language.equals("nn")) {
            language = "nb";
        }
        String exact = language + "_" + region;
        if (AVAILABLE.contains(exact)) {
            return exact;
        }
        String preferred = switch (language) {
            case "en" -> List.of("AU", "NZ", "IE", "ZA", "IN").contains(region) ? "en_GB" : "en_US";
            case "es" -> "es_MX";
            case "pt" -> "pt_BR";
            case "zh" -> region.equals("SG") ? "zh_CN" : "zh_TW";
            default -> null;
        };
        if (preferred != null) {
            return preferred;
        }
        // The language's own country first (fr_FR for fr_be, not fr_CA), else its only variant.
        String home = language + "_" + language.toUpperCase(Locale.ROOT);
        if (AVAILABLE.contains(home)) {
            return home;
        }
        for (String code : AVAILABLE) {
            if (code.startsWith(language + "_")) {
                return code;
            }
        }
        return fallback;
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

    private void ensureLoading() {
        if (this.loading) {
            return;
        }
        synchronized (this) {
            if (this.loading) {
                return;
            }
            this.loading = true;
        }
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
        return get(key) != null;
    }

    /**
     * The raw translation pattern of {@code key}: server packs first, then vanilla, then the same in
     * en_US; null when no language knows it.
     */
    @Override
    public @Nullable String get(String key) {
        String value = this.packEntries.get(key);
        if (value == null) {
            value = this.entries.get(key);
        }
        if (value == null && !this.code.equals(ENGLISH_CODE)) {
            BedrockLang fallback = this.english;
            if (fallback == null) {
                fallback = forCode(ENGLISH_CODE);
                this.english = fallback;
            }
            value = fallback.packEntries.get(key);
            if (value == null) {
                value = fallback.entries.get(key);
            }
        }
        return value;
    }

    /**
     * Translates {@code key} with Bedrock's placeholder syntax (see {@link #format(String, List)}).
     * Unknown keys are returned unchanged, followed by the arguments. For full Bedrock text, with
     * {@code %key} references and translated parameters, use {@link BedrockTranslation}.
     */
    public String translate(String key, String... args) {
        String pattern = get(key);
        if (pattern == null) {
            return args.length == 0 ? key : key + " " + String.join(" ", args);
        }
        return format(pattern, List.of(args));
    }

    public static String format(String pattern, String... args) {
        return format(pattern, List.of(args));
    }

    /**
     * Fills Bedrock placeholders: {@code %s %d %f} take the next argument, {@code %1$s} and
     * {@code %1} a positional one, {@code %%} is a percent sign. Missing arguments become empty
     * strings; inserted arguments are not scanned again.
     */
    public static String format(String pattern, List<String> args) {
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
                out.append(next < args.size() ? args.get(next) : "");
                next++;
                i++;
            } else if (n >= '0' && n <= '9') {
                int j = i + 1;
                int index = 0;
                while (j < length && pattern.charAt(j) >= '0' && pattern.charAt(j) <= '9') {
                    index = Math.min(index * 10 + (pattern.charAt(j) - '0'), 10_000);
                    j++;
                }
                if (j + 1 < length && pattern.charAt(j) == '$' && "sdf".indexOf(pattern.charAt(j + 1)) >= 0) {
                    j += 2;
                }
                index--;
                out.append(index >= 0 && index < args.size() ? args.get(index) : "");
                i = j - 1;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
