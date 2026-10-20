package io.github.wyze3306.jtb.inventory;

import io.github.wyze3306.jtb.item.ItemTranslator;
import io.github.wyze3306.jtb.item.TestItems;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerEnumName;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ConsumeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftRecipeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.DropAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.PlaceAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.SwapAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.TakeAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Java clicks simulated on the mirror: the planned Bedrock requests and the predicted contents. */
class ClickHandlerTest {

    private TestItems.TestSession test;
    private InventoryState state;
    private Mirror mirror;
    private Menu menu;
    private Transaction tx;
    private ClickHandler click;

    @BeforeEach
    void setUp() {
        this.test = TestItems.session();
        this.state = this.test.session().get(InventoryState.KEY);
        this.mirror = this.state.mirror();
        this.menu = Menus.player(this.mirror);
        this.tx = new Transaction();
        this.click = new ClickHandler(this.state, this.menu, this.tx);
    }

    private ItemData item(String id, int count, int netId) {
        return ItemTranslator.withNetId(this.test.item(id, count), netId);
    }

    /** Java slot of the player screen. */
    private ItemData slot(int javaSlot) {
        return this.tx.get(this.menu.slot(javaSlot));
    }

    private ItemData cursor() {
        return this.tx.get(this.mirror.cursor);
    }

    private ItemStackRequestAction only(int request) {
        ItemStackRequestAction[] actions = this.tx.planned().get(request).actions();
        assertEquals(1, actions.length);
        return actions[0];
    }

    @Test
    void leftClickPicksUpAndPlaces() {
        this.mirror.player.set(0, item("minecraft:oak_log", 8, 11));
        this.click.pickup(36, 0);
        this.click.pickup(9, 0);
        assertEquals(2, this.tx.planned().size());
        TakeAction take = assertInstanceOf(TakeAction.class, only(0));
        assertEquals(8, take.getAmount());
        assertEquals(ContainerEnumName.HOTBAR_CONTAINER, take.getSource().getContainerEnumName());
        assertEquals(0, take.getSource().getSlot());
        assertEquals(11, take.getSource().getStackNetworkId());
        assertEquals(ContainerEnumName.CURSOR_CONTAINER, take.getDestination().getContainerEnumName());
        PlaceAction place = assertInstanceOf(PlaceAction.class, only(1));
        assertEquals(8, place.getAmount());
        assertEquals(ContainerEnumName.INVENTORY_CONTAINER, place.getDestination().getContainerEnumName());
        assertEquals(9, place.getDestination().getSlot());
        assertTrue(ItemTranslator.isAir(slot(36)));
        assertEquals(8, slot(9).getCount());
        assertTrue(ItemTranslator.isAir(cursor()));
    }

    @Test
    void rightClickSplitsAndPlacesOne() {
        this.mirror.player.set(0, item("minecraft:oak_log", 7, 11));
        this.click.pickup(36, 1);
        assertEquals(4, cursor().getCount());
        assertEquals(3, slot(36).getCount());
        this.click.pickup(9, 1);
        this.click.pickup(9, 1);
        assertEquals(2, slot(9).getCount());
        assertEquals(2, cursor().getCount());
        assertInstanceOf(PlaceAction.class, only(2));
    }

    @Test
    void differentItemsSwapWithTheCursor() {
        this.mirror.player.set(0, item("minecraft:oak_log", 8, 11));
        this.mirror.ui.set(Mirror.CURSOR, item("minecraft:cobblestone", 5, 12));
        this.click.pickup(36, 0);
        assertInstanceOf(SwapAction.class, only(0));
        assertEquals("minecraft:cobblestone", slot(36).getDefinition().getIdentifier());
        assertEquals("minecraft:oak_log", cursor().getDefinition().getIdentifier());
    }

    @Test
    void stackingRespectsTheMaximum() {
        this.mirror.player.set(0, item("minecraft:ender_pearl", 10, 11));
        this.mirror.ui.set(Mirror.CURSOR, item("minecraft:ender_pearl", 10, 12));
        this.click.pickup(36, 0);
        assertEquals(16, slot(36).getCount());
        assertEquals(4, cursor().getCount());
    }

    @Test
    void shiftClickMovesBetweenHotbarAndInventory() {
        this.mirror.player.set(9, item("minecraft:oak_log", 8, 11));
        this.mirror.player.set(2, item("minecraft:oak_log", 60, 13));
        this.click.quickMove(9);
        // Merges into the existing hotbar stack first, the rest goes to the first empty hotbar slot.
        assertEquals(64, slot(38).getCount());
        assertEquals(4, slot(36).getCount());
        assertTrue(ItemTranslator.isAir(slot(9)));
        this.click.quickMove(36);
        assertEquals(4, slot(9).getCount());
    }

    @Test
    void shiftClickEquipsArmor() {
        this.mirror.player.set(9, item("minecraft:diamond_helmet", 1, 11));
        this.click.quickMove(9);
        PlaceAction place = assertInstanceOf(PlaceAction.class, only(0));
        assertEquals(ContainerEnumName.ARMOR_CONTAINER, place.getDestination().getContainerEnumName());
        assertEquals(0, place.getDestination().getSlot());
        assertEquals("minecraft:diamond_helmet", slot(5).getDefinition().getIdentifier());
    }

    @Test
    void numberKeysAndOffhandSwap() {
        this.mirror.player.set(9, item("minecraft:oak_log", 8, 11));
        this.click.swap(9, 2);
        assertEquals(8, slot(38).getCount());
        assertTrue(ItemTranslator.isAir(slot(9)));
        this.click.swap(38, 40);
        assertEquals(8, slot(45).getCount());
        SwapAction swap = assertInstanceOf(SwapAction.class, only(1));
        assertEquals(ContainerEnumName.OFFHAND_CONTAINER, swap.getDestination().getContainerEnumName());
        assertEquals(1, swap.getDestination().getSlot());
    }

    @Test
    void dropsOneOrTheStack() {
        this.mirror.player.set(0, item("minecraft:oak_log", 8, 11));
        this.click.throwItem(36, 0);
        assertEquals(1, assertInstanceOf(DropAction.class, only(0)).getAmount());
        this.click.throwItem(36, 1);
        assertEquals(7, assertInstanceOf(DropAction.class, only(1)).getAmount());
        assertTrue(ItemTranslator.isAir(slot(36)));
        this.mirror.ui.set(Mirror.CURSOR, item("minecraft:cobblestone", 5, 12));
        this.click.pickup(InventoryState.OUTSIDE, 1);
        assertEquals(4, cursor().getCount());
        this.click.pickup(InventoryState.OUTSIDE, 0);
        assertTrue(ItemTranslator.isAir(cursor()));
    }

    @Test
    void dragSplitsEvenlyOrOneEach() {
        this.mirror.ui.set(Mirror.CURSOR, item("minecraft:cobblestone", 64, 12));
        this.click.quickCraft(IntArrayList.of(9, 10, 11), 0);
        assertEquals(21, slot(9).getCount());
        assertEquals(21, slot(10).getCount());
        assertEquals(21, slot(11).getCount());
        assertEquals(1, cursor().getCount());
        this.click.quickCraft(IntArrayList.of(9, 12), 1);
        // Only one item left: it cannot cover two slots.
        assertEquals(21, slot(9).getCount());
        assertEquals(1, cursor().getCount());
    }

    @Test
    void doubleClickGathersNonFullStacksFirst() {
        this.mirror.ui.set(Mirror.CURSOR, item("minecraft:cobblestone", 16, 12));
        this.mirror.player.set(10, item("minecraft:cobblestone", 64, 13));
        this.mirror.player.set(11, item("minecraft:cobblestone", 16, 14));
        this.mirror.player.set(12, item("minecraft:cobblestone", 16, 15));
        this.click.pickupAll(9, 0);
        assertEquals(64, cursor().getCount());
        assertEquals(48, slot(10).getCount());
        assertTrue(ItemTranslator.isAir(slot(11)));
        assertTrue(ItemTranslator.isAir(slot(12)));
    }

    @Test
    void takingTheCraftingResult() {
        this.state.crafting().load(CraftingTest.recipes(this.test));
        this.mirror.ui.set(28, item("minecraft:oak_log", 3, 20));
        Results.update(this.state, this.menu);
        assertEquals("minecraft:oak_planks", this.menu.result.getDefinition().getIdentifier());
        this.click.pickup(0, 0);
        ItemStackRequestAction[] actions = this.tx.planned().getFirst().actions();
        CraftRecipeAction craft = assertInstanceOf(CraftRecipeAction.class, actions[0]);
        assertEquals(CraftingTest.PLANKS, craft.getRecipeNetId().getRawId());
        assertEquals(1, craft.getNumberOfRequestedCrafts());
        ConsumeAction consume = assertInstanceOf(ConsumeAction.class, actions[1]);
        assertEquals(1, consume.getAmount());
        assertEquals(ContainerEnumName.CRAFTING_INPUT_CONTAINER, consume.getSource().getContainerEnumName());
        assertEquals(28, consume.getSource().getSlot());
        TakeAction take = assertInstanceOf(TakeAction.class, actions[actions.length - 1]);
        assertEquals(ContainerEnumName.CREATED_OUTPUT_CONTAINER, take.getSource().getContainerEnumName());
        assertEquals(4, take.getAmount());
        assertEquals(4, cursor().getCount());
        assertEquals(2, slot(1).getCount());
    }

    @Test
    void shiftClickCraftsAsManyAsPossible() {
        this.state.crafting().load(CraftingTest.recipes(this.test));
        this.mirror.ui.set(28, item("minecraft:oak_log", 3, 20));
        Results.update(this.state, this.menu);
        this.click.quickMove(0);
        assertEquals(1, this.tx.planned().size());
        CraftRecipeAction craft = assertInstanceOf(CraftRecipeAction.class, this.tx.planned().getFirst().actions()[0]);
        assertEquals(3, craft.getNumberOfRequestedCrafts());
        int planks = 0;
        for (int javaSlot = 9; javaSlot < 45; javaSlot++) {
            ItemData item = slot(javaSlot);
            if (!ItemTranslator.isAir(item)) {
                assertEquals("minecraft:oak_planks", item.getDefinition().getIdentifier());
                planks += item.getCount();
            }
        }
        assertEquals(12, planks);
        // Vanilla fills the inventory from its end: the last hotbar slot first.
        assertEquals(12, slot(44).getCount());
        assertTrue(ItemTranslator.isAir(slot(1)));
    }

    @Test
    void transactionNetIds() {
        this.mirror.player.set(0, item("minecraft:oak_log", 8, 11));
        assertEquals(11, this.tx.netId(this.mirror.hotbar(0)));
        this.click.pickup(36, 1);
        // Changed earlier in the transaction: its new id is unknown.
        assertEquals(0, this.tx.netId(this.mirror.hotbar(0)));
        List<Transaction.Change> changes = this.tx.planned().getFirst().changes();
        assertEquals(2, changes.size());
    }
}
