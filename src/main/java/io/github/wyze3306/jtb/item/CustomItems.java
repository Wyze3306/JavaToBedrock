package io.github.wyze3306.jtb.item;

import io.github.wyze3306.jtb.text.BedrockLang;
import io.github.wyze3306.jtb.text.BedrockText;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.Consumable;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.Equippable;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.FoodProperties;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ToolData;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.UseCooldown;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.BuiltinSound;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Custom (component based) items of the server, i.e. identifiers that are not vanilla Bedrock items,
 * as Java items: a vanilla base item chosen from the item's behaviour plus components that describe
 * it ({@code item_model}, {@code item_name}, stack size, durability, food, equipment, tool).
 * <p>
 * <b>Model naming convention</b> (shared with the resource pack converter): the Java item model of
 * the Bedrock item {@code <namespace>:<path>} is {@code jtb:<namespace>/<path>}, both parts lower
 * case with every character outside {@code [a-z0-9_.-]} replaced by {@code _} (and {@code /} kept in
 * the path), i.e. the item model definition {@code assets/jtb/items/<namespace>/<path>.json}. Use
 * {@link #modelKey(String)} rather than rebuilding the name.
 */
public final class CustomItems {

    /** Everything about a custom item that does not depend on a particular stack. */
    public record CustomItem(int baseJavaId, int maxStackSize, int maxDamage, DataComponents components) {
    }

    private CustomItems() {
    }

    /** Java item model of a custom Bedrock item; see the class documentation for the naming rule. */
    public static Key modelKey(String bedrockIdentifier) {
        int colon = bedrockIdentifier.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : bedrockIdentifier.substring(0, colon);
        String path = colon < 0 ? bedrockIdentifier : bedrockIdentifier.substring(colon + 1);
        return Key.key("jtb", sanitize(namespace, false) + "/" + sanitize(path, true));
    }

    private static String sanitize(String part, boolean allowSlash) {
        String lower = part.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-'
                    || (allowSlash && c == '/');
            out.append(valid ? c : '_');
        }
        return out.isEmpty() ? "_" : out.toString();
    }

    /**
     * Builds the Java form of a custom item from its server definition ({@code ItemRegistryPacket}
     * component data). The item name is resolved in {@code lang}.
     */
    public static CustomItem build(ItemDefinition definition, BedrockLang lang) {
        String identifier = definition.getIdentifier();
        NbtMap data = definition.getComponentData();
        NbtMap components = data == null ? NbtMap.EMPTY : data.getCompound("components", NbtMap.EMPTY);
        NbtMap properties = components.getCompound("item_properties", NbtMap.EMPTY);
        ItemMappings mappings = ItemMappings.get();

        DataComponents out = new DataComponents(new HashMap<>());
        out.put(DataComponentTypes.ITEM_MODEL, modelKey(identifier));
        out.put(DataComponentTypes.ITEM_NAME, name(identifier, components, lang));

        int maxDamage = num(components.getCompound("minecraft:durability", NbtMap.EMPTY), "max_durability", 0).intValue();
        int maxStack = num(components.getCompound("minecraft:max_stack_size", NbtMap.EMPTY), "value",
                num(properties, "max_stack_size", 64)).intValue();
        maxStack = Math.clamp(maxStack, 1, 99);
        if (maxDamage > 0) {
            // Java rejects stacks that are both damageable and stackable.
            maxStack = 1;
            out.put(DataComponentTypes.MAX_DAMAGE, maxDamage);
        }
        out.put(DataComponentTypes.MAX_STACK_SIZE, maxStack);

        if (bool(properties, "foil", false) || components.containsKey("minecraft:glint")) {
            out.put(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }

        NbtMap food = components.getCompound("minecraft:food", null);
        if (food != null) {
            out.put(DataComponentTypes.FOOD, new FoodProperties(num(food, "nutrition", 0).intValue(),
                    num(food, "saturation_modifier", 0.6f).floatValue(), bool(food, "can_always_eat", false)));
            out.put(DataComponentTypes.CONSUMABLE, new Consumable(useSeconds(components, properties),
                    Consumable.ItemUseAnimation.EAT, BuiltinSound.ENTITY_GENERIC_EAT, true, List.of()));
        }

        EquipmentSlot slot = wearableSlot(components.getCompound("minecraft:wearable", NbtMap.EMPTY).getString("slot", ""));
        if (slot != null) {
            out.put(DataComponentTypes.EQUIPPABLE, new Equippable(slot, BuiltinSound.ITEM_ARMOR_EQUIP_GENERIC, null, null,
                    null, true, true, true, false, false, BuiltinSound.ITEM_SHEARS_SNIP));
        }

        boolean digger = components.containsKey("minecraft:digger");
        if (digger) {
            out.put(DataComponentTypes.TOOL, new ToolData(List.of(), num(properties, "mining_speed", 1.0f).floatValue(), 1,
                    bool(properties, "can_destroy_in_creative", true)));
        }

        NbtMap cooldown = components.getCompound("minecraft:cooldown", null);
        if (cooldown != null && num(cooldown, "duration", 0).floatValue() > 0) {
            String category = cooldown.getString("category", "");
            Key group = category.isEmpty() ? null : Key.key("jtb", sanitize(category, false));
            out.put(DataComponentTypes.USE_COOLDOWN, new UseCooldown(num(cooldown, "duration", 0).floatValue(), group));
        }

        int base;
        if (components.containsKey("minecraft:shooter")) {
            base = mappings.javaId("bow");
        } else if (components.containsKey("minecraft:throwable") || components.containsKey("minecraft:projectile")) {
            base = mappings.javaId("snowball");
        } else {
            base = mappings.javaId("paper");
        }
        if (base == mappings.javaId("bow") && maxDamage <= 0) {
            // A bow is damageable by default: keep the custom item unbreakable-looking but stackable as declared.
            out.put(DataComponentTypes.MAX_DAMAGE, null);
            out.put(DataComponentTypes.DAMAGE, null);
        }
        return new CustomItem(base, maxStack, maxDamage, out);
    }

    private static float useSeconds(NbtMap components, NbtMap properties) {
        NbtMap modifiers = components.getCompound("minecraft:use_modifiers", null);
        if (modifiers != null && num(modifiers, "use_duration", 0).floatValue() > 0) {
            return num(modifiers, "use_duration", 0).floatValue();
        }
        int ticks = num(properties, "use_duration", 32).intValue();
        return ticks > 0 ? ticks / 20.0f : 1.6f;
    }

    private static @Nullable EquipmentSlot wearableSlot(String slot) {
        return switch (slot) {
            case "slot.armor.head" -> EquipmentSlot.HELMET;
            case "slot.armor.chest" -> EquipmentSlot.CHESTPLATE;
            case "slot.armor.legs" -> EquipmentSlot.LEGGINGS;
            case "slot.armor.feet" -> EquipmentSlot.BOOTS;
            case "slot.weapon.offhand" -> EquipmentSlot.OFF_HAND;
            default -> null;
        };
    }

    /**
     * The display name: the {@code minecraft:display_name} component (a lang key or literal text), else
     * the lang keys the Bedrock client tries, else a readable form of the identifier.
     */
    private static Component name(String identifier, NbtMap components, BedrockLang lang) {
        String display = components.getCompound("minecraft:display_name", NbtMap.EMPTY).getString("value", "");
        if (!display.isEmpty()) {
            return BedrockText.legacyToComponent(lang.has(display) ? lang.translate(display) : display);
        }
        for (String key : new String[]{"item." + identifier + ".name", "item." + identifier}) {
            if (lang.has(key)) {
                return BedrockText.legacyToComponent(lang.translate(key));
            }
        }
        return Component.text(readableName(identifier));
    }

    /** A numeric NBT value whatever its exact type (servers write bytes, shorts or ints). */
    private static Number num(NbtMap map, String key, Number defaultValue) {
        return map.get(key) instanceof Number number ? number : defaultValue;
    }

    private static boolean bool(NbtMap map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value instanceof Number number ? number.intValue() != 0 : defaultValue;
    }

    /** {@code mymod:ruby_sword} -> {@code Ruby Sword}. */
    public static String readableName(String identifier) {
        String path = identifier.substring(identifier.indexOf(':') + 1);
        StringBuilder out = new StringBuilder(path.length());
        boolean upper = true;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '_' || c == '.' || c == '/') {
                out.append(' ');
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString().trim();
    }

    /** Whether the item has a {@code minecraft:tags} entry (e.g. {@code minecraft:is_sword}). */
    static boolean hasTag(ItemDefinition definition, String tag) {
        NbtMap data = definition.getComponentData();
        if (data == null) {
            return false;
        }
        NbtMap tags = data.getCompound("components", NbtMap.EMPTY).getCompound("minecraft:tags", NbtMap.EMPTY);
        return tags.getList("tags", NbtType.STRING, List.of()).contains(tag);
    }
}
