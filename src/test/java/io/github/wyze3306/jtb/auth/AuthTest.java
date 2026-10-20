package io.github.wyze3306.jtb.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.wyze3306.jtb.skin.BedrockSkinData;
import io.github.wyze3306.jtb.skin.DefaultSkins;
import org.jose4j.jws.JsonWebSignature;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What PowerNukkitX checks on a login: these tests mirror its rules (ClientChainData, ClientSkinData,
 * SkinUtils.isValid, JavaBridge) so a change here cannot silently get every Java player kicked.
 */
class AuthTest {

    private static final UUID JAVA_ID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    private static JsonObject payload(String jwt) throws Exception {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setCompactSerialization(jwt);
        return JsonParser.parseString(jws.getUnverifiedPayload()).getAsJsonObject();
    }

    private static boolean verifies(String jwt, java.security.PublicKey key) throws Exception {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setCompactSerialization(jwt);
        jws.setKey(key);
        return jws.verifySignature();
    }

    @Test
    void offlineXuidIsStableUniqueAndXuidShaped() {
        String xuid = OfflineAuthProvider.offlineXuid(JAVA_ID);
        assertEquals(xuid, OfflineAuthProvider.offlineXuid(JAVA_ID));
        assertEquals(16, xuid.length());
        assertTrue(xuid.startsWith("1"));
        assertTrue(xuid.chars().allMatch(Character::isDigit));
        assertNotEquals(xuid, OfflineAuthProvider.offlineXuid(UUID.randomUUID()));
    }

    @Test
    void certificateCarriesTheIdentityPnxReads() throws Exception {
        KeyPair key = BedrockCrypto.createKeyPair();
        String jwt = BedrockCrypto.sign(key, OfflineAuthProvider.chainPayload(key, "Notch", JAVA_ID, "1234").toString());
        assertTrue(verifies(jwt, key.getPublic()));
        JsonObject payload = payload(jwt);
        assertEquals(BedrockCrypto.encodeKey(key.getPublic()), payload.get("identityPublicKey").getAsString());
        JsonObject extra = payload.getAsJsonObject("extraData");
        assertEquals("Notch", extra.get("displayName").getAsString());
        assertEquals(JAVA_ID.toString(), extra.get("identity").getAsString());
        assertEquals("1234", extra.get("XUID").getAsString());
    }

    @Test
    void clientDataHasEveryClaimPowerNukkitXRequires() throws Exception {
        KeyPair key = BedrockCrypto.createKeyPair();
        BedrockSkinData skin = DefaultSkins.forUuid(JAVA_ID);
        String jwt = ClientDataBuilder.build(JAVA_ID, "Notch", "fr_FR", key, skin, "1.26.50", "127.0.0.1:19132");
        assertTrue(verifies(jwt, key.getPublic()));
        JsonObject data = payload(jwt);

        Map<String, String> required = Map.ofEntries(
                Map.entry("ClientRandomId", "number"), Map.entry("CompatibleWithClientSideChunkGen", "boolean"),
                Map.entry("CurrentInputMode", "number"), Map.entry("DefaultInputMode", "number"),
                Map.entry("DeviceId", "string"), Map.entry("DeviceModel", "string"), Map.entry("DeviceOS", "number"),
                Map.entry("GameVersion", "string"), Map.entry("GraphicsMode", "number"), Map.entry("GuiScale", "number"),
                Map.entry("ClientIsEditorCapable", "boolean"), Map.entry("LanguageCode", "string"),
                Map.entry("MaxViewDistance", "number"), Map.entry("MemoryTier", "number"),
                Map.entry("PlatformOfflineId", "string"), Map.entry("PlatformOnlineId", "string"),
                Map.entry("PlatformType", "number"), Map.entry("SelfSignedId", "string"), Map.entry("ServerAddress", "string"),
                Map.entry("ThirdPartyName", "string"), Map.entry("TrustedSkin", "boolean"), Map.entry("UIProfile", "number"),
                Map.entry("ClientEditorConnectionIntent", "number"), Map.entry("SkinId", "string"),
                Map.entry("SkinData", "string"), Map.entry("SkinImageWidth", "number"), Map.entry("SkinImageHeight", "number"),
                Map.entry("SkinResourcePatch", "string"), Map.entry("SkinGeometryData", "string"));
        required.forEach((claim, type) -> {
            assertTrue(data.has(claim), "missing claim " + claim);
            var value = data.get(claim).getAsJsonPrimitive();
            switch (type) {
                case "number" -> assertTrue(value.isNumber(), claim + " must be a number");
                case "boolean" -> assertTrue(value.isBoolean(), claim + " must be a boolean");
                default -> assertTrue(value.isString(), claim + " must be a string");
            }
        });
        // Its mere presence makes PowerNukkitX treat the client as Education Edition.
        assertFalse(data.has("IsEduMode"));
        // A whole number, so jose4j parses it as Long as ClientChainData requires.
        double randomId = data.get("ClientRandomId").getAsDouble();
        assertEquals(Math.rint(randomId), randomId);
        // BuildPlatform.WIN_32; an unknown id would reject the login.
        assertEquals(8, data.get("DeviceOS").getAsInt());
        UUID.fromString(data.get("SelfSignedId").getAsString());

        byte[] pixels = Base64.getDecoder().decode(data.get("SkinData").getAsString());
        assertEquals(data.get("SkinImageWidth").getAsInt() * data.get("SkinImageHeight").getAsInt() * 4, pixels.length);
        String patch = new String(Base64.getDecoder().decode(data.get("SkinResourcePatch").getAsString()), StandardCharsets.UTF_8);
        assertTrue(JsonParser.parseString(patch).getAsJsonObject().getAsJsonObject("geometry").get("default").isJsonPrimitive());
    }

    @Test
    void trustedAssertionIsBoundToTheIdentityKey() throws Exception {
        KeyPair proxy = BedrockCrypto.createKeyPair();
        KeyPair identity = BedrockCrypto.createKeyPair();
        String assertion = TrustedAuthProvider.assertion(proxy, identity.getPublic(), JAVA_ID, "Notch", ".Notch", true, 1000);
        assertTrue(verifies(assertion, proxy.getPublic()));
        assertFalse(verifies(assertion, BedrockCrypto.createKeyPair().getPublic()));
        JsonObject claims = payload(assertion);
        assertEquals(TrustedAuthProvider.ISSUER, claims.get("iss").getAsString());
        assertEquals(JAVA_ID.toString(), claims.get("sub").getAsString());
        assertEquals(".Notch", claims.get("bedrockName").getAsString());
        assertEquals(BedrockCrypto.encodeKey(identity.getPublic()), claims.get("cpk").getAsString());
        assertTrue(claims.get("online").getAsBoolean());
        assertEquals(1000 + 120, claims.get("exp").getAsLong());
    }

    @Test
    void sessionKeyAgreementIsSymmetric() throws Exception {
        KeyPair client = BedrockCrypto.createKeyPair();
        KeyPair server = BedrockCrypto.createKeyPair();
        byte[] salt = BedrockCrypto.randomBytes(16);
        assertEquals(BedrockCrypto.sessionKey(client.getPrivate(), server.getPublic(), salt),
                BedrockCrypto.sessionKey(server.getPrivate(), client.getPublic(), salt));
        assertEquals(client.getPublic(), BedrockCrypto.parseKey(BedrockCrypto.encodeKey(client.getPublic())));
    }
}
