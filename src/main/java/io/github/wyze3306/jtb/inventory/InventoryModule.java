package io.github.wyze3306.jtb.inventory;

import io.github.wyze3306.jtb.form.FormState;
import io.github.wyze3306.jtb.item.ItemMappings;
import io.github.wyze3306.jtb.translator.TranslatorModule;
import io.github.wyze3306.jtb.translator.TranslatorRegistry;
import org.cloudburstmc.protocol.bedrock.packet.ContainerClosePacket;
import org.cloudburstmc.protocol.bedrock.packet.ContainerOpenPacket;
import org.cloudburstmc.protocol.bedrock.packet.ContainerRegistryCleanupPacket;
import org.cloudburstmc.protocol.bedrock.packet.ContainerSetDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.CreativeContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.GuiDataPickItemPacket;
import org.cloudburstmc.protocol.bedrock.packet.HurtArmorPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerArmorDamagePacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerEnchantOptionsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket;
import org.cloudburstmc.protocol.bedrock.packet.TrimDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.UnlockedRecipesPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateEquipPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateTradePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerButtonClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerSlotStateChangedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundEditBookPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundPlaceRecipePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundRecipeBookChangeSettingsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundRecipeBookSeenRecipePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundRenameItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSelectBundleItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSelectTradePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetBeaconPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetCreativeModeSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;

/**
 * Translators of the inventory subsystem. See docs/DEVELOPING.md for the packets it owns. Every
 * translator delegates to {@link InventoryState}, which keeps the server-confirmed mirror.
 */
public final class InventoryModule implements TranslatorModule {

    @Override
    public void register(TranslatorRegistry registry) {
        // Load the item mapping data at startup rather than on the first session.
        ItemMappings.get();

        // ---------------------------------------------------------------- Bedrock -> Java
        registry.bedrock(InventoryContentPacket.class, (s, p) -> s.get(InventoryState.KEY).onInventoryContent(p));
        registry.bedrock(InventorySlotPacket.class, (s, p) -> s.get(InventoryState.KEY).onInventorySlot(p));
        registry.bedrock(ContainerOpenPacket.class, (s, p) -> s.get(InventoryState.KEY).onContainerOpen(p));
        registry.bedrock(ContainerClosePacket.class, (s, p) -> s.get(InventoryState.KEY).onContainerClose(p));
        registry.bedrock(ContainerSetDataPacket.class, (s, p) -> s.get(InventoryState.KEY).onContainerSetData(p));
        registry.bedrock(PlayerHotbarPacket.class, (s, p) -> s.get(InventoryState.KEY).onPlayerHotbar(p));
        registry.bedrock(ItemStackResponsePacket.class, (s, p) -> s.get(InventoryState.KEY).onItemStackResponse(p));
        registry.bedrock(CreativeContentPacket.class, (s, p) -> s.get(InventoryState.KEY).onCreativeContent(p));
        registry.bedrock(CraftingDataPacket.class, (s, p) -> s.get(InventoryState.KEY).onCraftingData(p));
        registry.bedrock(PlayerEnchantOptionsPacket.class, (s, p) -> s.get(InventoryState.KEY).onPlayerEnchantOptions(p));
        registry.bedrock(UpdateTradePacket.class, (s, p) -> s.get(InventoryState.KEY).onUpdateTrade(p));
        registry.bedrock(UpdateEquipPacket.class, (s, p) -> s.get(InventoryState.KEY).onUpdateEquip(p));
        registry.bedrock(TrimDataPacket.class, (s, p) -> s.get(InventoryState.KEY).onTrimData(p));
        // Server-authoritative inventories: legacy transactions from the server carry nothing to mirror.
        registry.bedrock(InventoryTransactionPacket.class, (s, p) -> { });
        // Dynamic containers (bundles) are not mirrored; their items are part of the item NBT.
        registry.bedrock(ContainerRegistryCleanupPacket.class, (s, p) -> { });
        // Recipe unlocking drives the Bedrock recipe book only; every recipe is craftable on Java.
        registry.bedrock(UnlockedRecipesPacket.class, (s, p) -> { });
        // Item name popup over the hotbar: the Java client shows it by itself on slot changes.
        registry.bedrock(GuiDataPickItemPacket.class, (s, p) -> { });
        // Armor damage: the new armor durability arrives as InventorySlot updates.
        registry.bedrock(HurtArmorPacket.class, (s, p) -> { });
        registry.bedrock(PlayerArmorDamagePacket.class, (s, p) -> { });

        // ---------------------------------------------------------------- Java -> Bedrock
        // Chest menus (forms shown as chests) belong to the form module.
        registry.java(ServerboundContainerClickPacket.class, (s, p) -> {
            if (!s.get(FormState.KEY).onMenuClick(p.getContainerId(), p.getSlot())) {
                s.get(InventoryState.KEY).onClick(p);
            }
        });
        registry.java(ServerboundContainerClosePacket.class, (s, p) -> {
            if (!s.get(FormState.KEY).onMenuClosed(p.getContainerId())) {
                s.get(InventoryState.KEY).onClose(p.getContainerId());
            }
        });
        registry.java(ServerboundContainerButtonClickPacket.class,
                (s, p) -> s.get(InventoryState.KEY).onButtonClick(p.getContainerId(), p.getButtonId()));
        registry.java(ServerboundContainerSlotStateChangedPacket.class,
                (s, p) -> s.get(InventoryState.KEY).onSlotStateChanged(p.getSlotId(), p.getContainerId(), p.isNewState()));
        registry.java(ServerboundSetCreativeModeSlotPacket.class,
                (s, p) -> s.get(InventoryState.KEY).onCreativeSlot(p.getSlot(), p.getClickedItem()));
        registry.java(ServerboundSetCarriedItemPacket.class, (s, p) -> s.get(InventoryState.KEY).onSetCarriedItem(p.getSlot()));
        registry.java(ServerboundRenameItemPacket.class, (s, p) -> s.get(InventoryState.KEY).onRename(p.getName()));
        registry.java(ServerboundSelectTradePacket.class, (s, p) -> s.get(InventoryState.KEY).onSelectTrade(p.getSlot()));
        registry.java(ServerboundSetBeaconPacket.class,
                (s, p) -> s.get(InventoryState.KEY).onSetBeacon(p.getPrimaryEffect(), p.getSecondaryEffect()));
        registry.java(ServerboundEditBookPacket.class,
                (s, p) -> s.get(InventoryState.KEY).onEditBook(p.getSlot(), p.getPages(), p.getTitle()));
        // The recipe book is not sent (no ClientboundRecipeBookAdd), so these never carry a known recipe.
        registry.java(ServerboundPlaceRecipePacket.class, (s, p) -> { });
        registry.java(ServerboundRecipeBookChangeSettingsPacket.class, (s, p) -> { });
        registry.java(ServerboundRecipeBookSeenRecipePacket.class, (s, p) -> { });
        // Bundle selection only changes which item the next use takes out; Bedrock has no equivalent request.
        registry.java(ServerboundSelectBundleItemPacket.class, (s, p) -> { });
    }
}
