package io.github.wyze3306.jtb.skin;

import java.util.UUID;

/**
 * A plain default skin drawn in code (no game asset is bundled), used when a Java player has no
 * custom skin or it cannot be downloaded. Arm width follows Java's default-skin rule: odd UUID
 * hash = slim.
 */
public final class DefaultSkins {

    private static final int SKIN = 0xFFC69C7B;
    private static final int HAIR = 0xFF3B2A1A;
    private static final int SHIRT = 0xFF2BA3A6;
    private static final int PANTS = 0xFF3E3A8C;
    private static final int SHOES = 0xFF4A4A4A;
    private static final int EYES = 0xFF3A4FB0;

    private static volatile BedrockSkinData wide;
    private static volatile BedrockSkinData slim;

    private DefaultSkins() {
    }

    public static BedrockSkinData forUuid(UUID uuid) {
        boolean isSlim = (uuid.hashCode() & 1) == 1;
        if (isSlim) {
            BedrockSkinData skin = slim;
            if (skin == null) {
                slim = skin = draw(true);
            }
            return skin;
        }
        BedrockSkinData skin = wide;
        if (skin == null) {
            wide = skin = draw(false);
        }
        return skin;
    }

    private static BedrockSkinData draw(boolean slimArms) {
        int[] px = new int[64 * 64];
        // Head (all six faces)
        fill(px, 0, 0, 32, 16, SKIN);
        fill(px, 8, 0, 8, 8, HAIR);          // top
        fill(px, 0, 8, 32, 2, HAIR);         // hair band around the head
        fill(px, 9, 12, 2, 1, EYES);         // eyes on the front face (8..16, 8..16)
        fill(px, 13, 12, 2, 1, EYES);
        // Body
        fill(px, 16, 16, 24, 16, SHIRT);
        // Right leg, left leg
        fill(px, 0, 16, 16, 16, PANTS);
        fill(px, 0, 28, 16, 4, SHOES);
        fill(px, 16, 48, 16, 16, PANTS);
        fill(px, 16, 60, 16, 4, SHOES);
        // Right arm, left arm: sleeve then skin
        int armWidth = slimArms ? 14 : 16;
        fill(px, 40, 16, armWidth, 16, SKIN);
        fill(px, 40, 20, armWidth, 4, SHIRT);
        fill(px, 32, 48, armWidth, 16, SKIN);
        fill(px, 32, 52, armWidth, 4, SHIRT);

        byte[] rgba = new byte[64 * 64 * 4];
        for (int i = 0; i < px.length; i++) {
            int argb = px[i];
            rgba[i * 4] = (byte) (argb >> 16);
            rgba[i * 4 + 1] = (byte) (argb >> 8);
            rgba[i * 4 + 2] = (byte) argb;
            rgba[i * 4 + 3] = (byte) (argb >>> 24);
        }
        return new BedrockSkinData(slimArms ? "jtb-default.slim" : "jtb-default.wide", 64, 64, rgba, slimArms, new byte[0]);
    }

    private static void fill(int[] px, int x, int y, int w, int h, int color) {
        for (int j = y; j < y + h; j++) {
            for (int i = x; i < x + w; i++) {
                px[j * 64 + i] = color;
            }
        }
    }
}
