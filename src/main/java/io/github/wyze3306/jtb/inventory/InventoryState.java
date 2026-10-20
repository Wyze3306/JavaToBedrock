package io.github.wyze3306.jtb.inventory;

import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.entity.EntityTracker;
import io.github.wyze3306.jtb.item.ItemMappings;
import io.github.wyze3306.jtb.item.ItemRegistry;
import io.github.wyze3306.jtb.item.ItemTranslator;
import io.github.wyze3306.jtb.mapping.JavaRegistries;
import io.github.wyze3306.jtb.network.java.JavaConfiguration;
import io.github.wyze3306.jtb.player.PlayerState;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import io.github.wyze3306.jtb.text.BedrockLang;
import io.github.wyze3306.jtb.text.BedrockText;
import io.github.wyze3306.jtb.world.WorldState;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.BookEditAction;
import org.cloudburstmc.protocol.bedrock.data.GameType;
import org.cloudburstmc.protocol.bedrock.data.TrimMaterial;
import org.cloudburstmc.protocol.bedrock.data.TrimPattern;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerEnumName;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerType;
import org.cloudburstmc.protocol.bedrock.data.inventory.EnchantmentInstance;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemEnchantOption;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemEnchants;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.BeaconPaymentAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ConsumeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftRecipeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.DestroyAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.PlaceAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackNetResult;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseContainerInfo;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseInfo;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseSlotInfo;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.RecipeNetId;
import org.cloudburstmc.protocol.bedrock.packet.BookEditPacket;
import org.cloudburstmc.protocol.bedrock.packet.ContainerClosePacket;
import org.cloudburstmc.protocol.bedrock.packet.ContainerOpenPacket;
import org.cloudburstmc.protocol.bedrock.packet.ContainerSetDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.CreativeContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.InteractPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.LecternUpdatePacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerEnchantOptionsPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerToggleCrafterSlotRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.TrimDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateEquipPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateTradePacket;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHeldSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundMountScreenOpenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetCursorItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * CONTRACT (owner: inventory subsystem). The player's inventory and open containers, mirrored
 * from the server, and the translation of Java clicks into Bedrock item stack requests.
 * <p>
 * PowerNukkitX is authoritative for inventories ({@code ItemStackRequest}): every Java action is
 * simulated on the mirror ({@link ClickHandler}), sent as item stack requests, and the Java client is
 * resynchronised from the server's answer: confirmed slots get their real counts and stack network
 * ids, rejected ones keep their previous content. Java actions arriving while requests are in flight
 * wait for the answers, so each one is planned on confirmed contents.
 * <p>
 * Every method runs on the session event loop.
 */
public final class InventoryState implements SessionState {

    public static final StateKey<InventoryState> KEY = StateKey.of("inventory", InventoryState::new);

    /** Java slot number of clicks outside the window. */
    static final int OUTSIDE = ServerboundContainerClickPacket.CLICK_OUTSIDE_NOT_HOLDING_SLOT;
    /** A request without an answer after this many proxy ticks is considered failed. */
    private static final int RESPONSE_TIMEOUT_TICKS = 40;
    /** Java window id of the lectern screen (Bedrock opens lecterns without a window). */
    private static final int LECTERN_JAVA_ID = 101;
    /** Requests per packet (PowerNukkitX ignores packets with more than 128). */
    private static final int REQUESTS_PER_PACKET = 64;

    /** One request in flight. */
    private record Pending(Transaction.Planned planned, Batch batch) {
    }

    /** The requests of one Java action and the Java slots to resynchronise once all are answered. */
    private static final class Batch {
        final Menu menu;
        final IntSet forced;
        final IntSet forcedPlayer;
        final long tick;
        int remaining;

        Batch(Menu menu, IntSet forced, IntSet forcedPlayer, long tick) {
            this.menu = menu;
            this.forced = forced;
            this.forcedPlayer = forcedPlayer;
            this.tick = tick;
        }
    }

    /** A Bedrock container opening whose Java screen waits for the content (it gives the size). */
    private record PendingOpen(int windowId, ContainerType type, Vector3i position, long entityId, long tick,
                               int saddle, int armor, int equipSlots) {
    }

    private final ProxySession session;
    private final Mirror mirror = new Mirror();
    private final Menu playerMenu;
    private @Nullable Menu openMenu;
    private final Crafting crafting;
    private final Creative creative;
    private final Map<String, String> trimPatterns = new HashMap<>();
    private final Map<String, String> trimMaterials = new HashMap<>();

    private int inventoryWindow = -1;
    private boolean inventoryOpenRequested;
    private boolean closeInventoryWhenOpened;
    private @Nullable PendingOpen pendingOpen;
    private int closingWindow = Integer.MIN_VALUE;

    private int selectedSlot;
    private @Nullable ItemStack lastCursor;

    private final Int2ObjectMap<Pending> pending = new Int2ObjectOpenHashMap<>();
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private final List<Batch> completed = new ArrayList<>();
    private int nextRequestId = -1;
    private boolean closed;
    private boolean fullContentNeeded = true;

    private int dragType = -1;
    private int dragMenu = -1;
    private final IntLinkedOpenHashSet dragSlots = new IntLinkedOpenHashSet();

    private InventoryState(ProxySession session) {
        this.session = session;
        this.playerMenu = Menus.player(this.mirror);
        this.crafting = new Crafting(session);
        this.creative = new Creative(session, this.mirror.createdOutput);
        session.get(JavaConfiguration.KEY).onGameStart(this::onGameStart);
    }

    // ------------------------------------------------------------------ contract

    /** Selected hotbar slot, 0-8. */
    public int selectedSlot() {
        return this.selectedSlot;
    }

    /** The item in the main hand, exactly as the server knows it (with its stack network id). */
    public ItemData heldItem() {
        return this.mirror.player.get(this.selectedSlot);
    }

    /** The item in the off hand. */
    public ItemData offhandItem() {
        return this.mirror.offhand.get(0);
    }

    /** The item of a player inventory slot (0-8 hotbar, 9-35 main), exactly as the server knows it. */
    public ItemData inventoryItem(int slot) {
        return this.mirror.player.get(slot);
    }

    /** Drops one item, or the whole stack, from the main hand (Java Q / Ctrl+Q). */
    public void dropHeldItem(boolean wholeStack) {
        runOrQueue(() -> {
            Transaction tx = new Transaction();
            ItemData held = heldItem();
            if (!ItemTranslator.isAir(held)) {
                tx.drop(this.mirror.hotbar(this.selectedSlot), wholeStack ? held.getCount() : 1);
            }
            submit(tx, this.playerMenu, IntSet.of(this.playerMenu.hotbarSlot(this.selectedSlot)), IntSet.of());
        });
    }

    /** Swaps main hand and off hand (Java F). */
    public void swapOffhand() {
        runOrQueue(() -> {
            Transaction tx = new Transaction();
            tx.swap(this.mirror.hotbar(this.selectedSlot), this.mirror.offhandSlot);
            submit(tx, this.playerMenu, IntSet.of(this.playerMenu.hotbarSlot(this.selectedSlot), 45), IntSet.of());
        });
    }

    // ------------------------------------------------------------------ package accessors

    ProxySession session() {
        return this.session;
    }

    Mirror mirror() {
        return this.mirror;
    }

    Crafting crafting() {
        return this.crafting;
    }

    Creative creative() {
        return this.creative;
    }

    boolean isCreative() {
        GameType type = this.session.get(PlayerState.KEY).gameType();
        return type == GameType.CREATIVE;
    }

    private boolean isSpectator() {
        return this.session.get(PlayerState.KEY).gameType() == GameType.SPECTATOR;
    }

    @Nullable String trimPattern(String templateItem) {
        return this.trimPatterns.get(templateItem);
    }

    @Nullable String trimMaterial(String materialItem) {
        return this.trimMaterials.get(materialItem);
    }

    private @Nullable Menu menu(int javaId) {
        if (javaId == 0) {
            return this.playerMenu;
        }
        return this.openMenu != null && this.openMenu.javaId == javaId ? this.openMenu : null;
    }

    // ------------------------------------------------------------------ Bedrock: contents

    void onInventoryContent(InventoryContentPacket packet) {
        int id = packet.getContainerId();
        List<ItemData> slots = packet.getSlots();
        switch (id) {
            case ContainerId.INVENTORY -> fill(this.mirror.player, slots, 36);
            case ContainerId.HOTBAR -> fill(this.mirror.player, slots, 9);
            case ContainerId.ARMOR -> fill(this.mirror.armor, slots, 4);
            case ContainerId.OFFHAND -> fill(this.mirror.offhand, slots, 1);
            case ContainerId.UI -> fill(this.mirror.ui, slots, 54);
            default -> {
                PendingOpen open = this.pendingOpen;
                if (open != null && open.windowId() == id) {
                    this.mirror.window.resize(Math.max(slots.size(), this.mirror.window.size()));
                    fill(this.mirror.window, slots, slots.size());
                    finishOpen(open, slots.size());
                } else if (this.openMenu != null && this.openMenu.bedrockWindowId == id) {
                    fillWindow(this.openMenu, slots);
                } else {
                    return;
                }
            }
        }
        flush();
    }

    private static void fill(BedrockContainer container, List<ItemData> slots, int max) {
        int n = Math.min(slots.size(), max);
        for (int i = 0; i < n; i++) {
            container.set(i, slots.get(i));
        }
    }

    /** Content of the open window: storage windows by index, UI-slot screens by their UI slots. */
    private void fillWindow(Menu menu, List<ItemData> slots) {
        int base = uiContentBase(menu.bedrockType);
        if (base < 0) {
            if (this.mirror.window.size() < slots.size()) {
                this.mirror.window.resize(slots.size());
            }
            fill(this.mirror.window, slots, slots.size());
            return;
        }
        for (int i = 0; i < slots.size(); i++) {
            int uiSlot = base + i;
            if (isUiInput(menu, uiSlot)) {
                this.mirror.ui.set(uiSlot, slots.get(i));
            }
        }
    }

    /**
     * First UI slot of the content packets of screens whose items live in UI slots (PowerNukkitX sends
     * their inventory in index order), or -1 for storage windows.
     */
    private static int uiContentBase(ContainerType type) {
        return switch (type) {
            case WORKBENCH -> 32;
            case ENCHANTMENT -> 14;
            case ANVIL -> 1;
            case GRINDSTONE -> 16;
            case LOOM -> 9;
            case CARTOGRAPHY -> 12;
            case SMITHING_TABLE -> 51;
            case BEACON -> 27;
            case TRADE -> 4;
            case STONECUTTER -> 3;
            default -> -1;
        };
    }

    private static boolean isUiInput(Menu menu, int uiSlot) {
        for (SlotRef slot : menu.slots) {
            if (slot != null && slot.container().kind == BedrockContainer.Kind.UI && slot.index() == uiSlot) {
                return true;
            }
        }
        return false;
    }

    void onInventorySlot(InventorySlotPacket packet) {
        int id = packet.getContainerID();
        int slot = packet.getSlot();
        ItemData item = packet.getItem() == null ? ItemData.AIR : packet.getItem();
        switch (id) {
            case ContainerId.INVENTORY, ContainerId.HOTBAR -> {
                if (slot < 36) {
                    this.mirror.player.set(slot, item);
                }
            }
            case ContainerId.ARMOR -> {
                if (slot < 4) {
                    this.mirror.armor.set(slot, item);
                }
            }
            case ContainerId.OFFHAND -> this.mirror.offhand.set(0, item);
            case ContainerId.UI -> {
                if (slot < 54) {
                    this.mirror.ui.set(slot, item);
                }
            }
            default -> {
                Menu menu = this.openMenu;
                PendingOpen open = this.pendingOpen;
                if (menu != null && menu.bedrockWindowId == id) {
                    // Slot updates carry network slots: UI slots for UI-slot screens, storage indices otherwise.
                    if (uiContentBase(menu.bedrockType) >= 0) {
                        if (slot < 54) {
                            this.mirror.ui.set(slot, item);
                        }
                    } else {
                        this.mirror.window.set(slot, item);
                    }
                } else if (open != null && open.windowId() == id) {
                    this.mirror.window.set(slot, item);
                } else {
                    // The 2x2 crafting grid is updated with window id -1: trust its container name only.
                    FullContainerName name = packet.getFullContainerName();
                    if (name == null || name.getContainerName() != ContainerEnumName.CRAFTING_INPUT_CONTAINER) {
                        return;
                    }
                    SlotRef ref = this.mirror.resolve(name.getContainerName(), slot);
                    if (ref == null) {
                        return;
                    }
                    ref.container().set(ref.index(), item);
                }
            }
        }
        flush();
    }

    // ------------------------------------------------------------------ Bedrock: windows

    void onContainerOpen(ContainerOpenPacket packet) {
        int id = packet.getContainerID();
        ContainerType type = packet.getContainerType();
        if (type == ContainerType.INVENTORY) {
            if (packet.getTargetActorID() == -1 || this.session.get(PlayerState.KEY).isLocal(packet.getTargetActorID())) {
                this.inventoryWindow = id;
                this.inventoryOpenRequested = true;
                if (this.closeInventoryWhenOpened) {
                    this.closeInventoryWhenOpened = false;
                    closeBedrockInventory();
                }
            }
            return;
        }
        Vector3i position = packet.getPosition() == null ? Vector3i.ZERO : packet.getPosition();
        if (type == ContainerType.LECTERN) {
            openLectern(position);
            return;
        }
        replaceOpenMenu();
        this.mirror.window.clear();
        switch (type) {
            case CONTAINER, MINECART_CHEST, CHEST_BOAT ->
                    // The content packet that follows gives the size (single or double chest...).
                    this.pendingOpen = new PendingOpen(id, type, position, packet.getTargetActorID(), this.session.tickCount(), -1, -1, 0);
            default -> {
                Menu menu = Menus.container(this.mirror, type, id, 0, ContainerEnumName.LEVEL_ENTITY_CONTAINER, false);
                if (menu == null) {
                    this.session.logger().debug("[{}] No Java screen for Bedrock container {}", this.session.getName(), type);
                    sendBedrockClose(id, type);
                    return;
                }
                menu.position = position;
                openJava(menu, title(type, position, 0));
            }
        }
    }

    /** Another screen opens over the current one (Java replaces its screen itself). */
    private void replaceOpenMenu() {
        Menu menu = this.openMenu;
        if (menu != null) {
            returnInputs(menu);
            clearWindow(menu);
            this.openMenu = null;
        }
        this.pendingOpen = null;
        resetDrag();
    }

    private void finishOpen(PendingOpen open, int size) {
        this.pendingOpen = null;
        Menu menu;
        Component title;
        if (open.type() == ContainerType.HORSE) {
            int chestStart = open.equipSlots();
            menu = Menus.mount(this.mirror, open.windowId(), open.saddle(), open.armor(), chestStart, Math.max(0, size - chestStart));
            menu.entityId = open.entityId();
            title = Component.empty();
        } else {
            NbtMap block = blockState(open.position());
            String name = block == null ? "" : block.getString("name", "");
            boolean shulker = name.endsWith("shulker_box");
            ContainerEnumName storage = name.equals("minecraft:barrel") ? ContainerEnumName.BARREL_CONTAINER
                    : shulker ? ContainerEnumName.SHULKER_BOX_CONTAINER : ContainerEnumName.LEVEL_ENTITY_CONTAINER;
            menu = Menus.container(this.mirror, open.type(), open.windowId(), size, storage, shulker);
            if (menu == null) {
                return;
            }
            menu.position = open.position();
            title = title(open.type(), open.position(), size);
        }
        openJava(menu, title);
    }

    private void openJava(Menu menu, Component title) {
        this.openMenu = menu;
        if (menu.kind == Menu.Kind.MOUNT) {
            int javaEntity = this.session.get(EntityTracker.KEY).javaId(menu.entityId);
            int columns = Math.max(0, (menu.containerSlots - 2) / 3);
            this.session.sendJava(new ClientboundMountScreenOpenPacket(menu.javaId, columns, javaEntity));
        } else if (menu.javaType != null) {
            this.session.sendJava(new ClientboundOpenScreenPacket(menu.javaId, menu.javaType, title));
        }
        Results.update(this, menu);
        sendContent(menu);
        if (menu.kind == Menu.Kind.FURNACE) {
            // PowerNukkitX sends the progress scaled to the cooking time but no totals: Java needs them.
            int total = menu.bedrockType == ContainerType.FURNACE ? 200 : 100;
            this.session.sendJava(new ClientboundContainerSetDataPacket(menu.javaId, 1, total));
            this.session.sendJava(new ClientboundContainerSetDataPacket(menu.javaId, 3, total));
        } else if (menu.kind == Menu.Kind.MERCHANT && menu.merchant != null) {
            this.session.sendJava(menu.merchant.javaOffers(this.session, menu.javaId));
        }
    }

    private Component title(ContainerType type, Vector3i position, int size) {
        NbtMap entity = this.session.get(WorldState.KEY).blockEntityAt(position.getX(), position.getY(), position.getZ());
        String custom = entity == null ? "" : entity.getString("CustomName", "");
        if (!custom.isEmpty()) {
            return BedrockText.legacyToComponent(custom);
        }
        String key = switch (type) {
            case CONTAINER -> {
                NbtMap block = blockState(position);
                String name = block == null ? "" : block.getString("name", "");
                if (name.equals("minecraft:barrel")) {
                    yield "container.barrel";
                } else if (name.endsWith("shulker_box")) {
                    yield "container.shulkerBox";
                } else if (name.equals("minecraft:ender_chest")) {
                    yield "container.enderchest";
                }
                yield size > 27 ? "container.chestDouble" : "container.chest";
            }
            case MINECART_CHEST -> "entity.minecraft.chest_minecart";
            case CHEST_BOAT -> "entity.minecraft.oak_chest_boat";
            case FURNACE -> "container.furnace";
            case BLAST_FURNACE -> "container.blast_furnace";
            case SMOKER -> "container.smoker";
            case BREWING_STAND -> "container.brewing";
            case WORKBENCH -> "container.crafting";
            case ENCHANTMENT -> "container.enchant";
            case ANVIL -> "container.repair";
            case GRINDSTONE -> "container.grindstone_title";
            case STONECUTTER -> "container.stonecutter";
            case LOOM -> "container.loom";
            case CARTOGRAPHY -> "container.cartography_table";
            case SMITHING_TABLE -> "container.upgrade";
            case BEACON -> "container.beacon";
            case HOPPER, MINECART_HOPPER -> "container.hopper";
            case DISPENSER -> "container.dispenser";
            case DROPPER -> "container.dropper";
            case CRAFTER -> "container.crafter";
            case LECTERN -> "container.lectern";
            default -> "container.inventory";
        };
        return Component.translatable(key);
    }

    private @Nullable NbtMap blockState(Vector3i position) {
        int runtimeId = this.session.get(WorldState.KEY).bedrockBlockAt(position.getX(), position.getY(), position.getZ(), 0);
        return this.session.get(BlockMapper.KEY).bedrockState(runtimeId);
    }

    void onContainerClose(ContainerClosePacket packet) {
        int id = packet.getContainerID();
        if (id == this.inventoryWindow) {
            this.inventoryWindow = -1;
            this.inventoryOpenRequested = false;
            if (this.closingWindow == id) {
                this.closingWindow = Integer.MIN_VALUE;
            } else if (packet.isServerInitiatedClose() && this.openMenu == null) {
                this.session.sendJava(new ClientboundContainerClosePacket(0));
            }
            flush();
            return;
        }
        if (id == this.closingWindow && !packet.isServerInitiatedClose()) {
            // The answer to our own close.
            this.closingWindow = Integer.MIN_VALUE;
            return;
        }
        PendingOpen open = this.pendingOpen;
        if (open != null && open.windowId() == id) {
            this.pendingOpen = null;
            return;
        }
        Menu menu = this.openMenu;
        if (menu != null && menu.bedrockWindowId == id) {
            this.session.sendJava(new ClientboundContainerClosePacket(menu.javaId));
            returnInputs(menu);
            clearWindow(menu);
            this.openMenu = null;
            resetDrag();
            flush();
        }
    }

    void onContainerSetData(ContainerSetDataPacket packet) {
        Menu menu = this.openMenu;
        if (menu == null || menu.bedrockWindowId != packet.getContainerID()) {
            return;
        }
        int property = switch (menu.kind) {
            case FURNACE -> switch (packet.getId()) {
                case ContainerSetDataPacket.FURNACE_TICK_COUNT -> 2;
                case ContainerSetDataPacket.FURNACE_LIT_TIME -> 0;
                case ContainerSetDataPacket.FURNACE_LIT_DURATION -> 1;
                default -> -1;
            };
            case BREWING -> switch (packet.getId()) {
                case ContainerSetDataPacket.BREWING_STAND_BREW_TIME -> 0;
                case ContainerSetDataPacket.BREWING_STAND_FUEL_AMOUNT -> 1;
                default -> -1;
            };
            default -> -1;
        };
        if (property >= 0) {
            this.session.sendJava(new ClientboundContainerSetDataPacket(menu.javaId, property, packet.getValue()));
        }
    }

    void onPlayerEnchantOptions(PlayerEnchantOptionsPacket packet) {
        Menu menu = this.openMenu;
        if (menu == null || menu.kind != Menu.Kind.ENCHANTING) {
            return;
        }
        menu.enchantOptions.clear();
        menu.enchantOptions.addAll(packet.getOptions());
        JavaRegistries.Registry enchantments = JavaRegistries.registry("minecraft:enchantment");
        for (int i = 0; i < 3; i++) {
            ItemEnchantOption option = i < menu.enchantOptions.size() ? menu.enchantOptions.get(i) : null;
            int hint = -1;
            int level = -1;
            EnchantmentInstance first = option == null ? null : firstEnchantment(option.getItemEnchants());
            if (first != null) {
                Key key = ItemMappings.get().javaEnchantment(first.getEnchantType());
                hint = key == null ? -1 : enchantments.id(key);
                level = hint < 0 ? -1 : first.getEnchantLevel();
            }
            // Java properties: 0-2 level costs, 4-6 hinted enchantment, 7-9 hinted level.
            this.session.sendJava(new ClientboundContainerSetDataPacket(menu.javaId, i, option == null ? 0 : option.getCost()));
            this.session.sendJava(new ClientboundContainerSetDataPacket(menu.javaId, 4 + i, hint));
            this.session.sendJava(new ClientboundContainerSetDataPacket(menu.javaId, 7 + i, level));
        }
    }

    private static List<List<EnchantmentInstance>> groups(@Nullable ItemEnchants enchants) {
        if (enchants == null) {
            return List.of();
        }
        List<List<EnchantmentInstance>> groups = new ArrayList<>(3);
        for (List<EnchantmentInstance> group : Arrays.asList(enchants.getEnchants0(), enchants.getEnchants1(), enchants.getEnchants2())) {
            if (group != null) {
                groups.add(group);
            }
        }
        return groups;
    }

    private static @Nullable EnchantmentInstance firstEnchantment(@Nullable ItemEnchants enchants) {
        for (List<EnchantmentInstance> group : groups(enchants)) {
            if (!group.isEmpty()) {
                return group.getFirst();
            }
        }
        return null;
    }

    void onUpdateTrade(UpdateTradePacket packet) {
        Merchant merchant = Merchant.read(this.session, packet);
        Menu menu = this.openMenu;
        if (menu != null && menu.kind == Menu.Kind.MERCHANT && menu.bedrockWindowId == packet.getContainerId()) {
            menu.merchant = merchant;
            this.session.sendJava(merchant.javaOffers(this.session, menu.javaId));
            flush();
            return;
        }
        replaceOpenMenu();
        Menu trade = Menus.container(this.mirror, ContainerType.TRADE, packet.getContainerId(), 0,
                ContainerEnumName.LEVEL_ENTITY_CONTAINER, false);
        if (trade == null) {
            return;
        }
        trade.merchant = merchant;
        trade.entityId = packet.getEntityUniqueId();
        String name = packet.getDisplayName() == null ? "" : packet.getDisplayName();
        Component title = name.isEmpty() ? Component.translatable("entity.minecraft.villager")
                : BedrockText.legacyToComponent(BedrockLang.forJavaLocale(this.session.getLocale()).translate(name));
        openJava(trade, title);
    }

    void onUpdateEquip(UpdateEquipPacket packet) {
        NbtMap tag = packet.getTag() == null ? NbtMap.EMPTY : packet.getTag();
        List<NbtMap> slots = tag.getList("slots", NbtType.COMPOUND, List.of());
        int saddle = -1;
        int armor = -1;
        for (int i = 0; i < slots.size(); i++) {
            boolean acceptsSaddle = false;
            for (NbtMap accepted : slots.get(i).getList("acceptedItems", NbtType.COMPOUND, List.of())) {
                acceptsSaddle |= accepted.getCompound("slotItem", NbtMap.EMPTY).getString("Name", "").equals("minecraft:saddle");
            }
            if (acceptsSaddle && saddle < 0) {
                saddle = i;
            } else if (armor < 0) {
                armor = i;
            }
        }
        replaceOpenMenu();
        this.mirror.window.clear();
        // The content packet that follows gives the chest size.
        this.pendingOpen = new PendingOpen(packet.getContainerId(), ContainerType.HORSE, Vector3i.ZERO, packet.getEntityUniqueId(),
                this.session.tickCount(), saddle, armor, slots.size());
    }

    private void openLectern(Vector3i position) {
        NbtMap entity = this.session.get(WorldState.KEY).blockEntityAt(position.getX(), position.getY(), position.getZ());
        NbtMap book = entity == null ? null : entity.getCompound("book", null);
        replaceOpenMenu();
        Menu menu = Menus.container(this.mirror, ContainerType.LECTERN, LECTERN_JAVA_ID, 0, ContainerEnumName.LEVEL_ENTITY_CONTAINER, false);
        if (menu == null) {
            return;
        }
        menu.result = book == null ? ItemData.AIR : ItemTranslator.nbtItem(this.session, book);
        menu.position = position;
        menu.lecternPages = book == null ? 0 : book.getCompound("tag", NbtMap.EMPTY).getList("pages", NbtType.COMPOUND, List.of()).size();
        menu.lecternPage = entity == null ? 0 : entity.getNumber("page", 0).intValue();
        openJava(menu, title(ContainerType.LECTERN, position, 0));
        this.session.sendJava(new ClientboundContainerSetDataPacket(menu.javaId, 0, menu.lecternPage));
    }

    void onTrimData(TrimDataPacket packet) {
        this.trimPatterns.clear();
        this.trimMaterials.clear();
        for (TrimPattern pattern : packet.getTrimPatternList()) {
            this.trimPatterns.put(pattern.getItemName(), pattern.getPatternId());
        }
        for (TrimMaterial material : packet.getTrimMaterialList()) {
            this.trimMaterials.put(material.getItemName(), material.getMaterialId());
        }
    }

    void onCraftingData(CraftingDataPacket packet) {
        this.crafting.load(packet);
        this.session.sendJava(this.crafting.javaRecipes());
        flush();
    }

    void onCreativeContent(CreativeContentPacket packet) {
        this.creative.load(packet);
    }

    void onPlayerHotbar(PlayerHotbarPacket packet) {
        if (packet.getContainerID() != ContainerId.INVENTORY || packet.getSelectedSlot() < 0 || packet.getSelectedSlot() > 8) {
            return;
        }
        this.selectedSlot = packet.getSelectedSlot();
        if (packet.isShouldSelectSlot()) {
            this.session.sendJava(new ClientboundSetHeldSlotPacket(this.selectedSlot));
        }
    }

    // ------------------------------------------------------------------ Bedrock: responses

    void onItemStackResponse(ItemStackResponsePacket packet) {
        for (ItemStackResponseInfo response : packet.getResponses()) {
            int id = response.getClientRequestId() == null ? 0 : response.getClientRequestId().getID();
            Pending request = this.pending.remove(id);
            boolean success = response.getResult() == ItemStackNetResult.SUCCESS;
            if (request != null && success) {
                for (Transaction.Change change : request.planned().changes()) {
                    change.slot().container().set(change.slot().index(), change.item());
                }
            } else if (request != null) {
                this.session.logger().debug("[{}] Item stack request {} refused: {}", this.session.getName(), id, response.getResult());
            }
            if (success) {
                applyResponse(response);
            }
            if (request != null && --request.batch().remaining == 0) {
                this.completed.add(request.batch());
            }
        }
        afterRequests();
    }

    /** Applies the counts and stack network ids the server reports: they are authoritative. */
    private void applyResponse(ItemStackResponseInfo response) {
        if (response.getContainers() == null) {
            return;
        }
        for (ItemStackResponseContainerInfo container : response.getContainers()) {
            FullContainerName full = container.getFullContainerName();
            ContainerEnumName name = full != null && full.getContainerName() != null ? full.getContainerName() : legacyName(container);
            if (name == null || container.getSlots() == null) {
                continue;
            }
            for (ItemStackResponseSlotInfo slot : container.getSlots()) {
                SlotRef ref = this.mirror.resolve(name, slot.getSlot());
                if (ref == null) {
                    continue;
                }
                ItemData item = ref.container().get(ref.index());
                if (slot.getAmount() <= 0) {
                    ref.container().set(ref.index(), ItemData.AIR);
                } else if (!ItemTranslator.isAir(item)) {
                    int netId = slot.getItemStackNetId() == null ? 0 : slot.getItemStackNetId().getID();
                    ref.container().set(ref.index(), ItemTranslator.withNetId(ItemTranslator.withCount(item, slot.getAmount()), netId));
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static @Nullable ContainerEnumName legacyName(ItemStackResponseContainerInfo container) {
        return container.getContainerEnumName();
    }

    private void afterRequests() {
        if (!this.completed.isEmpty()) {
            List<Batch> batches = List.copyOf(this.completed);
            this.completed.clear();
            for (Batch batch : batches) {
                if (batch.menu == this.openMenu || batch.menu == this.playerMenu) {
                    resync(batch.menu, batch.forced);
                }
                if (!batch.forcedPlayer.isEmpty()) {
                    resync(this.playerMenu, batch.forcedPlayer);
                }
            }
        }
        flush();
        drainQueue();
    }

    // ------------------------------------------------------------------ requests

    /** Runs an inventory action now, or after the requests in flight are answered. */
    private void runOrQueue(Runnable action) {
        if (this.pending.isEmpty() && this.queue.isEmpty()) {
            action.run();
        } else {
            this.queue.add(action);
        }
    }

    private void drainQueue() {
        while (this.pending.isEmpty() && !this.queue.isEmpty()) {
            this.queue.poll().run();
        }
    }

    /**
     * Sends the requests of a transaction; once all are answered the given Java slots are
     * resynchronised (and the cursor), whatever the outcome.
     *
     * @param forced        slots of {@code menu} the Java client may have changed on its own
     * @param forcedPlayer  slots of the player inventory screen (window 0) the Java client may have changed
     */
    private void submit(Transaction tx, Menu menu, IntSet forced, IntSet forcedPlayer) {
        Batch batch = new Batch(menu, forced, forcedPlayer, this.session.tickCount());
        if (tx.isEmpty()) {
            this.completed.add(batch);
            afterRequests();
            return;
        }
        ItemStackRequestPacket packet = new ItemStackRequestPacket();
        for (Transaction.Planned planned : tx.planned()) {
            int id = this.nextRequestId;
            // Negative odd ids, like the vanilla client.
            this.nextRequestId = id < -0x3FFF_FFFF ? -1 : id - 2;
            packet.getRequests().add(new ItemStackRequest(id, planned.actions(), planned.filterStrings()));
            this.pending.put(id, new Pending(planned, batch));
            batch.remaining++;
            if (packet.getRequests().size() == REQUESTS_PER_PACKET) {
                this.session.sendBedrock(packet);
                packet = new ItemStackRequestPacket();
            }
        }
        if (!packet.getRequests().isEmpty()) {
            this.session.sendBedrock(packet);
        }
    }

    @Override
    public void close() {
        this.closed = true;
        this.pending.clear();
        this.queue.clear();
    }

    @Override
    public void tick() {
        if (!this.pending.isEmpty()) {
            long now = this.session.tickCount();
            IntSet expired = new IntOpenHashSet();
            for (Int2ObjectMap.Entry<Pending> entry : this.pending.int2ObjectEntrySet()) {
                if (now - entry.getValue().batch().tick > RESPONSE_TIMEOUT_TICKS) {
                    expired.add(entry.getIntKey());
                }
            }
            if (!expired.isEmpty()) {
                this.session.logger().debug("[{}] {} item stack request(s) unanswered", this.session.getName(), expired.size());
                for (int id : expired) {
                    Pending request = this.pending.remove(id);
                    if (--request.batch().remaining == 0) {
                        this.completed.add(request.batch());
                    }
                }
                afterRequests();
            }
        }
        PendingOpen open = this.pendingOpen;
        if (open != null && this.session.tickCount() - open.tick() > 2) {
            // No content came: open with the default size.
            finishOpen(open, open.type() == ContainerType.HORSE ? open.equipSlots() : 27);
            flush();
        }
    }

    // ------------------------------------------------------------------ Java: clicks

    void onClick(ServerboundContainerClickPacket packet) {
        runOrQueue(() -> click(packet));
    }

    private void click(ServerboundContainerClickPacket packet) {
        Menu menu = menu(packet.getContainerId());
        if (menu == null || menu.pendingClose) {
            return;
        }
        if (menu == this.playerMenu && !isCreative() && !isSpectator()) {
            ensureInventoryOpen();
        }
        int slot = packet.getSlot();
        ContainerActionType mode = packet.getAction();
        ContainerAction param = packet.getParam();
        int button = param == null ? -1 : param.getId();
        if (mode == ContainerActionType.DROP_ITEM) {
            // MCProtocolLib decodes the drop button offset by 2 for real slots: the raw button is id % 2.
            button = button % 2;
        }
        Transaction tx = new Transaction();
        ClickHandler handler = new ClickHandler(this, menu, tx);
        IntSet forced = new IntOpenHashSet(packet.getChangedSlots().keySet());
        IntSet forcedPlayer = new IntOpenHashSet();
        if (slot >= 0 && slot < menu.size()) {
            forced.add(slot);
        }
        switch (mode) {
            case CLICK_ITEM -> handler.pickup(slot, button);
            case SHIFT_CLICK_ITEM -> handler.quickMove(slot);
            case MOVE_TO_HOTBAR_SLOT -> {
                handler.swap(slot, button);
                if (button == 40) {
                    forcedPlayer.add(45);
                } else if (button >= 0 && button < 9) {
                    forced.add(menu.hotbarSlot(button));
                }
            }
            case CREATIVE_GRAB_MAX_STACK -> handler.cloneStack(slot);
            case DROP_ITEM -> handler.throwItem(slot, button);
            case SPREAD_ITEM -> {
                if (!drag(menu, handler, slot, button)) {
                    return;
                }
            }
            case FILL_STACK -> handler.pickupAll(slot, button);
        }
        if (menu.resultSlot >= 0) {
            forced.add(menu.resultSlot);
        }
        submit(tx, menu, forced, forcedPlayer);
    }

    /**
     * QUICK_CRAFT: the Java client sends the start, each slot and the end as separate clicks.
     *
     * @return true at the end of a drag (the distribution was planned), false for the other stages
     */
    private boolean drag(Menu menu, ClickHandler handler, int slot, int button) {
        int stage = button & 3;
        int type = (button >> 2) & 3;
        switch (stage) {
            case 0 -> {
                resetDrag();
                if (type != 2 || isCreative()) {
                    this.dragType = type;
                    this.dragMenu = menu.javaId;
                }
                return false;
            }
            case 1 -> {
                if (this.dragType == type && this.dragMenu == menu.javaId && slot >= 0 && slot < menu.size()) {
                    ItemData carried = this.mirror.ui.get(Mirror.CURSOR);
                    SlotRef ref = menu.slot(slot);
                    ItemData existing = ref == null ? ItemData.AIR : ref.container().get(ref.index());
                    if (ref != null && !ItemTranslator.isAir(carried) && handler.mayPlace(slot, carried)
                            && (ItemTranslator.isAir(existing) || ItemTranslator.stackable(existing, carried))
                            && (type == 2 || carried.getCount() > this.dragSlots.size())) {
                        this.dragSlots.add(slot);
                    }
                }
                return false;
            }
            case 2 -> {
                if (this.dragType == type && this.dragMenu == menu.javaId) {
                    handler.quickCraft(new IntLinkedOpenHashSet(this.dragSlots), type);
                }
                resetDrag();
                return true;
            }
            default -> {
                resetDrag();
                return false;
            }
        }
    }

    private void resetDrag() {
        this.dragType = -1;
        this.dragMenu = -1;
        this.dragSlots.clear();
    }

    /** Like the vanilla client on E: Bedrock expects the inventory to be opened before it is used. */
    private void ensureInventoryOpen() {
        if (this.inventoryWindow >= 0 || this.inventoryOpenRequested || this.openMenu != null) {
            return;
        }
        InteractPacket interact = new InteractPacket();
        interact.setAction(InteractPacket.Action.OPEN_INVENTORY);
        interact.setTargetRuntimeID(this.session.get(PlayerState.KEY).runtimeId());
        this.session.sendBedrock(interact);
        this.inventoryOpenRequested = true;
    }

    void onClose(int javaId) {
        runOrQueue(() -> close(javaId));
    }

    private void close(int javaId) {
        resetDrag();
        if (javaId == 0) {
            returnInputs(this.playerMenu);
            if (this.inventoryWindow >= 0) {
                closeBedrockInventory();
            } else if (this.inventoryOpenRequested) {
                this.closeInventoryWhenOpened = true;
            }
            return;
        }
        Menu menu = this.openMenu;
        if (menu == null || menu.javaId != javaId) {
            return;
        }
        this.openMenu = null;
        menu.pendingClose = true;
        returnInputs(menu);
        if (menu.kind != Menu.Kind.LECTERN) {
            this.closingWindow = menu.bedrockWindowId;
            sendBedrockClose(menu.bedrockWindowId, menu.bedrockType);
        }
        clearWindow(menu);
        flush();
    }

    /**
     * What the Bedrock client does before closing a screen: the items left in its input slots
     * (crafting grid, anvil, enchanting table...) and the cursor go back to the player inventory, or
     * are dropped when it is full. In creative mode, the items parked in the cursor are deleted.
     */
    private void returnInputs(Menu menu) {
        Transaction tx = new Transaction();
        for (int i = 0; i < menu.slots.length; i++) {
            SlotRef slot = menu.slots[i];
            if (slot != null && slot.container() == this.mirror.ui && !menu.isResult(i) && slot.index() != Mirror.CREATED_OUTPUT
                    && slot.index() != Mirror.CURSOR) {
                returnToInventory(tx, slot);
            }
        }
        if (isCreative()) {
            ItemData parked = tx.get(this.mirror.cursor);
            if (!ItemTranslator.isAir(parked)) {
                tx.destroy(this.mirror.cursor, parked.getCount());
            }
        } else {
            returnToInventory(tx, this.mirror.cursor);
        }
        if (!tx.isEmpty()) {
            submit(tx, this.playerMenu, IntSet.of(), IntSet.of());
        }
    }

    /**
     * Moves a slot's items into the player inventory like vanilla {@code placeItemBackInInventory}:
     * stacks with room first (selected slot, off hand, then slots 0-35), then empty slots (0-35), the
     * rest is dropped.
     */
    private void returnToInventory(Transaction tx, SlotRef from) {
        for (int guard = 0; guard < 40; guard++) {
            ItemData item = tx.get(from);
            if (ItemTranslator.isAir(item)) {
                return;
            }
            SlotRef target = roomFor(tx, item);
            if (target == null) {
                tx.drop(from, item.getCount());
                return;
            }
            ItemData existing = tx.get(target);
            int max = ItemTranslator.maxStackSize(this.session, item);
            int room = ItemTranslator.isAir(existing) ? max : max - existing.getCount();
            tx.transfer(from, target, Math.min(room, item.getCount()), false);
        }
    }

    private @Nullable SlotRef roomFor(Transaction tx, ItemData item) {
        int max = ItemTranslator.maxStackSize(this.session, item);
        SlotRef selected = this.mirror.hotbar(this.selectedSlot);
        if (hasRoom(tx.get(selected), item, max)) {
            return selected;
        }
        if (hasRoom(tx.get(this.mirror.offhandSlot), item, max)) {
            return this.mirror.offhandSlot;
        }
        for (int i = 0; i < 36; i++) {
            SlotRef slot = this.mirror.inventorySlot(i);
            if (hasRoom(tx.get(slot), item, max)) {
                return slot;
            }
        }
        for (int i = 0; i < 36; i++) {
            SlotRef slot = this.mirror.inventorySlot(i);
            if (ItemTranslator.isAir(tx.get(slot))) {
                return slot;
            }
        }
        return null;
    }

    private static boolean hasRoom(ItemData existing, ItemData item, int max) {
        return !ItemTranslator.isAir(existing) && ItemTranslator.stackable(existing, item) && existing.getCount() < max;
    }

    private void closeBedrockInventory() {
        this.closingWindow = this.inventoryWindow;
        sendBedrockClose(this.inventoryWindow, ContainerType.INVENTORY);
    }

    private void sendBedrockClose(int windowId, ContainerType type) {
        ContainerClosePacket close = new ContainerClosePacket();
        close.setContainerID((byte) windowId);
        close.setServerInitiatedClose(false);
        close.setContainerType(type);
        this.session.sendBedrock(close);
    }

    /**
     * The server gives the items of a closed screen's UI inputs back to the inventory and only tells
     * about the inventory side: forget the inputs in the mirror.
     */
    /** The window mirror only describes the open window. */
    private void clearWindow(Menu menu) {
        if (menu.kind != Menu.Kind.PLAYER) {
            this.mirror.window.clear();
        }
    }


    void onButtonClick(int javaId, int button) {
        runOrQueue(() -> buttonClick(javaId, button));
    }

    private void buttonClick(int javaId, int button) {
        Menu menu = menu(javaId);
        if (menu == null || menu.pendingClose) {
            return;
        }
        switch (menu.kind) {
            case ENCHANTING -> enchant(menu, button);
            case STONECUTTER -> {
                menu.selectedRecipe = button;
                Results.update(this, menu);
                resync(menu, IntSet.of(menu.resultSlot));
            }
            case LOOM -> {
                menu.selectedPattern = button;
                Results.update(this, menu);
                resync(menu, IntSet.of(menu.resultSlot));
            }
            case LECTERN -> lecternButton(menu, button);
            default -> {
            }
        }
    }

    private void enchant(Menu menu, int button) {
        if (button < 0 || button >= menu.enchantOptions.size()) {
            return;
        }
        ItemEnchantOption option = menu.enchantOptions.get(button);
        SlotRef input = Objects.requireNonNull(menu.slot(0));
        SlotRef lapis = Objects.requireNonNull(menu.slot(1));
        Transaction tx = new Transaction();
        ItemData item = tx.get(input);
        if (ItemTranslator.isAir(item)) {
            return;
        }
        List<ItemStackRequestAction> actions = new ArrayList<>();
        actions.add(new CraftRecipeAction(new RecipeNetId(option.getEnchantNetId()), 1));
        actions.add(new ConsumeAction(item.getCount(), input.info(tx.netId(input))));
        List<Transaction.Change> changes = new ArrayList<>();
        ItemData lapisItem = tx.get(lapis);
        int lapisCost = button + 1;
        if (!isCreative() && !ItemTranslator.isAir(lapisItem)) {
            actions.add(new ConsumeAction(Math.min(lapisCost, lapisItem.getCount()), lapis.info(tx.netId(lapis))));
            changes.add(new Transaction.Change(lapis, ItemTranslator.withCount(lapisItem, lapisItem.getCount() - lapisCost)));
        }
        if (isCreative()) {
            // In creative mode the server keeps the input: clear it before the enchanted copy goes in.
            actions.add(new DestroyAction(item.getCount(), input.info(0)));
        }
        actions.add(new PlaceAction(1, this.mirror.createdOutput.info(0), input.info(0)));
        changes.add(new Transaction.Change(input, enchanted(item, option)));
        tx.request(actions, new String[0], changes);
        submit(tx, menu, IntSet.of(0, 1), IntSet.of());
    }

    /** The predicted result of an enchant option (the server's copy replaces it when it sends the slot). */
    private ItemData enchanted(ItemData item, ItemEnchantOption option) {
        List<NbtMap> list = new ArrayList<>();
        for (List<EnchantmentInstance> group : groups(option.getItemEnchants())) {
            for (EnchantmentInstance instance : group) {
                list.add(NbtMap.builder().putShort("id", (short) instance.getEnchantType())
                        .putShort("lvl", (short) instance.getEnchantLevel()).build());
            }
        }
        ItemDefinition definition = item.getDefinition();
        if ("minecraft:book".equals(definition.getIdentifier())) {
            ItemDefinition book = this.session.get(ItemRegistry.KEY).byIdentifier("minecraft:enchanted_book");
            if (book != null) {
                definition = book;
            }
        }
        NbtMap tag = item.getTag() == null ? NbtMap.EMPTY : item.getTag();
        return item.toBuilder().definition(definition).count(1).usingNetId(false).netId(0)
                .tag(tag.toBuilder().putList("ench", NbtType.COMPOUND, list).build()).build();
    }

    private void lecternButton(Menu menu, int button) {
        Vector3i position = menu.position;
        if (position == null || menu.lecternPages <= 0) {
            return;
        }
        int page = switch (button) {
            case 1 -> Math.max(0, menu.lecternPage - 1);
            case 2 -> Math.min(menu.lecternPages - 1, menu.lecternPage + 1);
            default -> button >= 100 ? Math.min(menu.lecternPages - 1, button - 100) : -1;
        };
        if (page < 0) {
            return;
        }
        menu.lecternPage = page;
        LecternUpdatePacket update = new LecternUpdatePacket();
        update.setNewPageToShow(page);
        update.setTotalPages(menu.lecternPages);
        update.setPositionOfLecternToUpdate(position);
        this.session.sendBedrock(update);
        this.session.sendJava(new ClientboundContainerSetDataPacket(menu.javaId, 0, page));
    }

    void onCreativeSlot(int slot, @Nullable ItemStack item) {
        runOrQueue(() -> creativeSlot(slot, item));
    }

    private void creativeSlot(int slot, @Nullable ItemStack stack) {
        if (!isCreative()) {
            resync(this.playerMenu, slot >= 0 ? IntSet.of(slot) : IntSet.of());
            return;
        }
        Transaction tx = new Transaction();
        ItemData wanted = creativeItem(stack);
        if (slot < 0) {
            // Thrown out of the creative screen.
            ItemData parked = tx.get(this.mirror.cursor);
            if (!ItemTranslator.isAir(wanted)) {
                if (ItemTranslator.stackable(parked, wanted)) {
                    tx.drop(this.mirror.cursor, Math.min(wanted.getCount(), parked.getCount()));
                } else {
                    this.creative.createAndDrop(tx, wanted, wanted.getCount());
                }
            }
            submit(tx, this.playerMenu, IntSet.of(), IntSet.of());
            return;
        }
        SlotRef ref = this.playerMenu.slot(slot);
        if (ref == null) {
            return;
        }
        if (stack != null && stack.getAmount() > 0 && ItemTranslator.isAir(wanted)) {
            // Nothing on the server matches this Java item.
            resync(this.playerMenu, IntSet.of(slot));
            return;
        }
        this.creative.setSlot(tx, ref, wanted, this.mirror.cursor);
        submit(tx, this.playerMenu, IntSet.of(slot), IntSet.of());
    }

    /** The Bedrock item a creative Java stack stands for: its exact identity, else the matching creative entry. */
    private ItemData creativeItem(@Nullable ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0 || stack.getId() == 0) {
            return ItemData.AIR;
        }
        ItemData exact = ItemTranslator.fromIdentity(this.session, stack);
        if (exact != null) {
            return exact;
        }
        int netId = this.creative.netId(stack);
        ItemData entry = netId < 0 ? null : this.creative.item(netId);
        if (entry != null) {
            return entry.toBuilder().count(Math.max(1, stack.getAmount())).usingNetId(false).netId(0).build();
        }
        return ItemTranslator.fromVanilla(this.session, stack);
    }

    void onSetCarriedItem(int slot) {
        if (slot < 0 || slot > 8) {
            return;
        }
        this.selectedSlot = slot;
        // What the vanilla client sends on a hotbar change; the server checks the item against its own.
        MobEquipmentPacket equipment = new MobEquipmentPacket();
        equipment.setTargetRuntimeID(this.session.get(PlayerState.KEY).runtimeId());
        equipment.setItem(heldItem());
        equipment.setSlot(slot);
        equipment.setSelectedSlot(slot);
        equipment.setContainerId(ContainerId.INVENTORY);
        this.session.sendBedrock(equipment);
    }

    void onRename(String name) {
        Menu menu = this.openMenu;
        if (menu != null && menu.kind == Menu.Kind.ANVIL) {
            menu.renameText = name.length() > 64 ? name.substring(0, 64) : name;
            Results.update(this, menu);
            resync(menu, IntSet.of(menu.resultSlot));
        }
    }

    /** Java anvils show the level cost (property 0) and refuse to give the result while it is 0. */
    void setAnvilCost(Menu menu, int cost) {
        if (cost != menu.anvilCost) {
            menu.anvilCost = cost;
            this.session.sendJava(new ClientboundContainerSetDataPacket(menu.javaId, 0, cost));
        }
    }

    void onSelectTrade(int index) {
        runOrQueue(() -> selectTrade(index));
    }

    /** Like the vanilla server: selects the offer and moves its payment from the inventory into the trade slots. */
    private void selectTrade(int index) {
        Menu menu = this.openMenu;
        if (menu == null || menu.kind != Menu.Kind.MERCHANT || menu.merchant == null) {
            return;
        }
        menu.selectedTrade = index;
        Merchant.Offer offer = menu.merchant.offer(index);
        Transaction tx = new Transaction();
        if (offer != null) {
            fillTradeSlot(tx, menu, 0, offer.buyA());
            fillTradeSlot(tx, menu, 1, offer.buyB());
        }
        IntSet forced = new IntOpenHashSet(new int[]{0, 1, 2});
        for (int i = menu.playerStart; i < menu.playerStart + 36; i++) {
            forced.add(i);
        }
        submit(tx, menu, forced, IntSet.of());
    }

    private void fillTradeSlot(Transaction tx, Menu menu, int javaSlot, ItemData price) {
        SlotRef target = Objects.requireNonNull(menu.slot(javaSlot));
        ItemData current = tx.get(target);
        if (!ItemTranslator.isAir(current) && (ItemTranslator.isAir(price) || current.getDefinition() != price.getDefinition()
                || current.getDamage() != price.getDamage())) {
            // Give back what does not pay for this offer.
            new ClickHandler(this, menu, tx).quickMove(javaSlot);
        }
        if (ItemTranslator.isAir(price)) {
            return;
        }
        int max = ItemTranslator.maxStackSize(this.session, price);
        for (int i = menu.playerStart; i < menu.playerStart + 36; i++) {
            ItemData have = tx.get(target);
            int room = max - (ItemTranslator.isAir(have) ? 0 : have.getCount());
            if (room <= 0) {
                return;
            }
            SlotRef source = Objects.requireNonNull(menu.slot(i));
            ItemData item = tx.get(source);
            if (!ItemTranslator.isAir(item) && item.getDefinition() == price.getDefinition() && item.getDamage() == price.getDamage()
                    && (price.getTag() == null || Objects.equals(price.getTag(), item.getTag()))
                    && (ItemTranslator.isAir(have) || ItemTranslator.stackable(have, item))) {
                tx.transfer(source, target, Math.min(room, item.getCount()), false);
            }
        }
    }

    void onSetBeacon(OptionalInt primary, OptionalInt secondary) {
        runOrQueue(() -> {
            Menu menu = this.openMenu;
            if (menu == null || menu.kind != Menu.Kind.BEACON) {
                return;
            }
            SlotRef payment = Objects.requireNonNull(menu.slot(0));
            Transaction tx = new Transaction();
            ItemData item = tx.get(payment);
            if (ItemTranslator.isAir(item)) {
                return;
            }
            // Java sends mob effect registry ids; Bedrock effect ids start at 1, 0 meaning none.
            int first = primary.isPresent() ? Math.max(ItemMappings.get().bedrockEffect(primary.getAsInt()), 0) : 0;
            int second = secondary.isPresent() ? Math.max(ItemMappings.get().bedrockEffect(secondary.getAsInt()), 0) : 0;
            tx.request(List.of(new BeaconPaymentAction(first, second), new DestroyAction(1, payment.info(tx.netId(payment)))),
                    new String[0], List.of(new Transaction.Change(payment, ItemTranslator.withCount(item, item.getCount() - 1))));
            submit(tx, menu, IntSet.of(0), IntSet.of());
        });
    }

    /** Java book editing: replaces the pages of the writable book of a hotbar slot, and signs it when titled. */
    void onEditBook(int slot, List<String> pages, @Nullable String title) {
        if (slot < 0 || slot > 8) {
            return;
        }
        ItemData book = this.mirror.player.get(slot);
        if (ItemTranslator.isAir(book) || !"minecraft:writable_book".equals(book.getDefinition().getIdentifier())) {
            return;
        }
        int existing = book.getTag() == null ? 0 : book.getTag().getList("pages", NbtType.COMPOUND, List.of()).size();
        int count = Math.min(pages.size(), 50);
        for (int i = 0; i < count; i++) {
            String text = pages.get(i).length() > 1024 ? pages.get(i).substring(0, 1024) : pages.get(i);
            if (i < existing) {
                BookEditAction.ReplacePage replace = new BookEditAction.ReplacePage();
                replace.setPageIndex(i);
                replace.setPageText(text);
                replace.setPhotoName("");
                sendBookEdit(slot, replace);
            } else {
                BookEditAction.AddPage add = new BookEditAction.AddPage();
                add.setPageIndex(i);
                add.setPageText(text);
                add.setPhotoName("");
                sendBookEdit(slot, add);
            }
        }
        for (int i = existing - 1; i >= count; i--) {
            BookEditAction.DeletePage delete = new BookEditAction.DeletePage();
            delete.setPageIndex(i);
            sendBookEdit(slot, delete);
        }
        if (title != null) {
            BookEditAction.Finalize sign = new BookEditAction.Finalize();
            sign.setTitle(title.length() > 64 ? title.substring(0, 64) : title);
            sign.setAuthor(this.session.getBedrockName());
            sign.setXuid("");
            sendBookEdit(slot, sign);
        }
    }

    private void sendBookEdit(int slot, BookEditAction action) {
        BookEditPacket packet = new BookEditPacket();
        packet.setBookSlot(slot);
        packet.setOperation(action);
        this.session.sendBedrock(packet);
    }

    /** Crafter slot toggles (Java sends the new enabled state). */
    void onSlotStateChanged(int slot, int javaId, boolean enabled) {
        Menu menu = menu(javaId);
        if (menu == null || menu.kind != Menu.Kind.CRAFTER || slot < 0 || slot > 8 || menu.position == null) {
            return;
        }
        PlayerToggleCrafterSlotRequestPacket packet = new PlayerToggleCrafterSlotRequestPacket();
        packet.setPos(menu.position);
        packet.setSlotIndex((byte) slot);
        packet.setDisabled(!enabled);
        this.session.sendBedrock(packet);
    }

    // ------------------------------------------------------------------ Java sync

    /** Sends the full content of a screen (on open). */
    private void sendContent(Menu menu) {
        ItemStack[] items = new ItemStack[menu.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = javaStack(menu, i);
            menu.setLastSent(i, items[i]);
        }
        this.lastCursor = this.mirror.ui.java(this.session, Mirror.CURSOR);
        this.session.sendJava(new ClientboundContainerSetContentPacket(menu.javaId, menu.nextStateId(), items, this.lastCursor));
    }

    /** Sends the given slots and the cursor whatever was sent before: the Java client may have guessed differently. */
    private void resync(Menu menu, IntSet slots) {
        if (menu.resultSlot >= 0) {
            Results.update(this, menu);
        }
        for (int slot : slots) {
            if (slot >= 0 && slot < menu.size()) {
                ItemStack stack = javaStack(menu, slot);
                this.session.sendJava(new ClientboundContainerSetSlotPacket(menu.javaId, menu.nextStateId(), slot, stack));
                menu.setLastSent(slot, stack);
            }
        }
        this.lastCursor = this.mirror.ui.java(this.session, Mirror.CURSOR);
        this.session.sendJava(new ClientboundSetCursorItemPacket(this.lastCursor));
    }

    /** Sends every slot of the shown screens whose content changed since it was last sent. */
    void flush() {
        if (this.fullContentNeeded) {
            // First content of this session (or after a respawn): the whole window at once.
            this.fullContentNeeded = false;
            this.playerMenu.forgetSent();
            sendContent(this.playerMenu);
        }
        Menu open = this.openMenu;
        if (open != null) {
            sync(open, 0, open.size());
            // Window 0 keeps its own slots (grid, armour, off hand) while another screen is shown.
            sync(this.playerMenu, 0, 9);
            sync(this.playerMenu, 45, 46);
        } else {
            sync(this.playerMenu, 0, this.playerMenu.size());
        }
        ItemStack cursor = this.mirror.ui.java(this.session, Mirror.CURSOR);
        if (!Objects.equals(cursor, this.lastCursor)) {
            this.lastCursor = cursor;
            this.session.sendJava(new ClientboundSetCursorItemPacket(cursor));
        }
    }

    private void sync(Menu menu, int from, int to) {
        if (menu.resultSlot >= from && menu.resultSlot < to) {
            Results.update(this, menu);
        }
        for (int i = from; i < to; i++) {
            ItemStack stack = javaStack(menu, i);
            ItemStack last = menu.lastSent(i);
            if (stack != last && !Objects.equals(stack, last)) {
                this.session.sendJava(new ClientboundContainerSetSlotPacket(menu.javaId, menu.nextStateId(), i, stack));
                menu.setLastSent(i, stack);
            }
        }
    }

    private @Nullable ItemStack javaStack(Menu menu, int slot) {
        if (menu.isResult(slot)) {
            if (menu.resultSource != menu.result) {
                menu.resultSource = menu.result;
                menu.resultJava = ItemTranslator.toJava(this.session, menu.result);
            }
            return menu.resultJava;
        }
        SlotRef ref = menu.slot(slot);
        return ref == null ? null : ref.container().java(this.session, ref.index());
    }

    /**
     * Sends the whole player inventory, the cursor and the selected slot again. To be called after the
     * Java client recreated its player ({@code ClientboundRespawn}: death, dimension change), which
     * empties its inventory.
     */
    public void resendAll() {
        onGameStart();
    }

    /** Called once the Java client is in the game: sends the whole player inventory and the selected slot. */
    void onGameStart() {
        if (this.closed) {
            return;
        }
        this.fullContentNeeded = true;
        flush();
        this.session.sendJava(new ClientboundSetHeldSlotPacket(this.selectedSlot));
    }
}
