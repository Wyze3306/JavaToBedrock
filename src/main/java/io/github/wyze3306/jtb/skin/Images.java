package io.github.wyze3306.jtb.skin;

import java.awt.image.BufferedImage;

/**
 * Pixel helpers between {@link BufferedImage} and the raw RGBA byte arrays Bedrock uses.
 */
public final class Images {

    private Images() {
    }

    public static byte[] toRgba(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] out = new byte[width * height * 4];
        int i = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                out[i++] = (byte) (argb >> 16);
                out[i++] = (byte) (argb >> 8);
                out[i++] = (byte) argb;
                out[i++] = (byte) (argb >>> 24);
            }
        }
        return out;
    }

    public static BufferedImage fromRgba(byte[] rgba, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int i = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = rgba[i++] & 0xFF;
                int g = rgba[i++] & 0xFF;
                int b = rgba[i++] & 0xFF;
                int a = rgba[i++] & 0xFF;
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    /** The top-left {@code width x height} area (Java capes are 64x32 inside a larger canvas). */
    public static BufferedImage crop(BufferedImage image, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < Math.min(height, image.getHeight()); y++) {
            for (int x = 0; x < Math.min(width, image.getWidth()); x++) {
                out.setRGB(x, y, image.getRGB(x, y));
            }
        }
        return out;
    }
}
