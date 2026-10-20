package io.github.wyze3306.jtb.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

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
}
