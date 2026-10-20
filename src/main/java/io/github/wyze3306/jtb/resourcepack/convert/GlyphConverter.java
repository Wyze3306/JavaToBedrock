package io.github.wyze3306.jtb.resourcepack.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.wyze3306.jtb.resourcepack.JavaAssetNames;

import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Bedrock glyph sheets ({@code font/glyph_XX.png}: a 16 x 16 grid, cell (column, row) is code point
 * {@code 0xXX00 + row * 16 + column}) become bitmap providers of the Java default font, so the
 * private-use characters servers use for icons and HUD art render on Java too.
 * <p>
 * Only the private-use pages {@code E0}-{@code F8} are converted: lower pages would replace Java's
 * own Latin glyphs. Every glyph is drawn text-sized ({@code height 8, ascent 7}) whatever the sheet
 * resolution, as on Bedrock (ViaBedrock uses the cell height, which doubles 16-pixel icons).
 * The providers are written to {@code assets/minecraft/font/default.json}, which the client merges
 * with the vanilla font.
 */
final class GlyphConverter {

    static final int FIRST_PAGE = 0xE0;
    static final int LAST_PAGE = 0xF8;
    private static final int HEIGHT = 8;
    private static final int ASCENT = 7;

    private GlyphConverter() {
    }

    static void convert(ConversionContext context) {
        JsonArray providers = new JsonArray();
        for (int page = FIRST_PAGE; page <= LAST_PAGE; page++) {
            String hex = String.format(Locale.ROOT, "%02x", page);
            PackFiles.PackFile file = context.stack().file("font/glyph_" + hex + ".png");
            if (file == null) {
                continue;
            }
            BufferedImage sheet = PackImages.decode(file.path(), file.data());
            if (sheet == null || sheet.getWidth() < 16 || sheet.getHeight() < 16) {
                context.count("unreadable glyph sheets");
                continue;
            }
            int width = (sheet.getWidth() + 15) / 16 * 16;
            int height = (sheet.getHeight() + 15) / 16 * 16;
            byte[] png = width == sheet.getWidth() && height == sheet.getHeight() && PackImages.isPng(file.data())
                    ? file.data() : PackImages.encodePng(PackImages.pad(sheet, width, height));
            String texture = "font/glyph_" + hex + ".png";
            context.writer().put("assets/" + JavaAssetNames.NAMESPACE + "/textures/" + texture, png);

            JsonObject provider = new JsonObject();
            provider.addProperty("type", "bitmap");
            provider.addProperty("file", JavaAssetNames.NAMESPACE + ":" + texture);
            provider.addProperty("height", HEIGHT);
            provider.addProperty("ascent", ASCENT);
            JsonArray chars = new JsonArray();
            for (int row = 0; row < 16; row++) {
                StringBuilder line = new StringBuilder(16);
                for (int column = 0; column < 16; column++) {
                    line.append((char) (page << 8 | row << 4 | column));
                }
                chars.add(line.toString());
            }
            provider.add("chars", chars);
            providers.add(provider);
            context.artifacts().addGlyphPage(page);
            context.count("glyph pages");
        }
        if (!providers.isEmpty()) {
            JsonObject font = new JsonObject();
            font.add("providers", providers);
            context.writer().putJson("assets/minecraft/font/default.json", font);
        }
        overlayFont(context);
    }

    /**
     * Font {@code jtb:overlay}: U+E000 is a white band large enough to cover the screen when shown as
     * a title (titles are drawn 4x: 1024 x 256 GUI pixels), tinted by the ui subsystem for Bedrock
     * camera fades ({@link io.github.wyze3306.jtb.resourcepack.ConversionArtifacts#fadeOverlay()}).
     */
    private static void overlayFont(ConversionContext context) {
        BufferedImage band = new BufferedImage(64, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < band.getHeight(); y++) {
            for (int x = 0; x < band.getWidth(); x++) {
                band.setRGB(x, y, 0xFFFFFFFF);
            }
        }
        String texture = "font/overlay.png";
        context.writer().put("assets/" + JavaAssetNames.NAMESPACE + "/textures/" + texture, PackImages.encodePng(band));
        JsonObject provider = new JsonObject();
        provider.addProperty("type", "bitmap");
        provider.addProperty("file", JavaAssetNames.NAMESPACE + ":" + texture);
        provider.addProperty("height", 256);
        provider.addProperty("ascent", 128);
        JsonArray chars = new JsonArray();
        chars.add("\uE000");
        provider.add("chars", chars);
        JsonArray providers = new JsonArray();
        providers.add(provider);
        JsonObject font = new JsonObject();
        font.add("providers", providers);
        context.writer().putJson("assets/" + JavaAssetNames.NAMESPACE + "/font/overlay.json", font);
        context.artifacts().setFadeOverlay(true);
    }
}
