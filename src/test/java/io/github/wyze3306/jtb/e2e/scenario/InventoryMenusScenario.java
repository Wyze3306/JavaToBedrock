package io.github.wyze3306.jtb.e2e.scenario;

import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.WrittenBookContent;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.ItemStackSlotDisplay;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundUpdateRecipesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerButtonClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundEditBookPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundRenameItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetBeaconPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;

import java.util.List;
import java.util.OptionalInt;

/**
 * The special screens against PowerNukkitX, each block placed with {@code /setblock}: blast furnace
 * (smelting, progress properties), stonecutter (recipe button, craft all), enchanting table (options,
 * button), anvil (rename), grindstone (disenchant), smithing table (netherite upgrade), brewing stand,
 * hopper, barrel, ender chest, loom (pattern button), beacon (payment), book and quill (writing,
 * signing), hotbar selection, a double chest placed by the player, and a chest to check on the server what the anvil, the grindstone, the
 * loom and the book editing produced. Items go in with shift-clicks, which follow each menu's own
 * rules. The tester must be an operator. Run it for 220 s.
 */
public final class InventoryMenusScenario extends InventoryScript {

    private final int rawIron = id("raw_iron");
    private final int coal = id("coal");
    private final int ironIngot = id("iron_ingot");
    private final int stone = id("stone");
    private final int stoneBricks = id("stone_bricks");
    private final int sword = id("diamond_sword");
    private final int lapis = id("lapis_lazuli");
    private final int pickaxe = id("diamond_pickaxe");
    private final int template = id("netherite_upgrade_smithing_template");
    private final int netherite = id("netherite_ingot");
    private final int netheritePickaxe = id("netherite_pickaxe");
    private final int blazePowder = id("blaze_powder");
    private final int cobblestone = id("cobblestone");
    private final int whiteBanner = id("white_banner");
    private final int redDye = id("red_dye");
    private final int writableBook = id("writable_book");
    private final int writtenBook = id("written_book");
    private final int dirt = id("dirt");
    private final int chestItem = id("chest");

    private int bookSlot;
    private int ingots;
    private int chatMark;

    @Override
    protected void script() {
        Vector3i blastFurnace = this.feet.add(2, 0, 0);
        Vector3i stonecutter = this.feet.add(-2, 0, 0);
        Vector3i enchanting = this.feet.add(0, 0, -2);
        Vector3i anvil = this.feet.add(0, 0, 2);
        Vector3i grindstone = this.feet.add(2, 0, 2);
        Vector3i smithing = this.feet.add(-2, 0, 2);
        Vector3i brewing = this.feet.add(2, 0, -2);
        Vector3i chest = this.feet.add(-2, 0, -2);
        Vector3i hopper = this.feet.add(3, 0, 0);
        Vector3i barrel = this.feet.add(0, 0, 3);
        Vector3i enderChest = this.feet.add(0, 0, -3);
        Vector3i loom = this.feet.add(-3, 0, 0);
        Vector3i beacon = this.feet.add(-3, 0, 3);
        Vector3i placed = this.feet.add(1, 0, -1);

        step("blocks 1", 20, () -> {
            setblock(blastFurnace, "blast_furnace");
            setblock(stonecutter, "stonecutter_block");
            setblock(enchanting, "enchanting_table");
            setblock(anvil, "anvil");
            setblock(grindstone, "grindstone [\"attachment\"=\"standing\"]");
            setblock(smithing, "smithing_table");
        });
        step("blocks 2", 20, () -> {
            setblock(brewing, "brewing_stand");
            setblock(chest, "chest");
            setblock(hopper, "hopper");
            setblock(barrel, "barrel");
            setblock(enderChest, "ender_chest");
        });
        step("blocks 3", 20, () -> {
            setblock(loom, "loom");
            // An active beacon: a 3x3 iron pyramid under it and the sky above.
            command("fill " + (beacon.getX() - 1) + " " + (beacon.getY() - 1) + " " + (beacon.getZ() - 1) + " "
                    + (beacon.getX() + 1) + " " + (beacon.getY() - 1) + " " + (beacon.getZ() + 1) + " iron_block");
            command("fill " + beacon.getX() + " " + (beacon.getY() + 1) + " " + beacon.getZ() + " "
                    + beacon.getX() + " " + (beacon.getY() + 60) + " " + beacon.getZ() + " air");
            setblock(beacon, "beacon");
        });
        step("give 1", 20, () -> {
            command("xp 30L " + this.name);
            command("give " + this.name + " raw_iron 1");
            command("give " + this.name + " coal 1");
            command("give " + this.name + " stone 4");
            command("give " + this.name + " diamond_sword 2");
            command("give " + this.name + " lapis_lazuli 3");
        });
        step("give 2", 30, () -> {
            command("give " + this.name + " diamond_pickaxe 1");
            command("give " + this.name + " netherite_upgrade_smithing_template 1");
            command("give " + this.name + " netherite_ingot 1");
            command("give " + this.name + " blaze_powder 1");
            command("give " + this.name + " cobblestone 16");
        });
        step("give 3", 30, () -> {
            command("give " + this.name + " banner 1 15");
            command("give " + this.name + " red_dye 1");
            command("give " + this.name + " writable_book 1");
            command("give " + this.name + " dirt 1");
            command("give " + this.name + " chest 3");
        });
        expect("given items arrived", () -> total(0, 9, 45, this.sword) == 2
                && total(0, 9, 45, this.cobblestone) == 16);

        // -------------------------------------------------------------- blast furnace
        step("open blast furnace", 30, () -> use(blastFurnace));
        expect("blast furnace opens a Java blast furnace screen", () -> this.openType == ContainerType.BLAST_FURNACE && size(this.openId) == 39);
        emptyContainer("blast furnace", 3);
        step("ore in", 15, () -> shiftClick(3, 39, this.rawIron));
        step("fuel in", 15, () -> shiftClick(3, 39, this.coal));
        expect("shift click: ore to the input, coal to the fuel slot (burning at once)", () -> is(this.openId, 0, this.rawIron, 1) && total(this.openId, 3, 39, this.coal) == 0
                        && (is(this.openId, 1, this.coal, 1) || property(this.openId, 0) > 0));
        // The furnace ticks with the world, which PowerNukkitX slows down when it is busy.
        expect("blast furnace smelted the ore (server slot updates)", 800, () -> count(item(this.openId, 2), this.ironIngot) >= 1 && empty(this.openId, 0));
        expect("furnace properties sent (fuel and progress)", () -> property(this.openId, 0) != Integer.MIN_VALUE
                && property(this.openId, 1) != Integer.MIN_VALUE && property(this.openId, 3) == 100);
        step("take ingot", 15, () -> {
            this.ingots = count(item(this.openId, 2), this.ironIngot);
            click(this.openId, 2, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK);
        });
        expect("shift click takes the result", () -> total(this.openId, 3, 39, this.ironIngot) == this.ingots
                && empty(this.openId, 2));
        step("close blast furnace", 15, this::close);
        serverCount("iron_ingot", () -> this.ingots);

        // -------------------------------------------------------------- stonecutter
        step("open stonecutter", 30, () -> use(stonecutter));
        expect("stonecutter opens a Java stonecutter screen", () -> this.openType == ContainerType.STONECUTTER && size(this.openId) == 38);
        step("stone in", 15, () -> shiftClick(2, 38, this.stone));
        step("select bricks", 15, () -> {
            int index = stonecutterIndex(this.stone, this.stoneBricks);
            check("stonecutter recipes sent for stone -> stone bricks", index >= 0);
            this.context.send(new ServerboundContainerButtonClickPacket(this.openId, Math.max(index, 0)));
        });
        expect("selected recipe shows its result", () -> is(this.openId, 0, this.stone, 4) && is(this.openId, 1, this.stoneBricks, 1));
        step("cut all", 20, () -> click(this.openId, 1, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK));
        expect("shift click cuts the whole stack", () -> total(this.openId, 2, 38, this.stoneBricks) == 4
                && empty(this.openId, 0) && empty(this.openId, 1));
        step("close stonecutter", 15, this::close);
        serverCount("stone_bricks", 4);

        // -------------------------------------------------------------- enchanting table
        step("open enchanting table", 30, () -> use(enchanting));
        expect("enchanting table opens a Java enchantment screen", () -> this.openType == ContainerType.ENCHANTMENT && size(this.openId) == 38);
        step("lapis in", 15, () -> shiftClick(2, 38, this.lapis));
        step("sword in", 30, () -> shiftClick(2, 38, this.sword));
        expect("enchantment options shown (level costs)", () -> is(this.openId, 0, this.sword, 1) && is(this.openId, 1, this.lapis, 3) && property(this.openId, 0) > 0);
        step("enchant", 25, () -> this.context.send(new ServerboundContainerButtonClickPacket(this.openId, 0)));
        expect("first option enchants the sword and takes one lapis", () -> enchanted(item(this.openId, 0)) && is(this.openId, 1, this.lapis, 2));
        step("sword out", 15, () -> click(this.openId, 0, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK));
        step("close enchanting table", 15, this::close);
        serverCount("lapis_lazuli", 2);

        // -------------------------------------------------------------- anvil
        step("open anvil", 30, () -> use(anvil));
        expect("anvil opens a Java anvil screen", () -> this.openType == ContainerType.ANVIL && size(this.openId) == 39);
        step("plain sword in", 15, () -> {
            int slot = findSword(3, 39, false);
            click(this.openId, slot, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK);
        });
        step("rename", 20, () -> this.context.send(new ServerboundRenameItemPacket("Excalibur")));
        expect("renaming shows the named result and its cost", () -> count(item(this.openId, 2), this.sword) == 1 && "Excalibur".equals(customName(item(this.openId, 2)))
                        && property(this.openId, 0) >= 1);
        step("take named", 2, () -> click(this.openId, 2, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("place named", 15, () -> click(this.openId, free(this.openId, 3, 39), ContainerActionType.CLICK_ITEM,
                ClickItemAction.LEFT_CLICK));
        expect("named sword taken, input consumed", () -> findNamed(3, 39) >= 0 && empty(this.openId, 0)
                && cursorEmpty());
        step("close anvil", 15, this::close);

        // -------------------------------------------------------------- grindstone
        step("open grindstone", 30, () -> use(grindstone));
        expect("grindstone opens a Java grindstone screen", () -> this.openType == ContainerType.GRINDSTONE && size(this.openId) == 39);
        step("enchanted sword in", 15, () -> {
            int slot = findSword(3, 39, true);
            click(this.openId, slot, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK);
        });
        expect("grindstone result is the disenchanted sword", () -> count(item(this.openId, 2), this.sword) == 1 && !enchanted(item(this.openId, 2)));
        step("take disenchanted", 2, () -> click(this.openId, 2, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("place disenchanted", 15, () -> click(this.openId, free(this.openId, 3, 39), ContainerActionType.CLICK_ITEM,
                ClickItemAction.LEFT_CLICK));
        expect("disenchanted sword taken", () -> findSword(3, 39, true) < 0
                && total(this.openId, 3, 39, this.sword) == 2 && empty(this.openId, 0) && empty(this.openId, 1));
        step("close grindstone", 15, this::close);

        // -------------------------------------------------------------- chest: what the server really has
        step("open chest", 2, () -> use(chest));
        expect("chest open", () -> this.openId > 0 && this.openType == ContainerType.GENERIC_9X3);
        emptyContainer("chest", 27);
        step("store named", 2, () -> click(this.openId, findNamed(27, 63), ContainerActionType.SHIFT_CLICK_ITEM,
                ShiftClickItemAction.LEFT_CLICK));
        expect("named sword stored", () -> "Excalibur".equals(customName(item(this.openId, 0))));
        step("store plain", 2, () -> click(this.openId, find(this.openId, 27, 63, this.sword), ContainerActionType.SHIFT_CLICK_ITEM,
                ShiftClickItemAction.LEFT_CLICK));
        expect("plain sword stored", () -> count(item(this.openId, 1), this.sword) == 1);
        step("close chest", 20, this::close);
        step("reopen chest", 2, () -> use(chest));
        expect("server kept the anvil's name", () -> this.openId > 0 && "Excalibur".equals(customName(item(this.openId, 0))));
        expect("server kept the grindstone's disenchanting", () -> count(item(this.openId, 1), this.sword) == 1
                && !enchanted(item(this.openId, 1)));
        step("close chest 2", 15, this::close);

        // -------------------------------------------------------------- smithing table
        step("open smithing table", 30, () -> use(smithing));
        expect("smithing table opens a Java smithing screen", () -> this.openType == ContainerType.SMITHING && size(this.openId) == 40);
        step("template in", 15, () -> shiftClick(4, 40, this.template));
        step("base in", 15, () -> shiftClick(4, 40, this.pickaxe));
        step("addition in", 15, () -> shiftClick(4, 40, this.netherite));
        expect("netherite upgrade: template, base and addition give the result", () -> is(this.openId, 0, this.template, 1) && is(this.openId, 1, this.pickaxe, 1) && is(this.openId, 2, this.netherite, 1)
                        && is(this.openId, 3, this.netheritePickaxe, 1));
        step("take upgrade", 20, () -> click(this.openId, 3, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK));
        expect("upgraded pickaxe taken, inputs consumed", () -> total(this.openId, 4, 40, this.netheritePickaxe) == 1 && empty(this.openId, 0) && empty(this.openId, 1)
                        && empty(this.openId, 2));
        step("close smithing table", 15, this::close);
        serverCount("netherite_pickaxe", 1);

        // -------------------------------------------------------------- brewing stand
        step("open brewing stand", 30, () -> use(brewing));
        expect("brewing stand opens a Java brewing screen", () -> this.openType == ContainerType.BREWING_STAND && size(this.openId) == 41);
        emptyContainer("brewing stand", 5);
        step("fuel in", 20, () -> shiftClick(5, 41, this.blazePowder));
        expect("shift click: blaze powder to the fuel slot", () -> count(item(this.openId, 4), this.blazePowder) >= 1 || property(this.openId, 1) > 0);
        step("close brewing stand", 15, this::close);

        // -------------------------------------------------------------- storage blocks
        step("open hopper", 30, () -> use(hopper));
        expect("hopper opens a Java hopper screen", () -> this.openType == ContainerType.HOPPER && size(this.openId) == 41);
        emptyContainer("hopper", 5);
        step("store in hopper", 15, () -> shiftClick(5, 41, this.cobblestone));
        expect("shift click stores in the hopper", () -> is(this.openId, 0, this.cobblestone, 16));
        step("close hopper", 15, this::close);
        step("open ender chest", 30, () -> use(enderChest));
        expect("ender chest opens a Java 9x3 screen", () -> this.openType == ContainerType.GENERIC_9X3 && size(this.openId) == 63);
        step("close ender chest", 15, this::close);
        step("open barrel", 30, () -> use(barrel));
        expect("barrel opens a Java 9x3 screen", () -> this.openType == ContainerType.GENERIC_9X3
                && size(this.openId) == 63);
        step("close barrel", 15, this::close);

        // -------------------------------------------------------------- loom
        step("open loom", 30, () -> use(loom));
        expect("loom opens a Java loom screen", () -> this.openType == ContainerType.LOOM && size(this.openId) == 40);
        step("banner in", 15, () -> shiftClick(4, 40, this.whiteBanner));
        step("dye in", 15, () -> shiftClick(4, 40, this.redDye));
        step("pattern", 20, () -> this.context.send(new ServerboundContainerButtonClickPacket(this.openId, 0)));
        expect("loom result: the banner with one red layer", () -> is(this.openId, 0, this.whiteBanner, 1) && is(this.openId, 1, this.redDye, 1)
                        && count(item(this.openId, 3), this.whiteBanner) == 1 && layers(item(this.openId, 3)) == 1);
        step("take banner", 20, () -> click(this.openId, 3, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK));
        expect("patterned banner taken, banner and dye consumed", () -> empty(this.openId, 0)
                && empty(this.openId, 1) && total(this.openId, 4, 40, this.whiteBanner) == 1);
        step("close loom", 15, this::close);

        // -------------------------------------------------------------- beacon
        step("open beacon", 30, () -> use(beacon));
        expect("beacon opens a Java beacon screen", () -> this.openType == ContainerType.BEACON
                && size(this.openId) == 37);
        step("payment in", 15, () -> shiftClick(1, 37, this.ironIngot));
        expect("shift click: the ingot goes to the payment slot", () -> count(item(this.openId, 0), this.ironIngot) >= 1);
        step("set beacon", 20, () -> this.context.send(new ServerboundSetBeaconPacket(OptionalInt.of(0), OptionalInt.empty())));
        expect("beacon power chosen: the payment is consumed", () -> empty(this.openId, 0));
        step("close beacon", 15, this::close);
        serverCount("iron_ingot", () -> this.ingots - 1);
        step("close inventory", 10, this::close);

        // -------------------------------------------------------------- book and quill
        step("book to hotbar", 15, () -> click(0, find(0, 9, 45, this.writableBook), ContainerActionType.MOVE_TO_HOTBAR_SLOT,
                MoveToHotbarAction.SLOT_5));
        step("dirt to hotbar", 15, () -> click(0, find(0, 9, 45, this.dirt), ContainerActionType.MOVE_TO_HOTBAR_SLOT,
                MoveToHotbarAction.SLOT_6));
        expect("book and dirt moved to the hotbar", () -> is(0, 40, this.writableBook, 1) && is(0, 41, this.dirt, 1));
        step("close inventory 2", 10, this::close);
        step("select book", 10, () -> {
            this.bookSlot = 4;
            this.context.send(new ServerboundSetCarriedItemPacket(this.bookSlot));
        });
        step("write", 20, () -> this.context.send(new ServerboundEditBookPacket(this.bookSlot, List.of("Hello", "World"), null)));
        step("sign", 30, () -> this.context.send(new ServerboundEditBookPacket(this.bookSlot, List.of("Hello", "World"), "Notes")));
        expect("signing turns the book into a written book (server update)", () -> count(item(0, 36 + this.bookSlot), this.writtenBook) == 1);

        // -------------------------------------------------------------- hotbar selection on the server
        // PowerNukkitX checks the use against the held item: let the selection reach it first (a busy world
        // may handle the player's packets only once a second).
        step("select dirt", 40, () -> this.context.send(new ServerboundSetCarriedItemPacket(5)));
        step("place dirt", 40, () -> use(placed.add(0, -1, 0)));
        step("test dirt", 20, () -> {
            this.chatMark = this.context.chat().size();
            command("testforblock " + placed.getX() + " " + placed.getY() + " " + placed.getZ() + " dirt");
        });
        expect("the server places the newly selected hotbar item", () -> this.context.chat().subList(this.chatMark, this.context.chat().size()).stream().anyMatch(l -> l.contains("Successfully")));

        // -------------------------------------------------------------- what the server has
        step("open chest 3", 2, () -> use(chest));
        expect("chest open 3", () -> this.openId > 0 && this.openType == ContainerType.GENERIC_9X3);
        step("store banner", 2, () -> shiftClick(27, 63, this.whiteBanner));
        expect("banner stored", () -> find(this.openId, 0, 27, this.whiteBanner) >= 0);
        step("store book", 2, () -> shiftClick(27, 63, this.writtenBook));
        expect("book stored", () -> find(this.openId, 0, 27, this.writtenBook) >= 0);
        step("close chest 3", 20, this::close);
        step("reopen chest 3", 2, () -> use(chest));
        expect("server kept the banner pattern", () -> {
            int banner = find(this.openId, 0, 27, this.whiteBanner);
            return this.openId > 0 && banner >= 0 && layers(item(this.openId, banner)) == 1;
        });
        expect("server kept the signed book", () -> {
            int book = find(this.openId, 0, 27, this.writtenBook);
            return book >= 0 && bookTitle(item(this.openId, book)).equals("Notes") && bookPages(item(this.openId, book)) == 2;
        });
        step("close chest 4", 15, this::close);

        // -------------------------------------------------------------- double chest placed by the player
        step("chests to hotbar", 15, () -> click(0, find(0, 9, 45, this.chestItem), ContainerActionType.MOVE_TO_HOTBAR_SLOT,
                MoveToHotbarAction.SLOT_7));
        expect("chests moved to the hotbar", () -> is(0, 42, this.chestItem, 3));
        step("close inventory 3", 10, this::close);
        step("select chests", 10, () -> this.context.send(new ServerboundSetCarriedItemPacket(6)));
        // An L of three chests: whatever the facing, two of them pair.
        step("place chest 1", 15, () -> use(this.feet.add(2, -1, 3)));
        step("place chest 2", 15, () -> use(this.feet.add(3, -1, 3)));
        step("place chest 3", 15, () -> use(this.feet.add(2, -1, 4)));
        step("open double chest", 30, () -> use(this.feet.add(2, 0, 3)));
        expect("a double chest opens a Java 9x6 screen", () -> this.openType == ContainerType.GENERIC_9X6 && size(this.openId) == 90);
        emptyContainer("double chest", 54);
        step("store in double chest", 15, () -> shiftClick(54, 90, this.netheritePickaxe));
        expect("shift click stores in the double chest", () -> is(this.openId, 0, this.netheritePickaxe, 1));
        step("close double chest", 15, this::close);
        step("done", 1, () -> this.context.log("Inventory menus scenario finished"));
    }

    private static int layers(ItemStack item) {
        if (item == null || item.getDataComponentsPatch() == null) {
            return 0;
        }
        List<?> patterns = item.getDataComponentsPatch().get(DataComponentTypes.BANNER_PATTERNS);
        return patterns == null ? 0 : patterns.size();
    }

    private static String bookTitle(ItemStack item) {
        WrittenBookContent content = item == null || item.getDataComponentsPatch() == null ? null
                : item.getDataComponentsPatch().get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        return content == null ? "" : content.getTitle().getRaw();
    }

    private static int bookPages(ItemStack item) {
        WrittenBookContent content = item == null || item.getDataComponentsPatch() == null ? null
                : item.getDataComponentsPatch().get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        return content == null ? 0 : content.getPages().size();
    }

    private void shiftClick(int from, int to, int id) {
        int slot = find(this.openId, from, to, id);
        if (slot < 0) {
            throw new IllegalStateException("no item " + id + " in " + from + ".." + to);
        }
        click(this.openId, slot, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK);
    }

    private int findSword(int from, int to, boolean enchanted) {
        for (int slot = from; slot < to; slot++) {
            ItemStack item = item(this.openId, slot);
            if (count(item, this.sword) > 0 && enchanted(item) == enchanted) {
                return slot;
            }
        }
        return -1;
    }

    private int findNamed(int from, int to) {
        for (int slot = from; slot < to; slot++) {
            if ("Excalibur".equals(customName(item(this.openId, slot)))) {
                return slot;
            }
        }
        return -1;
    }

    /** Index of the button the Java stonecutter shows for {@code input -> result}, like the client numbers them. */
    private int stonecutterIndex(int input, int result) {
        ClientboundUpdateRecipesPacket packet = this.recipes;
        if (packet == null) {
            return -1;
        }
        int index = 0;
        List<ClientboundUpdateRecipesPacket.SelectableRecipe> recipes = packet.getStonecutterRecipes();
        for (ClientboundUpdateRecipesPacket.SelectableRecipe recipe : recipes) {
            if (!recipe.input().getValues().getHolders().contains(input)) {
                continue;
            }
            if (recipe.recipe() instanceof ItemStackSlotDisplay display && display.itemStack().getId() == result) {
                return index;
            }
            index++;
        }
        return -1;
    }
}
