package io.github.wyze3306.jtb.network.bedrock;

import io.github.wyze3306.jtb.config.ProxyConfig;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The Bedrock servers behind the proxy: the default one ({@code bedrock.address} / {@code port}),
 * the named ones of {@code bedrock.servers}, and the choice of server from the address the Java
 * player typed ({@code bedrock.forced-hosts}), so one proxy can front a whole network.
 */
public final class BedrockServers {

    public static final int DEFAULT_PORT = 19132;
    public static final String DEFAULT_NAME = "default";

    /**
     * A Bedrock server. The address is resolved on each call, so a DNS change is picked up.
     */
    public record Target(String name, String host, int port) {

        public InetSocketAddress address() {
            return new InetSocketAddress(this.host, this.port);
        }
    }

    private final Target defaultTarget;
    private final Map<String, Target> byName = new LinkedHashMap<>();
    private final Map<String, Target> forcedHosts = new LinkedHashMap<>();

    public BedrockServers(ProxyConfig.Bedrock config) {
        this.defaultTarget = new Target(DEFAULT_NAME, config.address(), config.port());
        this.byName.put(DEFAULT_NAME, this.defaultTarget);
        config.servers().forEach((name, address) -> this.byName.put(name.toLowerCase(Locale.ROOT), parse(name, address)));
        config.forcedHosts().forEach((host, name) -> {
            Target target = this.byName.get(name.toLowerCase(Locale.ROOT));
            if (target == null) {
                throw new IllegalArgumentException("bedrock.forced-hosts." + host + " names an unknown server \"" + name
                        + "\" (defined: " + this.byName.keySet() + ")");
            }
            this.forcedHosts.put(normalizeHost(host), target);
        });
    }

    public Target defaultTarget() {
        return this.defaultTarget;
    }

    /** The server for a Java player who typed {@code host}, the default one when it is not forced. */
    public Target forJavaHost(@Nullable String host) {
        if (host == null || this.forcedHosts.isEmpty()) {
            return this.defaultTarget;
        }
        return this.forcedHosts.getOrDefault(normalizeHost(host), this.defaultTarget);
    }

    public @Nullable Target byName(String name) {
        return this.byName.get(name.toLowerCase(Locale.ROOT));
    }

    /** Every server, the default one first. */
    public Collection<Target> all() {
        return this.byName.values();
    }

    private static String normalizeHost(String host) {
        String clean = host.trim().toLowerCase(Locale.ROOT);
        return clean.endsWith(".") ? clean.substring(0, clean.length() - 1) : clean;
    }

    /** {@code host}, {@code host:port} or {@code [ipv6]:port}. */
    static Target parse(String name, String value) {
        String text = value.trim();
        String host = text;
        int port = DEFAULT_PORT;
        if (text.startsWith("[")) {
            int end = text.indexOf(']');
            if (end < 0) {
                throw new IllegalArgumentException("Invalid address for Bedrock server " + name + ": " + value);
            }
            host = text.substring(1, end);
            if (text.length() > end + 1 && text.charAt(end + 1) == ':') {
                port = parsePort(name, text.substring(end + 2));
            }
        } else {
            int colon = text.lastIndexOf(':');
            if (colon > 0 && text.indexOf(':') == colon) {
                host = text.substring(0, colon);
                port = parsePort(name, text.substring(colon + 1));
            }
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException("Invalid address for Bedrock server " + name + ": " + value);
        }
        return new Target(name, host, port);
    }

    private static int parsePort(String name, String text) {
        try {
            int port = Integer.parseInt(text.trim());
            if (port > 0 && port < 65536) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new IllegalArgumentException("Invalid port for Bedrock server " + name + ": " + text);
    }
}
