package io.github.wyze3306.jtb.resourcepack.convert;

import org.jspecify.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;

/**
 * Image helpers for pack textures. Bedrock packs ship PNG, TGA (no ImageIO reader in the JDK) and
 * JPG; Java packs only take PNG. Files are recognised by content first (PNG/JPG magic numbers, some
 * packs name a PNG {@code .tga}), then by extension for TGA, which has no magic number.
 */
public final class PackImages {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private PackImages() {
    }

    public static boolean isPng(byte[] data) {
        if (data.length < PNG_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (data[i] != PNG_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isJpeg(byte[] data) {
        return data.length > 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF;
    }

    /**
     * Width and height without decoding the pixels when the header tells them (PNG and TGA), else by
     * decoding.
     *
     * @return {width, height}, or null when the data is not a readable image
     */
    public static int @Nullable [] size(String path, byte[] data) {
        if (isPng(data) && data.length >= 24) {
            return new int[]{readInt(data, 16), readInt(data, 20)};
        }
        if (!isJpeg(data) && path.toLowerCase(Locale.ROOT).endsWith(".tga") && TgaDecoder.looksLikeTga(data)) {
            return new int[]{u16(data, 12), u16(data, 14)};
        }
        BufferedImage image = decode(path, data);
        return image == null ? null : new int[]{image.getWidth(), image.getHeight()};
    }

    /** Decodes a pack image, or returns null when it is not a readable PNG, JPG or TGA. */
    public static @Nullable BufferedImage decode(String path, byte[] data) {
        try {
            if (isPng(data) || isJpeg(data)) {
                return toArgb(ImageIO.read(new ByteArrayInputStream(data)));
            }
            if (TgaDecoder.looksLikeTga(data)) {
                return TgaDecoder.decode(data);
            }
            return toArgb(ImageIO.read(new ByteArrayInputStream(data)));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The image as PNG bytes: PNG input is returned unchanged (the Java client reads any valid PNG),
     * other formats are decoded and re-encoded.
     *
     * @return null when the data is not a readable image
     */
    public static byte @Nullable [] toPng(String path, byte[] data) {
        if (isPng(data)) {
            return data;
        }
        BufferedImage image = decode(path, data);
        return image == null ? null : encodePng(image);
    }

    public static byte[] encodePng(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(256, image.getWidth() * image.getHeight()));
            if (!ImageIO.write(image, "png", out)) {
                throw new IOException("No PNG writer");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static @Nullable BufferedImage toArgb(@Nullable BufferedImage image) {
        if (image == null || image.getType() == BufferedImage.TYPE_INT_ARGB) {
            return image;
        }
        BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        draw(argb, image);
        return argb;
    }

    /** Copies {@code image} into a transparent canvas of at least {@code width x height}. */
    public static BufferedImage pad(BufferedImage image, int width, int height) {
        if (image.getWidth() >= width && image.getHeight() >= height) {
            return image;
        }
        BufferedImage out = new BufferedImage(Math.max(width, image.getWidth()), Math.max(height, image.getHeight()),
                BufferedImage.TYPE_INT_ARGB);
        draw(out, image);
        return out;
    }

    private static void draw(BufferedImage target, BufferedImage source) {
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
    }

    private static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8
                | (data[offset + 3] & 0xFF);
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
    }
}
