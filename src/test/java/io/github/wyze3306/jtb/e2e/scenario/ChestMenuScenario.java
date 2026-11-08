package io.github.wyze3306.jtb.e2e.scenario;

import io.github.wyze3306.jtb.e2e.ClientContext;
import io.github.wyze3306.jtb.e2e.E2eScenario;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;

import java.util.List;

/**
 * Forms with buttons shown as chest menus (test plugin of {@code src/test/pnx-plugin}, run.sh builds it):
 * the simple form "Menu JTB" opens as a chest whose items are its buttons (Option B with the vanilla
 * diamond for its {@code textures/items/diamond} image, the content and labels in a book's lore); a
 * click on "Option C" answers the third button; the modal form "Confirmer" is a chest with "Oui" and
 * "Non", and a click on "Non" answers false. Run it for 40 s.
 */
public final class ChestMenuScenario implements E2eScenario {

    private volatile String title = "";
    private volatile boolean simpleSeen;
    private volatile boolean modalSeen;
    private volatile boolean diamondIcon;
    private volatile boolean contentInBook;
    private volatile boolean simpleClicked;
    private volatile boolean modalClicked;

    @Override
    public void onPacket(ClientContext context, Packet packet) {
        if (packet instanceof ClientboundOpenScreenPacket open) {
            this.title = plain(open.getTitle());
            context.log("Open screen " + open.getContainerId() + " " + open.getType() + " '" + this.title + "'");
        } else if (packet instanceof ClientboundContainerSetContentPacket content && content.getContainerId() == 120) {
            ItemStack[] items = content.getItems();
            if (this.title.equals("Menu JTB") && !this.simpleClicked) {
                this.simpleSeen = true;
                for (ItemStack item : items) {
                    String name = name(item);
                    if (name.equals("Option B")) {
                        this.diamondIcon = item.getId() == io.github.wyze3306.jtb.item.ItemMappings.get().javaId("diamond");
                    } else if (name.equals("Menu JTB")) {
                        List<Component> lore = item.getDataComponentsPatch().get(DataComponentTypes.LORE);
                        this.contentInBook = lore != null && lore.stream().map(ChestMenuScenario::plain).anyMatch(l -> l.contains("Choisissez"));
                    }
                }
                this.simpleClicked = click(context, content, "Option C");
            } else if (this.title.equals("Confirmer") && !this.modalClicked) {
                this.modalSeen = true;
                this.modalClicked = click(context, content, "Non");
            }
        }
    }

    private static boolean click(ClientContext context, ClientboundContainerSetContentPacket content, String button) {
        ItemStack[] items = content.getItems();
        for (int slot = 0; slot < items.length - 36; slot++) {
            if (name(items[slot]).equals(button)) {
                context.log("click '" + button + "' in slot " + slot);
                context.send(new ServerboundContainerClickPacket(120, content.getStateId(), slot, ContainerActionType.CLICK_ITEM,
                        ClickItemAction.LEFT_CLICK, null, new Int2ObjectOpenHashMap<>()));
                return true;
            }
        }
        return false;
    }

    private static String name(ItemStack item) {
        if (item == null || item.getDataComponentsPatch() == null) {
            return "";
        }
        Component name = item.getDataComponentsPatch().get(DataComponentTypes.CUSTOM_NAME);
        return name == null ? "" : plain(name);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Override
    public void finish(ClientContext context) {
        String log = DialogClicks.pnxLog();
        context.check("simple form opens as a chest menu", this.simpleSeen);
        context.check("button image textures/items/diamond shows the diamond", this.diamondIcon);
        context.check("form content in the book's lore", this.contentInBook);
        context.check("click on Option C answers the third button", log.contains("JTBTEST simple=2:Option C"));
        context.check("modal form opens as a chest menu", this.modalSeen);
        context.check("click on Non answers false", log.contains("JTBTEST modal=false"));
    }
}
