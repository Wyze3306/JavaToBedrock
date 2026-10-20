package io.github.wyze3306.jtb.inventory;

import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A screen as the Java client sees it: Java window id and menu type, and for every Java slot the
 * Bedrock slot it shows ({@code null} for result slots, whose content the proxy computes because the
 * Java client never does). Player inventory slots of a container screen come last: 27 main inventory
 * slots then 9 hotbar slots, like vanilla.
 * <p>
 * Also keeps what was last sent for each slot, so only changes are sent, and the state of the
 * special screens (selected stonecutter recipe, loom pattern, trade, anvil name, enchant options).
 */
final class Menu {

    enum Kind {
        PLAYER, GENERIC, SHULKER, HOPPER, DISPENSER, CRAFTING, FURNACE, BREWING, ENCHANTING, ANVIL, GRINDSTONE,
        STONECUTTER, LOOM, CARTOGRAPHY, SMITHING, BEACON, MERCHANT, CRAFTER, MOUNT, LECTERN
    }

    /** What a slot accepts, as far as the click simulation needs to know. */
    enum Rule {
        ANY, OUTPUT, HEAD, CHEST, LEGS, FEET, OFFHAND, FUEL, POTION, BREWING_FUEL, BREWING_INGREDIENT, LAPIS, SINGLE,
        PAYMENT, SADDLE, BODY_ARMOR, BANNER, DYE, BANNER_PATTERN, MAP, MAP_ADDITIONAL, SMITHING_TEMPLATE, SMITHING_BASE,
        SMITHING_ADDITION
    }

    final Kind kind;
    final int javaId;
    final @Nullable ContainerType javaType;
    /** Bedrock window id, or -1 for the player's own inventory. */
    final int bedrockWindowId;
    final org.cloudburstmc.protocol.bedrock.data.inventory.ContainerType bedrockType;
    final @Nullable SlotRef[] slots;
    final Rule[] rules;
    /** Java slots before the player inventory part. */
    final int containerSlots;
    /** Java slot of the computed result, or -1. */
    final int resultSlot;
    /** First Java slot of the player main inventory (hotbar follows 27 slots later). */
    final int playerStart;

    private final @Nullable ItemStack[] lastSent;
    private int stateId;
    boolean pendingClose;
    /** Bedrock item shown in {@link #resultSlot}. */
    ItemData result = ItemData.AIR;
    /** What taking {@link #result} sends to the server, or null when nothing can be taken. */
    @Nullable Craft craft;
    /** Java form of {@link #result}, computed when {@code resultSource} differs from it. */
    @Nullable ItemData resultSource;
    @Nullable ItemStack resultJava;
    /** Block position of the container (crafter slot toggles, lectern pages), or null. */
    @Nullable Vector3i position;

    // ---- special screens
    int selectedRecipe = -1;
    int selectedPattern = -1;
    int selectedTrade;
    String renameText = "";
    final List<org.cloudburstmc.protocol.bedrock.data.inventory.ItemEnchantOption> enchantOptions = new ArrayList<>();
    @Nullable Merchant merchant;
    /** Entity runtime id of a mount or merchant, else -1. */
    long entityId = -1;
    /** Anvil level cost last sent to Java, or -1. */
    int anvilCost = -1;
    int lecternPage;
    int lecternPages;

    Menu(Kind kind, int javaId, @Nullable ContainerType javaType, int bedrockWindowId,
         org.cloudburstmc.protocol.bedrock.data.inventory.ContainerType bedrockType,
         @Nullable SlotRef[] slots, Rule[] rules, int containerSlots, int resultSlot, int playerStart) {
        this.kind = kind;
        this.javaId = javaId;
        this.javaType = javaType;
        this.bedrockWindowId = bedrockWindowId;
        this.bedrockType = bedrockType;
        this.slots = slots;
        this.rules = rules;
        this.containerSlots = containerSlots;
        this.resultSlot = resultSlot;
        this.playerStart = playerStart;
        this.lastSent = new ItemStack[slots.length];
    }

    int size() {
        return this.slots.length;
    }

    @Nullable SlotRef slot(int javaSlot) {
        return javaSlot >= 0 && javaSlot < this.slots.length ? this.slots[javaSlot] : null;
    }

    boolean isResult(int javaSlot) {
        return javaSlot == this.resultSlot && javaSlot >= 0;
    }

    /** Java slot of hotbar slot {@code index} (0-8). */
    int hotbarSlot(int index) {
        return this.playerStart + 27 + index;
    }

    /** Java slot showing {@code ref}, or -1. */
    int javaSlotOf(BedrockContainer container, int index) {
        for (int i = 0; i < this.slots.length; i++) {
            SlotRef slot = this.slots[i];
            if (slot != null && slot.container() == container && slot.index() == index) {
                return i;
            }
        }
        return -1;
    }

    @Nullable ItemStack lastSent(int slot) {
        return this.lastSent[slot];
    }

    void setLastSent(int slot, @Nullable ItemStack stack) {
        this.lastSent[slot] = stack;
    }

    void forgetSent() {
        java.util.Arrays.fill(this.lastSent, null);
    }

    int nextStateId() {
        this.stateId = (this.stateId + 1) & 0x7FFF;
        return this.stateId;
    }

    int stateId() {
        return this.stateId;
    }
}
