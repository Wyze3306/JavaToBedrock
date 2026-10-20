package io.github.wyze3306.jtb.item;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import io.github.wyze3306.jtb.text.BedrockLang;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The server's item registry (vanilla and custom items with their runtime ids and components), as
 * sent in {@code ItemRegistryPacket}. Also installed in the codec so item stacks can be decoded.
 * <p>
 * Also keeps the Java form of the server's custom items ({@link #customItem}).
 */
public final class ItemRegistry implements SessionState {

    public static final StateKey<ItemRegistry> KEY = StateKey.of("item-registry", ItemRegistry::new);

    private final Int2ObjectMap<ItemDefinition> byRuntimeId = new Int2ObjectOpenHashMap<>();
    private final Map<String, ItemDefinition> byIdentifier = new HashMap<>();
    private final Map<ItemDefinition, CustomItems.CustomItem> customItems = new IdentityHashMap<>();
    private final ProxySession session;
    private DefinitionRegistry<ItemDefinition> codecRegistry = SimpleDefinitionRegistry.<ItemDefinition>builder()
            .add(ItemDefinition.AIR).build();

    private ItemRegistry(ProxySession session) {
        this.session = session;
        this.byRuntimeId.put(0, ItemDefinition.AIR);
        this.byIdentifier.put(ItemDefinition.AIR.getIdentifier(), ItemDefinition.AIR);
    }

    /** Registry with only air, installed before the server sends its own. */
    public static DefinitionRegistry<ItemDefinition> initialCodecRegistry() {
        return SimpleDefinitionRegistry.<ItemDefinition>builder().add(ItemDefinition.AIR).build();
    }

    /**
     * Builds the codec registry for the server's item definitions. Duplicate runtime ids keep the
     * first definition; air is added when missing. Pure function, safe from any thread.
     */
    public static DefinitionRegistry<ItemDefinition> buildCodecRegistry(List<ItemDefinition> definitions) {
        SimpleDefinitionRegistry.Builder<ItemDefinition> builder = SimpleDefinitionRegistry.builder();
        IntSet seen = new IntOpenHashSet();
        for (ItemDefinition definition : definitions) {
            if (seen.add(definition.getRuntimeId())) {
                builder.add(definition);
            }
        }
        if (!seen.contains(0)) {
            builder.add(ItemDefinition.AIR);
        }
        return builder.build();
    }

    /**
     * Replaces the registry with the server's (session loop). The codec registry itself is swapped
     * earlier, on the Netty thread, by the packet dispatcher: the packets that follow in the same
     * batch are decoded before this runs.
     */
    public void load(List<ItemDefinition> definitions, DefinitionRegistry<ItemDefinition> codecRegistry) {
        this.byRuntimeId.clear();
        this.byIdentifier.clear();
        this.customItems.clear();
        for (ItemDefinition definition : definitions) {
            if (this.byRuntimeId.putIfAbsent(definition.getRuntimeId(), definition) == null) {
                this.byIdentifier.putIfAbsent(definition.getIdentifier(), definition);
            }
        }
        this.byRuntimeId.putIfAbsent(0, ItemDefinition.AIR);
        this.byIdentifier.putIfAbsent(ItemDefinition.AIR.getIdentifier(), ItemDefinition.AIR);
        this.codecRegistry = codecRegistry;
    }

    public DefinitionRegistry<ItemDefinition> codecRegistry() {
        return this.codecRegistry;
    }

    public @Nullable ItemDefinition byRuntimeId(int runtimeId) {
        return this.byRuntimeId.get(runtimeId);
    }

    public @Nullable ItemDefinition byIdentifier(String identifier) {
        return this.byIdentifier.get(identifier);
    }

    public Map<String, ItemDefinition> definitions() {
        return this.byIdentifier;
    }

    /**
     * The Java form of a custom (non vanilla) item, built from its server definition on first use. The
     * name is resolved in the player's language at that moment.
     */
    public CustomItems.CustomItem customItem(ItemDefinition definition) {
        CustomItems.CustomItem item = this.customItems.get(definition);
        if (item == null) {
            item = CustomItems.build(definition, BedrockLang.forJavaLocale(this.session.getLocale()));
            this.customItems.put(definition, item);
        }
        return item;
    }
}
