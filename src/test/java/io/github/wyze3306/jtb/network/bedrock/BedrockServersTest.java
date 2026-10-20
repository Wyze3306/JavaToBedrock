package io.github.wyze3306.jtb.network.bedrock;

import io.github.wyze3306.jtb.config.ProxyConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BedrockServersTest {

    private static ProxyConfig.Bedrock config(Map<String, String> servers, Map<String, String> forcedHosts) {
        return new ProxyConfig.Bedrock("127.0.0.1", 19132, ProxyConfig.AuthMode.OFFLINE, "key.pem", "", 10, "fr_FR", true,
                new ProxyConfig.Reconnect(300, List.of()), servers, forcedHosts);
    }

    @Test
    void parsesAddresses() {
        assertEquals(new BedrockServers.Target("a", "play.example.org", 19132), BedrockServers.parse("a", "play.example.org"));
        assertEquals(new BedrockServers.Target("a", "10.0.0.2", 19133), BedrockServers.parse("a", " 10.0.0.2:19133 "));
        assertEquals(new BedrockServers.Target("a", "::1", 19140), BedrockServers.parse("a", "[::1]:19140"));
        assertEquals(new BedrockServers.Target("a", "::1", 19132), BedrockServers.parse("a", "[::1]"));
        assertThrows(IllegalArgumentException.class, () -> BedrockServers.parse("a", "host:99999"));
        assertThrows(IllegalArgumentException.class, () -> BedrockServers.parse("a", "[::1"));
    }

    @Test
    void forcedHostsPickTheServer() {
        BedrockServers servers = new BedrockServers(config(Map.of("Survie", "10.0.0.3:19134"),
                Map.of("Survie.Example.org", "survie", "lobby.example.org", "default")));
        assertEquals("Survie", servers.forJavaHost("survie.example.org.").name());
        assertEquals(19134, servers.forJavaHost("SURVIE.example.org").port());
        assertEquals(BedrockServers.DEFAULT_NAME, servers.forJavaHost("lobby.example.org").name());
        assertEquals(BedrockServers.DEFAULT_NAME, servers.forJavaHost("other.example.org").name());
        assertEquals(BedrockServers.DEFAULT_NAME, servers.forJavaHost(null).name());
        assertEquals("Survie", servers.byName("survie").name());
        assertNull(servers.byName("creatif"));
        assertEquals(2, servers.all().size());
    }

    @Test
    void unknownForcedServerIsAConfigurationError() {
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockServers(config(Map.of(), Map.of("play.example.org", "missing"))));
    }
}
