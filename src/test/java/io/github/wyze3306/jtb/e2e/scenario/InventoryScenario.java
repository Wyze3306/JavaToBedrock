package io.github.wyze3306.jtb.e2e.scenario;

import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.CreativeGrabAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.FillStackAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.SpreadItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetCreativeModeSlotPacket;

/**
 * Inventories against PowerNukkitX (server-authoritative, {@code ItemStackRequest}). The tester must be
 * an operator (add it to the server's {@code ops.txt}): the scenario gives itself items with
 * {@code /give}, moves them with every kind of Java click in its own inventory (pickup, split, shift,
 * number key, drag, double click, drops), crafts in the 2x2 grid (one, then all), opens a crafting
 * table placed with {@code /setblock} and crafts sticks and a crafting table, opens a chest, stores
 * items and checks they are still there after reopening it, then creates items in creative mode.
 * <p>
 * Each step checks the Java view after the server's answer (the proxy resynchronises every touched
 * slot from the confirmed mirror) and, at key points, the server's own count with
 * {@code /clear <player> <item> 0 0}. Run it for 150 s.
 */
public final class InventoryScenario extends InventoryScript {

    private final int log = id("oak_log");
    private final int cobblestone = id("cobblestone");
    private final int planks = id("oak_planks");
    private final int stick = id("stick");
    private final int craftingTable = id("crafting_table");
    private final int diamond = id("diamond");

    private Vector3i table;
    private Vector3i chest;

    @Override
    protected void script() {
        this.table = this.feet.add(2, 0, 0);
        this.chest = this.feet.add(0, 0, 2);

        step("give", 30, () -> {
            command("give " + this.name + " oak_log 8");
            command("give " + this.name + " cobblestone 64");
        });
        step("given items arrived", 1, () -> {
            check("player inventory sent as a whole (SetContent 0) before slot updates",
                    this.playerContents > 0 && this.slotsBeforeContent == 0);
            check("given: 8 oak logs in hotbar slot 1", is(0, 36, this.log, 8));
            check("given: 64 cobblestone in hotbar slot 2", is(0, 37, this.cobblestone, 64));
        });

        // -------------------------------------------------------------- own inventory
        step("pickup", 2, () -> click(0, 36, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("place", 15, () -> click(0, 9, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("check move", 1, () -> check("left click pickup + place: logs 36 -> 9",
                is(0, 9, this.log, 8) && empty(0, 36) && cursorEmpty()));

        step("split", 2, () -> click(0, 9, ContainerActionType.CLICK_ITEM, ClickItemAction.RIGHT_CLICK));
        step("place half", 15, () -> click(0, 10, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("check split", 1, () -> check("right click takes half: 4 + 4", is(0, 9, this.log, 4) && is(0, 10, this.log, 4)));

        step("shift click", 15, () -> click(0, 10, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK));
        step("check shift", 1, () -> check("shift click main -> hotbar", is(0, 36, this.log, 4) && empty(0, 10)));

        step("number key", 15, () -> click(0, 9, ContainerActionType.MOVE_TO_HOTBAR_SLOT, MoveToHotbarAction.SLOT_3));
        step("check number key", 1, () -> check("number key 3 swaps with hotbar slot 3", is(0, 38, this.log, 4) && empty(0, 9)));

        step("drag pickup", 2, () -> click(0, 37, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("drag", 15, () -> {
            click(0, ServerboundContainerClickPacket.CLICK_OUTSIDE_NOT_HOLDING_SLOT, ContainerActionType.SPREAD_ITEM,
                    SpreadItemAction.LEFT_MOUSE_BEGIN_DRAG);
            for (int slot = 9; slot <= 12; slot++) {
                click(0, slot, ContainerActionType.SPREAD_ITEM, SpreadItemAction.LEFT_MOUSE_ADD_SLOT);
            }
            click(0, ServerboundContainerClickPacket.CLICK_OUTSIDE_NOT_HOLDING_SLOT, ContainerActionType.SPREAD_ITEM,
                    SpreadItemAction.LEFT_MOUSE_END_DRAG);
        });
        step("check drag", 1, () -> check("left drag spreads 64 over 4 slots", is(0, 9, this.cobblestone, 16)
                && is(0, 10, this.cobblestone, 16) && is(0, 11, this.cobblestone, 16) && is(0, 12, this.cobblestone, 16)
                && empty(0, 37) && cursorEmpty()));

        step("double click", 15, () -> {
            click(0, 9, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK);
            click(0, 9, ContainerActionType.FILL_STACK, FillStackAction.FILL);
        });
        step("check double click", 1, () -> check("double click gathers the 4 stacks", count(this.cursor, this.cobblestone) == 64
                && empty(0, 9) && empty(0, 10) && empty(0, 11) && empty(0, 12)));
        step("place gathered", 15, () -> click(0, 20, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("check gathered", 1, () -> check("gathered stack placed", is(0, 20, this.cobblestone, 64) && cursorEmpty()));

        step("drop one", 15, () -> click(0, 38, ContainerActionType.DROP_ITEM, DropItemAction.DROP_FROM_SELECTED));
        step("check drop one", 1, () -> check("Q drops one", is(0, 38, this.log, 3)));
        step("drop stack", 15, () -> click(0, 38, ContainerActionType.DROP_ITEM, DropItemAction.DROP_SELECTED_STACK));
        step("check drop stack", 1, () -> check("Ctrl+Q drops the stack", empty(0, 38)));
        step("pickup cobblestone", 2, () -> click(0, 20, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("drop outside one", 15, () -> click(0, ServerboundContainerClickPacket.CLICK_OUTSIDE_NOT_HOLDING_SLOT,
                ContainerActionType.CLICK_ITEM, ClickItemAction.RIGHT_CLICK));
        step("check drop outside one", 1, () -> check("right click outside drops one", count(this.cursor, this.cobblestone) == 63));
        step("drop outside all", 15, () -> click(0, ServerboundContainerClickPacket.CLICK_OUTSIDE_NOT_HOLDING_SLOT,
                ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("check drop outside all", 1, () -> check("left click outside drops the cursor", cursorEmpty() && empty(0, 20)));
        serverCount("oak_log", 4);
        serverCount("cobblestone", 0);

        // -------------------------------------------------------------- 2x2 crafting
        step("grid pickup", 2, () -> click(0, 36, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("grid place one", 2, () -> click(0, 1, ContainerActionType.CLICK_ITEM, ClickItemAction.RIGHT_CLICK));
        step("grid put back", 15, () -> click(0, 36, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("check 2x2 result", 1, () -> check("2x2 grid: 1 log -> result 4 planks", is(0, 0, this.planks, 4)
                && is(0, 1, this.log, 1) && is(0, 36, this.log, 3)));
        step("take result", 2, () -> click(0, 0, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("place result", 15, () -> click(0, 21, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("check craft", 1, () -> check("crafted 4 planks, log consumed", is(0, 21, this.planks, 4) && empty(0, 1)
                && empty(0, 0) && cursorEmpty()));

        step("grid pickup 2", 2, () -> click(0, 36, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("grid place 2", 2, () -> {
            click(0, 1, ContainerActionType.CLICK_ITEM, ClickItemAction.RIGHT_CLICK);
            click(0, 1, ContainerActionType.CLICK_ITEM, ClickItemAction.RIGHT_CLICK);
        });
        step("grid put back 2", 15, () -> click(0, 36, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("shift craft", 20, () -> click(0, 0, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK));
        step("check shift craft", 1, () -> check("shift click crafts all (2 logs -> 8 planks, merged)",
                is(0, 21, this.planks, 12) && empty(0, 1) && empty(0, 0) && is(0, 36, this.log, 1)));
        serverCount("oak_planks", 12);
        step("close inventory", 10, this::close);

        // -------------------------------------------------------------- crafting table
        step("place table", 30, () -> command("setblock " + this.table.getX() + " " + this.table.getY() + " "
                + this.table.getZ() + " crafting_table"));
        step("use table", 30, () -> use(this.table));
        step("check table open", 1, () -> check("crafting table opens a Java crafting screen",
                this.openId > 0 && this.openType == ContainerType.CRAFTING && size(this.openId) == 46));
        // Crafting menu: 0 result, 1-9 grid, 10-36 main inventory (Java inventory slot + 1), 37-45 hotbar.
        step("sticks pickup", 2, () -> click(this.openId, 22, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("sticks place", 2, () -> {
            click(this.openId, 1, ContainerActionType.CLICK_ITEM, ClickItemAction.RIGHT_CLICK);
            click(this.openId, 4, ContainerActionType.CLICK_ITEM, ClickItemAction.RIGHT_CLICK);
        });
        step("sticks put back", 15, () -> click(this.openId, 22, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("check sticks result", 1, () -> check("3x3 shaped: 2 planks (vertical) -> 4 sticks",
                is(this.openId, 0, this.stick, 4) && is(this.openId, 22, this.planks, 10)));
        step("take sticks", 20, () -> click(this.openId, 0, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK));
        step("check sticks", 1, () -> check("sticks crafted into the inventory", total(this.openId, 10, 46, this.stick) == 4
                && empty(this.openId, 1) && empty(this.openId, 4) && empty(this.openId, 0)));

        step("table pickup", 2, () -> click(this.openId, 22, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("table place", 2, () -> {
            for (int slot : new int[]{1, 2, 4, 5, 9}) {
                click(this.openId, slot, ContainerActionType.CLICK_ITEM, ClickItemAction.RIGHT_CLICK);
            }
        });
        step("table put back", 15, () -> click(this.openId, 22, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("check table result", 1, () -> check("4 planks in a square (+1 aside) -> no result",
                empty(this.openId, 0)));
        step("remove extra", 2, () -> click(this.openId, 9, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("put extra back", 15, () -> click(this.openId, 22, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("check table result 2", 1, () -> check("4 planks in a square -> crafting table",
                is(this.openId, 0, this.craftingTable, 1)));
        step("take table", 2, () -> click(this.openId, 0, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("place table item", 15, () -> click(this.openId, 30, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("check table item", 1, () -> check("crafting table item taken", is(this.openId, 30, this.craftingTable, 1)
                && empty(this.openId, 1) && empty(this.openId, 5)));
        step("leave one in grid", 2, () -> click(this.openId, 22, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("grid one", 2, () -> click(this.openId, 9, ContainerActionType.CLICK_ITEM, ClickItemAction.RIGHT_CLICK));
        step("grid rest back", 15, () -> click(this.openId, 22, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("close table", 20, this::close);
        step("check grid returned", 1, () -> check("closing returns the grid to the inventory",
                total(0, 9, 45, this.planks) == 6));
        serverCount("oak_planks", 6);
        serverCount("stick", 4);
        serverCount("crafting_table", 1);

        // -------------------------------------------------------------- chest
        step("place chest", 30, () -> command("setblock " + this.chest.getX() + " " + this.chest.getY() + " "
                + this.chest.getZ() + " chest"));
        step("open chest", 30, () -> use(this.chest));
        step("check chest", 1, () -> check("chest opens a 9x3 screen with the player inventory",
                this.openId > 0 && this.openType == ContainerType.GENERIC_9X3 && size(this.openId) == 63));
        // Generic 9x3: 0-26 chest, 27-53 main inventory (Java inventory slot + 18), 54-62 hotbar.
        step("store", 15, () -> click(this.openId, 21 + 18, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK));
        step("check store", 1, () -> check("shift click stores the planks in the chest", is(this.openId, 0, this.planks, 6)
                && empty(this.openId, 39)));
        step("store sticks", 2, () -> {
            int slot = find(this.openId, 27, 63, this.stick);
            click(this.openId, slot, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK);
        });
        step("place sticks", 15, () -> click(this.openId, 13, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("check sticks stored", 1, () -> check("sticks placed in chest slot 13", is(this.openId, 13, this.stick, 4)));
        step("close chest", 20, this::close);
        serverCount("oak_planks", 0);
        step("reopen chest", 30, () -> use(this.chest));
        step("check chest content", 1, () -> check("chest kept the items (server content on reopen)",
                this.openId > 0 && is(this.openId, 0, this.planks, 6) && is(this.openId, 13, this.stick, 4)));
        step("take back", 15, () -> click(this.openId, 0, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK));
        step("check take back", 1, () -> check("shift click takes the planks back", empty(this.openId, 0)
                && total(this.openId, 27, 63, this.planks) == 6));
        step("close chest 2", 20, this::close);
        serverCount("oak_planks", 6);

        // -------------------------------------------------------------- creative
        step("creative", 30, () -> command("gamemode creative " + this.name));
        step("creative set", 20, () -> this.context.send(new ServerboundSetCreativeModeSlotPacket((short) 40,
                new ItemStack(this.diamond, 5, null))));
        step("check creative", 1, () -> check("creative slot: 5 diamonds created", is(0, 40, this.diamond, 5)));
        serverCount("diamond", 5);
        step("creative clear slot", 20, () -> this.context.send(new ServerboundSetCreativeModeSlotPacket((short) 40, null)));
        step("check creative clear", 1, () -> check("creative slot cleared", empty(0, 40)));
        step("close creative inventory", 15, this::close);
        step("check parked items", 1, () -> check("closing the creative inventory leaves no carried item", cursorEmpty()));
        step("creative chest", 30, () -> use(this.chest));
        step("clone", 15, () -> click(this.openId, 13, ContainerActionType.CREATIVE_GRAB_MAX_STACK, CreativeGrabAction.GRAB));
        step("check clone", 1, () -> check("creative middle click: a full stack of the slot's item on the cursor",
                count(this.cursor, this.stick) == 64 && is(this.openId, 13, this.stick, 4)));
        step("place clone", 15, () -> click(this.openId, 20, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("close creative chest", 20, this::close);
        step("reopen creative chest", 30, () -> use(this.chest));
        step("check cloned stack", 1, () -> check("the cloned stack is in the chest on the server", is(this.openId, 20, this.stick, 64)));
        step("close creative chest 2", 15, this::close);
        step("survival", 30, () -> command("gamemode survival " + this.name));
        serverCount("diamond", 0);
        step("done", 1, () -> this.context.log("Inventory scenario finished"));
    }
}
