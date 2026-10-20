package io.github.wyze3306.jtb.mapping;

import net.kyori.adventure.key.Key;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The vanilla Java 26.3 synchronised registries (dimension types, biomes, damage types...) and tags,
 * as shipped by MCProtocolLib. Entry ids are their index in the registry, in the order they are sent
 * to the client.
 */
public final class JavaRegistries {

    /** A registry as sent to the client: name and ordered entries (data may be null). */
    public record Registry(Key key, List<RegistryEntry> entries, Map<Key, Integer> ids) {

        public int id(Key entry) {
            Integer id = this.ids.get(entry);
            return id == null ? -1 : id;
        }

        public int id(String entry) {
            return id(Key.key(entry));
        }
    }

    private static Map<Key, Registry> registries;
    private static Map<Key, Map<Key, int[]>> tags;

    private JavaRegistries() {
    }

    static synchronized void load() {
        if (registries != null) {
            return;
        }
        NbtMap codec = MinecraftProtocol.loadNetworkCodec();
        Map<Key, Registry> loaded = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : codec.entrySet()) {
            NbtMap registryTag = (NbtMap) entry.getValue();
            Key registryKey = Key.key(registryTag.getString("type"));
            List<NbtMap> values = registryTag.getList("value", NbtType.COMPOUND);
            RegistryEntry[] ordered = new RegistryEntry[values.size()];
            Map<Key, Integer> ids = new LinkedHashMap<>();
            for (NbtMap value : values) {
                Key name = Key.key(value.getString("name"));
                int id = value.getInt("id");
                ordered[id] = new RegistryEntry(name, value.get("element"));
                ids.put(name, id);
            }
            loaded.put(registryKey, new Registry(registryKey, List.of(ordered), Collections.unmodifiableMap(ids)));
        }
        registries = Collections.unmodifiableMap(loaded);

        NbtMap tagsTag = MinecraftProtocol.loadNetworkTags();
        Map<Key, Map<Key, int[]>> loadedTags = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : tagsTag.entrySet()) {
            NbtMap registry = (NbtMap) entry.getValue();
            Map<Key, int[]> registryTags = new LinkedHashMap<>();
            for (String tag : registry.keySet()) {
                registryTags.put(Key.key(tag), registry.getIntArray(tag));
            }
            loadedTags.put(Key.key(entry.getKey()), Collections.unmodifiableMap(registryTags));
        }
        tags = Collections.unmodifiableMap(loadedTags);
    }

    /** All synchronised registries in sending order. */
    public static Map<Key, Registry> registries() {
        return registries;
    }

    public static Registry registry(String key) {
        Registry registry = registries.get(Key.key(key));
        if (registry == null) {
            throw new IllegalArgumentException("Unknown synchronised registry " + key);
        }
        return registry;
    }

    /** Vanilla tags: registry -> tag -> entry ids. */
    public static Map<Key, Map<Key, int[]>> tags() {
        return tags;
    }

    /**
     * Copies a registry's entries, dropping their data when the client already knows the vanilla
     * pack (known packs negotiation), so only names are sent.
     */
    public static List<RegistryEntry> entriesForClient(Registry registry, boolean clientKnowsVanilla) {
        List<RegistryEntry> entries = new ArrayList<>(registry.entries().size());
        for (RegistryEntry entry : registry.entries()) {
            entries.add(clientKnowsVanilla ? new RegistryEntry(entry.getId(), null) : entry);
        }
        return entries;
    }
}
