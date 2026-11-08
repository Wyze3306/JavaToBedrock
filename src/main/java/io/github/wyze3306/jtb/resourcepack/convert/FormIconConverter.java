package io.github.wyze3306.jtb.resourcepack.convert;

import io.github.wyze3306.jtb.resourcepack.JavaAssetNames;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Item models for the images form buttons show: a server names any texture of its packs as a button
 * image ({@code textures/ui/shop}, {@code textures/custom/...}), and only learns the proxy which one when
 * the form arrives, after the pack was sent. So every square image of the folders button images come
 * from gets a flat item model, {@code JavaAssetNames.formIcon(path)}; chest menus show buttons as items
 * with them ({@code form.ChestMenu}).
 */
final class FormIconConverter {

    private static final String[] FOLDERS = {"textures/ui/", "textures/items/", "textures/custom/", "textures/blocks/"};
    /** Larger images are backgrounds and screens, not icons. */
    private static final int MAX_SIZE = 256;

    private FormIconConverter() {
    }

    static void convert(ConversionContext context) {
        Set<String> seen = new HashSet<>();
        for (PackFiles pack : context.stack().topToBottom()) {
            for (String folder : FOLDERS) {
                for (String file : pack.list(folder)) {
                    String key = key(file);
                    if (key == null || !seen.add(key)) {
                        continue;
                    }
                    byte[] data = pack.get(file);
                    int[] size = data == null ? null : PackImages.size(file, data);
                    if (size == null || size[0] != size[1] || size[0] > MAX_SIZE || size[0] < 8) {
                        continue;
                    }
                    String texture = context.texture(key, ConversionContext.TextureKind.ITEM);
                    if (texture == null) {
                        continue;
                    }
                    String itemModel = JavaAssetNames.formIcon(key);
                    String path = itemModel.substring(itemModel.indexOf(':') + 1);
                    JsonObject model = new JsonObject();
                    model.addProperty("parent", "minecraft:item/generated");
                    JsonObject textures = new JsonObject();
                    textures.addProperty("layer0", texture);
                    model.add("textures", textures);
                    context.writer().putJson("assets/" + JavaAssetNames.NAMESPACE + "/models/item/" + path + ".json", model);
                    context.writer().putJson("assets/" + JavaAssetNames.NAMESPACE + "/items/" + path + ".json",
                            ItemModels.plain(JavaAssetNames.NAMESPACE + ":item/" + path));
                    context.artifacts().putFormIcon(key, itemModel);
                    context.count("form button icons");
                }
            }
        }
    }

    /** {@code textures/ui/shop} for {@code textures/ui/shop.png}; null for files that are not images. */
    static String key(String file) {
        String lower = file.toLowerCase(Locale.ROOT);
        for (String extension : new String[]{".png", ".tga", ".jpg", ".jpeg"}) {
            if (lower.endsWith(extension)) {
                return lower.substring(0, lower.length() - extension.length());
            }
        }
        return null;
    }
}
