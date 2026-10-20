package io.github.wyze3306.jtb.resourcepack.convert;

import java.awt.image.BufferedImage;

/**
 * Dependency-free TGA decoder for Bedrock pack textures (JDK ImageIO has no TGA reader).
 * <p>
 * Supports image types 1/2/3 (color-mapped, true-color, grayscale) and their RLE variants 9/10/11,
 * 8/15/16/24/32 bpp, 15/16/24/32-bit color maps, the image-id field and both origin bits
 * (descriptor bit 4 = right-to-left, bit 5 = top-to-bottom). RLE packets may cross scanlines
 * (Pillow fails on such a file in the vanilla pack; this decoder does not). Truncated data is
 * padded with transparent pixels instead of throwing.
 * <p>
 * Verified by {@code TgaCheck}: identical RGBA to Pillow on all 3327 Pillow-readable TGA files of
 * bedrock-samples v1.26.50.4 (types 1/8bpp, 2/24, 2/32, 10/32; both origins).
 */
final class TgaDecoder {

    /** Larger images are refused (8192 x 4096 pixels at most). */
    private static final long MAX_PIXELS = 8192L * 4096L;

    private TgaDecoder() {
    }

    /** Returns true when {@code data} looks like a TGA we can decode (there is no magic number). */
    static boolean looksLikeTga(byte[] data) {
        if (data.length < 18) {
            return false;
        }
        int cmapType = data[1] & 0xFF;
        int type = data[2] & 0xFF;
        int bpp = data[16] & 0xFF;
        return cmapType <= 1 && (type == 1 || type == 2 || type == 3 || type == 9 || type == 10 || type == 11)
                && (bpp == 8 || bpp == 15 || bpp == 16 || bpp == 24 || bpp == 32);
    }

    /**
     * @throws IllegalArgumentException for an unsupported image type or pixel depth, or a header
     *                                  shorter than 18 bytes
     */
    static BufferedImage decode(byte[] d) {
        if (d.length < 18) {
            throw new IllegalArgumentException("Truncated TGA header");
        }
        int idLength = d[0] & 0xFF;
        int colorMapType = d[1] & 0xFF;
        int imageType = d[2] & 0xFF;
        int cmapFirst = u16(d, 3);
        int cmapLength = u16(d, 5);
        int cmapBits = d[7] & 0xFF;
        int width = u16(d, 12);
        int height = u16(d, 14);
        int bpp = d[16] & 0xFF;
        int descriptor = d[17] & 0xFF;
        int alphaBits = descriptor & 0x0F;
        boolean rightToLeft = (descriptor & 0x10) != 0;
        boolean topToBottom = (descriptor & 0x20) != 0;
        int pos = 18 + idLength;

        int[] palette = null;
        if (colorMapType == 1) {
            int entry = (cmapBits + 7) / 8;
            palette = new int[cmapLength];
            for (int i = 0; i < cmapLength; i++) {
                palette[i] = color(d, pos + i * entry, cmapBits, alphaBits);
            }
            pos += cmapLength * entry;
        }

        int base = imageType & ~8;
        boolean rle = (imageType & 8) != 0;
        if (base != 1 && base != 2 && base != 3) {
            throw new IllegalArgumentException("Unsupported TGA image type " + imageType);
        }
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("Unsupported TGA size " + width + "x" + height);
        }
        int pixelBytes = (bpp + 7) / 8;
        int count = width * height;
        byte[] raw = new byte[count * pixelBytes];
        if (!rle) {
            System.arraycopy(d, pos, raw, 0, Math.min(raw.length, Math.max(0, d.length - pos)));
        } else {
            int out = 0;
            while (out < raw.length && pos < d.length) {
                int header = d[pos++] & 0xFF;
                int n = (header & 0x7F) + 1;
                if ((header & 0x80) != 0) {
                    for (int k = 0; k < n && out < raw.length; k++) {
                        for (int b = 0; b < pixelBytes; b++) {
                            raw[out++] = pos + b < d.length ? d[pos + b] : 0;
                        }
                    }
                    pos += pixelBytes;
                } else {
                    int size = Math.min(n * pixelBytes, raw.length - out);
                    System.arraycopy(d, pos, raw, out, Math.max(0, Math.min(size, d.length - pos)));
                    pos += n * pixelBytes;
                    out += size;
                }
            }
        }

        int[] pixels = new int[count];
        for (int i = 0; i < count; i++) {
            int x = i % width;
            int y = i / width;
            if (!topToBottom) {
                y = height - 1 - y;
            }
            if (rightToLeft) {
                x = width - 1 - x;
            }
            int o = i * pixelBytes;
            int argb;
            if (base == 1) {
                int index = pixelBytes == 1 ? raw[o] & 0xFF : u16(raw, o);
                int p = index - cmapFirst;
                argb = palette != null && p >= 0 && p < palette.length ? palette[p] : 0;
            } else if (base == 3) {
                int v = raw[o] & 0xFF;
                int a = pixelBytes == 2 ? raw[o + 1] & 0xFF : 0xFF;
                argb = a << 24 | v << 16 | v << 8 | v;
            } else {
                argb = color(raw, o, bpp, alphaBits);
            }
            pixels[y * width + x] = argb;
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    /** Little-endian BGR(A) (or 15/16-bit ARRRRRGGGGGBBBBB) to ARGB. */
    private static int color(byte[] b, int o, int bits, int alphaBits) {
        switch (bits) {
            case 32:
                return (b[o + 3] & 0xFF) << 24 | (b[o + 2] & 0xFF) << 16 | (b[o + 1] & 0xFF) << 8 | (b[o] & 0xFF);
            case 24:
                return 0xFF000000 | (b[o + 2] & 0xFF) << 16 | (b[o + 1] & 0xFF) << 8 | (b[o] & 0xFF);
            case 15:
            case 16: {
                int v = u16(b, o);
                int r = (v >> 10) & 0x1F, g = (v >> 5) & 0x1F, bl = v & 0x1F;
                int a = bits == 15 || alphaBits == 0 ? 0xFF : ((v & 0x8000) != 0 ? 0xFF : 0);
                return a << 24 | (r << 3 | r >> 2) << 16 | (g << 3 | g >> 2) << 8 | (bl << 3 | bl >> 2);
            }
            default:
                throw new IllegalArgumentException("Unsupported TGA pixel depth " + bits);
        }
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8;
    }
}
