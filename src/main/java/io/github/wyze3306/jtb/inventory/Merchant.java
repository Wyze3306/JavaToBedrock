package io.github.wyze3306.jtb.inventory;

import io.github.wyze3306.jtb.item.ItemTranslator;
import io.github.wyze3306.jtb.session.ProxySession;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.UpdateTradePacket;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.VillagerTrade;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundMerchantOffersPacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The trades of a villager or wandering trader ({@code UpdateTradePacket} offers NBT) as Java merchant
 * offers. Trade {@code i} of the Java list is offer {@code i} here; taking its result sends
 * {@code CRAFT_RECIPE(netId)} with the offer's own recipe network id.
 */
final class Merchant {

    /**
     * One offer. {@code buyA}/{@code buyB} counts are the prices the server expects (already reduced by
     * the player's reputation on PowerNukkitX).
     */
    record Offer(int netId, ItemData buyA, ItemData buyB, ItemData sell, int uses, int maxUses, int traderXp,
                 float priceMultiplier, int demand, int tier) {

        /** How many trades the two input stacks pay for (0 when they do not match). */
        int timesSatisfied(ItemData first, ItemData second) {
            int times = paid(this.buyA, first);
            if (!ItemTranslator.isAir(this.buyB)) {
                times = Math.min(times, paid(this.buyB, second));
            }
            return Math.min(times, Math.max(0, this.maxUses - this.uses));
        }

        private static int paid(ItemData price, ItemData stack) {
            if (ItemTranslator.isAir(price)) {
                return Integer.MAX_VALUE;
            }
            if (ItemTranslator.isAir(stack) || stack.getDefinition() != price.getDefinition()
                    || stack.getDamage() != price.getDamage()
                    || (price.getTag() != null && !Objects.equals(price.getTag(), stack.getTag()))) {
                return 0;
            }
            return stack.getCount() / Math.max(1, price.getCount());
        }
    }

    private final List<Offer> offers;
    private final int tier;

    private Merchant(List<Offer> offers, int tier) {
        this.offers = offers;
        this.tier = tier;
    }

    static Merchant read(ProxySession session, UpdateTradePacket packet) {
        List<Offer> offers = new ArrayList<>();
        NbtMap root = packet.getOffers() == null ? NbtMap.EMPTY : packet.getOffers();
        for (NbtMap recipe : root.getList("Recipes", NbtType.COMPOUND, List.of())) {
            int tier = recipe.getNumber("tier", 0).intValue();
            if (tier > packet.getTradeTier()) {
                // Like the Bedrock client: offers of locked tiers are not shown.
                continue;
            }
            ItemData buyA = item(session, recipe.getCompound("buyA", null));
            ItemData sell = item(session, recipe.getCompound("sell", null));
            if (ItemTranslator.isAir(buyA) || ItemTranslator.isAir(sell)) {
                continue;
            }
            offers.add(new Offer(recipe.getNumber("netId", 0).intValue(), buyA, item(session, recipe.getCompound("buyB", null)), sell,
                    recipe.getNumber("uses", 0).intValue(), recipe.getNumber("maxUses", 0).intValue(),
                    recipe.getNumber("traderExp", 0).intValue(), recipe.getNumber("priceMultiplierA", 0f).floatValue(),
                    recipe.getNumber("demand", 0).intValue(), tier));
        }
        return new Merchant(List.copyOf(offers), packet.getTradeTier());
    }

    private static ItemData item(ProxySession session, @Nullable NbtMap nbt) {
        return nbt == null ? ItemData.AIR : ItemTranslator.nbtItem(session, nbt);
    }

    @Nullable Offer offer(int index) {
        return index >= 0 && index < this.offers.size() ? this.offers.get(index) : null;
    }

    int size() {
        return this.offers.size();
    }

    ClientboundMerchantOffersPacket javaOffers(ProxySession session, int containerId) {
        List<VillagerTrade> trades = new ArrayList<>(this.offers.size());
        for (Offer offer : this.offers) {
            ItemStack result = ItemTranslator.toJava(session, offer.sell());
            VillagerTrade.ItemCost costA = cost(session, offer.buyA());
            if (result == null || costA == null) {
                continue;
            }
            trades.add(new VillagerTrade(costA, result, cost(session, offer.buyB()), offer.uses() >= offer.maxUses(),
                    offer.uses(), offer.maxUses(), offer.traderXp(), 0, offer.priceMultiplier(), offer.demand()));
        }
        // Java levels 1-5 are Bedrock tiers 0-4; the xp bar needs data the trade packet does not carry.
        return new ClientboundMerchantOffersPacket(containerId, trades, Math.clamp(this.tier + 1, 1, 5), 0, false, false);
    }

    private static VillagerTrade.@Nullable ItemCost cost(ProxySession session, ItemData item) {
        if (ItemTranslator.isAir(item)) {
            return null;
        }
        // No component condition: the Java client only checks the item and the count; the server checks the rest.
        return new VillagerTrade.ItemCost(ItemTranslator.javaItem(session, item).id(), item.getCount(), Map.of());
    }
}
