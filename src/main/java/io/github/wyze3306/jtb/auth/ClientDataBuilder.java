package io.github.wyze3306.jtb.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.skin.BedrockSkinData;
import org.jose4j.lang.JoseException;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * Builds the client data JWT of the Bedrock login: device, language and skin, with every claim a
 * vanilla 1.26.50 Windows client sends. PowerNukkitX rejects a login when any of the device claims
 * is missing or has the wrong type, so all of them are always present.
 */
public final class ClientDataBuilder {

    private static final Base64.Encoder B64 = Base64.getEncoder();
    /** BuildPlatform.WIN_32 */
    private static final int DEVICE_OS = 8;
    private static final int INPUT_MOUSE = 1;
    private static final int GRAPHICS_FANCY = 1;
    private static final int MEMORY_TIER_HIGH = 3;
    private static final int PLATFORM_DESKTOP = 0;
    private static final int UI_CLASSIC = 0;

    private ClientDataBuilder() {
    }

    public static String build(ProxySession session, KeyPair identityKey, BedrockSkinData skin, String gameVersion,
                               String serverAddress) throws JoseException {
        return build(session.getProfile().getId(), session.getName(),
                bedrockLanguage(session.getLocale(), session.config().bedrock().defaultLanguage()),
                identityKey, skin, gameVersion, serverAddress);
    }

    /**
     * @param languageCode Bedrock language code, e.g. {@code fr_FR}
     */
    public static String build(UUID javaId, String javaName, String languageCode, KeyPair identityKey,
                               BedrockSkinData skin, String gameVersion, String serverAddress) throws JoseException {
        JsonObject data = new JsonObject();
        data.add("AnimatedImageData", new JsonArray());
        data.addProperty("ArmSize", skin.armSize());
        boolean hasCape = skin.capeRgba().length == 64 * 32 * 4;
        data.addProperty("CapeData", hasCape ? B64.encodeToString(skin.capeRgba()) : "");
        data.addProperty("CapeId", hasCape ? "jtb-cape-" + javaId : "");
        data.addProperty("CapeImageHeight", hasCape ? 32 : 0);
        data.addProperty("CapeImageWidth", hasCape ? 64 : 0);
        data.addProperty("CapeOnClassicSkin", false);
        data.addProperty("ClientRandomId", javaId.getMostSignificantBits() ^ javaId.getLeastSignificantBits());
        data.addProperty("ClientEditorConnectionIntent", 0);
        data.addProperty("ClientIsEditorCapable", false);
        data.addProperty("CompatibleWithClientSideChunkGen", false);
        data.addProperty("CurrentInputMode", INPUT_MOUSE);
        data.addProperty("DefaultInputMode", INPUT_MOUSE);
        data.addProperty("DeviceId", UUID.nameUUIDFromBytes(("jtb-device:" + javaId).getBytes(StandardCharsets.UTF_8)).toString().replace("-", ""));
        data.addProperty("DeviceModel", "JavaToBedrock");
        data.addProperty("DeviceOS", DEVICE_OS);
        data.addProperty("GameVersion", gameVersion);
        data.addProperty("GraphicsMode", GRAPHICS_FANCY);
        data.addProperty("GuiScale", 0);
        data.addProperty("IsEditorMode", false);
        data.addProperty("LanguageCode", languageCode);
        data.addProperty("MaxViewDistance", 96);
        data.addProperty("MemoryTier", MEMORY_TIER_HIGH);
        data.addProperty("OverrideSkin", false);
        data.add("PersonaPieces", new JsonArray());
        data.addProperty("PersonaSkin", false);
        data.add("PieceTintColors", new JsonArray());
        data.addProperty("PlatformOfflineId", "");
        data.addProperty("PlatformOnlineId", "");
        data.addProperty("PlatformType", PLATFORM_DESKTOP);
        data.addProperty("PlayFabId", "");
        data.addProperty("PremiumSkin", false);
        data.addProperty("SelfSignedId", javaId.toString());
        data.addProperty("ServerAddress", serverAddress);
        data.addProperty("SkinAnimationData", "");
        data.addProperty("SkinColor", "#0");
        data.addProperty("SkinData", B64.encodeToString(skin.rgba()));
        data.addProperty("SkinGeometryData", B64.encodeToString("".getBytes(StandardCharsets.UTF_8)));
        data.addProperty("SkinGeometryDataEngineVersion", B64.encodeToString(gameVersion.getBytes(StandardCharsets.UTF_8)));
        data.addProperty("SkinId", skin.skinId());
        data.addProperty("SkinImageHeight", skin.height());
        data.addProperty("SkinImageWidth", skin.width());
        data.addProperty("SkinResourcePatch", B64.encodeToString(skin.resourcePatch().getBytes(StandardCharsets.UTF_8)));
        data.addProperty("ThirdPartyName", javaName);
        data.addProperty("TrustedSkin", false);
        data.addProperty("UIProfile", UI_CLASSIC);
        return BedrockCrypto.sign(identityKey, data.toString());
    }

    /**
     * Java locale ({@code fr_fr}) to Bedrock language code ({@code fr_FR}).
     */
    public static String bedrockLanguage(String javaLocale, String fallback) {
        if (javaLocale == null || javaLocale.length() < 5 || javaLocale.charAt(2) != '_') {
            return fallback;
        }
        return javaLocale.substring(0, 2).toLowerCase(Locale.ROOT) + "_" + javaLocale.substring(3).toUpperCase(Locale.ROOT);
    }
}
