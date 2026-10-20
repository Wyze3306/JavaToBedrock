package io.github.wyze3306.jtb.resourcepack.convert;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Java item model definitions ({@code assets/<ns>/items/<path>.json}, what the {@code item_model}
 * component points to) and simple item models.
 */
final class ItemModels {

    private ItemModels() {
    }

    /** {@code {"model": {"type": "minecraft:model", "model": <model>}}}. */
    static JsonObject plain(String model) {
        JsonObject definition = new JsonObject();
        definition.add("model", reference(model));
        return definition;
    }

    /**
     * An item definition showing {@code flat} in inventories, on the ground and in frames and
     * {@code held} elsewhere (in hand, on the head).
     */
    static JsonObject flatInInventory(String flat, String held) {
        JsonObject select = new JsonObject();
        select.addProperty("type", "minecraft:select");
        select.addProperty("property", "minecraft:display_context");
        JsonArray cases = new JsonArray();
        JsonObject inventory = new JsonObject();
        JsonArray when = new JsonArray();
        when.add("gui");
        when.add("ground");
        when.add("fixed");
        when.add("on_shelf");
        inventory.add("when", when);
        inventory.add("model", reference(flat));
        cases.add(inventory);
        select.add("cases", cases);
        select.add("fallback", reference(held));
        JsonObject definition = new JsonObject();
        definition.add("model", select);
        return definition;
    }

    /** A flat item model ({@code item/generated} or {@code item/handheld}) of one texture. */
    static JsonObject generated(String texture, boolean handheld) {
        JsonObject model = new JsonObject();
        model.addProperty("parent", handheld ? "minecraft:item/handheld" : "minecraft:item/generated");
        JsonObject textures = new JsonObject();
        textures.addProperty("layer0", texture);
        model.add("textures", textures);
        return model;
    }

    private static JsonObject reference(String model) {
        JsonObject reference = new JsonObject();
        reference.addProperty("type", "minecraft:model");
        reference.addProperty("model", model);
        return reference;
    }
}
