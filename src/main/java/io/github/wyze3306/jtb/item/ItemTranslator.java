package io.github.wyze3306.jtb.item;

import io.github.wyze3306.jtb.mapping.JavaRegistries;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.text.BedrockLang;
import io.github.wyze3306.jtb.text.BedrockText;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.mcprotocollib.protocol.data.game.Holder;
import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ArmorTrim;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.BannerPatternLayer;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.BlockStateProperties;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponent;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.Filterable;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.Fireworks;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ItemEnchantments;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.LodestoneTracker;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.MobEffectDetails;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.MobEffectInstance;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.PotDecorations;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.PotionContents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.Unit;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.WritableBookContent;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.WrittenBookContent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * CONTRACT (owner: inventory/item subsystem). Bedrock item stacks to Java item stacks and back.
 * <p>
 * A converted stack carries its exact Bedrock identity in {@code custom_data}:
 * <pre>{@code {jtb: {id: "minecraft:stone", damage?: int, block?: int, tag?: {...}, can_place?: [..],
 *        can_break?: [..], blocking?: long}}}</pre>
 * so {@link #toBedrock} rebuilds it exactly (the count comes from the Java stack). The stack network
 * id is deliberately not stored: Java stacks of the same Bedrock item must stay identical so the Java
 * client merges them like Bedrock does. Java stacks without that data (taken from the creative tabs)
 * are mapped back through the vanilla item tables.
 * <p>
 * Everything else in the Java stack only decides how the item looks and behaves on the Java client:
 * vanilla items map to their Java equivalent (with potion, enchantment, name, lore, dye, firework,
 * book, banner, trim, container... components translated from the Bedrock NBT); server custom items
 * become a base Java item plus components, see {@link CustomItems}.
 */
public final class ItemTranslator {

    /** Root key of the proxy's data inside {@code custom_data}. */
    public static final String CUSTOM_DATA_KEY = "jtb";
    private static final String LODESTONE_COMPASS = "minecraft:lodestone_compass";
    /** Bedrock firework colour index (dye order) to the RGB value Java uses for that dye. */
    private static final int[] FIREWORK_COLORS = {1973019, 11743532, 3887386, 5320730, 2437522, 8073150, 2651799,
            11250603, 4408131, 14188952, 4312372, 14602026, 6719955, 12801229, 15435844, 15790320};

    private static final int ENCHANTED_BOOK = ItemMappings.get().javaId("enchanted_book");
    private static final int WRITTEN_BOOK = ItemMappings.get().javaId("written_book");
    private static final int SHIELD = ItemMappings.get().javaId("shield");
    private static final int OMINOUS_BOTTLE = ItemMappings.get().javaId("ominous_bottle");

    private ItemTranslator() {
    }

    // ------------------------------------------------------------------ Bedrock -> Java

    /** Java stack for a Bedrock stack, or null for air. */
    public static @Nullable ItemStack toJava(ProxySession session, ItemData item) {
        return toJava(session, item, true);
    }

    /**
     * @param withIdentity whether to add the lossless {@code custom_data}; false for stacks nested in
     *                     another item (container contents, charged projectiles), which are only shown
     */
    public static @Nullable ItemStack toJava(ProxySession session, ItemData item, boolean withIdentity) {
        if (isAir(item)) {
            return null;
        }
        ItemDefinition definition = item.getDefinition();
        String identifier = definition.getIdentifier();
        ItemMappings mappings = ItemMappings.get();
        ItemMappings.Mapping mapping = mappings.bedrock(identifier, item.getDamage());
        DataComponents components;
        int javaId;
        int maxDamage;
        if (mapping != null) {
            javaId = mapping.javaId();
            maxDamage = mappings.javaItem(javaId).maxDamage();
            components = new DataComponents(new HashMap<>());
            applyMapping(session, mapping, components);
        } else {
            CustomItems.CustomItem custom = session.get(ItemRegistry.KEY).customItem(definition);
            javaId = custom.baseJavaId();
            maxDamage = custom.maxDamage();
            components = custom.components().clone();
        }
        NbtMap tag = item.getTag();
        if (tag != null && !tag.isEmpty()) {
            applyNbt(session, javaId, maxDamage, tag, components);
        }
        if (LODESTONE_COMPASS.equals(identifier)) {
            components.put(DataComponentTypes.LODESTONE_TRACKER, new LodestoneTracker(null, true));
        } else if (javaId == OMINOUS_BOTTLE && item.getDamage() > 0) {
            components.put(DataComponentTypes.OMINOUS_BOTTLE_AMPLIFIER, Math.min(item.getDamage(), 4));
        }
        addPredicateLore(item.getCanPlace(), "item.canPlace", components);
        addPredicateLore(item.getCanBreak(), "item.canBreak", components);
        if (withIdentity) {
            components.put(DataComponentTypes.CUSTOM_DATA, identityTag(item));
        }
        return new ItemStack(javaId, Math.clamp(item.getCount(), 1, 99),
                components.getDataComponents().isEmpty() ? null : components);
    }

    private static void applyMapping(ProxySession session, ItemMappings.Mapping mapping, DataComponents components) {
        if (mapping.nameKey() != null) {
            components.put(DataComponentTypes.ITEM_NAME, bedrockText(session, mapping.nameKey()));
        }
        if (mapping.potion() != null) {
            int potion = ItemMappings.get().potionId(mapping.potion());
            if (potion >= 0) {
                components.put(DataComponentTypes.POTION_CONTENTS, new PotionContents(potion, -1, List.of(), null));
            }
        } else if (mapping.effects() != null) {
            List<MobEffectInstance> effects = new ArrayList<>(mapping.effects().size());
            for (ItemMappings.EffectSpec spec : mapping.effects()) {
                Effect effect = effect(spec.effect());
                if (effect != null) {
                    effects.add(new MobEffectInstance(effect,
                            new MobEffectDetails(spec.amplifier(), spec.duration(), false, true, true, null)));
                }
            }
            components.put(DataComponentTypes.POTION_CONTENTS, new PotionContents(-1, mapping.color(), effects, null));
        }
        if (mapping.blockState() != null) {
            components.put(DataComponentTypes.BLOCK_STATE, new BlockStateProperties(mapping.blockState()));
        }
        if (mapping.instrument() != null) {
            int id = JavaRegistries.registry("minecraft:instrument").id(mapping.instrument());
            if (id >= 0) {
                components.put(DataComponentTypes.INSTRUMENT, Holder.ofId(id));
            }
        }
    }

    private static @Nullable Effect effect(Key key) {
        try {
            return Effect.valueOf(key.value().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Text of a Bedrock translation key in the player's language ({@code §} codes kept as styles). */
    static Component bedrockText(ProxySession session, String key) {
        BedrockLang lang = BedrockLang.forJavaLocale(session.getLocale());
        if (lang.has(key)) {
            return BedrockText.legacyToComponent(lang.translate(key));
        }
        // Language not loaded yet, or unknown key: a translatable with a readable fallback.
        String readable = key.endsWith(".name") ? key.substring(0, key.length() - 5) : key;
        return Component.translatable(key, CustomItems.readableName(readable.substring(readable.lastIndexOf('.') + 1)));
    }

    private static int num(NbtMap tag, String key, int defaultValue) {
        return tag.getNumber(key, defaultValue).intValue();
    }

    private static void applyNbt(ProxySession session, int javaId, int maxDamage, NbtMap tag, DataComponents out) {
        NbtMap display = tag.getCompound("display", null);
        if (display != null) {
            String name = display.getString("Name", "");
            if (!name.isEmpty()) {
                out.put(DataComponentTypes.CUSTOM_NAME, BedrockText.legacyToComponent(name));
            }
            List<String> lore = display.getList("Lore", NbtType.STRING, List.of());
            if (!lore.isEmpty()) {
                List<Component> lines = new ArrayList<>(Math.min(lore.size(), 256));
                for (int i = 0; i < lore.size() && i < 256; i++) {
                    lines.add(BedrockText.legacyToComponent(lore.get(i)));
                }
                out.put(DataComponentTypes.LORE, lines);
            }
        }

        if (tag.containsKey("ench", NbtType.LIST)) {
            Map<Integer, Integer> enchantments = enchantments(tag.getList("ench", NbtType.COMPOUND, List.of()));
            if (!enchantments.isEmpty()) {
                out.put(javaId == ENCHANTED_BOOK ? DataComponentTypes.STORED_ENCHANTMENTS : DataComponentTypes.ENCHANTMENTS,
                        new ItemEnchantments(enchantments));
            } else {
                // An empty enchantment list is how Bedrock gives an item the glint.
                out.put(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            }
        }

        if (maxDamage > 0 && tag.containsKey("Damage")) {
            out.put(DataComponentTypes.DAMAGE, Math.clamp(num(tag, "Damage", 0), 0, maxDamage));
        }
        if (num(tag, "Unbreakable", 0) != 0) {
            out.put(DataComponentTypes.UNBREAKABLE, Unit.INSTANCE);
        }
        if (num(tag, "RepairCost", 0) > 0) {
            out.put(DataComponentTypes.REPAIR_COST, num(tag, "RepairCost", 0));
        }
        if (tag.containsKey("customColor")) {
            out.put(DataComponentTypes.DYED_COLOR, num(tag, "customColor", 0) & 0xFFFFFF);
        }

        NbtMap fireworks = tag.getCompound("Fireworks", null);
        if (fireworks != null) {
            List<Fireworks.FireworkExplosion> explosions = new ArrayList<>();
            for (NbtMap explosion : fireworks.getList("Explosions", NbtType.COMPOUND, List.of())) {
                explosions.add(explosion(explosion));
            }
            out.put(DataComponentTypes.FIREWORKS, new Fireworks(num(fireworks, "Flight", 1), explosions));
        }
        NbtMap star = tag.getCompound("FireworksItem", null);
        if (star != null) {
            out.put(DataComponentTypes.FIREWORK_EXPLOSION, explosion(star));
        }

        if (tag.containsKey("pages", NbtType.LIST)) {
            book(tag, javaId == WRITTEN_BOOK, out);
        }

        boolean ominous = num(tag, "Type", 0) == 1;
        if (tag.containsKey("Patterns", NbtType.LIST)) {
            out.put(DataComponentTypes.BANNER_PATTERNS, bannerPatterns(tag.getList("Patterns", NbtType.COMPOUND, List.of())));
        }
        if (ominous) {
            out.put(DataComponentTypes.ITEM_NAME, Component.translatable("block.minecraft.ominous_banner")
                    .color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        }
        if (javaId == SHIELD && tag.containsKey("Base")) {
            out.put(DataComponentTypes.BASE_COLOR, 15 - (num(tag, "Base", 0) & 15));
        }

        NbtMap trim = tag.getCompound("Trim", null);
        if (trim != null) {
            int material = JavaRegistries.registry("minecraft:trim_material").id(namespaced(trim.getString("Material", "")));
            int pattern = JavaRegistries.registry("minecraft:trim_pattern").id(namespaced(trim.getString("Pattern", "")));
            if (material >= 0 && pattern >= 0) {
                out.put(DataComponentTypes.TRIM, new ArmorTrim(Holder.ofId(material), Holder.ofId(pattern)));
            }
        }

        if (tag.containsKey("Items", NbtType.LIST)) {
            out.put(DataComponentTypes.CONTAINER, containerContents(session, tag.getList("Items", NbtType.COMPOUND, List.of())));
        }
        NbtMap charged = tag.getCompound("chargedItem", null);
        if (charged != null) {
            ItemStack projectile = toJava(session, nbtItem(session, charged), false);
            if (projectile != null) {
                out.put(DataComponentTypes.CHARGED_PROJECTILES, List.of(projectile));
            }
        }
        if (tag.containsKey("map_uuid")) {
            out.put(DataComponentTypes.MAP_ID, session.get(ItemRegistry.KEY).javaMapId(tag.getNumber("map_uuid", 0).longValue()));
        }
        List<String> sherds = tag.getList("sherds", NbtType.STRING, List.of());
        if (sherds.size() == 4) {
            out.put(DataComponentTypes.POT_DECORATIONS, new PotDecorations(sherd(sherds.get(0)), sherd(sherds.get(1)),
                    sherd(sherds.get(2)), sherd(sherds.get(3))));
        }
    }

    private static Map<Integer, Integer> enchantments(List<NbtMap> list) {
        Map<Integer, Integer> enchantments = new HashMap<>();
        JavaRegistries.Registry registry = JavaRegistries.registry("minecraft:enchantment");
        for (NbtMap entry : list) {
            Key key = ItemMappings.get().javaEnchantment(num(entry, "id", -1));
            int id = key == null ? -1 : registry.id(key);
            if (id >= 0) {
                enchantments.put(id, Math.clamp(num(entry, "lvl", 1), 1, 255));
            }
        }
        return enchantments;
    }

    private static Fireworks.FireworkExplosion explosion(NbtMap explosion) {
        return new Fireworks.FireworkExplosion(Math.clamp(num(explosion, "FireworkType", 0), 0, 4),
                fireworkColors(explosion.getByteArray("FireworkColor", new byte[0])),
                fireworkColors(explosion.getByteArray("FireworkFade", new byte[0])),
                num(explosion, "FireworkTrail", 0) != 0, num(explosion, "FireworkFlicker", 0) != 0);
    }

    private static int[] fireworkColors(byte[] colors) {
        int[] rgb = new int[colors.length];
        for (int i = 0; i < colors.length; i++) {
            rgb[i] = FIREWORK_COLORS[colors[i] & 15];
        }
        return rgb;
    }

    private static void book(NbtMap tag, boolean written, DataComponents out) {
        List<NbtMap> pages = tag.getList("pages", NbtType.COMPOUND, List.of());
        if (written) {
            List<Filterable<Component>> text = new ArrayList<>(pages.size());
            for (NbtMap page : pages) {
                text.add(new Filterable<>(BedrockText.legacyToComponent(page.getString("text", "")), null));
            }
            out.put(DataComponentTypes.WRITTEN_BOOK_CONTENT, new WrittenBookContent(new Filterable<>(tag.getString("title", ""), null),
                    tag.getString("author", ""), Math.clamp(num(tag, "generation", 0), 0, 3), text, true));
        } else {
            List<Filterable<String>> text = new ArrayList<>(pages.size());
            for (NbtMap page : pages) {
                text.add(new Filterable<>(page.getString("text", ""), null));
            }
            out.put(DataComponentTypes.WRITABLE_BOOK_CONTENT, new WritableBookContent(text));
        }
    }

    private static List<BannerPatternLayer> bannerPatterns(List<NbtMap> patterns) {
        JavaRegistries.Registry registry = JavaRegistries.registry("minecraft:banner_pattern");
        List<BannerPatternLayer> layers = new ArrayList<>(patterns.size());
        for (NbtMap pattern : patterns) {
            Key key = ItemMappings.get().javaBannerPattern(pattern.getString("Pattern", ""));
            int id = key == null ? -1 : registry.id(key);
            if (id >= 0) {
                // Bedrock numbers banner dye colours in reverse order.
                layers.add(new BannerPatternLayer(Holder.ofId(id), 15 - (num(pattern, "Color", 0) & 15)));
            }
        }
        return layers;
    }

    private static List<Optional<ItemStack>> containerContents(ProxySession session, List<NbtMap> items) {
        ItemStack[] slots = new ItemStack[0];
        for (NbtMap entry : items) {
            int slot = num(entry, "Slot", -1);
            if (slot < 0 || slot >= 256) {
                continue;
            }
            if (slot >= slots.length) {
                slots = Arrays.copyOf(slots, slot + 1);
            }
            slots[slot] = toJava(session, nbtItem(session, entry), false);
        }
        List<Optional<ItemStack>> contents = new ArrayList<>(slots.length);
        for (ItemStack stack : slots) {
            contents.add(Optional.ofNullable(stack));
        }
        return contents;
    }

    private static @Nullable ItemStack sherd(String identifier) {
        if (identifier.isEmpty() || identifier.equals("minecraft:brick")) {
            return null;
        }
        ItemMappings.Mapping mapping = ItemMappings.get().bedrock(identifier, 0);
        return mapping == null ? null : new ItemStack(mapping.javaId(), 1);
    }

    private static void addPredicateLore(String[] blocks, String titleKey, DataComponents components) {
        if (blocks.length == 0) {
            return;
        }
        List<Component> lore = new ArrayList<>();
        List<Component> existing = components.get(DataComponentTypes.LORE);
        if (existing != null) {
            lore.addAll(existing);
        }
        lore.add(Component.translatable(titleKey).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        for (String block : blocks) {
            if (lore.size() >= 256) {
                break;
            }
            lore.add(Component.text(CustomItems.readableName(block)).color(NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        components.put(DataComponentTypes.LORE, lore);
    }

    private static Key namespaced(String id) {
        return id.indexOf(':') >= 0 ? Key.key(id) : Key.key("minecraft", id);
    }

    /** A Bedrock item stored as NBT ({@code {Name, Count, Damage, tag}}, e.g. shulker contents). */
    public static ItemData nbtItem(ProxySession session, NbtMap nbt) {
        ItemDefinition definition = session.get(ItemRegistry.KEY).byIdentifier(nbt.getString("Name", ""));
        if (definition == null || definition.getRuntimeId() == 0) {
            return ItemData.AIR;
        }
        return ItemData.builder()
                .definition(definition)
                .damage(num(nbt, "Damage", 0))
                .count(num(nbt, "Count", 1))
                .tag(nbt.getCompound("tag", null))
                .build();
    }

    /** The {@code custom_data} identifying the Bedrock item exactly (count and stack network id excluded). */
    static NbtMap identityTag(ItemData item) {
        NbtMapBuilder jtb = NbtMap.builder().putString("id", item.getDefinition().getIdentifier());
        if (item.getDamage() != 0) {
            jtb.putInt("damage", item.getDamage());
        }
        BlockDefinition block = item.getBlockDefinition();
        if (block != null && block.getRuntimeId() != 0) {
            jtb.putInt("block", block.getRuntimeId());
        }
        if (item.getTag() != null) {
            jtb.putCompound("tag", item.getTag());
        }
        if (item.getCanPlace().length > 0) {
            jtb.putList("can_place", NbtType.STRING, List.of(item.getCanPlace()));
        }
        if (item.getCanBreak().length > 0) {
            jtb.putList("can_break", NbtType.STRING, List.of(item.getCanBreak()));
        }
        if (item.getBlockingTicks() != 0) {
            jtb.putLong("blocking", item.getBlockingTicks());
        }
        return NbtMap.builder().putCompound(CUSTOM_DATA_KEY, jtb.build()).build();
    }

    // ------------------------------------------------------------------ Java -> Bedrock

    /** Bedrock stack for a Java stack (air for null). */
    public static ItemData toBedrock(ProxySession session, @Nullable ItemStack item) {
        if (item == null || item.getAmount() <= 0 || item.getId() == 0) {
            return ItemData.AIR;
        }
        ItemData identity = fromIdentity(session, item);
        return identity != null ? identity : fromVanilla(session, item);
    }

    /** Rebuilds the exact Bedrock item from the stack's {@code custom_data}, or null when it has none. */
    public static @Nullable ItemData fromIdentity(ProxySession session, ItemStack item) {
        DataComponents components = item.getDataComponentsPatch();
        NbtMap custom = components == null ? null : components.get(DataComponentTypes.CUSTOM_DATA);
        NbtMap jtb = custom == null ? null : custom.getCompound(CUSTOM_DATA_KEY, null);
        if (jtb == null) {
            return null;
        }
        ItemDefinition definition = session.get(ItemRegistry.KEY).byIdentifier(jtb.getString("id", ""));
        if (definition == null) {
            return null;
        }
        int block = num(jtb, "block", 0);
        return ItemData.builder()
                .definition(definition)
                .damage(num(jtb, "damage", 0))
                .count(item.getAmount())
                .tag(jtb.getCompound("tag", null))
                .canPlace(jtb.getList("can_place", NbtType.STRING, List.of()).toArray(String[]::new))
                .canBreak(jtb.getList("can_break", NbtType.STRING, List.of()).toArray(String[]::new))
                .blockingTicks(jtb.getNumber("blocking", 0L).longValue())
                .blockDefinition(block == 0 ? null : new RuntimeBlock(block))
                .build();
    }

    /**
     * The Bedrock item a vanilla Java stack stands for (a stack without the proxy's identity data,
     * e.g. taken from the Java creative tabs): the best vanilla Bedrock variant of the Java item that
     * matches its potion, instrument or block state. Names, enchantments and other components are not
     * carried over. Air when the Java item has no Bedrock counterpart.
     */
    public static ItemData fromVanilla(ProxySession session, ItemStack item) {
        List<ItemMappings.BedrockVariant> variants = ItemMappings.get().bedrockVariants(item.getId());
        ItemRegistry registry = session.get(ItemRegistry.KEY);
        DataComponents components = item.getDataComponentsPatch();
        ItemMappings.BedrockVariant fallback = null;
        for (ItemMappings.BedrockVariant variant : variants) {
            if (registry.byIdentifier(variant.identifier()) == null) {
                continue;
            }
            if (matches(variant.mapping(), components)) {
                return build(registry, variant, item.getAmount());
            }
            if (fallback == null) {
                fallback = variant;
            }
        }
        return fallback == null ? ItemData.AIR : build(registry, fallback, item.getAmount());
    }

    private static ItemData build(ItemRegistry registry, ItemMappings.BedrockVariant variant, int count) {
        ItemDefinition definition = registry.byIdentifier(variant.identifier());
        return ItemData.builder()
                .definition(definition)
                .damage(Math.max(variant.aux(), 0))
                .count(count)
                .build();
    }

    private static boolean matches(ItemMappings.Mapping mapping, @Nullable DataComponents components) {
        PotionContents potion = components == null ? null : components.get(DataComponentTypes.POTION_CONTENTS);
        if (mapping.potion() != null || potion != null) {
            return potion != null && mapping.potion() != null && ItemMappings.get().potionId(mapping.potion()) == potion.getPotionId();
        }
        Holder<?> instrument = components == null ? null : components.get(DataComponentTypes.INSTRUMENT);
        if (mapping.instrument() != null || instrument != null) {
            return instrument != null && instrument.isId() && mapping.instrument() != null
                    && JavaRegistries.registry("minecraft:instrument").id(mapping.instrument()) == instrument.id();
        }
        BlockStateProperties state = components == null ? null : components.get(DataComponentTypes.BLOCK_STATE);
        if (mapping.blockState() != null || state != null) {
            return state != null && state.getProperties().equals(mapping.blockState());
        }
        return mapping.nameKey() == null;
    }

    // ------------------------------------------------------------------ helpers

    public static boolean isAir(@Nullable ItemData item) {
        return item == null || item.getCount() <= 0 || item.getDefinition() == null
                || item.getDefinition().getRuntimeId() == 0 || "minecraft:air".equals(item.getDefinition().getIdentifier());
    }

    /**
     * Whether two Bedrock stacks stack together: same item, aux value and user data, as both the
     * Bedrock client and PowerNukkitX ({@code Item.equals(item, true, true)}) decide it.
     */
    public static boolean stackable(ItemData a, ItemData b) {
        return !isAir(a) && !isAir(b) && a.equals(b, false, true, true);
    }

    /**
     * Maximum stack size of a Bedrock item as the Java client sees it (for vanilla items, the same
     * limit the server enforces).
     */
    public static int maxStackSize(ProxySession session, ItemData item) {
        if (isAir(item)) {
            return 64;
        }
        ItemMappings mappings = ItemMappings.get();
        ItemMappings.Mapping mapping = mappings.bedrock(item.getDefinition().getIdentifier(), item.getDamage());
        if (mapping != null) {
            return mappings.javaItem(mapping.javaId()).maxStackSize();
        }
        return session.get(ItemRegistry.KEY).customItem(item.getDefinition()).maxStackSize();
    }

    /** The Java item a Bedrock item is shown as (air for air). */
    public static ItemMappings.JavaItem javaItem(ProxySession session, ItemData item) {
        ItemMappings mappings = ItemMappings.get();
        if (isAir(item)) {
            return mappings.javaItem(0);
        }
        ItemMappings.Mapping mapping = mappings.bedrock(item.getDefinition().getIdentifier(), item.getDamage());
        return mappings.javaItem(mapping != null ? mapping.javaId()
                : session.get(ItemRegistry.KEY).customItem(item.getDefinition()).baseJavaId());
    }

    /** A copy of {@code item} with another count (air when {@code count <= 0}); the net id is kept. */
    public static ItemData withCount(ItemData item, int count) {
        if (count <= 0 || isAir(item)) {
            return ItemData.AIR;
        }
        if (count == item.getCount()) {
            return item;
        }
        return item.toBuilder().count(count).build();
    }

    /** A copy of {@code item} with another stack network id (0 = unknown). */
    public static ItemData withNetId(ItemData item, int netId) {
        if (isAir(item) || (item.isUsingNetId() == (netId != 0) && item.getNetId() == netId)) {
            return item;
        }
        return item.toBuilder().usingNetId(netId != 0).netId(netId).build();
    }

    /**
     * Describes which variant of its Java item a Java stack is (potion type, instrument, stored
     * enchantments, ominous amplifier, block state, firework flight), ignoring names and identity
     * data: equal keys mean the same creative-tab entry.
     */
    public static String variantKey(ItemStack stack) {
        StringBuilder key = new StringBuilder(16).append(stack.getId());
        DataComponents components = stack.getDataComponentsPatch();
        if (components == null) {
            return key.toString();
        }
        appendVariant(key, components, DataComponentTypes.POTION_CONTENTS);
        appendVariant(key, components, DataComponentTypes.INSTRUMENT);
        appendVariant(key, components, DataComponentTypes.STORED_ENCHANTMENTS);
        appendVariant(key, components, DataComponentTypes.OMINOUS_BOTTLE_AMPLIFIER);
        appendVariant(key, components, DataComponentTypes.BLOCK_STATE);
        Fireworks fireworks = components.get(DataComponentTypes.FIREWORKS);
        if (fireworks != null) {
            key.append("|f").append(fireworks.getFlightDuration());
        }
        return key.toString();
    }

    private static void appendVariant(StringBuilder key, DataComponents components, DataComponentType<?> type) {
        DataComponent<?, ?> component = components.getDataComponents().get(type);
        if (component == null || component.getValue() == null) {
            return;
        }
        Object value = component.getValue();
        if (value instanceof PotionContents potion) {
            value = potion.getPotionId();
        } else if (value instanceof Holder<?> holder) {
            value = holder.isId() ? holder.id() : holder.custom();
        } else if (value instanceof ItemEnchantments enchantments) {
            value = enchantments.getEnchantments();
        } else if (value instanceof BlockStateProperties state) {
            value = state.getProperties();
        }
        key.append('|').append(type.getId()).append('=').append(value);
    }

    /** A block definition carrying only a runtime id (all the codec writes for items). */
    public record RuntimeBlock(int runtimeId) implements BlockDefinition {
        @Override
        public int getRuntimeId() {
            return this.runtimeId;
        }
    }
}
