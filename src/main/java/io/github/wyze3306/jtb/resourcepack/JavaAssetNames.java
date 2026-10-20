package io.github.wyze3306.jtb.resourcepack;

import io.github.wyze3306.jtb.sound.CustomSounds;

import java.util.Locale;

/**
 * Names of the assets the converted Java pack provides for server-defined content, shared by the
 * pack converter and the translators that reference them. Every method is a pure function of its
 * input, so a translator can compute a name without the pack; it should still check
 * {@link ConversionArtifacts} (through {@link ResourcePackState#artifacts()}) that the asset exists
 * and that the client loaded the pack, otherwise the client shows the missing model / plays nothing.
 * <p>
 * Java resource locations only allow {@code [a-z0-9_.-]} in namespaces and {@code [a-z0-9_.-/]} in
 * paths: names are lower-cased and every other character becomes {@code _}. All generated assets
 * live in the {@value #NAMESPACE} namespace.
 */
public final class JavaAssetNames {

    public static final String NAMESPACE = "jtb";

    private JavaAssetNames() {
    }

    /**
     * {@code item_model} component of a custom Bedrock item: {@code jtb:item/<namespace>/<path>}
     * ({@code demo:ruby_sword} -> {@code jtb:item/demo/ruby_sword}); identifiers without a
     * namespace are in {@code minecraft}. The pack defines it in
     * {@code assets/jtb/items/item/<namespace>/<path>.json}.
     */
    public static String itemModel(String identifier) {
        return NAMESPACE + ":" + itemModelPath(identifier);
    }

    /** {@link #itemModel} without the namespace. */
    public static String itemModelPath(String identifier) {
        return "item/" + identifierPath(identifier);
    }

    /**
     * Java custom sound event of a Bedrock sound: {@code jtb:<name lower-cased, invalid characters
     * replaced by _>} ({@code mob.Custom.roar} -> {@code jtb:mob.custom.roar}). Play it with
     * {@code CustomSound(soundEvent(name), true, range)}.
     */
    public static String soundEvent(String bedrockName) {
        return NAMESPACE + ":" + soundEventPath(bedrockName);
    }

    /** {@link #soundEvent} without the namespace (the key in {@code assets/jtb/sounds.json}). */
    public static String soundEventPath(String bedrockName) {
        // One convention for both sides: the sound translator plays CustomSounds.key(name).
        return CustomSounds.path(bedrockName);
    }

    /**
     * Block model shown for one visual variant of a custom block:
     * {@code jtb:block/custom/<namespace>/<path>} for variant 0, {@code .../<path>_<variant>} for the
     * others (permutations that look different).
     */
    public static String customBlockModel(String identifier, int variant) {
        return NAMESPACE + ":block/custom/" + identifierPath(identifier) + (variant == 0 ? "" : "_" + variant);
    }

    /**
     * Equipment asset ({@code equippable.asset_id}) of a custom armour item:
     * {@code jtb:<namespace>/<path>}, defined in {@code assets/jtb/equipment/<namespace>/<path>.json}.
     */
    public static String equipmentAsset(String identifier) {
        return NAMESPACE + ":" + identifierPath(identifier);
    }

    /**
     * Item model of a custom entity: {@code jtb:entity/<namespace>/<path>/<variant>} where the variant
     * is {@code <geometry key>_<texture key>} of the client entity definition
     * ({@code default_default} for the usual single model).
     */
    public static String entityModel(String identifier, String variant) {
        return NAMESPACE + ":entity/" + identifierPath(identifier) + "/" + sanitize(variant, false);
    }

    /** {@code namespace/path} of a Bedrock identifier, each part sanitised ({@code minecraft} when absent). */
    public static String identifierPath(String identifier) {
        int colon = identifier.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : identifier.substring(0, colon);
        String path = colon < 0 ? identifier : identifier.substring(colon + 1);
        return sanitize(namespace.isEmpty() ? "minecraft" : namespace, false) + "/" + sanitize(path, true);
    }

    /**
     * A Bedrock file path (relative, no extension, e.g. {@code textures/items/Ruby Gem}) as a Java
     * resource path: lower case, {@code /} kept, other invalid characters replaced by {@code _}.
     */
    public static String path(String bedrockPath) {
        return sanitize(bedrockPath, true);
    }

    private static String sanitize(String value, boolean allowSlash) {
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder out = null;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-'
                    || (allowSlash && c == '/');
            if (!valid) {
                if (out == null) {
                    out = new StringBuilder(lower.length()).append(lower, 0, i);
                }
                out.append('_');
            } else if (out != null) {
                out.append(c);
            }
        }
        String result = out == null ? lower : out.toString();
        return result.isEmpty() ? "_" : result;
    }
}
