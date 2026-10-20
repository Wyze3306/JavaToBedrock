package io.github.wyze3306.jtb.form;

import org.cloudburstmc.protocol.bedrock.data.ddui.DataStoreChange;
import org.cloudburstmc.protocol.bedrock.data.ddui.DataStorePropertyType;
import org.cloudburstmc.protocol.bedrock.data.ddui.DataStoreRemoval;
import org.cloudburstmc.protocol.bedrock.data.ddui.DataStoreUpdate;
import org.cloudburstmc.protocol.bedrock.data.ddui.DynamicValue;
import org.cloudburstmc.protocol.bedrock.data.ddui.DynamicValueType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The data store of data-driven screens, with the shape PowerNukkitX's {@code ddui.CustomForm} sends. */
class DataStoreTest {

    @Test
    void appliesChangesUpdatesAndRemovals() {
        DataStore store = new DataStore();
        store.apply(change("custom_form_data_3", screen()));
        assertEquals("custom_form_data_3", store.findProperty("minecraft", 3));
        assertNull(store.findProperty("other", 3));

        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) store.get("minecraft", "custom_form_data_3");
        List<DduiDialog.Element> elements = DduiDialog.elements(root);
        assertEquals(3, elements.size());
        assertEquals(DduiDialog.Kind.TOGGLE, elements.get(0).kind());
        assertEquals(DduiDialog.Kind.BUTTON, elements.get(1).kind());
        assertEquals(DduiDialog.Kind.LABEL, elements.get(2).kind());

        DataStoreUpdate update = new DataStoreUpdate();
        update.setDataStoreName("minecraft");
        update.setProperty("custom_form_data_3");
        update.setPath("layout[0].toggled");
        update.setType(DataStorePropertyType.BOOLEAN);
        update.setData(true);
        assertTrue(store.apply(update));
        assertEquals(true, elements.get(0).data().get("toggled"));

        assertTrue(store.set("minecraft", "custom_form_data_3", "layout.2.text", "nouveau"));
        assertEquals("nouveau", elements.get(2).data().get("text"));

        DataStoreRemoval removal = new DataStoreRemoval();
        removal.setDataStoreName("minecraft");
        assertTrue(store.apply(removal));
        assertNull(store.get("minecraft", "custom_form_data_3"));
    }

    @Test
    void splitsPaths() {
        assertEquals(List.of("layout", "2", "onClick"), DataStore.tokens("layout[2].onClick"));
        assertEquals(List.of("layout", "2", "onClick"), DataStore.tokens("layout.2.onClick"));
        assertEquals(List.of("title"), DataStore.tokens("title"));
    }

    private static DataStoreChange change(String property, DynamicValue value) {
        DataStoreChange change = new DataStoreChange();
        change.setDataStoreName("minecraft");
        change.setProperty(property);
        change.setUpdateCount(1);
        change.setTheNewPropertyValue(value);
        return change;
    }

    /** {title, layout: {0: toggle, 1: button, 2: label, length: 3}} with an unordered (HashMap) layout. */
    private static DynamicValue screen() {
        Map<String, DynamicValue> toggle = new LinkedHashMap<>();
        toggle.put("label", string("Option"));
        toggle.put("toggled", new DynamicValue(DynamicValueType.BOOLEAN, false));
        toggle.put("visible", new DynamicValue(DynamicValueType.BOOLEAN, true));
        toggle.put("toggle_visible", new DynamicValue(DynamicValueType.BOOLEAN, true));
        Map<String, DynamicValue> button = new LinkedHashMap<>();
        button.put("label", string("Valider"));
        button.put("onClick", new DynamicValue(DynamicValueType.INTEGER, 0L));
        button.put("button_visible", new DynamicValue(DynamicValueType.BOOLEAN, true));
        Map<String, DynamicValue> label = new LinkedHashMap<>();
        label.put("text", string("Texte"));
        label.put("label_visible", new DynamicValue(DynamicValueType.BOOLEAN, true));
        Map<String, DynamicValue> layout = new HashMap<>();
        layout.put("2", new DynamicValue(DynamicValueType.OBJECT, label));
        layout.put("length", new DynamicValue(DynamicValueType.INTEGER, 3L));
        layout.put("0", new DynamicValue(DynamicValueType.OBJECT, toggle));
        layout.put("1", new DynamicValue(DynamicValueType.OBJECT, button));
        Map<String, DynamicValue> root = new LinkedHashMap<>();
        root.put("title", string("DDUI"));
        root.put("layout", new DynamicValue(DynamicValueType.OBJECT, layout));
        return new DynamicValue(DynamicValueType.OBJECT, root);
    }

    private static DynamicValue string(String value) {
        return new DynamicValue(DynamicValueType.STRING, value);
    }
}
