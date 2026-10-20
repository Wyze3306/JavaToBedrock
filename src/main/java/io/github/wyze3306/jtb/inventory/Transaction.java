package io.github.wyze3306.jtb.inventory;

import io.github.wyze3306.jtb.item.ItemTranslator;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.DestroyAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.DropAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.PlaceAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.SwapAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.TakeAction;

import java.util.ArrayList;
import java.util.List;

/**
 * The Bedrock item stack requests a Java action turns into, planned on a working copy of the mirror.
 * <p>
 * Plain transfers, swaps and drops become <b>one request each</b>: PowerNukkitX applies a request's
 * actions one by one and does not roll back the earlier ones when a later one fails, and its error
 * responses say nothing about the slots, so only single-action requests keep the mirror exactly in
 * step with the server. Crafting-like operations need several actions in one request (the recipe
 * action must be in the same request as the transfer from the created output) and use
 * {@link #request}.
 * <p>
 * Stack network ids are those of the mirror; a slot already changed earlier in the transaction is sent
 * with id 0, which PowerNukkitX does not check (its new id is not known yet).
 */
final class Transaction {

    /** A predicted slot content after a request succeeded. */
    record Change(SlotRef slot, ItemData item) {
    }

    /** One planned request: its actions and the slot contents it should produce. */
    record Planned(ItemStackRequestAction[] actions, String[] filterStrings, List<Change> changes) {
    }

    private final List<Change> overlay = new ArrayList<>();
    private final List<Planned> planned = new ArrayList<>();

    /** Current content of a slot in the working copy. */
    ItemData get(SlotRef slot) {
        for (int i = this.overlay.size() - 1; i >= 0; i--) {
            Change change = this.overlay.get(i);
            if (change.slot().sameSlot(slot)) {
                return change.item();
            }
        }
        return slot.container().get(slot.index());
    }

    boolean touched(SlotRef slot) {
        for (Change change : this.overlay) {
            if (change.slot().sameSlot(slot)) {
                return true;
            }
        }
        return false;
    }

    /** Stack network id to send for a slot: the server's id, or 0 once the transaction changed it. */
    int netId(SlotRef slot) {
        if (touched(slot)) {
            return 0;
        }
        ItemData item = slot.container().get(slot.index());
        return item.isUsingNetId() ? item.getNetId() : 0;
    }

    List<Planned> planned() {
        return this.planned;
    }

    boolean isEmpty() {
        return this.planned.isEmpty();
    }

    // ------------------------------------------------------------------ single-action requests

    /**
     * Moves {@code amount} items from {@code from} into {@code to} (empty or holding the same item).
     * Sent as TAKE when the destination is the cursor, PLACE otherwise, like the Bedrock client.
     */
    void transfer(SlotRef from, SlotRef to, int amount, boolean toCursor) {
        ItemData source = get(from);
        ItemData target = get(to);
        if (amount <= 0 || ItemTranslator.isAir(source) || amount > source.getCount()) {
            return;
        }
        ItemStackRequestAction action = toCursor
                ? new TakeAction(amount, from.info(netId(from)), to.info(netId(to)))
                : new PlaceAction(amount, from.info(netId(from)), to.info(netId(to)));
        ItemData newTarget;
        if (ItemTranslator.isAir(target)) {
            // A whole stack keeps its network id on the server; a split part gets a new one.
            newTarget = amount == source.getCount() ? source : ItemTranslator.withNetId(ItemTranslator.withCount(source, amount), 0);
        } else {
            newTarget = ItemTranslator.withCount(target, target.getCount() + amount);
        }
        ItemData newSource = ItemTranslator.withCount(source, source.getCount() - amount);
        add(new ItemStackRequestAction[]{action}, new String[0], List.of(new Change(from, newSource), new Change(to, newTarget)));
    }

    /** Swaps two slots (PowerNukkitX swaps unconditionally, empty slots included). */
    void swap(SlotRef a, SlotRef b) {
        ItemData first = get(a);
        ItemData second = get(b);
        if (ItemTranslator.isAir(first) && ItemTranslator.isAir(second)) {
            return;
        }
        add(new ItemStackRequestAction[]{new SwapAction(a.info(netId(a)), b.info(netId(b)))}, new String[0],
                List.of(new Change(a, second), new Change(b, first)));
    }

    /** Drops {@code amount} items of a slot into the world. */
    void drop(SlotRef from, int amount) {
        ItemData source = get(from);
        if (amount <= 0 || ItemTranslator.isAir(source)) {
            return;
        }
        amount = Math.min(amount, source.getCount());
        add(new ItemStackRequestAction[]{new DropAction(amount, from.info(netId(from)), false)}, new String[0],
                List.of(new Change(from, ItemTranslator.withCount(source, source.getCount() - amount))));
    }

    /** Destroys items of a slot (creative mode only). */
    void destroy(SlotRef from, int amount) {
        ItemData source = get(from);
        if (amount <= 0 || ItemTranslator.isAir(source)) {
            return;
        }
        amount = Math.min(amount, source.getCount());
        add(new ItemStackRequestAction[]{new DestroyAction(amount, from.info(netId(from)))}, new String[0],
                List.of(new Change(from, ItemTranslator.withCount(source, source.getCount() - amount))));
    }

    // ------------------------------------------------------------------ multi-action requests

    /** Adds a request built elsewhere (crafting, creative...) with the slot contents it produces. */
    void request(List<ItemStackRequestAction> actions, String[] filterStrings, List<Change> changes) {
        add(actions.toArray(ItemStackRequestAction[]::new), filterStrings, changes);
    }

    /** Records a predicted change without a request of its own (part of the next {@link #request}). */
    void predict(SlotRef slot, ItemData item) {
        this.overlay.add(new Change(slot, item));
    }

    private void add(ItemStackRequestAction[] actions, String[] filterStrings, List<Change> changes) {
        this.planned.add(new Planned(actions, filterStrings, changes));
        this.overlay.addAll(changes);
    }
}
