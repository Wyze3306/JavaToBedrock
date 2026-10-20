package io.github.wyze3306.jtb.network.java;

import io.github.wyze3306.jtb.JavaToBedrock;
import org.geysermc.mcprotocollib.network.Server;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.crypt.EncryptionConfig;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;

import java.security.Key;

/**
 * Server-side protocol that installs the proxy's own connection listener instead of
 * MCProtocolLib's default one (which would finish the configuration phase immediately).
 */
public final class JtbMinecraftProtocol extends MinecraftProtocol {

    private final JavaToBedrock proxy;

    public JtbMinecraftProtocol(JavaToBedrock proxy) {
        super(MinecraftCodec.CODEC);
        this.proxy = proxy;
        setUseDefaultListeners(false);
    }

    @Override
    public void newServerSession(Server server, Session session) {
        super.newServerSession(server, session);
        session.addListener(new JavaConnectionListener(this.proxy));
    }

    /** Exposes encryption creation to the connection listener (protected in the parent). */
    EncryptionConfig encryption(Key key) {
        return createEncryption(key);
    }
}
