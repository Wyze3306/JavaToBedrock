package io.github.wyze3306.jtb.ui;

import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;

/**
 * The Java map colour palette and the conversion of Bedrock map pixels to it.
 * <p>
 * Java maps store one byte per pixel: {@code base * 4 + shade}, with 62 base colours (0 is
 * transparent) and four shades multiplying the base RGB by 180, 220, 255 and 135 / 255. Bedrock
 * maps send the RGB of every pixel (ABGR ints, alpha first). Server-rendered maps use exactly the
 * Java palette, so exact matches are looked up first; other colours (images drawn by plugins) go
 * through a 15-bit lookup table of nearest palette entries (perceptual "redmean" distance), built
 * once at startup. Base colours from GeyserMC's {@code MapColor} table (MIT).
 */
public final class MapColors {

    /** Base colours, index = base id; 0 is the transparent "none" colour. */
    private static final int[] BASE = {
            0, 0x7FB238, 0xF7E9A3, 0xC7C7C7, 0xFF0000, 0xA0A0FF, 0xA7A7A7, 0x007C00, 0xFFFFFF, 0xA4A8B8,
            0x976D4D, 0x707070, 0x4040FF, 0x8F7748, 0xFFFCF5, 0xD87F33, 0xB24CD8, 0x6699D8, 0xE5E533, 0x7FCC19,
            0xF27FA5, 0x4C4C4C, 0x999999, 0x4C7F99, 0x7F3FB2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919,
            0xFAEE4D, 0x5CDBD5, 0x4A80FF, 0x00D93A, 0x815631, 0x700200, 0xD1B1A1, 0x9F5224, 0x95576C, 0x706C8A,
            0xBA8524, 0x677535, 0xA04D4E, 0x392923, 0x876B62, 0x575C5C, 0x7A4958, 0x4C3E5C, 0x4C3223, 0x4C522A,
            0x8E3C2E, 0x251610, 0xBD3031, 0x943F61, 0x5C191D, 0x167E86, 0x3A8E8C, 0x562C3E, 0x14B485, 0x646464,
            0xD8AF93, 0x7FA796
    };
    private static final int[] SHADE_MULTIPLIERS = {180, 220, 255, 135};

    /** RGB of every Java colour id (ids 0-3 transparent). */
    private static final int[] RGB = new int[BASE.length * 4];
    private static final Int2ByteOpenHashMap EXACT = new Int2ByteOpenHashMap();
    private static final byte[] NEAREST = new byte[1 << 15];

    static {
        for (int base = 1; base < BASE.length; base++) {
            for (int shade = 0; shade < 4; shade++) {
                int m = SHADE_MULTIPLIERS[shade];
                int color = BASE[base];
                int rgb = ((color >> 16 & 0xFF) * m / 255) << 16 | ((color >> 8 & 0xFF) * m / 255) << 8 | (color & 0xFF) * m / 255;
                int id = base * 4 + shade;
                RGB[id] = rgb;
                EXACT.putIfAbsent(rgb, (byte) id);
            }
        }
        for (int i = 0; i < NEAREST.length; i++) {
            int r = (i >> 10 & 0x1F) << 3 | 4;
            int g = (i >> 5 & 0x1F) << 3 | 4;
            int b = (i & 0x1F) << 3 | 4;
            NEAREST[i] = (byte) nearest(r, g, b);
        }
    }

    private MapColors() {
    }

    /** Forces the one-time table construction (call at startup, off the session loops). */
    public static void warmUp() {
        // Class initialisation does the work.
    }

    /** RGB of a Java map colour id (0 for the transparent ids). */
    public static int rgb(int id) {
        return id >= 0 && id < RGB.length ? RGB[id] : 0;
    }

    /**
     * The Java map colour byte of a Bedrock pixel ({@code 0xAABBGGRR}). Mostly transparent pixels
     * (alpha below 128) are transparent.
     */
    public static byte fromBedrock(int abgr) {
        if ((abgr >>> 24) < 0x80) {
            return 0;
        }
        int r = abgr & 0xFF;
        int g = abgr >> 8 & 0xFF;
        int b = abgr >> 16 & 0xFF;
        int rgb = r << 16 | g << 8 | b;
        byte exact = EXACT.getOrDefault(rgb, (byte) 0);
        if (exact != 0) {
            return exact;
        }
        return NEAREST[(r >> 3) << 10 | (g >> 3) << 5 | (b >> 3)];
    }

    /** Converts {@code count} Bedrock pixels into {@code target}. */
    public static void fromBedrock(int[] pixels, int count, byte[] target) {
        for (int i = 0; i < count; i++) {
            target[i] = fromBedrock(pixels[i]);
        }
    }

    private static int nearest(int r, int g, int b) {
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int id = 4; id < RGB.length; id++) {
            int rgb = RGB[id];
            int dr = (rgb >> 16 & 0xFF) - r;
            int dg = (rgb >> 8 & 0xFF) - g;
            int db = (rgb & 0xFF) - b;
            int meanRed = ((rgb >> 16 & 0xFF) + r) >> 1;
            long distance = (long) ((512 + meanRed) * dr * dr >> 8) + 4L * dg * dg + ((767 - meanRed) * db * db >> 8);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = id;
            }
        }
        return best;
    }
}
