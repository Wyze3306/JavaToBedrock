package io.github.wyze3306.jtb.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.text.TextTranslator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Bedrock "rawtext" JSON ({@code {"rawtext":[{"text":..},{"translate":..,"with":[..]}]}}), used by
 * the {@code *_TEXT_OBJECT} title types and by script-generated forms, to Java components. Leaves are
 * rendered with {@link TextTranslator} so formatting codes and translations follow the chat
 * subsystem; {@code selector} and {@code score} entries (resolved by the server on Bedrock) are kept
 * as their plain text.
 */
public final class RawText {

    private RawText() {
    }

    /** Session-bound translator of Bedrock text that may be plain, translatable or rawtext JSON. */
    public static Function<String, Component> translator(ProxySession session) {
        return text -> toComponent(text, literal -> TextTranslator.translate(session, literal),
                (key, with) -> TextTranslator.translate(session, key, with));
    }

    /**
     * Renders a Bedrock string: rawtext JSON when it parses as such, otherwise {@code literal}.
     *
     * @param literal   renders plain Bedrock text ({@code §} codes, {@code %key} references)
     * @param translate renders a translation key with its parameters
     */
    public static Component toComponent(String text, Function<String, Component> literal,
                                        BiFunction<String, List<String>, Component> translate) {
        JsonObject root = parseRawText(text);
        if (root == null) {
            return literal.apply(text);
        }
        return render(root, literal, translate);
    }

    /** The rawtext root object of {@code text}, or null when it is not rawtext JSON. */
    public static @Nullable JsonObject parseRawText(String text) {
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        if (start >= text.length() || text.charAt(start) != '{' || !text.contains("\"rawtext\"")) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(text);
            if (element.isJsonObject() && element.getAsJsonObject().has("rawtext")) {
                return element.getAsJsonObject();
            }
        } catch (JsonParseException | IllegalStateException ignored) {
            // Not JSON: plain text that happens to start with a brace.
        }
        return null;
    }

    /** Renders a parsed rawtext object. */
    public static Component render(JsonObject root, Function<String, Component> literal,
                                   BiFunction<String, List<String>, Component> translate) {
        JsonElement parts = root.get("rawtext");
        if (parts == null || !parts.isJsonArray()) {
            return Component.empty();
        }
        TextComponent.Builder builder = Component.text();
        for (JsonElement part : parts.getAsJsonArray()) {
            if (part.isJsonObject()) {
                builder.append(renderPart(part.getAsJsonObject(), literal, translate));
            } else if (part.isJsonPrimitive()) {
                builder.append(literal.apply(part.getAsString()));
            }
        }
        return builder.build();
    }

    private static Component renderPart(JsonObject part, Function<String, Component> literal,
                                        BiFunction<String, List<String>, Component> translate) {
        if (part.has("rawtext")) {
            return render(part, literal, translate);
        }
        if (part.has("text")) {
            return literal.apply(part.get("text").getAsString());
        }
        if (part.has("translate")) {
            return translate.apply(part.get("translate").getAsString(), parameters(part.get("with")));
        }
        if (part.has("selector")) {
            return literal.apply(part.get("selector").getAsString());
        }
        if (part.has("score") && part.get("score").isJsonObject()) {
            JsonObject score = part.getAsJsonObject("score");
            JsonElement value = score.get("value");
            return literal.apply(value != null ? value.getAsString() : "");
        }
        return Component.empty();
    }

    /** {@code with}: a string array, or a nested rawtext whose entries are the parameters. */
    private static List<String> parameters(@Nullable JsonElement with) {
        List<String> parameters = new ArrayList<>();
        if (with == null) {
            return parameters;
        }
        JsonArray array = null;
        if (with.isJsonArray()) {
            array = with.getAsJsonArray();
        } else if (with.isJsonObject() && with.getAsJsonObject().get("rawtext") instanceof JsonArray nested) {
            array = nested;
        }
        if (array == null) {
            return parameters;
        }
        for (JsonElement element : array) {
            if (element.isJsonPrimitive()) {
                parameters.add(element.getAsString());
            } else if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                if (object.has("text")) {
                    parameters.add(object.get("text").getAsString());
                } else if (object.has("translate")) {
                    parameters.add(object.get("translate").getAsString());
                } else if (object.has("selector")) {
                    parameters.add(object.get("selector").getAsString());
                }
            }
        }
        return parameters;
    }
}
