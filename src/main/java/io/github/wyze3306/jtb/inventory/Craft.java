package io.github.wyze3306.jtb.inventory;

import io.github.wyze3306.jtb.item.ItemTranslator;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ConsumeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * How taking a computed result (crafting grid, stonecutter, anvil, trade...) is sent to the server:
 * the recipe action(s) for {@code times} crafts followed by the CONSUME actions of the inputs, and
 * what the inputs contain afterwards. The caller appends the transfers out of the created output.
 *
 * @param maxTimes  how many crafts the inputs allow (1 when the result cannot be crafted repeatedly)
 * @param recipe    the recipe action(s) for a number of crafts
 * @param inputs    the input slots and how many items each loses per craft
 */
record Craft(int maxTimes, IntFunction<List<ItemStackRequestAction>> recipe, List<SlotRef> inputs, int[] perCraft,
             String[] filterStrings) {

    /** Request actions for {@code times} crafts: recipe action(s) then one CONSUME per input. */
    List<ItemStackRequestAction> actions(Transaction tx, int times) {
        List<ItemStackRequestAction> actions = new ArrayList<>(this.recipe.apply(times));
        for (int i = 0; i < this.inputs.size(); i++) {
            SlotRef slot = this.inputs.get(i);
            int amount = Math.min(this.perCraft[i] * times, tx.get(slot).getCount());
            if (amount > 0) {
                actions.add(new ConsumeAction(amount, slot.info(tx.netId(slot))));
            }
        }
        return actions;
    }

    /** Input contents after {@code times} crafts. */
    List<Transaction.Change> consumption(Transaction tx, int times) {
        List<Transaction.Change> changes = new ArrayList<>(this.inputs.size());
        for (int i = 0; i < this.inputs.size(); i++) {
            SlotRef slot = this.inputs.get(i);
            ItemData item = tx.get(slot);
            if (this.perCraft[i] > 0 && !ItemTranslator.isAir(item)) {
                changes.add(new Transaction.Change(slot, ItemTranslator.withCount(item, item.getCount() - this.perCraft[i] * times)));
            }
        }
        return changes;
    }
}
