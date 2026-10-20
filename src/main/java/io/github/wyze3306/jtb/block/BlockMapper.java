package io.github.wyze3306.jtb.block;

import io.github.wyze3306.jtb.resourcepack.CustomBlockCarrier;
import io.github.wyze3306.jtb.resourcepack.ResourcePackState;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.ServerBlockProperty;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CONTRACT (owner: block/mapping subsystem). Bedrock block runtime ids of this session's server
 * (hashed or ordered, vanilla and custom blocks) to Java 26.3 global block state ids, and back.
 * <p>
 * Runtime ids: PowerNukkitX (and BDS by default) send hashed ids, see {@link BlockStateHasher}; other
 * servers send the index in the palette ordered by name hash, custom blocks included, which
 * {@link #onStartGame} rebuilds. Custom blocks come from {@code StartGamePacket.blockProperties} and
 * are shown with the Java states {@link CustomBlockAllocator} assigns ({@link #customBlocks()}).
 * <p>
 * <b>Threading:</b> configured once by {@link #onStartGame} on the session loop and read only
 * afterwards, so every query is safe from any thread (chunk conversion runs on worker threads),
 * O(1) and allocation free.
 */
public final class BlockMapper implements SessionState {

    public static final StateKey<BlockMapper> KEY = StateKey.of("block-mapper", BlockMapper::new);

    /** Trait -> enabled state -> properties (name -> values) it adds, as the Bedrock client expands them. */
    private static final Map<String, Map<String, Map<String, List<String>>>> TRAITS = Map.of(
            "minecraft:placement_direction", Map.of(
                    "minecraft:cardinal_direction", Map.of("minecraft:cardinal_direction", List.of("north", "south", "east", "west")),
                    "minecraft:facing_direction", Map.of("minecraft:facing_direction", List.of("down", "up", "north", "south", "east", "west")),
                    "minecraft:corner_and_cardinal_direction", linked(
                            "minecraft:cardinal_direction", List.of("north", "south", "east", "west"),
                            "minecraft:corner", List.of("none", "inner_left", "inner_right", "outer_left", "outer_right"))),
            "minecraft:placement_position", Map.of(
                    "minecraft:block_face", Map.of("minecraft:block_face", List.of("down", "up", "north", "south", "east", "west")),
                    "minecraft:vertical_half", Map.of("minecraft:vertical_half", List.of("bottom", "top"))),
            "minecraft:connection", Map.of(
                    "minecraft:cardinal_connections", linked(
                            "minecraft:connection_north", List.of("false", "true"),
                            "minecraft:connection_east", List.of("false", "true"),
                            "minecraft:connection_south", List.of("false", "true"),
                            "minecraft:connection_west", List.of("false", "true"))));

    private static final int AIR_HASH = BlockStateHasher.hash("minecraft:air", NbtMap.EMPTY);

    private final ProxySession session;
    private final Set<Integer> reportedUnknown = ConcurrentHashMap.newKeySet();
    private final Int2IntOpenHashMap customByRuntime = new Int2IntOpenHashMap();
    private final Int2IntOpenHashMap javaToCustom = new Int2IntOpenHashMap();
    private boolean hashed = true;
    /** Ordered ids: runtime id -> vanilla palette index, or -2 - custom index. */
    private int[] ordered = new int[0];
    /** Ordered ids: vanilla palette index -> runtime id. */
    private int[] vanillaRuntime = new int[0];
    private int[] customRuntime = new int[0];
    private int[] customJava = new int[0];
    private NbtMap[] customStates = new NbtMap[0];
    private String[] customNames = new String[0];
    private List<CustomBlock> customBlocks = List.of();
    private byte[] lightTable = JavaBlockStates.lightTable();
    private int airRuntimeId = AIR_HASH;
    private final int placeholder;

    private BlockMapper(ProxySession session) {
        this.session = session;
        this.customByRuntime.defaultReturnValue(-1);
        this.javaToCustom.defaultReturnValue(-1);
        // Unknown ids look like Bedrock's own "update!" block does through the vanilla mapping.
        int infoUpdate = BedrockBlockPalette.indexOfHash(BlockStateHasher.hash("minecraft:info_update", NbtMap.EMPTY));
        this.placeholder = infoUpdate >= 0 ? BedrockBlockPalette.javaState(infoUpdate) : JavaBlockStates.parse("minecraft:stone");
    }

    /** Reads {@code blockNetworkIdsAreHashes} and the custom block definitions ({@code blockProperties}). */
    public void onStartGame(StartGamePacket packet) {
        this.hashed = packet.isBlockNetworkIdsAreHashes();
        List<CustomDefinition> definitions = readDefinitions(packet.getBlockProperties());
        CustomBlockAllocator allocator = new CustomBlockAllocator(this.session.config().features().customBlocks());

        List<CustomBlock> blocks = new ArrayList<>();
        List<NbtMap> tags = new ArrayList<>();
        List<String> names = new ArrayList<>();
        IntOpenHashSet usedHashes = new IntOpenHashSet();
        int total = 0;
        for (CustomDefinition definition : definitions) {
            total += definition.permutations().size();
        }
        this.customRuntime = new int[total];
        this.customJava = new int[total];
        int index = 0;
        for (CustomDefinition definition : definitions) {
            List<CustomBlock.State> states = new ArrayList<>(definition.permutations().size());
            for (NbtMap permutation : definition.permutations()) {
                int hash = BlockStateHasher.hash(definition.identifier(), permutation);
                // The Bedrock client increments a colliding hash (PowerNukkitX itself refuses collisions).
                while (BedrockBlockPalette.indexOfHash(hash) >= 0 || !usedHashes.add(hash)) {
                    hash++;
                }
                int java = allocator.allocate(definition.identifier(), definition.nbt(), permutation);
                this.customRuntime[index] = hash;
                this.customJava[index] = java;
                tags.add(NbtMap.builder().putString("name", definition.identifier()).putCompound("states", permutation).build());
                names.add(definition.identifier());
                states.add(new CustomBlock.State(hash, permutation, java, states.size()));
                index++;
            }
            blocks.add(new CustomBlock(definition.identifier(), definition.nbt(), Collections.unmodifiableList(states)));
        }
        this.customStates = tags.toArray(new NbtMap[0]);
        this.customNames = names.toArray(new String[0]);
        if (!this.hashed) {
            buildOrderedIds(definitions, total);
            // Ordered ids replaced the hashes: fix the published states.
            int i = 0;
            for (int b = 0; b < blocks.size(); b++) {
                CustomBlock block = blocks.get(b);
                List<CustomBlock.State> states = new ArrayList<>(block.states().size());
                for (CustomBlock.State state : block.states()) {
                    states.add(new CustomBlock.State(this.customRuntime[i++], state.states(), state.javaState(), state.permutation()));
                }
                blocks.set(b, new CustomBlock(block.identifier(), block.definition(), Collections.unmodifiableList(states)));
            }
        }
        this.customBlocks = Collections.unmodifiableList(blocks);
        this.customByRuntime.clear();
        this.javaToCustom.clear();
        for (int i = 0; i < total; i++) {
            this.customByRuntime.put(this.customRuntime[i], i);
            this.javaToCustom.putIfAbsent(this.customJava[i], i);
        }
        this.airRuntimeId = this.hashed ? AIR_HASH : this.vanillaRuntime[BedrockBlockPalette.indexOfHash(AIR_HASH)];
        applyCustomLight(blocks);
        publishCarriers(blocks);
        if (total > 0) {
            this.session.logger().info("[{}] {} custom blocks ({} states, {} carrier states available), {} runtime ids",
                    this.session.getName(), blocks.size(), total, CustomBlockAllocator.capacity(CustomBlockAllocator.Pool.OPAQUE),
                    this.hashed ? "hashed" : "ordered");
        }
    }

    /**
     * Tells the resource pack converter which Java state shows each custom block state, so the pack
     * retextures those carrier states (states left on the fallback block are not rewritten).
     */
    private void publishCarriers(List<CustomBlock> blocks) {
        int fallback = JavaBlockStates.defaultState(CustomBlockAllocator.FALLBACK);
        List<CustomBlockCarrier> carriers = new ArrayList<>();
        for (CustomBlock block : blocks) {
            for (CustomBlock.State state : block.states()) {
                if (JavaBlockStates.blockId(state.javaState()) == JavaBlockStates.blockId(fallback)) {
                    continue;
                }
                carriers.add(new CustomBlockCarrier(block.identifier(), state.states(), block.definition(),
                        "minecraft:" + JavaBlockStates.blockName(JavaBlockStates.blockId(state.javaState())),
                        JavaBlockStates.properties(state.javaState())));
            }
        }
        this.session.get(ResourcePackState.KEY).setCustomBlockCarriers(carriers);
    }

    private record CustomDefinition(String identifier, NbtMap nbt, List<NbtMap> permutations) {
    }

    /** Custom definitions sorted by identifier, with their state permutations; vanilla names are ignored. */
    private List<CustomDefinition> readDefinitions(List<ServerBlockProperty> properties) {
        Map<String, NbtMap> unique = new LinkedHashMap<>();
        for (ServerBlockProperty property : properties) {
            String identifier = property.getName().toLowerCase(Locale.ROOT);
            NbtMap nbt = property.getProperties();
            // The Bedrock client lets no definition override a vanilla block (PowerNukkitX also lists its
            // data-driven vanilla blocks here) and ignores blocks without vanilla_block_data.
            if (BedrockBlockPalette.isVanillaName(identifier) || nbt == null || !(nbt.get("vanilla_block_data") instanceof NbtMap)) {
                continue;
            }
            unique.putIfAbsent(identifier, nbt);
        }
        List<CustomDefinition> definitions = new ArrayList<>();
        for (Map.Entry<String, NbtMap> entry : unique.entrySet()) {
            definitions.add(new CustomDefinition(entry.getKey(), entry.getValue(), permutations(entry.getKey(), entry.getValue())));
        }
        definitions.sort(Comparator.comparing(CustomDefinition::identifier));
        return definitions;
    }

    /** Cartesian product of the properties and the trait states, first property varying slowest. */
    private List<NbtMap> permutations(String identifier, NbtMap nbt) {
        Map<String, List<?>> properties = new LinkedHashMap<>();
        for (NbtMap property : nbt.getList("properties", NbtType.COMPOUND, List.of())) {
            String name = property.getString("name", null);
            if (name != null && property.get("enum") instanceof List<?> values && !values.isEmpty()) {
                properties.put(name, values);
            }
        }
        for (NbtMap trait : nbt.getList("traits", NbtType.COMPOUND, List.of())) {
            String name = namespaced(trait.getString("name", ""));
            Map<String, Map<String, List<String>>> known = TRAITS.get(name);
            if (known == null) {
                this.session.logger().warn("[{}] Unknown block trait {} on {}", this.session.getName(), name, identifier);
                continue;
            }
            NbtMap enabled = trait.getCompound("enabled_states", NbtMap.EMPTY);
            for (String state : enabled.keySet()) {
                Map<String, List<String>> added = known.get(namespaced(state));
                if (added != null && enabled.getBoolean(state)) {
                    properties.putAll(added);
                }
            }
        }
        List<NbtMap> out = new ArrayList<>();
        combine(new ArrayList<>(properties.entrySet()), 0, new LinkedHashMap<>(), out);
        return out;
    }

    private static String namespaced(String name) {
        return name.indexOf(':') >= 0 ? name : "minecraft:" + name;
    }

    private static void combine(List<Map.Entry<String, List<?>>> properties, int index, Map<String, Object> current,
                                List<NbtMap> out) {
        if (index == properties.size()) {
            out.add(NbtMap.fromMap(new LinkedHashMap<>(current)));
            return;
        }
        Map.Entry<String, List<?>> property = properties.get(index);
        for (Object value : property.getValue()) {
            current.put(property.getKey(), value);
            combine(properties, index + 1, current, out);
        }
        current.remove(property.getKey());
    }

    /** Ordered runtime ids: vanilla and custom states merged, sorted stably by the FNV-1 64 of the name. */
    private void buildOrderedIds(List<CustomDefinition> definitions, int customCount) {
        record Group(long order, int vanillaStart, int vanillaEnd, int customStart, int customEnd) {
        }
        List<Group> groups = new ArrayList<>();
        int size = BedrockBlockPalette.size();
        int start = 0;
        for (int i = 1; i <= size; i++) {
            if (i == size || !BedrockBlockPalette.name(i).equals(BedrockBlockPalette.name(start))) {
                groups.add(new Group(BlockStateHasher.nameOrder(BedrockBlockPalette.name(start)), start, i, 0, 0));
                start = i;
            }
        }
        int custom = 0;
        for (CustomDefinition definition : definitions) {
            int n = definition.permutations().size();
            groups.add(new Group(BlockStateHasher.nameOrder(definition.identifier()), 0, 0, custom, custom + n));
            custom += n;
        }
        groups.sort((a, b) -> Long.compareUnsigned(a.order(), b.order()));
        this.ordered = new int[size + customCount];
        this.vanillaRuntime = new int[size];
        int runtime = 0;
        for (Group group : groups) {
            for (int e = group.vanillaStart(); e < group.vanillaEnd(); e++) {
                this.vanillaRuntime[e] = runtime;
                this.ordered[runtime++] = e;
            }
            for (int c = group.customStart(); c < group.customEnd(); c++) {
                this.customRuntime[c] = runtime;
                this.ordered[runtime++] = -2 - c;
            }
        }
    }

    /** Carriers of custom blocks take the custom block's light emission and dampening. */
    private void applyCustomLight(List<CustomBlock> blocks) {
        byte[] vanilla = JavaBlockStates.lightTable();
        byte[] table = null;
        for (CustomBlock block : blocks) {
            NbtMap components = block.definition().getCompound("components", NbtMap.EMPTY);
            int emission = number(components.getCompound("minecraft:light_emission", NbtMap.EMPTY).get("emission"), 0);
            int dampening = number(components.getCompound("minecraft:light_dampening", NbtMap.EMPTY).get("lightLevel"), 15);
            byte value = (byte) ((Math.clamp(emission, 0, 15) << 4) | Math.clamp(dampening, 0, 15));
            for (CustomBlock.State state : block.states()) {
                if (vanilla[state.javaState()] != value) {
                    if (table == null) {
                        table = Arrays.copyOf(vanilla, vanilla.length);
                    }
                    table[state.javaState()] = value;
                }
            }
        }
        this.lightTable = table != null ? table : vanilla;
    }

    private static int number(@Nullable Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    // ------------------------------------------------------------------ queries

    /** Whether this server's runtime ids are hashes (true) or palette indices. */
    public boolean hashedIds() {
        return this.hashed;
    }

    /** Vanilla palette index (>= 0), custom state (-2 - index), or -1 for an unknown runtime id. */
    private int entry(int runtimeId) {
        if (this.hashed) {
            int index = BedrockBlockPalette.indexOfHash(runtimeId);
            if (index >= 0) {
                return index;
            }
            int custom = this.customByRuntime.get(runtimeId);
            return custom >= 0 ? -2 - custom : -1;
        }
        return runtimeId >= 0 && runtimeId < this.ordered.length ? this.ordered[runtimeId] : -1;
    }

    /** Whether the runtime id is known (vanilla or custom). */
    public boolean isKnown(int bedrockRuntimeId) {
        return entry(bedrockRuntimeId) != -1;
    }

    /** Java state id for a Bedrock runtime id of the block layer (layer 0). Unknown ids map to a visible placeholder. */
    public int toJava(int bedrockRuntimeId) {
        int entry = entry(bedrockRuntimeId);
        if (entry >= 0) {
            return BedrockBlockPalette.javaState(entry);
        }
        if (entry != -1) {
            return this.customJava[-2 - entry];
        }
        if (this.reportedUnknown.size() < 64 && this.reportedUnknown.add(bedrockRuntimeId)) {
            this.session.logger().warn("[{}] Unknown Bedrock block runtime id {}", this.session.getName(), bedrockRuntimeId);
        }
        return this.placeholder;
    }

    /**
     * Java state id for a block position holding {@code layer0} and, in the liquid layer, {@code layer1}
     * (water there makes waterloggable Java blocks waterlogged).
     */
    public int toJava(int layer0, int layer1) {
        int java = toJava(layer0);
        if (layer1 != this.airRuntimeId && isWater(layer1)) {
            return waterlog(java, layer1);
        }
        return java;
    }

    /** {@code java} with water in its liquid layer: its waterlogged form, the water itself for air, else unchanged. */
    public int waterlog(int java, int waterRuntimeId) {
        int wet = JavaBlockStates.waterlogged(java);
        if (wet >= 0) {
            return wet;
        }
        return JavaBlockStates.isAir(java) ? toJava(waterRuntimeId) : java;
    }

    /** Whether a runtime id is still or flowing water (what the liquid layer holds for waterlogged blocks). */
    public boolean isWater(int bedrockRuntimeId) {
        String name = bedrockName(bedrockRuntimeId);
        return name != null && (name.equals("minecraft:water") || name.equals("minecraft:flowing_water"));
    }

    /** The Bedrock state ({@code name} + {@code states}) of a runtime id, or null when unknown. */
    public @Nullable NbtMap bedrockState(int bedrockRuntimeId) {
        int entry = entry(bedrockRuntimeId);
        if (entry >= 0) {
            return BedrockBlockPalette.state(entry);
        }
        return entry == -1 ? null : this.customStates[-2 - entry];
    }

    /** Namespaced Bedrock block name of a runtime id, or null when unknown. */
    public @Nullable String bedrockName(int bedrockRuntimeId) {
        int entry = entry(bedrockRuntimeId);
        if (entry >= 0) {
            return BedrockBlockPalette.name(entry);
        }
        return entry == -1 ? null : this.customNames[-2 - entry];
    }

    /**
     * Whether a runtime id is an item frame block, and which: -1 when it is not, else its facing
     * direction (0 down, 1 up, 2 north, 3 south, 4 west, 5 east, Java's {@code Direction} order) plus
     * {@link BedrockBlockPalette#GLOW_FRAME} for {@code minecraft:glow_frame}. Bedrock frames are blocks
     * that Java shows as entities; the block itself maps to air.
     */
    public int itemFrame(int bedrockRuntimeId) {
        int entry = entry(bedrockRuntimeId);
        return entry >= 0 ? BedrockBlockPalette.itemFrame(entry) : -1;
    }

    /** Runtime id of {@code minecraft:air} on this server. */
    public int bedrockAir() {
        return this.airRuntimeId;
    }

    /** Runtime id of a Bedrock state given as {@code name} + {@code states} (persistent palettes), or air when unknown. */
    public int runtimeIdOf(String name, NbtMap states) {
        int hash = BlockStateHasher.hash(name, states);
        int index = BedrockBlockPalette.indexOfHash(hash);
        if (this.hashed) {
            return index >= 0 || this.customByRuntime.containsKey(hash) ? hash : this.airRuntimeId;
        }
        if (index >= 0) {
            return this.vanillaRuntime[index];
        }
        for (int i = 0; i < this.customStates.length; i++) {
            if (this.customNames[i].equals(name) && this.customStates[i].getCompound("states").equals(states)) {
                return this.customRuntime[i];
            }
        }
        return this.airRuntimeId;
    }

    /** Best Bedrock runtime id for a Java state id (block picking, placement prediction), or air. */
    public int toBedrock(int javaStateId) {
        if (javaStateId < 0 || javaStateId >= JavaBlockStates.count()) {
            return this.airRuntimeId;
        }
        int custom = this.javaToCustom.get(javaStateId);
        if (custom >= 0) {
            return this.customRuntime[custom];
        }
        int index = BedrockBlockPalette.indexOfJava(javaStateId);
        if (index < 0) {
            return this.airRuntimeId;
        }
        return this.hashed ? BedrockBlockPalette.hash(index) : this.vanillaRuntime[index];
    }

    /**
     * Custom blocks of this server with the Java state chosen for each of their states. Available once
     * StartGame was processed; empty for servers without custom blocks.
     */
    public List<CustomBlock> customBlocks() {
        return this.customBlocks;
    }

    /**
     * Light of every Java state ({@code emission << 4 | opacity}): the vanilla table, with custom block
     * carriers set to the custom block's light. Must not be modified.
     */
    public byte[] lightTable() {
        return this.lightTable;
    }

    private static Map<String, List<String>> linked(String k1, List<String> v1, String k2, List<String> v2) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, List<String>> linked(String k1, List<String> v1, String k2, List<String> v2,
                                                     String k3, List<String> v3, String k4, List<String> v4) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        map.put(k4, v4);
        return Collections.unmodifiableMap(map);
    }
}
