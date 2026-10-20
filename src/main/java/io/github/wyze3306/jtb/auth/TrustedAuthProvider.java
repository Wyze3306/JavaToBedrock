package io.github.wyze3306.jtb.auth;

import com.google.gson.JsonObject;
import io.github.wyze3306.jtb.session.ProxySession;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.lang.JoseException;

import java.security.KeyPair;

/**
 * Offline-style identity plus a proxy assertion: a JWS signed with the proxy's long-term key that
 * vouches for the Java identity (verified by Mojang when the proxy runs in online mode) and binds it
 * to this login's identity key. PowerNukkitX's {@code java-bridge} verifies it and then treats the
 * login as authenticated, so servers can keep {@code xbox-auth} enabled for Bedrock players.
 * <p>
 * The assertion lives in the certificate payload under {@value #CLAIM}:
 * <pre>
 * { "iss": "JavaToBedrock", "sub": "&lt;java uuid&gt;", "name": "&lt;java name&gt;", "bedrockName": "...",
 *   "cpk": "&lt;identityPublicKey&gt;", "online": true, "iat": ..., "exp": iat + 120 }
 * </pre>
 * Binding {@code cpk} matters: the assertion is only usable together with the private identity key
 * that also derives the encryption key, so a captured login cannot be replayed.
 */
public final class TrustedAuthProvider extends OfflineAuthProvider {

    public static final String CLAIM = "JavaToBedrock";
    public static final String ISSUER = "JavaToBedrock";
    private static final long VALIDITY_SECONDS = 120;

    private final KeyPair proxyKey;
    private final boolean onlineMode;

    public TrustedAuthProvider(KeyPair proxyKey, boolean onlineMode) {
        this.proxyKey = proxyKey;
        this.onlineMode = onlineMode;
    }

    @Override
    protected void decorate(ProxySession session, KeyPair keyPair, JsonObject payload) throws JoseException {
        GameProfile profile = session.getProfile();
        long now = System.currentTimeMillis() / 1000;
        JsonObject claims = new JsonObject();
        claims.addProperty("iss", ISSUER);
        claims.addProperty("sub", profile.getId().toString());
        claims.addProperty("name", profile.getName());
        claims.addProperty("bedrockName", session.getBedrockName());
        claims.addProperty("cpk", BedrockCrypto.encodeKey(keyPair.getPublic()));
        claims.addProperty("online", this.onlineMode);
        claims.addProperty("iat", now);
        claims.addProperty("exp", now + VALIDITY_SECONDS);

        JsonWebSignature jws = new JsonWebSignature();
        jws.setAlgorithmHeaderValue(BedrockCrypto.ALGORITHM);
        jws.setPayload(claims.toString());
        jws.setKey(this.proxyKey.getPrivate());
        payload.addProperty(CLAIM, jws.getCompactSerialization());
    }
}
