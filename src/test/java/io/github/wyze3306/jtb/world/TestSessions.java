package io.github.wyze3306.jtb.world;

import io.github.wyze3306.jtb.JavaToBedrock;
import io.github.wyze3306.jtb.block.BlockData;
import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.config.ProxyConfig;
import io.github.wyze3306.jtb.session.ProxySession;
import org.geysermc.mcprotocollib.auth.GameProfile;

import java.nio.file.Path;
import java.util.UUID;

/** A session without network, enough for per-session state used by unit tests. */
public final class TestSessions {

    private TestSessions() {
    }

    public static ProxySession session() {
        BlockData.load();
        JavaToBedrock proxy = new JavaToBedrock(Path.of("build", "test-data"), ProxyConfig.defaults());
        return new ProxySession(proxy, null, new GameProfile(UUID.randomUUID(), "Tester"), null);
    }

    /** A block mapper for a PowerNukkitX-like server (hashed ids, no custom blocks). */
    public static BlockMapper mapper() {
        return session().get(BlockMapper.KEY);
    }
}
