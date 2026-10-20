package io.github.wyze3306.jtb.resourcepack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What the converted pack provides, for the translators: lets them use a custom model, sound or
 * language entry only when the pack actually contains it.
 */
public final class ConversionArtifacts {

    /** Bedrock item identifier -> Java item model id ({@code item_model} component). */
    private final Map<String, String> itemModels = new HashMap<>();
    /** Bedrock sound event names available as Java custom sound events. */
    private final Set<String> sounds = new HashSet<>();
    /** Translations from the packs' {@code texts/*.lang}, by Bedrock language code. */
    private final Map<String, Map<String, String>> languages = new HashMap<>();
    /**
     * True when the pack has the screen overlay font used for camera fades: font
     * {@code jtb:overlay} whose character U+E000 is an opaque white square tall enough to cover the
     * screen at title scale (e.g. a bitmap provider with {@code height 256, ascent 128}). The ui
     * subsystem tints it with the fade colour.
     */
    private boolean fadeOverlay;

    public Map<String, String> itemModels() {
        return this.itemModels;
    }

    public Set<String> sounds() {
        return this.sounds;
    }

    public Map<String, Map<String, String>> languages() {
        return this.languages;
    }

    public boolean fadeOverlay() {
        return this.fadeOverlay;
    }

    public void setFadeOverlay(boolean fadeOverlay) {
        this.fadeOverlay = fadeOverlay;
    }
}
