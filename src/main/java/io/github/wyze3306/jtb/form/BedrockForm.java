package io.github.wyze3306.jtb.form;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A Bedrock form as sent in {@code ModalFormRequestPacket.formData} (and
 * {@code ServerSettingsResponsePacket.formData}), parsed. Three JSON types exist:
 * <pre>
 * {"type":"form","title":..,"content":..,"elements":[{"type":"button","text":..,"image":{"type":"path"|"url","data":..}},
 *                                                     {"type":"header"|"label"|"divider","text":..}]}   (legacy key: "buttons")
 * {"type":"modal","title":..,"content":..,"button1":..,"button2":..}
 * {"type":"custom_form","title":..,"content":[elements],"submit"?:..}
 *   label/header/divider {text}; input {text, placeholder, default}; toggle {text, default};
 *   slider {text, min, max, step, default}; step_slider {text, steps[], default}; dropdown {text, options[], default}
 * </pre>
 * Texts are kept raw (they may contain {@code §} codes, {@code %key} references or rawtext JSON);
 * a text field given as a JSON object (rawtext) is kept as its JSON string.
 */
public sealed interface BedrockForm permits BedrockForm.Simple, BedrockForm.Modal, BedrockForm.Custom {

    String title();

    /** {@code "form"}: a list of buttons, possibly with text elements in between. */
    record Simple(String title, String content, List<SimpleElement> elements) implements BedrockForm {

        /** Number of buttons (the response is a button index, counting buttons only). */
        public int buttonCount() {
            int count = 0;
            for (SimpleElement element : this.elements) {
                if (element instanceof Button) {
                    count++;
                }
            }
            return count;
        }
    }

    /** {@code "modal"}: a message and two buttons; the response is {@code true} for button1. */
    record Modal(String title, String content, String button1, String button2) implements BedrockForm {
    }

    /** {@code "custom_form"}: inputs answered together; the response is an array aligned with elements. */
    record Custom(String title, List<CustomElement> elements, @Nullable String submit) implements BedrockForm {
    }

    sealed interface SimpleElement permits Button, Text {
    }

    /** A button; {@code imageType} is {@code path} or {@code url} when it has an icon. */
    record Button(String text, @Nullable String imageType, @Nullable String imageData) implements SimpleElement {
    }

    /** A text element of either form type. */
    enum TextStyle { LABEL, HEADER, DIVIDER }

    record Text(TextStyle style, String text) implements SimpleElement, CustomElement {
    }

    sealed interface CustomElement permits Text, Input, Toggle, Slider, StepSlider, Dropdown {
    }

    record Input(String text, String placeholder, String defaultText) implements CustomElement {
    }

    record Toggle(String text, boolean defaultValue) implements CustomElement {
    }

    record Slider(String text, float min, float max, float step, float defaultValue) implements CustomElement {
    }

    record StepSlider(String text, List<String> steps, int defaultIndex) implements CustomElement {
    }

    record Dropdown(String text, List<String> options, int defaultIndex) implements CustomElement {
    }

    // ------------------------------------------------------------------ parsing

    /**
     * Parses form JSON. Lenient like the Bedrock client about missing optional fields and wrong
     * primitive types.
     *
     * @throws IllegalArgumentException when it is not a form
     */
    static BedrockForm parse(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid form JSON: " + e.getMessage(), e);
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("Form JSON is not an object");
        }
        JsonObject object = root.getAsJsonObject();
        String type = string(object, "type", "");
        String title = string(object, "title", "");
        return switch (type) {
            case "form" -> parseSimple(object, title);
            case "modal" -> new Modal(title, string(object, "content", ""), string(object, "button1", ""),
                    string(object, "button2", ""));
            case "custom_form" -> parseCustom(object, title);
            default -> throw new IllegalArgumentException("Unknown form type '" + type + "'");
        };
    }

    private static Simple parseSimple(JsonObject object, String title) {
        List<SimpleElement> elements = new ArrayList<>();
        JsonArray array = array(object, "elements");
        if (array == null) {
            array = array(object, "buttons");
        }
        if (array != null) {
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                String type = string(entry, "type", "button");
                switch (type) {
                    case "header" -> elements.add(new Text(TextStyle.HEADER, string(entry, "text", "")));
                    case "label" -> elements.add(new Text(TextStyle.LABEL, string(entry, "text", "")));
                    case "divider" -> elements.add(new Text(TextStyle.DIVIDER, string(entry, "text", "")));
                    default -> {
                        String imageType = null;
                        String imageData = null;
                        if (entry.get("image") instanceof JsonObject image) {
                            imageType = string(image, "type", null);
                            imageData = string(image, "data", null);
                        }
                        elements.add(new Button(string(entry, "text", ""), imageType, imageData));
                    }
                }
            }
        }
        return new Simple(title, string(object, "content", ""), List.copyOf(elements));
    }

    private static Custom parseCustom(JsonObject object, String title) {
        List<CustomElement> elements = new ArrayList<>();
        JsonArray array = array(object, "content");
        if (array != null) {
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    // Keep the response aligned with the server's element list.
                    elements.add(new Text(TextStyle.LABEL, ""));
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                String text = string(entry, "text", "");
                elements.add(switch (string(entry, "type", "label")) {
                    case "input" -> new Input(text, string(entry, "placeholder", ""), string(entry, "default", ""));
                    case "toggle" -> new Toggle(text, bool(entry, "default"));
                    case "slider" -> {
                        float min = number(entry, "min", 0f);
                        float max = number(entry, "max", min);
                        yield new Slider(text, min, max, number(entry, "step", 1f), number(entry, "default", min));
                    }
                    case "step_slider" -> {
                        List<String> steps = strings(entry, "steps");
                        yield new StepSlider(text, steps, (int) number(entry, "default", 0f));
                    }
                    case "dropdown" -> {
                        List<String> options = strings(entry, "options");
                        yield new Dropdown(text, options, (int) number(entry, "default", 0f));
                    }
                    case "header" -> new Text(TextStyle.HEADER, text);
                    case "divider" -> new Text(TextStyle.DIVIDER, text);
                    default -> new Text(TextStyle.LABEL, text);
                });
            }
        }
        String submit = object.has("submit") ? string(object, "submit", null) : null;
        return new Custom(title, List.copyOf(elements), submit == null || submit.isEmpty() ? null : submit);
    }

    private static @Nullable JsonArray array(JsonObject object, String key) {
        return object.get(key) instanceof JsonArray array ? array : null;
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        // A rawtext object: keep it as JSON, the text renderer understands it.
        return element.toString();
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element instanceof JsonPrimitive primitive) {
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isNumber()) {
                return primitive.getAsDouble() != 0;
            }
            return Boolean.parseBoolean(primitive.getAsString());
        }
        return false;
    }

    private static float number(JsonObject object, String key, float fallback) {
        JsonElement element = object.get(key);
        if (element instanceof JsonPrimitive primitive) {
            try {
                float value = primitive.isNumber() ? primitive.getAsFloat() : Float.parseFloat(primitive.getAsString().trim());
                return Float.isFinite(value) ? value : fallback;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static List<String> strings(JsonObject object, String key) {
        List<String> values = new ArrayList<>();
        JsonArray array = array(object, key);
        if (array != null) {
            for (JsonElement element : array) {
                values.add(element.isJsonPrimitive() ? element.getAsString() : element.toString());
            }
        }
        return List.copyOf(values);
    }
}
