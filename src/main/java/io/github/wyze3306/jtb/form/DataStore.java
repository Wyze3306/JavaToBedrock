package io.github.wyze3306.jtb.form;

import org.cloudburstmc.protocol.bedrock.data.ddui.DataStoreChange;
import org.cloudburstmc.protocol.bedrock.data.ddui.DataStoreChangeInfo;
import org.cloudburstmc.protocol.bedrock.data.ddui.DataStoreRemoval;
import org.cloudburstmc.protocol.bedrock.data.ddui.DataStoreUpdate;
import org.cloudburstmc.protocol.bedrock.data.ddui.DynamicValue;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The client-side copy of the server's data stores ({@code ClientboundDataStorePacket}), which hold
 * the content of data-driven screens. Values are plain Java objects: {@code Map<String, Object>}
 * for objects, {@code List<Object>} for arrays, {@code Boolean}, {@code Long} (integers),
 * {@code Double} (numbers), {@code String} or null.
 * <p>
 * Paths address nested values like the server writes them: {@code layout[2].toggled},
 * {@code layout.2.toggled} or {@code title}.
 */
final class DataStore {

    private final Map<String, Map<String, Object>> stores = new HashMap<>();

    /**
     * Applies one change.
     *
     * @return true when something changed
     */
    boolean apply(DataStoreChangeInfo info) {
        switch (info) {
            case DataStoreChange change -> {
                Object value = convert(change.getTheNewPropertyValue());
                store(change.getDataStoreName()).put(change.getProperty(), value);
                return true;
            }
            case DataStoreUpdate update -> {
                return set(update.getDataStoreName(), update.getProperty(), update.getPath(), update.getData());
            }
            case DataStoreRemoval removal -> {
                return this.stores.remove(removal.getDataStoreName()) != null;
            }
            default -> {
                return false;
            }
        }
    }

    @Nullable Object get(String store, String property) {
        Map<String, Object> values = this.stores.get(store);
        return values == null ? null : values.get(property);
    }

    /**
     * The property of {@code store} holding a screen instance: PowerNukkitX names it
     * {@code <screen property>_<instance id>}. When none has that suffix and the store has a single
     * object property, that one.
     */
    @Nullable String findProperty(String store, int instanceId) {
        Map<String, Object> values = this.stores.get(store);
        if (values == null || values.isEmpty()) {
            return null;
        }
        String suffix = "_" + instanceId;
        String single = null;
        int objects = 0;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }
            if (entry.getKey().endsWith(suffix)) {
                return entry.getKey();
            }
            single = entry.getKey();
            objects++;
        }
        return objects == 1 ? single : null;
    }

    /**
     * Sets the value at {@code path} inside a property, creating intermediate objects.
     *
     * @return true when the value was stored
     */
    boolean set(String store, String property, @Nullable String path, @Nullable Object value) {
        Map<String, Object> values = store(store);
        List<String> tokens = path == null ? List.of() : tokens(path);
        if (tokens.isEmpty()) {
            values.put(property, value);
            return true;
        }
        Object node = values.get(property);
        if (!(node instanceof Map) && !(node instanceof List)) {
            node = new LinkedHashMap<String, Object>();
            values.put(property, node);
        }
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            boolean last = i == tokens.size() - 1;
            if (node instanceof Map<?, ?> rawMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) rawMap;
                if (last) {
                    map.put(token, value);
                    return true;
                }
                Object child = map.get(token);
                if (!(child instanceof Map) && !(child instanceof List)) {
                    child = new LinkedHashMap<String, Object>();
                    map.put(token, child);
                }
                node = child;
            } else if (node instanceof List<?> rawList) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) rawList;
                int index;
                try {
                    index = Integer.parseInt(token);
                } catch (NumberFormatException e) {
                    return false;
                }
                if (index < 0 || index >= list.size()) {
                    return false;
                }
                if (last) {
                    list.set(index, value);
                    return true;
                }
                node = list.get(index);
            } else {
                return false;
            }
        }
        return false;
    }

    /** Splits {@code a.b[2].c} / {@code a.b.2.c} into tokens. */
    static List<String> tokens(String path) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.' || c == '[' || c == ']') {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private Map<String, Object> store(String name) {
        return this.stores.computeIfAbsent(name, k -> new HashMap<>());
    }

    @SuppressWarnings("unchecked")
    static @Nullable Object convert(@Nullable DynamicValue value) {
        if (value == null || value.getType() == null) {
            return null;
        }
        return switch (value.getType()) {
            case NULL -> null;
            case BOOLEAN, INTEGER, NUMBER, STRING -> value.getValue();
            case ARRAY -> {
                List<Object> list = new ArrayList<>();
                for (DynamicValue element : (List<DynamicValue>) value.getValue()) {
                    list.add(convert(element));
                }
                yield list;
            }
            case OBJECT -> {
                Map<String, Object> map = new LinkedHashMap<>();
                for (Map.Entry<String, DynamicValue> entry : ((Map<String, DynamicValue>) value.getValue()).entrySet()) {
                    map.put(entry.getKey(), convert(entry.getValue()));
                }
                yield map;
            }
        };
    }
}
