package io.github.wyze3306.jtb.resourcepack.convert;

import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * The server's packs in stack order. {@code ResourcePackStack} lists the highest priority pack
 * first: for a single file the top-most pack that has it wins; JSON tables (atlases, sound
 * definitions...) are merged bottom to top so higher packs override per entry.
 */
public final class PackStack {

    private final List<PackFiles> topToBottom;
    private final List<PackFiles> bottomToTop;

    public PackStack(List<PackFiles> topToBottom) {
        this.topToBottom = List.copyOf(topToBottom);
        this.bottomToTop = this.topToBottom.reversed();
    }

    public List<PackFiles> topToBottom() {
        return this.topToBottom;
    }

    public List<PackFiles> bottomToTop() {
        return this.bottomToTop;
    }

    public boolean isEmpty() {
        return this.topToBottom.isEmpty();
    }

    /** The file at {@code path} from the top-most pack that has it. */
    public PackFiles.@Nullable PackFile file(String path) {
        for (PackFiles pack : this.topToBottom) {
            byte[] data = pack.get(path);
            if (data != null) {
                return new PackFiles.PackFile(path.toLowerCase(Locale.ROOT), data);
            }
        }
        return null;
    }

    /** The image of a texture reference (no extension) from the top-most pack that has one. */
    public PackFiles.@Nullable PackFile image(String reference) {
        for (PackFiles pack : this.topToBottom) {
            PackFiles.PackFile image = pack.image(reference);
            if (image != null) {
                return image;
            }
        }
        return null;
    }

    /** The JSON object at {@code path} from the top-most pack whose copy is readable. */
    public @Nullable JsonObject json(String path) {
        for (PackFiles pack : this.topToBottom) {
            JsonObject json = pack.json(path);
            if (json != null) {
                return json;
            }
        }
        return null;
    }
}
