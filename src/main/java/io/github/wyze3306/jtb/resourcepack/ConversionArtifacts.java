package io.github.wyze3306.jtb.resourcepack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What the converted pack provides, for the translators: lets them use a custom model, sound or
 * language entry only when the pack actually contains it. Names follow {@link JavaAssetNames}.
 * <p>
 * Built by the converter, then read-only: the getters return unmodifiable views. Translators get it
 * from {@link ResourcePackState#artifacts()}, which is null unless the Java client reported the pack
 * as successfully loaded. Serialised into the pack ({@code jtb/artifacts.json}, outside
 * {@code assets/}, ignored by the client) so a cached pack can be reused after a restart.
 */
public final class ConversionArtifacts {

    /**
     * A custom entity rendered as an {@code item_display} holding {@code minecraft:paper} with an
     * {@code item_model}.
     *
     * @param defaultVariant key of the variant the render controllers select by default
     * @param variants       {@code <geometry key>_<texture key>} -> model
     */
    public record EntityModel(String defaultVariant, Map<String, Variant> variants) {

        public EntityModel {
            variants = Collections.unmodifiableMap(new TreeMap<>(variants));
        }

        public @Nullable Variant defaultModel() {
            return this.variants.get(this.defaultVariant);
        }
    }

    /**
     * One baked model of a custom entity. The model is shrunk to fit Java's element bounds; spawn the
     * display with {@code SCALE = scale x entity scale} and {@code TRANSLATION = (0, translationY x
     * entity scale, 0)} so the model's feet sit at the entity position. The model faces north (-Z),
     * like Bedrock geometry: give the display the entity's yaw.
     *
     * @param itemModel    {@code item_model} key ({@link JavaAssetNames#entityModel})
     * @param scale        display scale that undoes the shrinking (1 when none was needed)
     * @param translationY vertical display translation, in blocks
     */
    public record Variant(String itemModel, float scale, float translationY) {
    }

    private final Map<String, String> itemModels = new TreeMap<>();
    private final Map<String, String> equipmentAssets = new TreeMap<>();
    private final Map<String, String> soundEvents = new TreeMap<>();
    private final Map<String, Map<String, String>> languages = new TreeMap<>();
    private final NavigableSet<Integer> glyphPages = new TreeSet<>();
    private final Map<String, EntityModel> entityModels = new TreeMap<>();
    private final NavigableSet<String> customBlocks = new TreeSet<>();

    public ConversionArtifacts() {
    }

    /** A modifiable copy (phase B starts from the stack-only artifacts). */
    public ConversionArtifacts copy() {
        ConversionArtifacts copy = new ConversionArtifacts();
        copy.itemModels.putAll(this.itemModels);
        copy.equipmentAssets.putAll(this.equipmentAssets);
        copy.soundEvents.putAll(this.soundEvents);
        this.languages.forEach((code, entries) -> copy.languages.put(code, new TreeMap<>(entries)));
        copy.glyphPages.addAll(this.glyphPages);
        copy.entityModels.putAll(this.entityModels);
        copy.customBlocks.addAll(this.customBlocks);
        return copy;
    }

    // ------------------------------------------------------------------ read

    /** Bedrock item identifier -> Java {@code item_model} of the custom items the pack has a model for. */
    public Map<String, String> itemModels() {
        return Collections.unmodifiableMap(this.itemModels);
    }

    public @Nullable String itemModel(String bedrockIdentifier) {
        return this.itemModels.get(bedrockIdentifier);
    }

    /** Bedrock item identifier -> Java equipment asset id (for {@code equippable.asset_id}) of custom armour. */
    public Map<String, String> equipmentAssets() {
        return Collections.unmodifiableMap(this.equipmentAssets);
    }

    public @Nullable String equipmentAsset(String bedrockIdentifier) {
        return this.equipmentAssets.get(bedrockIdentifier);
    }

    /** Bedrock sound names defined (or re-defined with new files) by the server's packs. */
    public Set<String> sounds() {
        return Collections.unmodifiableSet(this.soundEvents.keySet());
    }

    /** Bedrock sound name -> Java custom sound event ({@link JavaAssetNames#soundEvent}). */
    public Map<String, String> soundEvents() {
        return Collections.unmodifiableMap(this.soundEvents);
    }

    public @Nullable String soundEvent(String bedrockName) {
        return this.soundEvents.get(bedrockName);
    }

    /** Translations from the packs' {@code texts/*.lang}, by Bedrock language code ({@code en_US}). */
    public Map<String, Map<String, String>> languages() {
        return Collections.unmodifiableMap(this.languages);
    }

    /**
     * High bytes of the glyph pages ({@code font/glyph_E1.png} -> {@code 0xE1}) the pack's default font
     * provides: code points {@code page << 8 .. page << 8 | 0xFF} render as the server's icons.
     */
    public Set<Integer> glyphPages() {
        return Collections.unmodifiableSet(this.glyphPages);
    }

    public boolean hasGlyph(int codePoint) {
        return codePoint >= 0 && this.glyphPages.contains(codePoint >> 8);
    }

    /** Bedrock entity identifier -> baked model of the custom entities the pack defines. */
    public Map<String, EntityModel> entityModels() {
        return Collections.unmodifiableMap(this.entityModels);
    }

    public @Nullable EntityModel entityModel(String identifier) {
        return this.entityModels.get(identifier);
    }

    /** Bedrock identifiers of the custom blocks whose carrier states got block models. */
    public Set<String> customBlocks() {
        return Collections.unmodifiableSet(this.customBlocks);
    }

    // ------------------------------------------------------------------ write (converter only)

    public void putItemModel(String bedrockIdentifier, String itemModel) {
        this.itemModels.put(bedrockIdentifier, itemModel);
    }

    public void putEquipmentAsset(String bedrockIdentifier, String asset) {
        this.equipmentAssets.put(bedrockIdentifier, asset);
    }

    public void putSoundEvent(String bedrockName, String javaEvent) {
        this.soundEvents.put(bedrockName, javaEvent);
    }

    public void putLanguage(String bedrockCode, Map<String, String> entries) {
        this.languages.computeIfAbsent(bedrockCode, k -> new TreeMap<>()).putAll(entries);
    }

    public void addGlyphPage(int page) {
        this.glyphPages.add(page);
    }

    public void putEntityModel(String identifier, EntityModel model) {
        this.entityModels.put(identifier, model);
    }

    public void addCustomBlock(String identifier) {
        this.customBlocks.add(identifier);
    }

    // ------------------------------------------------------------------ json

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.add("item_models", stringMap(this.itemModels));
        json.add("equipment_assets", stringMap(this.equipmentAssets));
        json.add("sound_events", stringMap(this.soundEvents));
        JsonObject languages = new JsonObject();
        this.languages.forEach((code, entries) -> languages.add(code, stringMap(entries)));
        json.add("languages", languages);
        JsonArray pages = new JsonArray();
        this.glyphPages.forEach(pages::add);
        json.add("glyph_pages", pages);
        JsonObject entities = new JsonObject();
        this.entityModels.forEach((id, model) -> {
            JsonObject entity = new JsonObject();
            entity.addProperty("default", model.defaultVariant());
            JsonObject variants = new JsonObject();
            model.variants().forEach((key, variant) -> {
                JsonObject v = new JsonObject();
                v.addProperty("item_model", variant.itemModel());
                v.addProperty("scale", variant.scale());
                v.addProperty("translation_y", variant.translationY());
                variants.add(key, v);
            });
            entity.add("variants", variants);
            entities.add(id, entity);
        });
        json.add("entity_models", entities);
        JsonArray blocks = new JsonArray();
        this.customBlocks.forEach(blocks::add);
        json.add("custom_blocks", blocks);
        return json;
    }

    /** Reads {@link #toJson()} output; unknown or malformed parts are skipped. */
    public static ConversionArtifacts fromJson(JsonObject json) {
        ConversionArtifacts artifacts = new ConversionArtifacts();
        readStringMap(json.get("item_models"), artifacts.itemModels);
        readStringMap(json.get("equipment_assets"), artifacts.equipmentAssets);
        readStringMap(json.get("sound_events"), artifacts.soundEvents);
        if (json.get("languages") instanceof JsonObject languages) {
            for (Map.Entry<String, JsonElement> language : languages.entrySet()) {
                Map<String, String> entries = new TreeMap<>();
                readStringMap(language.getValue(), entries);
                artifacts.languages.put(language.getKey(), entries);
            }
        }
        if (json.get("glyph_pages") instanceof JsonArray pages) {
            pages.forEach(page -> artifacts.glyphPages.add(page.getAsInt()));
        }
        if (json.get("entity_models") instanceof JsonObject entities) {
            for (Map.Entry<String, JsonElement> entity : entities.entrySet()) {
                if (!(entity.getValue() instanceof JsonObject model) || !(model.get("variants") instanceof JsonObject variants)) {
                    continue;
                }
                Map<String, Variant> parsed = new TreeMap<>();
                for (Map.Entry<String, JsonElement> variant : variants.entrySet()) {
                    if (variant.getValue() instanceof JsonObject v) {
                        parsed.put(variant.getKey(), new Variant(v.get("item_model").getAsString(),
                                v.get("scale").getAsFloat(), v.get("translation_y").getAsFloat()));
                    }
                }
                artifacts.entityModels.put(entity.getKey(), new EntityModel(model.get("default").getAsString(), parsed));
            }
        }
        if (json.get("custom_blocks") instanceof JsonArray blocks) {
            blocks.forEach(block -> artifacts.customBlocks.add(block.getAsString()));
        }
        return artifacts;
    }

    private static JsonObject stringMap(Map<String, String> map) {
        JsonObject json = new JsonObject();
        map.forEach(json::addProperty);
        return json;
    }

    private static void readStringMap(@Nullable JsonElement element, Map<String, String> into) {
        if (element instanceof JsonObject object) {
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    into.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
    }
}
