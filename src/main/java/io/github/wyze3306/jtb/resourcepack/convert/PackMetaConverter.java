package io.github.wyze3306.jtb.resourcepack.convert;

import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/**
 * {@code pack.mcmeta} (Java 26.3 resource pack format 97, written like the vanilla built-in packs:
 * integer {@code min_format}/{@code max_format}) and {@code pack.png} from the top pack's icon.
 */
final class PackMetaConverter {

    /** Java 26.3 resource pack format. */
    static final int PACK_FORMAT = 97;

    private PackMetaConverter() {
    }

    static void convert(ConversionContext context) {
        PackFiles top = context.stack().topToBottom().isEmpty() ? null : context.stack().topToBottom().getFirst();
        String name = top == null ? "Bedrock" : displayName(context, top);
        int others = context.stack().topToBottom().size() - 1;
        JsonObject description = new JsonObject();
        description.addProperty("text", name + (others > 0 ? " (+" + others + ")" : "") + " - JavaToBedrock");
        JsonObject pack = new JsonObject();
        pack.add("description", description);
        pack.addProperty("min_format", PACK_FORMAT);
        pack.addProperty("max_format", PACK_FORMAT);
        JsonObject mcmeta = new JsonObject();
        mcmeta.add("pack", pack);
        context.writer().putJson("pack.mcmeta", mcmeta);

        if (top != null) {
            PackFiles.PackFile icon = top.image("pack_icon");
            byte[] png = icon == null ? null : PackImages.toPng(icon.path(), icon.data());
            if (png != null) {
                context.writer().put("pack.png", png);
            }
        }
    }

    /** The manifest name, resolved through the pack's own translations when it is a lang key. */
    private static String displayName(ConversionContext context, PackFiles pack) {
        String name = pack.manifest().name();
        if (name.isBlank()) {
            return "Bedrock";
        }
        String translated = translate(context, name);
        return stripFormatting(translated == null ? name : translated);
    }

    private static @Nullable String translate(ConversionContext context, String key) {
        return context.definitions().translate("en_US", key);
    }

    private static String stripFormatting(String text) {
        return text.replaceAll("§.", "").trim();
    }
}
