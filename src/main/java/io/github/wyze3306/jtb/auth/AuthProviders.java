package io.github.wyze3306.jtb.auth;

import io.github.wyze3306.jtb.JavaToBedrock;
import io.github.wyze3306.jtb.config.ProxyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyPair;

/**
 * Creates the configured {@link AuthProvider}.
 */
public final class AuthProviders {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthProviders.class);

    private AuthProviders() {
    }

    public static AuthProvider create(JavaToBedrock proxy) throws Exception {
        ProxyConfig config = proxy.config();
        return switch (config.bedrock().auth()) {
            case OFFLINE -> new OfflineAuthProvider();
            case TRUSTED -> {
                KeyPair key = TrustedProxyKey.loadOrCreate(proxy.dataDirectory().resolve(config.bedrock().trustedKeyFile()));
                LOGGER.info("Trusted mode: put this public key in the PowerNukkitX pnx.yml (network-settings.java-bridge.proxyPublicKeys):");
                LOGGER.info("  {}", BedrockCrypto.encodeKey(key.getPublic()));
                if (!config.java().onlineMode()) {
                    LOGGER.warn("Trusted mode with java.online-mode=false: the server will trust unverified Java names!");
                }
                yield new TrustedAuthProvider(key, config.java().onlineMode());
            }
            case MICROSOFT -> new MicrosoftAuthProvider(proxy);
        };
    }
}
