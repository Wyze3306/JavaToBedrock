package io.github.wyze3306.jtb.form;

import io.github.wyze3306.jtb.form.BedrockForm.Button;
import io.github.wyze3306.jtb.form.BedrockForm.CustomElement;
import io.github.wyze3306.jtb.form.BedrockForm.Dropdown;
import io.github.wyze3306.jtb.form.BedrockForm.Input;
import io.github.wyze3306.jtb.form.BedrockForm.SimpleElement;
import io.github.wyze3306.jtb.form.BedrockForm.Slider;
import io.github.wyze3306.jtb.form.BedrockForm.StepSlider;
import io.github.wyze3306.jtb.form.BedrockForm.Text;
import io.github.wyze3306.jtb.form.BedrockForm.TextStyle;
import io.github.wyze3306.jtb.form.BedrockForm.Toggle;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.cloudburstmc.nbt.NbtMap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Bedrock forms to Java dialogs. Every button uses a {@code dynamic/custom} action with the dialog's
 * action id, so one {@code ServerboundCustomClickAction} carries everything {@link FormResponses}
 * needs:
 * <ul>
 *     <li>simple and modal forms: {@code {button_id: <index among buttons>}};</li>
 *     <li>custom forms: one input per interactive element keyed by its index in {@code content},
 *     submitted together by the single submit button;</li>
 *     <li>Escape (the exit button): {@code {exit: 1b}}, answered as {@code USER_CLOSED};</li>
 *     <li>text elements that have to be rendered as buttons (between simple form buttons):
 *     {@code {reopen: 1b}}, which just shows the dialog again.</li>
 * </ul>
 */
public final class FormDialogs {

    /** Payload key of the clicked simple/modal button. */
    public static final String BUTTON_ID = "button_id";
    /** Payload key set by the exit button (Escape). */
    public static final String EXIT = "exit";
    /** Payload key set by text elements rendered as buttons. */
    public static final String REOPEN = "reopen";
    /** Prefix of the keys of text-only pseudo inputs. */
    static final String LABEL_KEY_PREFIX = "l";

    private static final Component DIVIDER = Component.text("─".repeat(24), NamedTextColor.DARK_GRAY);
    /** Bedrock text inputs have no limit; the whole response must stay under 1024 characters anyway. */
    private static final int TEXT_MAX_LENGTH = 512;

    private FormDialogs() {
    }

    /** The close button label (Java's own translation of "Close"). */
    public static Component closeLabel() {
        return Component.translatable("mco.selectServer.close");
    }

    /** The exit button every form dialog has, so that Escape is reported to the server. */
    public static NbtMap exitButton(Key actionId) {
        return Dialogs.button(closeLabel(), Dialogs.dynamicCustom(actionId, NbtMap.builder().putBoolean(EXIT, true).build()));
    }

    /**
     * Builds the dialog for a form.
     *
     * @param actionId id of the custom click action the dialog's buttons send
     * @param text     renders a Bedrock text (formatting codes, translations, rawtext)
     */
    public static NbtMap build(BedrockForm form, Key actionId, Function<String, Component> text) {
        return switch (form) {
            case BedrockForm.Simple simple -> simple(simple, actionId, text);
            case BedrockForm.Modal modal -> modal(modal, actionId, text);
            case BedrockForm.Custom custom -> custom(custom, actionId, text);
        };
    }

    private static NbtMap simple(BedrockForm.Simple form, Key actionId, Function<String, Component> text) {
        Component title = text.apply(form.title());
        if (form.buttonCount() == 0) {
            Dialogs.Builder notice = Dialogs.notice(title);
            addBody(notice, form.content(), text);
            for (SimpleElement element : form.elements()) {
                if (element instanceof Text textElement) {
                    notice.body(Dialogs.plainMessage(textComponent(textElement, text), Dialogs.CONTENT_WIDTH));
                }
            }
            return notice.noticeAction(exitButton(actionId)).build();
        }
        Dialogs.Builder dialog = Dialogs.multiAction(title).exitAction(exitButton(actionId));
        addBody(dialog, form.content(), text);
        int buttonIndex = 0;
        for (SimpleElement element : form.elements()) {
            switch (element) {
                case Button button -> dialog.action(Dialogs.button(text.apply(button.text()),
                        Dialogs.dynamicCustom(actionId, NbtMap.builder().putInt(BUTTON_ID, buttonIndex++).build())));
                case Text textElement -> {
                    if (buttonIndex == 0) {
                        // Before the first button the text still belongs to the message.
                        dialog.body(Dialogs.plainMessage(textComponent(textElement, text), Dialogs.CONTENT_WIDTH));
                    } else if (textElement.style() != TextStyle.DIVIDER) {
                        // Between buttons: dialogs only have buttons there; this one just reopens the form.
                        Component label = textComponent(textElement, text).colorIfAbsent(NamedTextColor.GRAY);
                        dialog.action(Dialogs.button(label, null, Dialogs.BUTTON_WIDTH,
                                Dialogs.dynamicCustom(actionId, NbtMap.builder().putBoolean(REOPEN, true).build())));
                    }
                }
            }
        }
        return dialog.build();
    }

    private static NbtMap modal(BedrockForm.Modal form, Key actionId, Function<String, Component> text) {
        Dialogs.Builder dialog = Dialogs.multiAction(text.apply(form.title())).exitAction(exitButton(actionId));
        addBody(dialog, form.content(), text);
        dialog.action(Dialogs.button(text.apply(form.button1()),
                Dialogs.dynamicCustom(actionId, NbtMap.builder().putInt(BUTTON_ID, 0).build())));
        dialog.action(Dialogs.button(text.apply(form.button2()),
                Dialogs.dynamicCustom(actionId, NbtMap.builder().putInt(BUTTON_ID, 1).build())));
        return dialog.build();
    }

    private static NbtMap custom(BedrockForm.Custom form, Key actionId, Function<String, Component> text) {
        Dialogs.Builder dialog = Dialogs.multiAction(text.apply(form.title())).exitAction(exitButton(actionId));
        List<CustomElement> elements = form.elements();
        for (int i = 0; i < elements.size(); i++) {
            String key = String.valueOf(i);
            switch (elements.get(i)) {
                case Text textElement -> {
                    Component component = textComponent(textElement, text);
                    if (!dialog.hasInputs()) {
                        dialog.body(Dialogs.plainMessage(component, Dialogs.CONTENT_WIDTH));
                    } else {
                        dialog.input(Dialogs.labelInput(LABEL_KEY_PREFIX + i, component));
                    }
                }
                case Input input -> {
                    Component label = text.apply(input.text());
                    if (input.defaultText().isEmpty() && !input.placeholder().isEmpty()) {
                        // No placeholders on Java: show it next to the label.
                        label = label.append(Component.text(" (", NamedTextColor.GRAY))
                                .append(text.apply(input.placeholder()).colorIfAbsent(NamedTextColor.GRAY)
                                        .decorate(TextDecoration.ITALIC))
                                .append(Component.text(")", NamedTextColor.GRAY));
                    }
                    dialog.input(Dialogs.textInput(key, label, true, input.defaultText(), TEXT_MAX_LENGTH, Dialogs.CONTENT_WIDTH));
                }
                case Toggle toggle -> dialog.input(Dialogs.booleanInput(key, text.apply(toggle.text()), toggle.defaultValue()));
                case Slider slider -> dialog.input(Dialogs.numberRange(key, text.apply(slider.text()), slider.min(),
                        slider.max(), slider.step(), slider.defaultValue(), Dialogs.CONTENT_WIDTH));
                case StepSlider stepSlider -> addOptions(dialog, key, text.apply(stepSlider.text()), stepSlider.steps(),
                        stepSlider.defaultIndex(), text);
                case Dropdown dropdown -> addOptions(dialog, key, text.apply(dropdown.text()), dropdown.options(),
                        dropdown.defaultIndex(), text);
            }
        }
        Component submit = form.submit() != null ? text.apply(form.submit()) : Component.translatable("gui.done");
        dialog.action(Dialogs.button(submit, Dialogs.dynamicCustom(actionId, null)));
        return dialog.build();
    }

    private static void addOptions(Dialogs.Builder dialog, String key, Component label, List<String> values, int selected,
                                   Function<String, Component> text) {
        if (values.isEmpty()) {
            // The client rejects empty option lists; the response then falls back to the default index.
            dialog.input(Dialogs.labelInput(LABEL_KEY_PREFIX + key, label));
            return;
        }
        List<Dialogs.Option> options = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            options.add(new Dialogs.Option(String.valueOf(i), text.apply(values.get(i))));
        }
        dialog.input(Dialogs.singleOption(key, label, options, selected, Dialogs.CONTENT_WIDTH));
    }

    private static void addBody(Dialogs.Builder dialog, String content, Function<String, Component> text) {
        if (!content.isEmpty()) {
            dialog.body(Dialogs.plainMessage(text.apply(content), Dialogs.CONTENT_WIDTH));
        }
    }

    private static Component textComponent(Text element, Function<String, Component> text) {
        return switch (element.style()) {
            case HEADER -> text.apply(element.text()).decorate(TextDecoration.BOLD);
            case LABEL -> text.apply(element.text());
            case DIVIDER -> element.text().isEmpty() ? DIVIDER : text.apply(element.text());
        };
    }
}
