package io.github.wyze3306.jtb.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Immutable view of {@code config.yml}. Missing keys fall back to the defaults shipped in the jar,
 * so an old configuration file keeps working when new options are added.
 * <p>
 * Every value can be overridden by an environment variable named {@code JTB_} followed by its path
 * in upper case with dots and dashes replaced by underscores ({@code bedrock.address} &rarr;
 * {@code JTB_BEDROCK_ADDRESS}, {@code resource-packs.public-url} &rarr;
 * {@code JTB_RESOURCE_PACKS_PUBLIC_URL}), which is how containers are usually configured.
 */
public final class ProxyConfig {

    public enum AuthMode {
        OFFLINE, MICROSOFT, TRUSTED
    }

    public enum Lighting {
        COMPUTE, FULL_BRIGHT
    }

    public record Java(String bind, int port, boolean onlineMode, int compressionThreshold, String motd, int maxPlayers) {
    }

    public record Bedrock(String address, int port, AuthMode auth, String trustedKeyFile, String nethernetIdentityFile,
                          String usernamePrefix,
                          int connectTimeoutSeconds, String defaultLanguage, boolean followTransfers, Reconnect reconnect,
                          Map<String, String> servers, Map<String, String> forcedHosts) {
    }

    /** The waiting room used while the Bedrock server is down. */
    public record Reconnect(int timeoutSeconds, List<String> shutdownMessages) {
    }

    public record ResourcePacks(boolean enabled, String httpBind, int httpPort, String publicUrl, boolean required,
                                String prompt, String cacheDirectory) {
    }

    public record World(int maxViewDistance, Lighting lighting, int chunkThreads) {
    }

    public record Features(boolean forms, boolean chestMenus, boolean scoreboardNumbers, boolean customBlocks, boolean customEntities,
                           boolean javaSkins) {
    }

    public record Skins(boolean enabled, boolean geyserApi, String mineskinApiKey) {
    }

    public record Debug(boolean logUnhandledBedrock, boolean logUnhandledJava) {
    }

    private final Java java;
    private final Bedrock bedrock;
    private final ResourcePacks resourcePacks;
    private final World world;
    private final Features features;
    private final Skins skins;
    private final Debug debug;

    private ProxyConfig(Section root) {
        Section j = root.section("java");
        this.java = new Java(j.string("bind"), j.integer("port"), j.bool("online-mode"),
                j.integer("compression-threshold"), j.string("motd"), j.integer("max-players"));

        Section b = root.section("bedrock");
        AuthMode auth = AuthMode.valueOf(b.string("auth").toUpperCase(Locale.ROOT));
        this.bedrock = new Bedrock(b.string("address"), b.integer("port"), auth, b.string("trusted-key-file"),
                b.string("nethernet-identity-file"),
                usernamePrefix(b.string("username-prefix"), auth), b.integer("connect-timeout"), b.string("default-language"),
                b.bool("follow-transfers"),
                new Reconnect(b.section("reconnect").integer("timeout"), b.section("reconnect").strings("shutdown-messages")),
                b.stringMap("servers"), b.stringMap("forced-hosts"));

        Section r = root.section("resource-packs");
        this.resourcePacks = new ResourcePacks(r.bool("enabled"), r.string("http-bind"), r.integer("http-port"),
                r.string("public-url"), r.bool("required"), r.string("prompt"), r.string("cache-directory"));

        Section w = root.section("world");
        this.world = new World(w.integer("max-view-distance"),
                Lighting.valueOf(w.string("lighting").toUpperCase(Locale.ROOT).replace('-', '_')),
                w.integer("chunk-threads"));

        Section f = root.section("features");
        this.features = new Features(f.bool("forms"), f.bool("chest-menus"), f.bool("scoreboard-numbers"), f.bool("custom-blocks"), f.bool("custom-entities"), f.bool("java-skins"));

        Section s = root.section("skins");
        this.skins = new Skins(s.bool("enabled"), s.bool("geyser-api"), s.string("mineskin-api-key"));

        Section d = root.section("debug");
        this.debug = new Debug(d.bool("log-unhandled-bedrock"), d.bool("log-unhandled-java"));
    }

    /**
     * The prefix of the Bedrock names of Java players. {@code auto} is {@code "."} in trusted mode, the
     * default {@code java-bridge.namePrefix} PowerNukkitX requires (Java names and Xbox gamertags share
     * one namespace, a gamertag never contains a dot), and no prefix otherwise.
     */
    static String usernamePrefix(String configured, AuthMode auth) {
        if (configured.equalsIgnoreCase("auto")) {
            return auth == AuthMode.TRUSTED ? "." : "";
        }
        return configured;
    }

    /**
     * Loads the configuration from {@code file}, writing the default file first when it does not
     * exist.
     *
     * @throws IOException if the file cannot be read or written
     * @throws IllegalArgumentException if a value has the wrong type or an unknown enum constant
     */
    public static ProxyConfig load(Path file) throws IOException {
        return load(file, System::getenv);
    }

    /**
     * Same as {@link #load(Path)} with the environment variables read from {@code environment}.
     */
    static ProxyConfig load(Path file, UnaryOperator<String> environment) throws IOException {
        Map<String, Object> defaults = readDefaults();
        if (Files.notExists(file)) {
            try (InputStream in = defaultsStream()) {
                Files.copy(in, file);
            }
        }
        Map<String, Object> user;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            user = yaml().load(reader);
        }
        return new ProxyConfig(new Section("", user == null ? Map.of() : user, defaults, environment));
    }

    /**
     * The configuration made only of the shipped defaults (used by tests and embedded mode).
     */
    public static ProxyConfig defaults() {
        try {
            return new ProxyConfig(new Section("", Map.of(), readDefaults(), name -> null));
        } catch (IOException e) {
            throw new IllegalStateException("Default configuration missing from the jar", e);
        }
    }

    public Java java() {
        return this.java;
    }

    public Bedrock bedrock() {
        return this.bedrock;
    }

    public ResourcePacks resourcePacks() {
        return this.resourcePacks;
    }

    public World world() {
        return this.world;
    }

    public Features features() {
        return this.features;
    }

    public Skins skins() {
        return this.skins;
    }

    public Debug debug() {
        return this.debug;
    }

    private static Yaml yaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    private static InputStream defaultsStream() {
        return Objects.requireNonNull(ProxyConfig.class.getClassLoader().getResourceAsStream("config.yml"),
                "config.yml missing from the jar");
    }

    private static Map<String, Object> readDefaults() throws IOException {
        try (InputStream in = defaultsStream()) {
            return yaml().load(in);
        }
    }

    /**
     * A YAML mapping with a parallel mapping of defaults and the environment overriding both.
     */
    private record Section(String path, Map<String, Object> values, Map<String, Object> defaults,
                           UnaryOperator<String> environment) {

        @SuppressWarnings("unchecked")
        Section section(String key) {
            Object value = this.values.get(key);
            Object def = this.defaults.get(key);
            return new Section(this.path + key + ".",
                    value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(),
                    def instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(),
                    this.environment);
        }

        private Object raw(String key) {
            String variable = "JTB_" + (this.path + key).toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
            Object value = this.environment.apply(variable);
            if (value == null) {
                value = this.values.get(key);
            }
            if (value == null) {
                value = this.defaults.get(key);
            }
            if (value == null) {
                throw new IllegalArgumentException("Missing configuration value " + this.path + key);
            }
            return value;
        }

        String string(String key) {
            return String.valueOf(raw(key));
        }

        int integer(String key) {
            Object value = raw(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Configuration value " + this.path + key + " must be a number", e);
            }
        }

        /** A list of strings; an environment variable gives it comma-separated. */
        List<String> strings(String key) {
            Object value = raw(key);
            if (value instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
            String text = value.toString().trim();
            return text.isEmpty() ? List.of() : List.of(text.split("\\s*,\\s*"));
        }

        /** A mapping of strings; an environment variable gives it as {@code key=value,key=value}. */
        Map<String, String> stringMap(String key) {
            Object value = raw(key);
            Map<String, String> map = new LinkedHashMap<>();
            if (value instanceof Map<?, ?> yaml) {
                yaml.forEach((k, v) -> map.put(String.valueOf(k), String.valueOf(v)));
                return map;
            }
            for (String entry : value.toString().split(",")) {
                int equals = entry.indexOf('=');
                if (equals > 0) {
                    map.put(entry.substring(0, equals).trim(), entry.substring(equals + 1).trim());
                } else if (!entry.isBlank()) {
                    throw new IllegalArgumentException("Configuration value " + this.path + key + " must be key=value pairs");
                }
            }
            return map;
        }

        /**
         * A boolean; text values (environment variables) accept true/false, yes/no, on/off and 1/0, and
         * anything else is an error rather than a silent false (JTB_JAVA_ONLINE_MODE=1 must not turn
         * Mojang authentication off).
         */
        boolean bool(String key) {
            Object value = raw(key);
            if (value instanceof Boolean b) {
                return b;
            }
            return switch (value.toString().trim().toLowerCase(Locale.ROOT)) {
                case "true", "yes", "on", "1" -> true;
                case "false", "no", "off", "0" -> false;
                default -> throw new IllegalArgumentException("Configuration value " + this.path + key
                        + " must be true or false (got '" + value + "')");
            };
        }
    }
}
