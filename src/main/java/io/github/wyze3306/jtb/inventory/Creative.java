package io.github.wyze3306.jtb.inventory;

import io.github.wyze3306.jtb.item.ItemTranslator;
import io.github.wyze3306.jtb.session.ProxySession;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftCreativeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.DestroyAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.DropAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.PlaceAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.TakeAction;
import org.cloudburstmc.protocol.bedrock.data.payload.creative.CreativeItemEntryPayload;
import org.cloudburstmc.protocol.bedrock.packet.CreativeContentPacket;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Creative mode: the server's creative items ({@code CreativeContentPacket}) and the translation of
 * Java's {@code SetCreativeModeSlot}.
 * <p>
 * The Java creative screen does not send clicks: it sends the new content of every slot it changed,
 * and keeps the carried item to itself. To stay lossless, an item leaving a slot is parked in the
 * Bedrock cursor (TAKE), and a slot receiving an item takes it back from there when it is the same
 * item; anything else is created with {@code CRAFT_CREATIVE} from the matching creative entry (which
 * gives a full stack, trimmed with DESTROY) or deleted with DESTROY. Items with NBT that no creative
 * entry has cannot be created on the server and are refused (the slot is resynchronised).
 */
final class Creative {

    private final ProxySession session;
    private final SlotRef createdOutput;
    private final Int2ObjectMap<ItemData> items = new Int2ObjectOpenHashMap<>();
    private final List<CreativeItemEntryPayload> entries = new ArrayList<>();
    /** Java variant key (see {@link ItemTranslator#variantKey}) to creative net id. */
    private final Object2IntMap<String> byJavaVariant = new Object2IntOpenHashMap<>();
    private boolean javaIndexed;

    Creative(ProxySession session, SlotRef createdOutput) {
        this.session = session;
        this.createdOutput = createdOutput;
        this.byJavaVariant.defaultReturnValue(-1);
    }

    void load(CreativeContentPacket packet) {
        this.items.clear();
        this.entries.clear();
        this.entries.addAll(packet.getEntries());
        for (CreativeItemEntryPayload entry : packet.getEntries()) {
            this.items.put(entry.getCreativeNetId().getID(), entry.getItemInstance());
        }
        this.byJavaVariant.clear();
        this.javaIndexed = false;
    }

    /** Creative net id of an exact Bedrock item (same item, aux value and NBT), or -1. */
    int netId(ItemData item) {
        for (CreativeItemEntryPayload entry : this.entries) {
            ItemData creative = entry.getItemInstance();
            if (creative.getDefinition() == item.getDefinition() && creative.getDamage() == item.getDamage()
                    && Objects.equals(emptyToNull(creative.getTag()), emptyToNull(item.getTag()))) {
                return entry.getCreativeNetId().getID();
            }
        }
        return -1;
    }

    private static @Nullable Object emptyToNull(org.cloudburstmc.nbt.@Nullable NbtMap tag) {
        return tag == null || tag.isEmpty() ? null : tag;
    }

    /** Creative net id of a vanilla Java stack (from the creative tabs), or -1. */
    int netId(ItemStack javaStack) {
        if (!this.javaIndexed) {
            for (CreativeItemEntryPayload entry : this.entries) {
                ItemStack java = ItemTranslator.toJava(this.session, entry.getItemInstance(), false);
                if (java != null) {
                    this.byJavaVariant.putIfAbsent(ItemTranslator.variantKey(java), entry.getCreativeNetId().getID());
                    this.byJavaVariant.putIfAbsent(Integer.toString(java.getId()), entry.getCreativeNetId().getID());
                }
            }
            this.javaIndexed = true;
        }
        int id = this.byJavaVariant.getInt(ItemTranslator.variantKey(javaStack));
        return id >= 0 ? id : this.byJavaVariant.getInt(Integer.toString(javaStack.getId()));
    }

    @Nullable ItemData item(int netId) {
        return this.items.get(netId);
    }

    /**
     * Puts {@code count} items like {@code item} into {@code target} from the creative inventory. The
     * server overwrites the target with a full stack, trimmed afterwards.
     *
     * @return false when no creative entry has this exact item
     */
    boolean create(Transaction tx, ItemData item, int count, SlotRef target) {
        int netId = netId(item);
        if (netId < 0) {
            return false;
        }
        ItemData creative = Objects.requireNonNull(this.items.get(netId));
        int max = ItemTranslator.maxStackSize(this.session, creative);
        count = Math.min(count, max);
        SlotRef output = this.createdOutput;
        List<ItemStackRequestAction> actions = new ArrayList<>(3);
        actions.add(new CraftCreativeAction(netId, 1));
        if (target.name() == org.cloudburstmc.protocol.bedrock.data.inventory.ContainerEnumName.CURSOR_CONTAINER) {
            actions.add(new TakeAction(count, output.info(0), target.info(tx.netId(target))));
        } else {
            actions.add(new PlaceAction(count, output.info(0), target.info(tx.netId(target))));
        }
        if (max > count) {
            actions.add(new DestroyAction(max - count, target.info(0)));
        }
        ItemData result = creative.toBuilder().count(count).usingNetId(false).netId(0).build();
        tx.request(actions, new String[0], List.of(new Transaction.Change(target, result)));
        return true;
    }

    /** Drops {@code count} items like {@code item} from the creative inventory. */
    boolean createAndDrop(Transaction tx, ItemData item, int count) {
        int netId = netId(item);
        if (netId < 0) {
            return false;
        }
        tx.request(List.of(new CraftCreativeAction(netId, 1), new DropAction(count, this.createdOutput.info(0), false)),
                new String[0], List.of());
        return true;
    }

    /**
     * Java {@code SetCreativeModeSlot}: makes the slot hold {@code wanted} (air to empty it), parking or
     * reusing items in the Bedrock cursor so that moves inside the creative screen keep the exact items.
     */
    void setSlot(Transaction tx, SlotRef slot, ItemData wanted, SlotRef cursor) {
        ItemData current = tx.get(slot);
        if (sameStack(current, wanted)) {
            return;
        }
        ItemData parked = tx.get(cursor);
        if (!ItemTranslator.isAir(current) && !ItemTranslator.stackable(current, wanted)) {
            // The current item leaves the slot (to the Java cursor, or deleted).
            if (!ItemTranslator.isAir(parked) && !ItemTranslator.isAir(wanted)
                    && ItemTranslator.stackable(parked, wanted) && parked.getCount() == wanted.getCount()) {
                tx.swap(slot, cursor);
                return;
            }
            park(tx, slot, current.getCount(), cursor);
            current = tx.get(slot);
            parked = tx.get(cursor);
        }
        if (ItemTranslator.isAir(wanted)) {
            if (!ItemTranslator.isAir(current)) {
                park(tx, slot, current.getCount(), cursor);
            }
            return;
        }
        int have = ItemTranslator.isAir(current) ? 0 : current.getCount();
        int need = wanted.getCount() - have;
        if (need > 0) {
            if (ItemTranslator.stackable(parked, wanted) && parked.getCount() >= need) {
                tx.transfer(cursor, slot, need, false);
            } else if (have == 0) {
                create(tx, wanted, wanted.getCount(), slot);
            } else {
                // Grow an existing stack: recreate it whole when the creative inventory has it.
                int netId = netId(wanted);
                if (netId >= 0) {
                    create(tx, wanted, wanted.getCount(), slot);
                }
            }
        } else if (need < 0) {
            park(tx, slot, -need, cursor);
        }
    }

    /** Moves items into the Bedrock cursor, deleting what was parked there before if it is another item. */
    private void park(Transaction tx, SlotRef slot, int amount, SlotRef cursor) {
        ItemData item = tx.get(slot);
        ItemData parked = tx.get(cursor);
        if (!ItemTranslator.isAir(parked) && (!ItemTranslator.stackable(parked, item)
                || parked.getCount() + amount > ItemTranslator.maxStackSize(this.session, parked))) {
            tx.destroy(cursor, parked.getCount());
        }
        tx.transfer(slot, cursor, amount, true);
    }

    private static boolean sameStack(ItemData a, ItemData b) {
        if (ItemTranslator.isAir(a) || ItemTranslator.isAir(b)) {
            return ItemTranslator.isAir(a) && ItemTranslator.isAir(b);
        }
        return ItemTranslator.stackable(a, b) && a.getCount() == b.getCount();
    }
}
