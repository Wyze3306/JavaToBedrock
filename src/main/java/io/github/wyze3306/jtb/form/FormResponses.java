package io.github.wyze3306.jtb.form;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import io.github.wyze3306.jtb.form.BedrockForm.CustomElement;
import io.github.wyze3306.jtb.form.BedrockForm.Dropdown;
import io.github.wyze3306.jtb.form.BedrockForm.Input;
import io.github.wyze3306.jtb.form.BedrockForm.Slider;
import io.github.wyze3306.jtb.form.BedrockForm.StepSlider;
import io.github.wyze3306.jtb.form.BedrockForm.Text;
import io.github.wyze3306.jtb.form.BedrockForm.Toggle;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.ModalFormCancelReason;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Java dialog payloads ({@link FormDialogs}) to Bedrock {@code ModalFormResponsePacket} contents,
 * exactly as a vanilla client writes them (and as PowerNukkitX parses them):
 * <ul>
 *     <li>simple form: the clicked button index among buttons only, e.g. {@code "2"};</li>
 *     <li>modal form: {@code "true"} for button1, {@code "false"} for button2;</li>
 *     <li>custom form: a JSON array with one entry per element, in order: {@code null} for
 *     labels/headers/dividers, a string for inputs, a boolean for toggles, a number for sliders and
 *     an integer index for dropdowns and step sliders (PowerNukkitX uses {@code Integer.parseInt},
 *     so never {@code 2.0});</li>
 *     <li>closed: no response and cancel reason {@code USER_CLOSED}.</li>
 * </ul>
 * Like the vanilla client a trailing newline is appended. The response never exceeds
 * {@link #MAX_LENGTH} characters: PowerNukkitX disconnects a client that sends more, so text inputs
 * are shortened when needed.
 */
public final class FormResponses {

    /** Longest {@code jsonResponse} PowerNukkitX accepts. */
    public static final int MAX_LENGTH = 1024;

    private FormResponses() {
    }

    /**
     * What to do after a dialog click.
     *
     * @param json   the Bedrock response, or null when cancelled
     * @param cancel the cancel reason, or null when answered
     * @param reopen true when the click must not be answered and the dialog is shown again
     */
    public record Result(@Nullable String json, @Nullable ModalFormCancelReason cancel, boolean reopen) {

        public static final Result REOPEN = new Result(null, null, true);

        public static Result closed() {
            return new Result(null, ModalFormCancelReason.USER_CLOSED, false);
        }

        public static Result answer(String json) {
            return new Result(json, null, false);
        }
    }

    /** Interprets a click on a form dialog. */
    public static Result respond(BedrockForm form, @Nullable NbtMap payload) {
        NbtMap data = payload == null ? NbtMap.EMPTY : payload;
        if (data.getBoolean(FormDialogs.EXIT, false)) {
            return Result.closed();
        }
        if (data.getBoolean(FormDialogs.REOPEN, false)) {
            return Result.REOPEN;
        }
        return switch (form) {
            case BedrockForm.Simple simple -> {
                int button = integer(data.get(FormDialogs.BUTTON_ID), -1);
                yield button >= 0 && button < simple.buttonCount() ? Result.answer(button + "\n") : Result.REOPEN;
            }
            case BedrockForm.Modal ignored -> {
                int button = integer(data.get(FormDialogs.BUTTON_ID), -1);
                yield button == 0 || button == 1 ? Result.answer((button == 0 ? "true" : "false") + "\n") : Result.REOPEN;
            }
            case BedrockForm.Custom custom -> Result.answer(customResponse(custom, data));
        };
    }

    /** The JSON array answering a custom form, at most {@link #MAX_LENGTH} characters with its newline. */
    static String customResponse(BedrockForm.Custom form, NbtMap data) {
        List<CustomElement> elements = form.elements();
        Object[] values = new Object[elements.size()];
        for (int i = 0; i < elements.size(); i++) {
            Object value = data.get(String.valueOf(i));
            values[i] = switch (elements.get(i)) {
                case Text ignored -> null;
                case Input input -> value instanceof String string ? string : input.defaultText();
                case Toggle toggle -> value instanceof Number number ? number.intValue() != 0
                        : value instanceof Boolean bool ? bool : toggle.defaultValue();
                case Slider slider -> {
                    float number = value instanceof Number n ? n.floatValue() : slider.defaultValue();
                    float low = Math.min(slider.min(), slider.max());
                    float high = Math.max(slider.min(), slider.max());
                    yield Float.isFinite(number) ? Math.max(low, Math.min(high, number)) : low;
                }
                case StepSlider stepSlider -> index(value, stepSlider.steps().size(), stepSlider.defaultIndex());
                case Dropdown dropdown -> index(value, dropdown.options().size(), dropdown.defaultIndex());
            };
        }
        String json = toJson(values);
        while (json.length() + 1 > MAX_LENGTH) {
            int longest = -1;
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof String string && !string.isEmpty()
                        && (longest < 0 || string.length() > ((String) values[longest]).length())) {
                    longest = i;
                }
            }
            if (longest < 0) {
                break;
            }
            String string = (String) values[longest];
            int excess = json.length() + 1 - MAX_LENGTH;
            values[longest] = string.substring(0, Math.max(0, string.length() - Math.max(1, excess)));
            json = toJson(values);
        }
        return json + "\n";
    }

    private static String toJson(Object[] values) {
        JsonArray array = new JsonArray(values.length);
        for (Object value : values) {
            switch (value) {
                case null -> array.add(JsonNull.INSTANCE);
                case String string -> array.add(new JsonPrimitive(string));
                case Boolean bool -> array.add(new JsonPrimitive(bool));
                case Float number -> array.add(new JsonPrimitive(number));
                case Integer number -> array.add(new JsonPrimitive(number));
                default -> array.add(new JsonPrimitive(value.toString()));
            }
        }
        return array.toString();
    }

    private static int index(@Nullable Object value, int size, int fallback) {
        int index = integer(value, -1);
        if (index >= 0 && index < size) {
            return index;
        }
        return fallback >= 0 && fallback < size ? fallback : 0;
    }

    private static int integer(@Nullable Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
