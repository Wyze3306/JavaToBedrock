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
}
