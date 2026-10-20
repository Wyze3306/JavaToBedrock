package io.github.wyze3306.jtb.inventory;

import io.github.wyze3306.jtb.item.ItemMappings;
import io.github.wyze3306.jtb.item.ItemTranslator;
import io.github.wyze3306.jtb.item.TestItems;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerEnumName;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerType;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ConsumeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftRecipeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.TakeAction;
import org.cloudburstmc.protocol.bedrock.packet.UpdateTradePacket;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.VillagerTrade;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundMerchantOffersPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Villager trades: Bedrock offers NBT to Java offers, and taking a trade's result. */
class MerchantTest {

    private TestItems.TestSession test;

    @BeforeEach
    void setUp() {
        this.test = TestItems.session();
    }

    private static NbtMap item(String id, int count) {
        return NbtMap.builder().putString("Name", id).putByte("Count", (byte) count).putShort("Damage", (short) 0).build();
    }

    private static NbtMap offer(int netId, NbtMap buyA, NbtMap sell, int tier, int uses, int maxUses) {
        return NbtMap.builder().putCompound("buyA", buyA).putCompound("sell", sell).putInt("tier", tier).putInt("netId", netId)
                .putInt("uses", uses).putInt("maxUses", maxUses).putInt("traderExp", 2).putFloat("priceMultiplierA", 0.05f)
                .putInt("demand", 0).build();
    }

    private UpdateTradePacket trades() {
        UpdateTradePacket packet = new UpdateTradePacket();
        packet.setContainerId(7);
        packet.setType(ContainerType.TRADE);
        packet.setTradeTier(0);
        packet.setEntityUniqueId(42);
        packet.setDisplayName("entity.villager.farmer");
        packet.setOffers(NbtMap.builder().putList("Recipes", NbtType.COMPOUND, List.of(
                offer(1001, item("minecraft:emerald", 2), item("minecraft:bread", 6), 0, 0, 12),
                offer(1002, item("minecraft:wheat", 20), item("minecraft:emerald", 1), 0, 12, 12),
                offer(1003, item("minecraft:emerald", 1), item("minecraft:cake", 1), 1, 0, 12))).build());
        return packet;
    }

    @Test
    void offersForTheJavaClient() {
        Merchant merchant = Merchant.read(this.test.session(), trades());
        // The tier 1 offer is locked.
        assertEquals(2, merchant.size());
        ClientboundMerchantOffersPacket offers = merchant.javaOffers(this.test.session(), 3);
        assertEquals(3, offers.getContainerId());
        assertEquals(1, offers.getVillagerLevel());
        VillagerTrade bread = offers.getOffers().getFirst();
        assertEquals(ItemMappings.get().javaId("emerald"), bread.getItemCostA().itemId());
        assertEquals(2, bread.getItemCostA().count());
        assertNull(bread.getItemCostB());
        assertEquals(ItemMappings.get().javaId("bread"), bread.getResult().getId());
        assertEquals(6, bread.getResult().getAmount());
        assertEquals(12, bread.getMaxUses());
        VillagerTrade wheat = offers.getOffers().get(1);
        assertTrue(wheat.isOutOfStock());
    }

    @Test
    void timesSatisfied() {
        Merchant.Offer offer = Merchant.read(this.test.session(), trades()).offer(0);
        assertNotNull(offer);
        assertEquals(5, offer.timesSatisfied(this.test.item("minecraft:emerald", 11), ItemData.AIR));
        assertEquals(0, offer.timesSatisfied(this.test.item("minecraft:emerald", 1), ItemData.AIR));
        assertEquals(0, offer.timesSatisfied(this.test.item("minecraft:diamond", 10), ItemData.AIR));
    }

    @Test
    void takingATradeResult() {
        InventoryState state = this.test.session().get(InventoryState.KEY);
        Mirror mirror = state.mirror();
        state.onUpdateTrade(trades());
        Menu menu = Menus.container(mirror, ContainerType.TRADE, 7, 0, ContainerEnumName.LEVEL_ENTITY_CONTAINER, false);
        assertNotNull(menu);
        menu.merchant = Merchant.read(this.test.session(), trades());
        menu.selectedTrade = 0;
        mirror.ui.set(4, ItemTranslator.withNetId(this.test.item("minecraft:emerald", 5), 30));
        Results.update(state, menu);
        assertEquals("minecraft:bread", menu.result.getDefinition().getIdentifier());

        Transaction tx = new Transaction();
        new ClickHandler(state, menu, tx).pickup(2, 0);
        ItemStackRequestAction[] actions = tx.planned().getFirst().actions();
        CraftRecipeAction craft = assertInstanceOf(CraftRecipeAction.class, actions[0]);
        assertEquals(1001, craft.getRecipeNetId().getRawId());
        ConsumeAction consume = assertInstanceOf(ConsumeAction.class, actions[1]);
        assertEquals(2, consume.getAmount());
        assertEquals(ContainerEnumName.TRADE2_INGREDIENT1_CONTAINER, consume.getSource().getContainerEnumName());
        TakeAction take = assertInstanceOf(TakeAction.class, actions[actions.length - 1]);
        assertEquals(6, take.getAmount());
        assertEquals(3, tx.get(menu.slot(0)).getCount());
        assertEquals(6, tx.get(mirror.cursor).getCount());
    }
}
