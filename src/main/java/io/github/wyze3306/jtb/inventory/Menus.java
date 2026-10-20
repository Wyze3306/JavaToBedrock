package io.github.wyze3306.jtb.inventory;

import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerEnumName;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The Java menu layout of each Bedrock container type: which Bedrock slot each Java slot shows and
 * which slots hold a result computed by the proxy. Bedrock slot numbers are those PowerNukkitX (and
 * the vanilla client) use in item stack requests.
 */
final class Menus {

    /** A storage index no Bedrock container uses (reads as empty). */
    private static final int NO_SLOT = 1000;

    private Menus() {
    }

    /** Collects the slots of a menu in Java order. */
    private static final class Builder {
        final List<SlotRef> slots = new ArrayList<>();
        final List<Menu.Rule> rules = new ArrayList<>();
        int result = -1;

        Builder add(BedrockContainer container, int index, ContainerEnumName name, int netSlot, Menu.Rule rule) {
            this.slots.add(new SlotRef(container, index, name, netSlot));
            this.rules.add(rule);
            return this;
        }

        Builder ui(BedrockContainer ui, int slot, ContainerEnumName name, Menu.Rule rule) {
            return add(ui, slot, name, slot, rule);
        }

        Builder result() {
            this.result = this.slots.size();
            this.slots.add(null);
            this.rules.add(Menu.Rule.OUTPUT);
            return this;
        }

        Builder window(BedrockContainer window, int index, ContainerEnumName name, Menu.Rule rule) {
            return add(window, index, name, index, rule);
        }

        /** The 27 main inventory slots then the 9 hotbar slots. */
        Builder player(BedrockContainer player) {
            for (int i = 9; i < 36; i++) {
                add(player, i, ContainerEnumName.INVENTORY_CONTAINER, i, Menu.Rule.ANY);
            }
            for (int i = 0; i < 9; i++) {
                add(player, i, ContainerEnumName.HOTBAR_CONTAINER, i, Menu.Rule.ANY);
            }
            return this;
        }

        Menu build(Menu.Kind kind, int javaId, @Nullable ContainerType type, int windowId,
                   org.cloudburstmc.protocol.bedrock.data.inventory.ContainerType bedrockType, int containerSlots, int playerStart) {
            return new Menu(kind, javaId, type, windowId, bedrockType, this.slots.toArray(SlotRef[]::new),
                    this.rules.toArray(Menu.Rule[]::new), containerSlots, this.result, playerStart);
        }
    }

    /** Java window 0: result, 2x2 grid, armour, main inventory, hotbar, off hand (46 slots). */
    static Menu player(Mirror mirror) {
        Builder b = new Builder().result();
        for (int i = 0; i < 4; i++) {
            b.ui(mirror.ui, 28 + i, ContainerEnumName.CRAFTING_INPUT_CONTAINER, Menu.Rule.ANY);
        }
        Menu.Rule[] armor = {Menu.Rule.HEAD, Menu.Rule.CHEST, Menu.Rule.LEGS, Menu.Rule.FEET};
        for (int i = 0; i < 4; i++) {
            b.add(mirror.armor, i, ContainerEnumName.ARMOR_CONTAINER, i, armor[i]);
        }
        b.player(mirror.player);
        // The off hand is slot 1 in item stack requests although content packets carry it at index 0.
        b.add(mirror.offhand, 0, ContainerEnumName.OFFHAND_CONTAINER, 1, Menu.Rule.OFFHAND);
        return b.build(Menu.Kind.PLAYER, 0, null, -1, org.cloudburstmc.protocol.bedrock.data.inventory.ContainerType.INVENTORY, 9, 9);
    }

    /**
     * The Java menu of a Bedrock container, or null when Java has no equivalent.
     *
     * @param size        storage size for chest-like containers (from the first content packet)
     * @param storageName request container name of chest-like storage (chest, barrel, shulker box)
     */
    static @Nullable Menu container(Mirror mirror, org.cloudburstmc.protocol.bedrock.data.inventory.ContainerType type, int windowId,
                                    int size, ContainerEnumName storageName, boolean shulker) {
        BedrockContainer ui = mirror.ui;
        BedrockContainer window = mirror.window;
        Builder b = new Builder();
        switch (type) {
            case CONTAINER, MINECART_CHEST, CHEST_BOAT -> {
                int rows = Math.clamp((size + 8) / 9, 1, 6);
                if (shulker && size == 27) {
                    storage(b, window, 27, storageName).player(mirror.player);
                    return b.build(Menu.Kind.SHULKER, windowId, ContainerType.SHULKER_BOX, windowId, type, 27, 27);
                }
                storage(b, window, rows * 9, storageName).player(mirror.player);
                return b.build(Menu.Kind.GENERIC, windowId, ContainerType.values()[rows - 1], windowId, type, rows * 9, rows * 9);
            }
            case DISPENSER, DROPPER -> {
                storage(b, window, 9, ContainerEnumName.LEVEL_ENTITY_CONTAINER).player(mirror.player);
                return b.build(Menu.Kind.DISPENSER, windowId, ContainerType.GENERIC_3X3, windowId, type, 9, 9);
            }
            case HOPPER, MINECART_HOPPER -> {
                storage(b, window, 5, ContainerEnumName.LEVEL_ENTITY_CONTAINER).player(mirror.player);
                return b.build(Menu.Kind.HOPPER, windowId, ContainerType.HOPPER, windowId, type, 5, 5);
            }
            case FURNACE, BLAST_FURNACE, SMOKER -> {
                ContainerEnumName ingredient = switch (type) {
                    case BLAST_FURNACE -> ContainerEnumName.BLAST_FURNACE_INGREDIENT_CONTAINER;
                    case SMOKER -> ContainerEnumName.SMOKER_INGREDIENT_CONTAINER;
                    default -> ContainerEnumName.FURNACE_INGREDIENT_CONTAINER;
                };
                b.window(window, 0, ingredient, Menu.Rule.ANY)
                        .window(window, 1, ContainerEnumName.FURNACE_FUEL_CONTAINER, Menu.Rule.FUEL)
                        .window(window, 2, ContainerEnumName.FURNACE_RESULT_CONTAINER, Menu.Rule.OUTPUT)
                        .player(mirror.player);
                ContainerType javaType = switch (type) {
                    case BLAST_FURNACE -> ContainerType.BLAST_FURNACE;
                    case SMOKER -> ContainerType.SMOKER;
                    default -> ContainerType.FURNACE;
                };
                return b.build(Menu.Kind.FURNACE, windowId, javaType, windowId, type, 3, 3);
            }
            case BREWING_STAND -> {
                for (int i = 1; i <= 3; i++) {
                    b.window(window, i, ContainerEnumName.BREWING_STAND_RESULT_CONTAINER, Menu.Rule.POTION);
                }
                b.window(window, 0, ContainerEnumName.BREWING_STAND_INPUT_CONTAINER, Menu.Rule.BREWING_INGREDIENT)
                        .window(window, 4, ContainerEnumName.BREWING_STAND_FUEL_CONTAINER, Menu.Rule.BREWING_FUEL)
                        .player(mirror.player);
                return b.build(Menu.Kind.BREWING, windowId, ContainerType.BREWING_STAND, windowId, type, 5, 5);
            }
            case WORKBENCH -> {
                b.result();
                for (int i = 0; i < 9; i++) {
                    b.ui(ui, 32 + i, ContainerEnumName.CRAFTING_INPUT_CONTAINER, Menu.Rule.ANY);
                }
                b.player(mirror.player);
                return b.build(Menu.Kind.CRAFTING, windowId, ContainerType.CRAFTING, windowId, type, 10, 10);
            }
            case ENCHANTMENT -> {
                b.ui(ui, 14, ContainerEnumName.ENCHANTING_INPUT_CONTAINER, Menu.Rule.SINGLE)
                        .ui(ui, 15, ContainerEnumName.ENCHANTING_MATERIAL_CONTAINER, Menu.Rule.LAPIS)
                        .player(mirror.player);
                return b.build(Menu.Kind.ENCHANTING, windowId, ContainerType.ENCHANTMENT, windowId, type, 2, 2);
            }
            case ANVIL -> {
                b.ui(ui, 1, ContainerEnumName.ANVIL_INPUT_CONTAINER, Menu.Rule.ANY)
                        .ui(ui, 2, ContainerEnumName.ANVIL_MATERIAL_CONTAINER, Menu.Rule.ANY)
                        .result().player(mirror.player);
                return b.build(Menu.Kind.ANVIL, windowId, ContainerType.ANVIL, windowId, type, 3, 3);
            }
            case GRINDSTONE -> {
                b.ui(ui, 16, ContainerEnumName.GRINDSTONE_INPUT_CONTAINER, Menu.Rule.ANY)
                        .ui(ui, 17, ContainerEnumName.GRINDSTONE_ADDITIONAL_CONTAINER, Menu.Rule.ANY)
                        .result().player(mirror.player);
                return b.build(Menu.Kind.GRINDSTONE, windowId, ContainerType.GRINDSTONE, windowId, type, 3, 3);
            }
            case STONECUTTER -> {
                b.ui(ui, 3, ContainerEnumName.STONECUTTER_INPUT_CONTAINER, Menu.Rule.ANY).result().player(mirror.player);
                return b.build(Menu.Kind.STONECUTTER, windowId, ContainerType.STONECUTTER, windowId, type, 2, 2);
            }
            case LOOM -> {
                b.ui(ui, 9, ContainerEnumName.LOOM_INPUT_CONTAINER, Menu.Rule.BANNER)
                        .ui(ui, 10, ContainerEnumName.LOOM_DYE_CONTAINER, Menu.Rule.DYE)
                        .ui(ui, 11, ContainerEnumName.LOOM_MATERIAL_CONTAINER, Menu.Rule.BANNER_PATTERN)
                        .result().player(mirror.player);
                return b.build(Menu.Kind.LOOM, windowId, ContainerType.LOOM, windowId, type, 4, 4);
            }
            case CARTOGRAPHY -> {
                b.ui(ui, 12, ContainerEnumName.CARTOGRAPHY_INPUT_CONTAINER, Menu.Rule.MAP)
                        .ui(ui, 13, ContainerEnumName.CARTOGRAPHY_ADDITIONAL_CONTAINER, Menu.Rule.MAP_ADDITIONAL)
                        .result().player(mirror.player);
                return b.build(Menu.Kind.CARTOGRAPHY, windowId, ContainerType.CARTOGRAPHY, windowId, type, 3, 3);
            }
            case SMITHING_TABLE -> {
                b.ui(ui, 53, ContainerEnumName.SMITHING_TABLE_TEMPLATE_CONTAINER, Menu.Rule.SMITHING_TEMPLATE)
                        .ui(ui, 51, ContainerEnumName.SMITHING_TABLE_INPUT_CONTAINER, Menu.Rule.SMITHING_BASE)
                        .ui(ui, 52, ContainerEnumName.SMITHING_TABLE_MATERIAL_CONTAINER, Menu.Rule.SMITHING_ADDITION)
                        .result().player(mirror.player);
                return b.build(Menu.Kind.SMITHING, windowId, ContainerType.SMITHING, windowId, type, 4, 4);
            }
            case BEACON -> {
                b.ui(ui, 27, ContainerEnumName.BEACON_PAYMENT_CONTAINER, Menu.Rule.PAYMENT).player(mirror.player);
                return b.build(Menu.Kind.BEACON, windowId, ContainerType.BEACON, windowId, type, 1, 1);
            }
            case TRADE -> {
                b.ui(ui, 4, ContainerEnumName.TRADE2_INGREDIENT1_CONTAINER, Menu.Rule.ANY)
                        .ui(ui, 5, ContainerEnumName.TRADE2_INGREDIENT2_CONTAINER, Menu.Rule.ANY)
                        .result().player(mirror.player);
                return b.build(Menu.Kind.MERCHANT, windowId, ContainerType.MERCHANT, windowId, type, 3, 3);
            }
            case CRAFTER -> {
                storage(b, window, 9, ContainerEnumName.CRAFTER_LEVEL_ENTITY_CONTAINER).player(mirror.player).result();
                return b.build(Menu.Kind.CRAFTER, windowId, ContainerType.CRAFTER_3x3, windowId, type, 9, 9);
            }
            case LECTERN -> {
                // The book is shown read-only (it lives in the lectern block entity).
                b.result();
                return b.build(Menu.Kind.LECTERN, windowId, ContainerType.LECTERN, windowId, type, 1, 1);
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * A horse-like mount screen ({@code UpdateEquipPacket}): Java slot 0 saddle, 1 body armour, then
     * the chest. Bedrock storage indices: the equipment slots first, then the chest.
     *
     * @param saddle  Bedrock index of the saddle slot, or -1
     * @param armor   Bedrock index of the body armour / carpet slot, or -1
     */
    static Menu mount(Mirror mirror, int windowId, int saddle, int armor, int chestStart, int chestSize) {
        BedrockContainer window = mirror.window;
        Builder b = new Builder();
        // Java always has both equipment slots; a missing one shows an always empty index nothing can go in.
        b.window(window, saddle >= 0 ? saddle : NO_SLOT, ContainerEnumName.HORSE_EQUIP_CONTAINER,
                saddle >= 0 ? Menu.Rule.SADDLE : Menu.Rule.OUTPUT);
        b.window(window, armor >= 0 ? armor : NO_SLOT + 1, ContainerEnumName.HORSE_EQUIP_CONTAINER,
                armor >= 0 ? Menu.Rule.BODY_ARMOR : Menu.Rule.OUTPUT);
        for (int i = 0; i < chestSize; i++) {
            b.window(window, chestStart + i, ContainerEnumName.LEVEL_ENTITY_CONTAINER, Menu.Rule.ANY);
        }
        b.player(mirror.player);
        int containerSlots = 2 + chestSize;
        return b.build(Menu.Kind.MOUNT, windowId, null, windowId,
                org.cloudburstmc.protocol.bedrock.data.inventory.ContainerType.HORSE, containerSlots, containerSlots);
    }

    private static Builder storage(Builder b, BedrockContainer window, int size, ContainerEnumName name) {
        for (int i = 0; i < size; i++) {
            b.window(window, i, name, Menu.Rule.ANY);
        }
        return b;
    }
}
