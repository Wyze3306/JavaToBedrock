package io.github.wyze3306.jtb.inventory;

import io.github.wyze3306.jtb.item.ItemMappings;
import io.github.wyze3306.jtb.item.ItemTranslator;
import io.github.wyze3306.jtb.session.ProxySession;
import it.unimi.dsi.fastutil.ints.IntCollection;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.DropAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.PlaceAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.TakeAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.Equippable;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a Java click the way the vanilla server does ({@code AbstractContainerMenu.doClick} and each
 * menu's {@code quickMoveStack}) on the mirror, and records every elementary item move as a Bedrock
 * request in a {@link Transaction}. The server's answer, not this simulation, decides the final
 * contents: the Java client is resynchronised from the mirror afterwards.
 */
final class ClickHandler {

    private final InventoryState state;
    private final ProxySession session;
    private final Menu menu;
    private final Mirror mirror;
    private final SlotRef cursor;
    private final Transaction tx;

    ClickHandler(InventoryState state, Menu menu, Transaction tx) {
        this.state = state;
        this.session = state.session();
        this.menu = menu;
        this.mirror = state.mirror();
        this.cursor = this.mirror.cursor;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ click modes

    /** PICKUP (mode 0): left (button 0) or right (button 1) click; slot -999 drops the cursor. */
    void pickup(int slot, int button) {
        ItemData carried = this.tx.get(this.cursor);
        if (slot == InventoryState.OUTSIDE) {
            if (!ItemTranslator.isAir(carried)) {
                this.tx.drop(this.cursor, button == 0 ? carried.getCount() : 1);
            }
            return;
        }
        if (this.menu.isResult(slot)) {
            takeResult(Destination.CURSOR, null, false);
            return;
        }
        SlotRef ref = this.menu.slot(slot);
        if (ref == null) {
            return;
        }
        ItemData item = this.tx.get(ref);
        if (ItemTranslator.isAir(item)) {
            if (!ItemTranslator.isAir(carried) && mayPlace(slot, carried)) {
                int amount = Math.min(button == 0 ? carried.getCount() : 1, maxStack(slot, carried));
                this.tx.transfer(this.cursor, ref, amount, false);
            }
        } else if (ItemTranslator.isAir(carried)) {
            int amount = button == 0 ? item.getCount() : (item.getCount() + 1) / 2;
            this.tx.transfer(ref, this.cursor, amount, true);
        } else if (mayPlace(slot, carried)) {
            if (ItemTranslator.stackable(item, carried)) {
                int amount = Math.min(button == 0 ? carried.getCount() : 1, maxStack(slot, carried) - item.getCount());
                if (amount > 0) {
                    this.tx.transfer(this.cursor, ref, amount, false);
                }
            } else if (carried.getCount() <= maxStack(slot, carried)) {
                this.tx.swap(ref, this.cursor);
            }
        } else if (ItemTranslator.stackable(item, carried)) {
            // An output slot: the cursor takes what it can hold, all or nothing.
            int room = ItemTranslator.maxStackSize(this.session, carried) - carried.getCount();
            if (room >= item.getCount()) {
                this.tx.transfer(ref, this.cursor, item.getCount(), true);
            }
        }
    }

    /** QUICK_MOVE (mode 1): shift-click. */
    void quickMove(int slot) {
        if (slot < 0) {
            return;
        }
        if (this.menu.isResult(slot)) {
            takeResult(Destination.INVENTORY, null, false);
            return;
        }
        SlotRef ref = this.menu.slot(slot);
        if (ref == null || ItemTranslator.isAir(this.tx.get(ref))) {
            return;
        }
        quickMoveStack(slot, ref);
    }

    /** SWAP (mode 2): number keys 1-9 (button 0-8) and F (button 40) on a slot. */
    void swap(int slot, int button) {
        SlotRef source = button == 40 ? this.mirror.offhandSlot : button >= 0 && button < 9 ? this.mirror.hotbar(button) : null;
        if (source == null) {
            return;
        }
        if (this.menu.isResult(slot)) {
            if (ItemTranslator.isAir(this.tx.get(source))) {
                takeResult(Destination.SLOT, source, false);
            }
            return;
        }
        SlotRef target = this.menu.slot(slot);
        if (target == null || target.sameSlot(source)) {
            return;
        }
        ItemData sourceItem = this.tx.get(source);
        ItemData targetItem = this.tx.get(target);
        if (ItemTranslator.isAir(sourceItem) && ItemTranslator.isAir(targetItem)) {
            return;
        }
        if (ItemTranslator.isAir(sourceItem)) {
            this.tx.swap(target, source);
        } else if (mayPlace(slot, sourceItem)) {
            int max = maxStack(slot, sourceItem);
            if (sourceItem.getCount() <= max) {
                this.tx.swap(target, source);
            } else if (ItemTranslator.isAir(targetItem)) {
                this.tx.transfer(source, target, max, false);
            }
        }
    }

    /** CLONE (mode 3): middle click in creative mode, a full stack of the slot's item on the cursor. */
    void cloneStack(int slot) {
        if (!this.state.isCreative() || !ItemTranslator.isAir(this.tx.get(this.cursor))) {
            return;
        }
        ItemData item = this.menu.isResult(slot) ? this.menu.result : slotItem(slot);
        if (!ItemTranslator.isAir(item)) {
            this.state.creative().create(this.tx, item, ItemTranslator.maxStackSize(this.session, item), this.cursor);
        }
    }

    /** THROW (mode 4): Q (button 0, one item) and Ctrl+Q (button 1, the stack) over a slot. */
    void throwItem(int slot, int button) {
        if (slot < 0 || !ItemTranslator.isAir(this.tx.get(this.cursor))) {
            return;
        }
        if (this.menu.isResult(slot)) {
            takeResult(Destination.DROP, null, button == 1);
            return;
        }
        SlotRef ref = this.menu.slot(slot);
        if (ref == null) {
            return;
        }
        ItemData item = this.tx.get(ref);
        if (!ItemTranslator.isAir(item)) {
            this.tx.drop(ref, button == 0 ? 1 : item.getCount());
        }
    }

    /** PICKUP_ALL (mode 6): double click gathering matching stacks into the cursor. */
    void pickupAll(int slot, int button) {
        ItemData carried = this.tx.get(this.cursor);
        if (ItemTranslator.isAir(carried) || slot < 0) {
            return;
        }
        SlotRef clicked = this.menu.slot(slot);
        if (clicked != null && !ItemTranslator.isAir(this.tx.get(clicked))) {
            return;
        }
        int max = ItemTranslator.maxStackSize(this.session, carried);
        int size = this.menu.size();
        for (int pass = 0; pass < 2; pass++) {
            for (int n = 0; n < size; n++) {
                int i = button == 0 ? n : size - 1 - n;
                carried = this.tx.get(this.cursor);
                if (carried.getCount() >= max) {
                    return;
                }
                SlotRef ref = this.menu.slot(i);
                if (ref == null || this.menu.isResult(i)) {
                    continue;
                }
                ItemData item = this.tx.get(ref);
                if (ItemTranslator.stackable(item, carried)
                        && (pass != 0 || item.getCount() != ItemTranslator.maxStackSize(this.session, item))) {
                    this.tx.transfer(ref, this.cursor, Math.min(item.getCount(), max - carried.getCount()), true);
                }
            }
        }
    }

    /**
     * QUICK_CRAFT (mode 5) end: drag distribution over the collected slots. Type 0 splits the cursor
     * evenly, 1 places one item per slot, 2 (creative) fills each slot.
     */
    void quickCraft(IntCollection slots, int type) {
        ItemData carried = this.tx.get(this.cursor);
        if (ItemTranslator.isAir(carried) || slots.isEmpty()) {
            return;
        }
        if (slots.size() == 1) {
            pickup(slots.iterator().nextInt(), type == 1 ? 1 : 0);
            return;
        }
        int originalCount = carried.getCount();
        int max = ItemTranslator.maxStackSize(this.session, carried);
        int perSlot = switch (type) {
            case 0 -> originalCount / slots.size();
            case 1 -> 1;
            default -> max;
        };
        for (int slot : slots) {
            SlotRef ref = this.menu.slot(slot);
            if (ref == null || this.menu.isResult(slot) || !mayPlace(slot, carried)) {
                continue;
            }
            ItemData existing = this.tx.get(ref);
            if (!ItemTranslator.isAir(existing) && !ItemTranslator.stackable(existing, carried)) {
                continue;
            }
            if (type != 2 && originalCount < slots.size()) {
                continue;
            }
            int existingCount = ItemTranslator.isAir(existing) ? 0 : existing.getCount();
            int target = Math.min(perSlot + existingCount, Math.min(max, maxStack(slot, carried)));
            int amount = target - existingCount;
            if (amount <= 0) {
                continue;
            }
            if (type == 2) {
                this.state.creative().create(this.tx, carried, amount, ref);
            } else {
                this.tx.transfer(this.cursor, ref, Math.min(amount, this.tx.get(this.cursor).getCount()), false);
            }
        }
    }

    // ------------------------------------------------------------------ quick move

    private void quickMoveStack(int index, SlotRef ref) {
        ItemData stack = this.tx.get(ref);
        int containerSlots = this.menu.containerSlots;
        int invStart = this.menu.playerStart;
        int hotbarStart = invStart + 27;
        int end = hotbarStart + 9;
        switch (this.menu.kind) {
            case PLAYER -> {
                EquipmentSlot equip = equipmentSlot(stack);
                int armorSlot = equip == null ? -1 : switch (equip) {
                    case HELMET -> 5;
                    case CHESTPLATE -> 6;
                    case LEGGINGS -> 7;
                    case BOOTS -> 8;
                    default -> -1;
                };
                if (index >= 1 && index < 9) {
                    move(ref, 9, 45, false);
                } else if (armorSlot >= 0 && isEmpty(armorSlot)) {
                    move(ref, armorSlot, armorSlot + 1, false);
                } else if (equip == EquipmentSlot.OFF_HAND && isEmpty(45)) {
                    move(ref, 45, 46, false);
                } else if (index >= 9 && index < 36) {
                    move(ref, 36, 45, false);
                } else if (index >= 36 && index < 45) {
                    move(ref, 9, 36, false);
                } else {
                    move(ref, 9, 45, false);
                }
            }
            case GENERIC, SHULKER, HOPPER, DISPENSER -> {
                if (index < containerSlots) {
                    move(ref, containerSlots, end, true);
                } else {
                    move(ref, 0, containerSlots, false);
                }
            }
            case CRAFTING -> {
                if (index < containerSlots) {
                    move(ref, invStart, end, false);
                } else if (move(ref, 1, 10, false) == 0) {
                    inventoryHalves(ref, index, invStart);
                }
            }
            case CRAFTER -> {
                if (index < containerSlots) {
                    move(ref, invStart, end, true);
                } else if (move(ref, 0, 9, false) == 0) {
                    inventoryHalves(ref, index, invStart);
                }
            }
            case FURNACE -> {
                if (index < containerSlots) {
                    move(ref, invStart, end, index == 2);
                } else if (this.state.crafting().cookable(cookingStation(), stack)) {
                    if (move(ref, 0, 1, false) == 0) {
                        inventoryHalves(ref, index, invStart);
                    }
                } else if (mayPlace(1, stack)) {
                    if (move(ref, 1, 2, false) == 0) {
                        inventoryHalves(ref, index, invStart);
                    }
                } else {
                    inventoryHalves(ref, index, invStart);
                }
            }
            case BREWING -> {
                if (index < containerSlots) {
                    move(ref, invStart, end, true);
                } else if (mayPlace(4, stack) && move(ref, 4, 5, false) > 0) {
                    return;
                } else if (mayPlace(3, stack)) {
                    move(ref, 3, 4, false);
                } else if (mayPlace(0, stack)) {
                    move(ref, 0, 3, false);
                } else {
                    inventoryHalves(ref, index, invStart);
                }
            }
            case ENCHANTING -> {
                if (index < containerSlots) {
                    move(ref, invStart, end, true);
                } else if (mayPlace(1, stack)) {
                    move(ref, 1, 2, true);
                } else if (isEmpty(0)) {
                    this.tx.transfer(ref, slotRef(0), 1, false);
                }
            }
            case BEACON -> {
                if (index < containerSlots) {
                    move(ref, invStart, end, true);
                } else if (mayPlace(0, stack) && isEmpty(0)) {
                    this.tx.transfer(ref, slotRef(0), 1, false);
                } else {
                    inventoryHalves(ref, index, invStart);
                }
            }
            case MERCHANT -> {
                if (index < containerSlots) {
                    move(ref, invStart, end, true);
                } else {
                    inventoryHalves(ref, index, invStart);
                }
            }
            case MOUNT -> {
                if (index < containerSlots) {
                    move(ref, invStart, end, false);
                } else if (mayPlace(0, stack) && isEmpty(0)) {
                    move(ref, 0, 1, false);
                } else if (mayPlace(1, stack) && isEmpty(1)) {
                    move(ref, 1, 2, false);
                } else if (containerSlots <= 2 || move(ref, 2, containerSlots, false) == 0) {
                    inventoryHalves(ref, index, invStart);
                }
            }
            default -> {
                // Item combiner screens (anvil, grindstone, smithing, loom, cartography, stonecutter).
                if (index < containerSlots) {
                    move(ref, invStart, end, true);
                } else {
                    int moved = 0;
                    for (int i = 0; i < containerSlots && moved == 0; i++) {
                        if (!this.menu.isResult(i) && mayPlace(i, stack)) {
                            moved = move(ref, i, i + 1, false);
                        }
                    }
                    if (moved == 0) {
                        inventoryHalves(ref, index, invStart);
                    }
                }
            }
        }
    }

    /** Main inventory to hotbar and back, the fallback of every menu. */
    private void inventoryHalves(SlotRef ref, int index, int invStart) {
        if (index >= invStart && index < invStart + 27) {
            move(ref, invStart + 27, invStart + 36, false);
        } else if (index >= invStart + 27 && index < invStart + 36) {
            move(ref, invStart, invStart + 27, false);
        }
    }

    /**
     * Vanilla {@code moveItemStackTo}: first tops up partial stacks of the same item, then fills the
     * first empty slot that accepts it.
     *
     * @return the number of items moved
     */
    private int move(SlotRef from, int start, int end, boolean reverse) {
        int moved = 0;
        ItemData stack = this.tx.get(from);
        int itemMax = ItemTranslator.maxStackSize(this.session, stack);
        if (itemMax > 1) {
            for (int n = start; n < end; n++) {
                stack = this.tx.get(from);
                if (ItemTranslator.isAir(stack)) {
                    return moved;
                }
                int i = reverse ? end - 1 - (n - start) : n;
                SlotRef target = slotRef(i);
                if (target == null || this.menu.isResult(i) || target.sameSlot(from)) {
                    continue;
                }
                ItemData existing = this.tx.get(target);
                if (ItemTranslator.stackable(existing, stack)) {
                    int max = maxStack(i, existing);
                    int amount = Math.min(stack.getCount(), max - existing.getCount());
                    if (amount > 0) {
                        this.tx.transfer(from, target, amount, false);
                        moved += amount;
                    }
                }
            }
        }
        stack = this.tx.get(from);
        if (ItemTranslator.isAir(stack)) {
            return moved;
        }
        for (int n = start; n < end; n++) {
            int i = reverse ? end - 1 - (n - start) : n;
            SlotRef target = slotRef(i);
            if (target == null || this.menu.isResult(i) || target.sameSlot(from)) {
                continue;
            }
            if (ItemTranslator.isAir(this.tx.get(target)) && mayPlace(i, stack)) {
                int amount = Math.min(stack.getCount(), maxStack(i, stack));
                this.tx.transfer(from, target, amount, false);
                return moved + amount;
            }
        }
        return moved;
    }

    // ------------------------------------------------------------------ results

    enum Destination { CURSOR, INVENTORY, SLOT, DROP }

    /**
     * Takes the computed result: one request with the craft actions, the CONSUME actions of the inputs
     * and the transfer(s) out of the created output into the destination.
     */
    void takeResult(Destination destination, @Nullable SlotRef slot, boolean all) {
        Craft craft = this.menu.craft;
        ItemData result = this.menu.result;
        if (craft == null || ItemTranslator.isAir(result)) {
            return;
        }
        SlotRef output = this.mirror.createdOutput;
        int perCraft = result.getCount();
        List<Transaction.Change> changes = new ArrayList<>();
        List<ItemStackRequestAction> transfers = new ArrayList<>();
        int times;
        switch (destination) {
            case CURSOR -> {
                ItemData carried = this.tx.get(this.cursor);
                ItemData newCursor;
                if (ItemTranslator.isAir(carried)) {
                    newCursor = ItemTranslator.withNetId(result, 0);
                } else if (ItemTranslator.stackable(carried, result)
                        && carried.getCount() + perCraft <= ItemTranslator.maxStackSize(this.session, carried)) {
                    newCursor = ItemTranslator.withCount(carried, carried.getCount() + perCraft);
                } else {
                    return;
                }
                times = 1;
                transfers.add(new TakeAction(perCraft, output.info(0), this.cursor.info(this.tx.netId(this.cursor))));
                changes.add(new Transaction.Change(this.cursor, newCursor));
            }
            case SLOT -> {
                times = 1;
                transfers.add(new PlaceAction(perCraft, output.info(0), slot.info(this.tx.netId(slot))));
                changes.add(new Transaction.Change(slot, ItemTranslator.withNetId(result, 0)));
            }
            case DROP -> {
                times = all ? craft.maxTimes() : 1;
                transfers.add(new DropAction(perCraft * times, output.info(0), false));
            }
            default -> {
                times = distribute(craft, result, transfers, changes);
                if (times == 0) {
                    return;
                }
            }
        }
        List<ItemStackRequestAction> actions = new ArrayList<>(craft.actions(this.tx, times));
        actions.addAll(transfers);
        List<Transaction.Change> all2 = new ArrayList<>(craft.consumption(this.tx, times));
        all2.addAll(changes);
        this.tx.request(actions, craft.filterStrings(), all2);
    }

    /**
     * Shift-click on a result: crafts as long as the result fits in the player inventory (vanilla puts
     * results in the inventory from the end, i.e. the hotbar first), and drops what does not fit.
     *
     * @return the number of crafts
     */
    private int distribute(Craft craft, ItemData result, List<ItemStackRequestAction> transfers, List<Transaction.Change> changes) {
        SlotRef output = this.mirror.createdOutput;
        int invStart = this.menu.playerStart;
        int end = invStart + 36;
        int perCraft = result.getCount();
        int max = ItemTranslator.maxStackSize(this.session, result);
        // Simulated contents of the player slots while crafting repeatedly.
        int size = this.menu.size();
        ItemData[] simulated = new ItemData[size];
        for (int i = invStart; i < end; i++) {
            simulated[i] = this.tx.get(slotRef(i));
        }
        int[] added = new int[size];
        int times = 0;
        int dropped = 0;
        int limit = Math.min(craft.maxTimes(), 64);
        while (times < limit) {
            int left = perCraft;
            for (int n = end - 1; n >= invStart && left > 0; n--) {
                if (ItemTranslator.stackable(simulated[n], result) && simulated[n].getCount() < maxStack(n, result)) {
                    int amount = Math.min(left, Math.min(max, maxStack(n, result)) - simulated[n].getCount());
                    simulated[n] = ItemTranslator.withCount(simulated[n], simulated[n].getCount() + amount);
                    added[n] += amount;
                    left -= amount;
                }
            }
            for (int n = end - 1; n >= invStart && left > 0; n--) {
                if (ItemTranslator.isAir(simulated[n])) {
                    int amount = Math.min(left, maxStack(n, result));
                    simulated[n] = ItemTranslator.withNetId(ItemTranslator.withCount(result, amount), 0);
                    added[n] += amount;
                    left -= amount;
                }
            }
            if (left == perCraft) {
                break;
            }
            times++;
            if (left > 0) {
                dropped = left;
                break;
            }
        }
        if (times == 0) {
            return 0;
        }
        for (int n = end - 1; n >= invStart; n--) {
            if (added[n] > 0) {
                SlotRef target = slotRef(n);
                transfers.add(new PlaceAction(added[n], output.info(0), target.info(this.tx.netId(target))));
                changes.add(new Transaction.Change(target, simulated[n]));
            }
        }
        if (dropped > 0) {
            transfers.add(new DropAction(dropped, output.info(0), false));
        }
        return times;
    }

    // ------------------------------------------------------------------ slot rules

    private @Nullable SlotRef slotRef(int javaSlot) {
        return this.menu.slot(javaSlot);
    }

    private ItemData slotItem(int javaSlot) {
        SlotRef ref = this.menu.slot(javaSlot);
        return ref == null ? ItemData.AIR : this.tx.get(ref);
    }

    private boolean isEmpty(int javaSlot) {
        return ItemTranslator.isAir(slotItem(javaSlot));
    }

    private String cookingStation() {
        return switch (this.menu.bedrockType) {
            case BLAST_FURNACE -> "blast_furnace";
            case SMOKER -> "smoker";
            default -> "furnace";
        };
    }

    /** Maximum count of {@code item} in a Java slot (vanilla {@code Slot.getMaxStackSize(stack)}). */
    int maxStack(int javaSlot, ItemData item) {
        int max = ItemTranslator.maxStackSize(this.session, item);
        return switch (this.menu.rules[javaSlot]) {
            case HEAD, CHEST, LEGS, FEET, POTION, SINGLE, PAYMENT, SADDLE, BODY_ARMOR -> Math.min(max, 1);
            default -> max;
        };
    }

    /** Whether a Java slot accepts the item (vanilla {@code Slot.mayPlace}). */
    boolean mayPlace(int javaSlot, ItemData item) {
        if (javaSlot < 0 || javaSlot >= this.menu.size() || this.menu.isResult(javaSlot)) {
            return false;
        }
        ItemMappings.JavaItem java = ItemTranslator.javaItem(this.session, item);
        String path = java.key().value();
        return switch (this.menu.rules[javaSlot]) {
            case ANY, SINGLE, OFFHAND -> true;
            case OUTPUT -> false;
            case HEAD -> equipmentSlot(item) == EquipmentSlot.HELMET;
            case CHEST -> equipmentSlot(item) == EquipmentSlot.CHESTPLATE;
            case LEGS -> equipmentSlot(item) == EquipmentSlot.LEGGINGS;
            case FEET -> equipmentSlot(item) == EquipmentSlot.BOOTS;
            case FUEL -> java.fuel() || path.equals("bucket");
            case POTION -> path.equals("potion") || path.equals("splash_potion") || path.equals("lingering_potion")
                    || path.equals("glass_bottle");
            case BREWING_FUEL -> java.brewingFuel();
            case BREWING_INGREDIENT -> this.state.crafting().brewingReagent(item);
            case LAPIS -> path.equals("lapis_lazuli");
            case PAYMENT -> path.equals("iron_ingot") || path.equals("gold_ingot") || path.equals("diamond")
                    || path.equals("emerald") || path.equals("netherite_ingot");
            case SADDLE -> path.equals("saddle");
            case BODY_ARMOR -> java.equipSlot() == 5 || path.endsWith("_carpet");
            case BANNER -> path.endsWith("_banner");
            case DYE -> Results.dyeColor(this.session, item) >= 0;
            case BANNER_PATTERN -> path.endsWith("_banner_pattern");
            case MAP -> path.equals("filled_map");
            case MAP_ADDITIONAL -> path.equals("paper") || path.equals("map") || path.equals("glass_pane");
            case SMITHING_TEMPLATE, SMITHING_BASE, SMITHING_ADDITION -> true;
        };
    }

    /** The equipment slot of an item: vanilla armour from the Java defaults, custom items from their components. */
    @Nullable EquipmentSlot equipmentSlot(ItemData item) {
        if (ItemTranslator.isAir(item)) {
            return null;
        }
        EquipmentSlot slot = ItemTranslator.javaItem(this.session, item).armorSlot();
        if (slot != null) {
            return slot;
        }
        ItemStack java = ItemTranslator.toJava(this.session, item);
        Equippable equippable = java == null || java.getDataComponentsPatch() == null ? null
                : java.getDataComponentsPatch().get(DataComponentTypes.EQUIPPABLE);
        return equippable == null ? null : equippable.slot();
    }
}
