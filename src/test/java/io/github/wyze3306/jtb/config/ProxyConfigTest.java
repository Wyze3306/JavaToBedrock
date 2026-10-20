package io.github.wyze3306.jtb.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyConfigTest {

    @Test
    void defaultsLoad() {
        ProxyConfig config = ProxyConfig.defaults();
        assertEquals(25565, config.java().port());
        assertEquals(19132, config.bedrock().port());
        assertEquals(ProxyConfig.AuthMode.OFFLINE, config.bedrock().auth());
        assertTrue(config.resourcePacks().enabled());
        assertTrue(config.bedrock().followTransfers());
    }

    @Test
    void writesDefaultFileAndKeepsMissingKeysAtDefault(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.yml");
        ProxyConfig.load(file);
        assertTrue(Files.exists(file));

        Files.writeString(file, "bedrock:\n  address: play.example.org\n  auth: trusted\nworld:\n  lighting: full-bright\n");
        ProxyConfig config = ProxyConfig.load(file);
        assertEquals("play.example.org", config.bedrock().address());
        assertEquals(19132, config.bedrock().port());
        assertEquals(ProxyConfig.AuthMode.TRUSTED, config.bedrock().auth());
        assertEquals(ProxyConfig.Lighting.FULL_BRIGHT, config.world().lighting());
        assertEquals(25565, config.java().port());
    }

    @Test
    void environmentOverridesFileAndDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "bedrock:\n  address: play.example.org\n");
        Map<String, String> env = Map.of("JTB_BEDROCK_ADDRESS", "pnx", "JTB_BEDROCK_PORT", "19133",
                "JTB_RESOURCE_PACKS_PUBLIC_URL", "http://packs.example.org:8765", "JTB_JAVA_ONLINE_MODE", "false");
        ProxyConfig config = ProxyConfig.load(file, env::get);
        assertEquals("pnx", config.bedrock().address());
        assertEquals(19133, config.bedrock().port());
        assertEquals("http://packs.example.org:8765", config.resourcePacks().publicUrl());
        assertEquals(false, config.java().onlineMode());
        assertEquals(ProxyConfig.AuthMode.OFFLINE, config.bedrock().auth());
    }

    @Test
    void listsAndMapsFromFileAndEnvironment(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "bedrock:\n  servers:\n    lobby: 127.0.0.1:19133\n  forced-hosts:\n    lobby.example.org: lobby\n");
        ProxyConfig config = ProxyConfig.load(file, name -> null);
        assertEquals(Map.of("lobby", "127.0.0.1:19133"), config.bedrock().servers());
        assertEquals(Map.of("lobby.example.org", "lobby"), config.bedrock().forcedHosts());
        assertTrue(config.bedrock().reconnect().shutdownMessages().contains("Server closed"));

        Map<String, String> env = Map.of("JTB_BEDROCK_SERVERS", "a=1.2.3.4:19132, b=5.6.7.8",
                "JTB_BEDROCK_RECONNECT_SHUTDOWN_MESSAGES", "Bye, Restarting", "JTB_BEDROCK_RECONNECT_TIMEOUT", "0");
        ProxyConfig fromEnv = ProxyConfig.load(file, env::get);
        assertEquals(Map.of("a", "1.2.3.4:19132", "b", "5.6.7.8"), fromEnv.bedrock().servers());
        assertEquals(java.util.List.of("Bye", "Restarting"), fromEnv.bedrock().reconnect().shutdownMessages());
        assertEquals(0, fromEnv.bedrock().reconnect().timeoutSeconds());
    }

    @Test
    void booleansFromTextAreStrict(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "java:\n  online-mode: true\n");
        assertEquals(true, ProxyConfig.load(file, Map.of("JTB_JAVA_ONLINE_MODE", "1")::get).java().onlineMode());
        assertEquals(true, ProxyConfig.load(file, Map.of("JTB_JAVA_ONLINE_MODE", "Yes")::get).java().onlineMode());
        assertEquals(false, ProxyConfig.load(file, Map.of("JTB_JAVA_ONLINE_MODE", "off")::get).java().onlineMode());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ProxyConfig.load(file, Map.of("JTB_JAVA_ONLINE_MODE", "ture")::get));
    }
}
