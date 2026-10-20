package io.github.wyze3306.jtb.e2e.scenario;

import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.Equippable;

/**
 * Server-defined (component-based) items: needs the PowerNukkitX test plugin {@code JtbE2eItems}
 * (items {@code jtb:ruby} and {@code jtb:ruby_helmet}, see {@code ResourcePackScenario}) in the
 * server's {@code plugins} directory, and an operator tester. Checks how they look on Java (base
 * item, name, stack size, equipment) and that clicks move them losslessly on the server (shift-click
 * into the helmet slot, split, merge). Run it for 40 s.
 */
public final class InventoryCustomItemsScenario extends InventoryScript {

    private final int paper = id("paper");

    @Override
    protected void script() {
        step("give", 30, () -> {
            command("give " + this.name + " jtb:ruby 5");
            command("give " + this.name + " jtb:ruby_helmet 1");
        });
        expect("custom item shown as its base item with its identity", () -> count(item(0, 36), this.paper) == 5
                && "jtb:ruby".equals(identity(item(0, 36))));
        expect("custom item has a name", () -> !plain(component(item(0, 36), DataComponentTypes.ITEM_NAME)).isEmpty());
        expect("custom wearable: head equipment, one per stack", () -> count(item(0, 37), this.paper) == 1
                && "jtb:ruby_helmet".equals(identity(item(0, 37))) && equipmentSlot(item(0, 37)) == EquipmentSlot.HELMET
                && Integer.valueOf(1).equals(component(item(0, 37), DataComponentTypes.MAX_STACK_SIZE)));
        step("equip", 15, () -> click(0, 37, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK));
        expect("shift click puts the custom helmet on the head", () -> "jtb:ruby_helmet".equals(identity(item(0, 5))) && empty(0, 37));
        step("split", 2, () -> click(0, 36, ContainerActionType.CLICK_ITEM, ClickItemAction.RIGHT_CLICK));
        step("place half", 15, () -> click(0, 20, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        expect("custom stack split: 2 + 3", () -> count(item(0, 36), this.paper) == 2
                && count(item(0, 20), this.paper) == 3 && "jtb:ruby".equals(identity(item(0, 20))));
        step("merge pickup", 2, () -> click(0, 20, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("merge", 15, () -> click(0, 36, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        expect("custom stacks merge back", () -> count(item(0, 36), this.paper) == 5 && empty(0, 20));
        step("close inventory", 10, this::close);
        serverCount("jtb:ruby", 5);
        step("done", 1, () -> this.context.log("Custom items scenario finished"));
    }

    private static <T> T component(ItemStack item, DataComponentType<T> type) {
        DataComponents components = item == null ? null : item.getDataComponentsPatch();
        return components == null ? null : components.get(type);
    }

    private static String identity(ItemStack item) {
        NbtMap custom = component(item, DataComponentTypes.CUSTOM_DATA);
        NbtMap jtb = custom == null ? null : custom.getCompound("jtb", null);
        return jtb == null ? null : jtb.getString("id", null);
    }

    private static EquipmentSlot equipmentSlot(ItemStack item) {
        Equippable equippable = component(item, DataComponentTypes.EQUIPPABLE);
        return equippable == null ? null : equippable.slot();
    }
}
