package io.github.wyze3306.jtb.form;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.ddui.DataDrivenScreenClosedReason;
import org.cloudburstmc.protocol.bedrock.data.ddui.DataStorePropertyType;
import org.cloudburstmc.protocol.bedrock.data.ddui.DataStoreUpdate;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundDataStorePacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A data-driven screen ({@code ClientboundDataDrivenUIShowScreen}, PowerNukkitX {@code ddui}) as a
 * dialog that stays open ({@code after_action: none}), like the Bedrock screen.
 * <p>
 * The screen content is a data store property ({@code custom_form_data_<id>} in store
 * {@code minecraft}): {@code {title, layout: {"0": element, ..., length}, closeButton?}}. Element
 * types are recognised by their visibility flag ({@code button_visible}, {@code toggle_visible},
 * {@code textfield_visible}, {@code slider_visible}, {@code dropdown_visible}, {@code label_visible},
 * {@code header_visible}, {@code spacer_visible}). Interactions are reported the way the Bedrock
 * client does, as {@code ServerboundDataStore} updates of the element's value
 * ({@code layout[i].toggled|text|value}) and of {@code layout[i].onClick} for buttons; closing sends
 * {@code ServerboundDataDrivenScreenClosed(CLIENT_CANCELED)} (and the close button's {@code onClick}).
 * Since dialogs only report inputs on a button click, changed values are sent just before the click.
 */
final class DduiDialog extends ShownDialog {

    /** Payload key of the clicked button (element index). */
    static final String CLICKED = "b";
    private static final String INPUT_PREFIX = "d";
    private static final int TEXT_MAX_LENGTH = 512;

    private final FormState state;
    private final String screenId;
    final int formId;
    private final String store;
    private final String property;
    private int updateCount;

    DduiDialog(FormState state, String screenId, int formId, String store, String property) {
        this.state = state;
        this.screenId = screenId;
        this.formId = formId;
        this.store = store;
        this.property = property;
    }

    /** Data store of a screen: the namespace of its id ({@code minecraft:custom_form} -> {@code minecraft}). */
    static String storeName(String screenId) {
        int colon = screenId.indexOf(':');
        return colon < 0 ? screenId : screenId.substring(0, colon);
    }

    enum Kind { BUTTON, TOGGLE, TEXT_FIELD, SLIDER, DROPDOWN, LABEL, HEADER, SPACER, UNKNOWN }

    record Element(int index, Kind kind, Map<String, Object> data) {
    }

    private @Nullable Map<String, Object> root() {
        return this.state.dataStore().get(this.store, this.property) instanceof Map<?, ?> map ? cast(map) : null;
    }

    /** Layout elements in order. */
    static List<Element> elements(Map<String, Object> root) {
        List<Element> elements = new ArrayList<>();
        if (!(root.get("layout") instanceof Map<?, ?> layout)) {
            return elements;
        }
        int length = layout.get("length") instanceof Number number ? number.intValue() : layout.size();
        for (int i = 0; i < length; i++) {
            if (layout.get(String.valueOf(i)) instanceof Map<?, ?> element) {
                Map<String, Object> data = cast(element);
                elements.add(new Element(i, kind(data), data));
            }
        }
        return elements;
    }

    static Kind kind(Map<String, Object> data) {
        if (data.containsKey("button_visible") || data.containsKey("onClick")) {
            return Kind.BUTTON;
        }
        if (data.containsKey("toggle_visible") || data.containsKey("toggled")) {
            return Kind.TOGGLE;
        }
        if (data.containsKey("textfield_visible")) {
            return Kind.TEXT_FIELD;
        }
        if (data.containsKey("slider_visible") || data.containsKey("minValue")) {
            return Kind.SLIDER;
        }
        if (data.containsKey("dropdown_visible") || data.containsKey("items")) {
            return Kind.DROPDOWN;
        }
        if (data.containsKey("header_visible")) {
            return Kind.HEADER;
        }
        if (data.containsKey("label_visible")) {
            return Kind.LABEL;
        }
        if (data.containsKey("spacer_visible")) {
            return Kind.SPACER;
        }
        return data.containsKey("text") ? Kind.LABEL : Kind.UNKNOWN;
    }

    private static boolean visible(Map<String, Object> data) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if ((entry.getKey().equals("visible") || entry.getKey().endsWith("_visible")) && Boolean.FALSE.equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    @Override
    NbtMap build(Key actionId) {
        Map<String, Object> root = root();
        if (root == null) {
            throw new IllegalStateException("No data for screen " + this.screenId);
        }
        Dialogs.Builder dialog = Dialogs.multiAction(this.state.text(string(root.get("title"), "")))
                .afterAction(Dialogs.AFTER_NONE)
                .exitAction(exitButton(root, actionId));
        int buttons = 0;
        for (Element element : elements(root)) {
            Map<String, Object> data = element.data();
            if (!visible(data)) {
                continue;
            }
            String key = INPUT_PREFIX + element.index();
            Component label = this.state.text(string(data.get("label"), ""));
            boolean disabled = Boolean.TRUE.equals(data.get("disabled"));
            switch (element.kind()) {
                case BUTTON -> {
                    buttons++;
                    Component tooltip = data.get("tooltip") instanceof String tip && !tip.isEmpty() ? this.state.text(tip) : null;
                    NbtMap additions = disabled
                            ? NbtMap.builder().putBoolean(FormDialogs.REOPEN, true).build()
                            : NbtMap.builder().putInt(CLICKED, element.index()).build();
                    dialog.action(Dialogs.button(disabled ? label.colorIfAbsent(NamedTextColor.DARK_GRAY) : label, tooltip,
                            Dialogs.BUTTON_WIDTH, Dialogs.dynamicCustom(actionId, additions)));
                }
                case TOGGLE -> {
                    boolean value = Boolean.TRUE.equals(data.get("toggled"));
                    if (disabled) {
                        addText(dialog, key, label.append(Component.text(": " + (value ? "✔" : "✘"), NamedTextColor.GRAY)));
                    } else {
                        dialog.input(Dialogs.booleanInput(key, label, value));
                    }
                }
                case TEXT_FIELD -> {
                    String value = string(data.get("text"), "");
                    if (disabled) {
                        addText(dialog, key, label.append(Component.text(": " + value, NamedTextColor.GRAY)));
                    } else {
                        dialog.input(Dialogs.textInput(key, label, true, value, TEXT_MAX_LENGTH, Dialogs.CONTENT_WIDTH));
                    }
                }
                case SLIDER -> {
                    float min = number(data.get("minValue"), 0);
                    float max = number(data.get("maxValue"), min);
                    float value = number(data.get("value"), min);
                    if (disabled) {
                        addText(dialog, key, label.append(Component.text(": " + format(value), NamedTextColor.GRAY)));
                    } else {
                        dialog.input(Dialogs.numberRange(key, label, min, max, number(data.get("step"), 1), value, Dialogs.CONTENT_WIDTH));
                    }
                }
                case DROPDOWN -> addDropdown(dialog, key, label, data, disabled);
                case LABEL -> addText(dialog, key, this.state.text(string(data.get("text"), "")));
                case HEADER -> addText(dialog, key, this.state.text(string(data.get("text"), "")).decorate(TextDecoration.BOLD));
                case SPACER, UNKNOWN -> {
                }
            }
        }
        if (buttons == 0) {
            // multi_action needs a button: the close button.
            dialog.action(exitButton(root, actionId));
        }
        return dialog.build();
    }

    private NbtMap exitButton(Map<String, Object> root, Key actionId) {
        Component label = FormDialogs.closeLabel();
        if (root.get("closeButton") instanceof Map<?, ?> close && close.get("label") instanceof String text && !text.isEmpty()) {
            label = this.state.text(text);
        }
        return Dialogs.button(label, Dialogs.dynamicCustom(actionId, NbtMap.builder().putBoolean(FormDialogs.EXIT, true).build()));
    }

    private void addDropdown(Dialogs.Builder dialog, String key, Component label, Map<String, Object> data, boolean disabled) {
        List<Dialogs.Option> options = new ArrayList<>();
        int selected = (int) number(data.get("value"), 0);
        int selectedIndex = 0;
        if (data.get("items") instanceof Map<?, ?> items) {
            int length = items.get("length") instanceof Number number ? number.intValue() : items.size();
            for (int i = 0; i < length; i++) {
                if (items.get(String.valueOf(i)) instanceof Map<?, ?> item) {
                    int value = (int) number(item.get("value"), i);
                    if (value == selected) {
                        selectedIndex = options.size();
                    }
                    options.add(new Dialogs.Option(String.valueOf(value), this.state.text(string(item.get("label"), ""))));
                }
            }
        }
        if (options.isEmpty() || disabled) {
            Component shown = options.isEmpty() ? label
                    : label.append(Component.text(": ", NamedTextColor.GRAY)).append(options.get(selectedIndex).display());
            addText(dialog, key, shown);
            return;
        }
        dialog.input(Dialogs.singleOption(key, label, options, selectedIndex, Dialogs.CONTENT_WIDTH));
    }

    private static void addText(Dialogs.Builder dialog, String key, Component text) {
        if (dialog.hasInputs()) {
            dialog.input(Dialogs.labelInput(FormDialogs.LABEL_KEY_PREFIX + key, text));
        } else {
            dialog.body(Dialogs.plainMessage(text, Dialogs.CONTENT_WIDTH));
        }
    }

    @Override
    Outcome click(@Nullable NbtMap payload) {
        NbtMap data = payload == null ? NbtMap.EMPTY : payload;
        Map<String, Object> root = root();
        if (data.getBoolean(FormDialogs.EXIT, false) || root == null) {
            if (root != null && root.get("closeButton") instanceof Map<?, ?> close) {
                sendUpdate("closeButton.onClick", DataStorePropertyType.DOUBLE, number(close.get("onClick"), 0) + 1d);
            }
            this.state.closedScreen(this.formId, DataDrivenScreenClosedReason.CLIENT_CANCELED);
            return Outcome.DONE;
        }
        if (data.getBoolean(FormDialogs.REOPEN, false)) {
            return Outcome.REOPEN;
        }
        for (Element element : elements(root)) {
            Object value = data.get(INPUT_PREFIX + element.index());
            if (value == null || !visible(element.data()) || Boolean.TRUE.equals(element.data().get("disabled"))) {
                continue;
            }
            String path = "layout[" + element.index() + "].";
            switch (element.kind()) {
                case TOGGLE -> {
                    boolean toggled = value instanceof Number number ? number.intValue() != 0 : Boolean.TRUE.equals(value);
                    if (!Objects.equals(element.data().get("toggled"), toggled)) {
                        element.data().put("toggled", toggled);
                        sendUpdate(path + "toggled", DataStorePropertyType.BOOLEAN, toggled);
                    }
                }
                case TEXT_FIELD -> {
                    String text = value.toString();
                    if (!text.equals(element.data().get("text"))) {
                        element.data().put("text", text);
                        sendUpdate(path + "text", DataStorePropertyType.STRING, text);
                    }
                }
                case SLIDER -> {
                    double number = value instanceof Number n ? Math.round(n.doubleValue()) : 0;
                    if (number(element.data().get("value"), Float.NaN) != (float) number) {
                        element.data().put("value", (long) number);
                        sendUpdate(path + "value", DataStorePropertyType.DOUBLE, number);
                    }
                }
                case DROPDOWN -> {
                    double number;
                    try {
                        number = Integer.parseInt(value.toString());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (number(element.data().get("value"), Float.NaN) != (float) number) {
                        element.data().put("value", (long) number);
                        sendUpdate(path + "value", DataStorePropertyType.DOUBLE, number);
                    }
                }
                default -> {
                }
            }
        }
        if (data.get(CLICKED) instanceof Number clicked) {
            for (Element element : elements(root)) {
                if (element.index() == clicked.intValue() && element.kind() == Kind.BUTTON) {
                    double count = number(element.data().get("onClick"), 0) + 1d;
                    element.data().put("onClick", (long) count);
                    sendUpdate("layout[" + element.index() + "].onClick", DataStorePropertyType.DOUBLE, count);
                }
            }
        }
        return Outcome.KEEP;
    }

    @Override
    boolean closesItself() {
        return false;
    }

    private void sendUpdate(String path, DataStorePropertyType type, Object value) {
        DataStoreUpdate update = new DataStoreUpdate();
        update.setDataStoreName(this.store);
        update.setProperty(this.property);
        update.setPath(path);
        update.setType(type);
        update.setData(value);
        update.setPropertyUpdateCount(++this.updateCount);
        update.setPathUpdateCount(1);
        ServerboundDataStorePacket packet = new ServerboundDataStorePacket();
        packet.setUpdate(update);
        this.state.session().sendBedrock(packet);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static String string(@Nullable Object value, String fallback) {
        return value instanceof String string ? string : fallback;
    }

    private static float number(@Nullable Object value, float fallback) {
        return value instanceof Number number ? number.floatValue() : fallback;
    }

    private static String format(float value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Float.toString(value);
    }

    @Override
    public String toString() {
        return "data-driven screen " + this.screenId + "#" + this.formId;
    }
}
