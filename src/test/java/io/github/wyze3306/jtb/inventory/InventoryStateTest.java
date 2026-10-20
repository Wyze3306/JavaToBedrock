package io.github.wyze3306.jtb.inventory;

import io.github.wyze3306.jtb.item.ItemMappings;
import io.github.wyze3306.jtb.item.ItemTranslator;
import io.github.wyze3306.jtb.item.TestItems;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerEnumName;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackNetResult;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseContainerInfo;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseInfo;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseSlotInfo;
import org.cloudburstmc.protocol.bedrock.data.payload.common.RedactableString;
import org.cloudburstmc.protocol.bedrock.data.payload.inventory.net.ItemStackNetId;
import org.cloudburstmc.protocol.bedrock.data.payload.inventory.net.ItemStackRequestId;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetCursorItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The request / response cycle: the mirror only follows the server, and Java is resynchronised from it. */
class InventoryStateTest {

    private TestItems.TestSession test;
    private InventoryState state;
    private int oakLog;

    @BeforeEach
    void setUp() {
        this.test = TestItems.session();
        this.state = this.test.session().get(InventoryState.KEY);
        this.oakLog = ItemMappings.get().javaId("oak_log");
        InventoryContentPacket content = new InventoryContentPacket();
        content.setContainerId(ContainerId.INVENTORY);
        List<ItemData> slots = new ArrayList<>(Collections.nCopies(36, ItemData.AIR));
        slots.set(0, ItemTranslator.withNetId(this.test.item("minecraft:oak_log", 8), 11));
        content.getSlots().addAll(slots);
        this.state.onInventoryContent(content);
    }

    private static ServerboundContainerClickPacket leftClick(int slot) {
        return new ServerboundContainerClickPacket(0, 0, slot, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK, null,
                new Int2ObjectOpenHashMap<>());
    }

    private static ItemStackResponseInfo response(int id, ItemStackNetResult result, ItemStackResponseContainerInfo... containers) {
        return new ItemStackResponseInfo(result, new ItemStackRequestId(id), List.of(containers));
    }

    private static ItemStackResponseContainerInfo container(ContainerEnumName name, ItemStackResponseSlotInfo... slots) {
        return new ItemStackResponseContainerInfo(name, List.of(slots), new FullContainerName(name, null));
    }

    private static ItemStackResponseSlotInfo slot(int slot, int count, int netId) {
        return new ItemStackResponseSlotInfo(slot, slot, count, new ItemStackNetId(netId), new RedactableString("", ""), 0);
    }

    private void answer(ItemStackResponseInfo... responses) {
        ItemStackResponsePacket packet = new ItemStackResponsePacket();
        packet.getResponses().addAll(List.of(responses));
        this.state.onItemStackResponse(packet);
    }

    @Test
    void firstContentIsSentWhole() {
        List<ClientboundContainerSetContentPacket> contents = this.test.sent(ClientboundContainerSetContentPacket.class);
        assertEquals(1, contents.size());
        assertEquals(46, contents.getFirst().getItems().length);
        assertEquals(this.oakLog, contents.getFirst().getItems()[36].getId());
        assertEquals(8, contents.getFirst().getItems()[36].getAmount());
    }

    @Test
    void acceptedRequestsUpdateTheMirrorAndJava() {
        this.test.javaPackets().clear();
        this.state.onClick(leftClick(36));
        // Nothing changes before the server answers.
        assertEquals(8, this.state.inventoryItem(0).getCount());
        this.state.onClick(leftClick(9));
        answer(response(-1, ItemStackNetResult.SUCCESS,
                container(ContainerEnumName.HOTBAR_CONTAINER, slot(0, 0, 0)),
                container(ContainerEnumName.CURSOR_CONTAINER, slot(0, 8, 11))));
        assertTrue(ItemTranslator.isAir(this.state.inventoryItem(0)));
        ClientboundContainerSetSlotPacket hotbar = this.test.sent(ClientboundContainerSetSlotPacket.class).stream()
                .filter(p -> p.getSlot() == 36).findFirst().orElseThrow();
        assertNull(hotbar.getItem());
        ClientboundSetCursorItemPacket cursor = this.test.sent(ClientboundSetCursorItemPacket.class).getLast();
        assertNotNull(cursor.getContents());
        assertEquals(8, cursor.getContents().getAmount());

        // The queued second click was planned on the confirmed cursor and is answered with a new id.
        answer(response(-3, ItemStackNetResult.SUCCESS,
                container(ContainerEnumName.CURSOR_CONTAINER, slot(0, 0, 0)),
                container(ContainerEnumName.INVENTORY_CONTAINER, slot(9, 8, 42))));
        ItemData placed = this.state.inventoryItem(9);
        assertEquals(8, placed.getCount());
        assertEquals(42, placed.getNetId());
        assertNull(this.test.sent(ClientboundSetCursorItemPacket.class).getLast().getContents());
    }

    @Test
    void refusedRequestsRestoreTheJavaView() {
        this.test.javaPackets().clear();
        this.state.onClick(leftClick(36));
        answer(response(-1, ItemStackNetResult.ERROR));
        // The mirror keeps the server's content, and Java is told again what the slot holds.
        assertEquals(8, this.state.inventoryItem(0).getCount());
        ClientboundContainerSetSlotPacket hotbar = this.test.sent(ClientboundContainerSetSlotPacket.class).stream()
                .filter(p -> p.getSlot() == 36).reduce((a, b) -> b).orElseThrow();
        assertNotNull(hotbar.getItem());
        assertEquals(8, hotbar.getItem().getAmount());
        assertNull(this.test.sent(ClientboundSetCursorItemPacket.class).getLast().getContents());
    }

    @Test
    void serverContentsReachJava() {
        this.test.javaPackets().clear();
        InventoryContentPacket content = new InventoryContentPacket();
        content.setContainerId(ContainerId.INVENTORY);
        List<ItemData> slots = new ArrayList<>(Collections.nCopies(36, ItemData.AIR));
        slots.set(0, ItemTranslator.withNetId(this.test.item("minecraft:oak_log", 8), 11));
        slots.set(20, ItemTranslator.withNetId(this.test.item("minecraft:diamond", 3), 12));
        content.getSlots().addAll(slots);
        this.state.onInventoryContent(content);
        // A whole player container is sent to Java whole (it may have lost its copy on a respawn).
        List<ClientboundContainerSetContentPacket> sent = this.test.sent(ClientboundContainerSetContentPacket.class);
        assertEquals(1, sent.size());
        assertEquals(3, sent.getFirst().getItems()[20].getAmount());
        assertEquals(8, sent.getFirst().getItems()[36].getAmount());
        assertTrue(this.test.sent(ClientboundContainerSetSlotPacket.class).isEmpty());
    }
}
