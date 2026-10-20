package io.github.wyze3306.jtb.form;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.mcprotocollib.protocol.data.DefaultComponentSerializer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds Java 26.3 dialog definitions (the inline NBT of {@code ClientboundShowDialog*}).
 * <p>
 * Schema notes (vanilla 26.3, the 1.21.6 dialog format):
 * <ul>
 *     <li>dialog types {@code notice} (one {@code action} button, Escape runs it), {@code confirmation}
 *     ({@code yes}/{@code no}, Escape runs {@code no}) and {@code multi_action} ({@code actions},
 *     non-empty, plus an optional {@code exit_action} that Escape runs);</li>
 *     <li>a button is {@code {label, tooltip?, width 1..1024, action?}}; without an action it only
 *     closes the dialog, without telling the server anything;</li>
 *     <li>{@code dynamic/custom} actions make the client send {@code ServerboundCustomClickAction}
 *     with every input value plus the static {@code additions} (additions win on equal keys):
 *     text = string, boolean = byte, number_range = float, single_option = option id string;</li>
 *     <li>the client validates the definition and drops the connection on an invalid one: text input
 *     {@code initial} longer than {@code max_length}, a number range whose initial value is out of
 *     range or whose step is not positive, a single option list that is empty or has several
 *     initial entries, an empty {@code actions} list, or {@code pause} with {@code after_action}
 *     {@code none}. The builders here never produce those.</li>
 * </ul>
 * Texts are Adventure components, serialised with MCProtocolLib's NBT component serializer.
 */
public final class Dialogs {

    /** Width of a regular dialog button (vanilla default 150, Bedrock-like forms look better wider). */
    public static final int BUTTON_WIDTH = 200;
    /** Width of message bodies and inputs. */
    public static final int CONTENT_WIDTH = 300;

    /** {@code after_action}: close the dialog after a button action. */
    public static final String AFTER_CLOSE = "close";
    /** {@code after_action}: keep the dialog open. Not allowed together with {@code pause}. */
    public static final String AFTER_NONE = "none";
    /** {@code after_action}: show a waiting screen until the server sends a dialog or clears it. */
    public static final String AFTER_WAIT = "wait_for_response";

    private Dialogs() {
    }

    /** A text component as dialog NBT (a plain string or a component compound). */
    public static Object text(Component component) {
        return DefaultComponentSerializer.nbt().serialize(component);
    }

    // ------------------------------------------------------------------ bodies

    /** A {@code plain_message} body; newlines in the component are rendered as line breaks. */
    public static NbtMap plainMessage(Component contents, int width) {
        NbtMapBuilder builder = NbtMap.builder().putString("type", "minecraft:plain_message");
        builder.put("contents", text(contents));
        builder.putInt("width", clampWidth(width));
        return builder.build();
    }

    // ------------------------------------------------------------------ buttons and actions

    /** A button; {@code action} may be null (the button then only closes the dialog). */
    public static NbtMap button(Component label, @Nullable Component tooltip, int width, @Nullable NbtMap action) {
        NbtMapBuilder builder = NbtMap.builder();
        builder.put("label", text(label));
        if (tooltip != null) {
            builder.put("tooltip", text(tooltip));
        }
        builder.putInt("width", clampWidth(width));
        if (action != null) {
            builder.putCompound("action", action);
        }
        return builder.build();
    }

    public static NbtMap button(Component label, @Nullable NbtMap action) {
        return button(label, null, BUTTON_WIDTH, action);
    }

    /** {@code open_url} action. */
    public static NbtMap openUrl(String url) {
        return NbtMap.builder().putString("type", "minecraft:open_url").putString("url", url).build();
    }

    /** {@code custom} action: the client sends {@code ServerboundCustomClickAction(id, payload)}. */
    public static NbtMap custom(Key id, @Nullable NbtMap payload) {
        NbtMapBuilder builder = NbtMap.builder().putString("type", "minecraft:custom").putString("id", id.asString());
        if (payload != null) {
            builder.putCompound("payload", payload);
        }
        return builder.build();
    }

    /**
     * {@code dynamic/custom} action: the client sends {@code ServerboundCustomClickAction(id, inputs + additions)}.
     */
    public static NbtMap dynamicCustom(Key id, @Nullable NbtMap additions) {
        NbtMapBuilder builder = NbtMap.builder().putString("type", "minecraft:dynamic/custom").putString("id", id.asString());
        if (additions != null && !additions.isEmpty()) {
            builder.putCompound("additions", additions);
        }
        return builder.build();
    }

    // ------------------------------------------------------------------ inputs

    /**
     * A single-line text input. {@code maxLength} is raised to the initial text length when needed
     * (the client rejects a longer initial value).
     */
    public static NbtMap textInput(String key, Component label, boolean labelVisible, String initial, int maxLength, int width) {
        NbtMapBuilder builder = input("minecraft:text", key, label);
        builder.putBoolean("label_visible", labelVisible);
        builder.putString("initial", initial);
        builder.putInt("max_length", Math.max(1, Math.max(maxLength, initial.length())));
        builder.putInt("width", clampWidth(width));
        return builder.build();
    }

    /**
     * A text-only line among inputs: a tiny (16x1), label-visible text input. Dialogs cannot
     * interleave bodies with inputs, so text that comes after an input is rendered this way to keep
     * its place (ViaBedrock uses a full-width one-pixel-high box; the width is kept positive after
     * the box's inner padding). The key must not collide with real inputs; its value is meaningless.
     */
    public static NbtMap labelInput(String key, Component label) {
        NbtMapBuilder builder = input("minecraft:text", key, label);
        builder.putBoolean("label_visible", true);
        builder.putString("initial", "");
        builder.putInt("max_length", 1);
        builder.putInt("width", 16);
        builder.putCompound("multiline", NbtMap.builder().putInt("max_lines", 1).putInt("height", 1).build());
        return builder.build();
    }

    /** A checkbox; its value is reported as a byte (0/1). */
    public static NbtMap booleanInput(String key, Component label, boolean initial) {
        NbtMapBuilder builder = input("minecraft:boolean", key, label);
        builder.putBoolean("initial", initial);
        return builder.build();
    }

    /**
     * A slider from {@code start} to {@code end}. A non-positive {@code step} means continuous; the
     * initial value is clamped into the range and an empty range is widened by one step so the
     * client accepts it. Its value is reported as a float.
     */
    public static NbtMap numberRange(String key, Component label, float start, float end, float step, float initial, int width) {
        float low = Math.min(start, end);
        float high = Math.max(start, end);
        if (!(high > low)) {
            high = low + (step > 0 ? step : 1f);
        }
        float value = Float.isNaN(initial) ? low : Math.max(low, Math.min(high, initial));
        NbtMapBuilder builder = input("minecraft:number_range", key, label);
        builder.putFloat("start", low);
        builder.putFloat("end", high);
        if (step > 0 && Float.isFinite(step)) {
            builder.putFloat("step", step);
        }
        builder.putFloat("initial", value);
        builder.putInt("width", clampWidth(width));
        return builder.build();
    }

    /** One option of a {@link #singleOption} input. */
    public record Option(String id, Component display) {
    }

    /**
     * A cycling option button; {@code initial} is the index of the selected option (clamped). Its value is
     * reported as the option id. {@code options} must not be empty.
     */
    public static NbtMap singleOption(String key, Component label, List<Option> options, int initial, int width) {
        if (options.isEmpty()) {
            throw new IllegalArgumentException("A single option input needs options");
        }
        int selected = initial >= 0 && initial < options.size() ? initial : 0;
        List<NbtMap> list = new ArrayList<>(options.size());
        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            NbtMapBuilder entry = NbtMap.builder().putString("id", option.id());
            entry.put("display", text(option.display()));
            if (i == selected) {
                entry.putBoolean("initial", true);
            }
            list.add(entry.build());
        }
        NbtMapBuilder builder = input("minecraft:single_option", key, label);
        builder.putList("options", NbtType.COMPOUND, list);
        builder.putInt("width", clampWidth(width));
        return builder.build();
    }

    private static NbtMapBuilder input(String type, String key, Component label) {
        NbtMapBuilder builder = NbtMap.builder().putString("type", type).putString("key", key);
        builder.put("label", text(label));
        return builder;
    }

    // ------------------------------------------------------------------ dialogs

    /** A {@code notice} dialog: one button, which Escape also runs. */
    public static Builder notice(Component title) {
        return new Builder("minecraft:notice", title);
    }

    /** A {@code multi_action} dialog: a column of buttons and an optional exit button run by Escape. */
    public static Builder multiAction(Component title) {
        return new Builder("minecraft:multi_action", title);
    }

    /** Accumulates a dialog definition. */
    public static final class Builder {

        private final String type;
        private final Component title;
        private @Nullable Component externalTitle;
        private final List<NbtMap> body = new ArrayList<>();
        private final List<NbtMap> inputs = new ArrayList<>();
        private final List<NbtMap> actions = new ArrayList<>();
        private @Nullable NbtMap exitAction;
        private @Nullable NbtMap noticeAction;
        private int columns = 1;
        private boolean canCloseWithEscape = true;
        private String afterAction = AFTER_CLOSE;

        private Builder(String type, Component title) {
            this.type = type;
            this.title = title;
        }

        /** Label of the dialog when listed elsewhere (pause menu button, dialog lists). */
        public Builder externalTitle(Component externalTitle) {
            this.externalTitle = externalTitle;
            return this;
        }

        public Builder body(NbtMap element) {
            this.body.add(element);
            return this;
        }

        public Builder input(NbtMap input) {
            this.inputs.add(input);
            return this;
        }

        public boolean hasInputs() {
            return !this.inputs.isEmpty();
        }

        /** Adds a button to a {@code multi_action} dialog. */
        public Builder action(NbtMap button) {
            this.actions.add(button);
            return this;
        }

        /** The button of a {@code multi_action} dialog that Escape runs. */
        public Builder exitAction(NbtMap button) {
            this.exitAction = button;
            return this;
        }

        /** The single button of a {@code notice} dialog (Escape runs it too). */
        public Builder noticeAction(NbtMap button) {
            this.noticeAction = button;
            return this;
        }

        public Builder columns(int columns) {
            this.columns = Math.max(1, columns);
            return this;
        }

        public Builder canCloseWithEscape(boolean canCloseWithEscape) {
            this.canCloseWithEscape = canCloseWithEscape;
            return this;
        }

        /** One of {@link #AFTER_CLOSE}, {@link #AFTER_NONE}, {@link #AFTER_WAIT}. */
        public Builder afterAction(String afterAction) {
            this.afterAction = afterAction;
            return this;
        }

        public NbtMap build() {
            NbtMapBuilder builder = NbtMap.builder().putString("type", this.type);
            builder.put("title", text(this.title));
            if (this.externalTitle != null) {
                builder.put("external_title", text(this.externalTitle));
            }
            if (!this.body.isEmpty()) {
                builder.putList("body", NbtType.COMPOUND, new NbtList<>(NbtType.COMPOUND, this.body));
            }
            if (!this.inputs.isEmpty()) {
                builder.putList("inputs", NbtType.COMPOUND, new NbtList<>(NbtType.COMPOUND, this.inputs));
            }
            builder.putBoolean("can_close_with_escape", this.canCloseWithEscape);
            // Never pause: it only matters in singleplayer and is invalid with after_action "none".
            builder.putBoolean("pause", false);
            builder.putString("after_action", this.afterAction);
            if (this.type.equals("minecraft:multi_action")) {
                if (this.actions.isEmpty()) {
                    throw new IllegalStateException("A multi_action dialog needs at least one button");
                }
                builder.putList("actions", NbtType.COMPOUND, new NbtList<>(NbtType.COMPOUND, this.actions));
                builder.putInt("columns", this.columns);
                if (this.exitAction != null) {
                    builder.putCompound("exit_action", this.exitAction);
                }
            } else if (this.noticeAction != null) {
                builder.putCompound("action", this.noticeAction);
            }
            return builder.build();
        }
    }

    private static int clampWidth(int width) {
        return Math.max(1, Math.min(1024, width));
    }
}
