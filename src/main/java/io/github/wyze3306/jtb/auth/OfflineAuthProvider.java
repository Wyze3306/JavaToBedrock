package io.github.wyze3306.jtb.auth;

import com.google.gson.JsonObject;
import io.github.wyze3306.jtb.session.ProxySession;
import org.cloudburstmc.protocol.bedrock.data.auth.PlayerAuthenticationType;
import org.jose4j.lang.JoseException;

import java.security.KeyPair;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A self-signed identity chain with a single certificate, like a Bedrock client that is not signed
 * in. Accepted by servers running with {@code xbox-auth=false}.
 */
public class OfflineAuthProvider implements AuthProvider {

    /** Minecraft: Bedrock Edition (Windows) title id, sent by real clients. */
    static final String TITLE_ID = "896928775";

    @Override
    public CompletableFuture<LoginData> login(ProxySession session) {
        try {
            KeyPair keyPair = BedrockCrypto.createKeyPair();
            String name = session.getBedrockName();
            UUID identity = session.getProfile().getId();
            String xuid = offlineXuid(identity);
            JsonObject payload = chainPayload(keyPair, name, identity, xuid);
            decorate(session, keyPair, payload);
            String certificate = BedrockCrypto.sign(keyPair, payload.toString());
            return CompletableFuture.completedFuture(new LoginData(keyPair, PlayerAuthenticationType.SELF_SIGNED,
                    List.of(certificate), null, name, identity, xuid));
        } catch (JoseException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Hook for subclasses to add claims to the certificate payload. */
    protected void decorate(ProxySession session, KeyPair keyPair, JsonObject payload) throws JoseException {
    }

    /**
     * A stable, unique, XUID-shaped id for a player that has no Xbox account. PowerNukkitX derives the
     * player UUID from the XUID, so an empty XUID would give every offline player the same UUID (and
     * each login would kick the previous player). Real XUIDs start with 2533/2535; these start with 1.
     */
    static String offlineXuid(UUID javaUuid) {
        long bits = javaUuid.getMostSignificantBits() ^ Long.rotateLeft(javaUuid.getLeastSignificantBits(), 17);
        return "1" + String.format("%015d", Long.remainderUnsigned(bits, 1_000_000_000_000_000L));
    }

    static JsonObject chainPayload(KeyPair keyPair, String name, UUID identity, String xuid) {
        long now = System.currentTimeMillis() / 1000;
        JsonObject extraData = new JsonObject();
        extraData.addProperty("displayName", name);
        extraData.addProperty("identity", identity.toString());
        extraData.addProperty("XUID", xuid);
        extraData.addProperty("titleId", TITLE_ID);
        JsonObject payload = new JsonObject();
        payload.add("extraData", extraData);
        payload.addProperty("identityPublicKey", BedrockCrypto.encodeKey(keyPair.getPublic()));
        payload.addProperty("certificateAuthority", true);
        payload.addProperty("nbf", now - 60);
        payload.addProperty("iat", now);
        payload.addProperty("exp", now + 24 * 3600);
        return payload;
    }
}
