package io.github.wyze3306.jtb.inventory;

import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerEnumName;
import org.jspecify.annotations.Nullable;

/**
 * Every Bedrock container the proxy mirrors for one player, and the mapping from the container names
 * of item stack requests/responses to mirrored slots.
 */
final class Mirror {

    /** UI slot of the cursor. */
    static final int CURSOR = 0;
    /** UI slot of the created output (crafting, creative, trade results). */
    static final int CREATED_OUTPUT = 50;

    final BedrockContainer player = new BedrockContainer(BedrockContainer.Kind.PLAYER, 36);
    final BedrockContainer armor = new BedrockContainer(BedrockContainer.Kind.ARMOR, 4);
    final BedrockContainer offhand = new BedrockContainer(BedrockContainer.Kind.OFFHAND, 1);
    final BedrockContainer ui = new BedrockContainer(BedrockContainer.Kind.UI, 54);
    final BedrockContainer window = new BedrockContainer(BedrockContainer.Kind.WINDOW, 27);

    final SlotRef cursor = new SlotRef(this.ui, CURSOR, ContainerEnumName.CURSOR_CONTAINER, CURSOR);
    final SlotRef createdOutput = new SlotRef(this.ui, CREATED_OUTPUT, ContainerEnumName.CREATED_OUTPUT_CONTAINER, CREATED_OUTPUT);
    final SlotRef offhandSlot = new SlotRef(this.offhand, 0, ContainerEnumName.OFFHAND_CONTAINER, 1);

    SlotRef hotbar(int slot) {
        return new SlotRef(this.player, slot, ContainerEnumName.HOTBAR_CONTAINER, slot);
    }

    /** Player inventory slot 0-35 (0-8 hotbar, 9-35 main inventory). */
    SlotRef inventorySlot(int slot) {
        return slot < 9 ? hotbar(slot) : new SlotRef(this.player, slot, ContainerEnumName.INVENTORY_CONTAINER, slot);
    }

    /**
     * The mirrored slot a request/response container name and network slot designate, or null (the
     * created output, unknown names).
     */
    @Nullable SlotRef resolve(ContainerEnumName name, int slot) {
        return switch (name) {
            case HOTBAR_CONTAINER, INVENTORY_CONTAINER, COMBINED_HOTBAR_AND_INVENTORY_CONTAINER ->
                    slot >= 0 && slot < 36 ? new SlotRef(this.player, slot, name, slot) : null;
            case ARMOR_CONTAINER -> slot >= 0 && slot < 4 ? new SlotRef(this.armor, slot, name, slot) : null;
            case OFFHAND_CONTAINER -> this.offhandSlot;
            case CURSOR_CONTAINER -> this.cursor;
            case CREATED_OUTPUT_CONTAINER -> null;
            case CRAFTING_INPUT_CONTAINER, ANVIL_INPUT_CONTAINER, ANVIL_MATERIAL_CONTAINER, STONECUTTER_INPUT_CONTAINER,
                 TRADE2_INGREDIENT1_CONTAINER, TRADE2_INGREDIENT2_CONTAINER, LOOM_INPUT_CONTAINER, LOOM_DYE_CONTAINER,
                 LOOM_MATERIAL_CONTAINER, CARTOGRAPHY_INPUT_CONTAINER, CARTOGRAPHY_ADDITIONAL_CONTAINER,
                 ENCHANTING_INPUT_CONTAINER, ENCHANTING_MATERIAL_CONTAINER, GRINDSTONE_INPUT_CONTAINER,
                 GRINDSTONE_ADDITIONAL_CONTAINER, BEACON_PAYMENT_CONTAINER, SMITHING_TABLE_INPUT_CONTAINER,
                 SMITHING_TABLE_MATERIAL_CONTAINER, SMITHING_TABLE_TEMPLATE_CONTAINER ->
                    slot > 0 && slot < 54 && slot != CREATED_OUTPUT ? new SlotRef(this.ui, slot, name, slot) : null;
            case LEVEL_ENTITY_CONTAINER, BARREL_CONTAINER, SHULKER_BOX_CONTAINER, FURNACE_INGREDIENT_CONTAINER,
                 FURNACE_FUEL_CONTAINER, FURNACE_RESULT_CONTAINER, BLAST_FURNACE_INGREDIENT_CONTAINER, SMOKER_INGREDIENT_CONTAINER,
                 BREWING_STAND_INPUT_CONTAINER, BREWING_STAND_RESULT_CONTAINER, BREWING_STAND_FUEL_CONTAINER,
                 HORSE_EQUIP_CONTAINER, CRAFTER_LEVEL_ENTITY_CONTAINER ->
                    slot >= 0 && slot < 256 ? new SlotRef(this.window, slot, name, slot) : null;
            default -> null;
        };
    }
}
