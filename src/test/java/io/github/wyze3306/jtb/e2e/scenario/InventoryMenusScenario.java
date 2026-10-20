package io.github.wyze3306.jtb.e2e.scenario;

import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.ItemStackSlotDisplay;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundUpdateRecipesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerButtonClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundRenameItemPacket;

import java.util.List;

/**
 * The special screens against PowerNukkitX, each block placed with {@code /setblock}: blast furnace
 * (smelting, progress properties), stonecutter (recipe button, craft all), enchanting table (options,
 * button), anvil (rename), grindstone (disenchant), smithing table (netherite upgrade), brewing stand,
 * hopper, barrel, ender chest, and a chest to check on the server what the anvil and the
 * grindstone produced. Items go in with shift-clicks, which follow each menu's own rules. The tester
 * must be an operator. Run it for 150 s.
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
        step("check given", 1, () -> check("given items arrived", total(0, 9, 45, this.sword) == 2
                && total(0, 9, 45, this.cobblestone) == 16));

        // -------------------------------------------------------------- blast furnace
        step("open blast furnace", 30, () -> use(blastFurnace));
        step("check blast furnace", 1, () -> check("blast furnace opens a Java blast furnace screen",
                this.openType == ContainerType.BLAST_FURNACE && size(this.openId) == 39));
        step("ore in", 15, () -> shiftClick(3, 39, this.rawIron));
        step("fuel in", 15, () -> shiftClick(3, 39, this.coal));
        step("check furnace inputs", 1, () -> check("shift click: ore to the input, coal to the fuel slot (burning at once)",
                is(this.openId, 0, this.rawIron, 1) && total(this.openId, 3, 39, this.coal) == 0
                        && (is(this.openId, 1, this.coal, 1) || property(this.openId, 0) > 0)));
        step("smelting", 160, () -> this.context.log("Waiting for the blast furnace"));
        step("check smelted", 1, () -> {
            check("blast furnace smelted the ore (server slot updates)", is(this.openId, 2, this.ironIngot, 1) && empty(this.openId, 0));
            check("furnace properties sent (fuel and progress)", property(this.openId, 0) != Integer.MIN_VALUE
                    && property(this.openId, 1) != Integer.MIN_VALUE && property(this.openId, 3) == 100);
        });
        step("take ingot", 15, () -> click(this.openId, 2, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK));
        step("check ingot", 1, () -> check("shift click takes the result", total(this.openId, 3, 39, this.ironIngot) == 1
                && empty(this.openId, 2)));
        step("close blast furnace", 15, this::close);
        serverCount("iron_ingot", 1);

        // -------------------------------------------------------------- stonecutter
        step("open stonecutter", 30, () -> use(stonecutter));
        step("check stonecutter", 1, () -> check("stonecutter opens a Java stonecutter screen",
                this.openType == ContainerType.STONECUTTER && size(this.openId) == 38));
        step("stone in", 15, () -> shiftClick(2, 38, this.stone));
        step("select bricks", 15, () -> {
            int index = stonecutterIndex(this.stone, this.stoneBricks);
            check("stonecutter recipes sent for stone -> stone bricks", index >= 0);
            this.context.send(new ServerboundContainerButtonClickPacket(this.openId, Math.max(index, 0)));
        });
        step("check bricks result", 1, () -> check("selected recipe shows its result",
                is(this.openId, 0, this.stone, 4) && is(this.openId, 1, this.stoneBricks, 1)));
        step("cut all", 20, () -> click(this.openId, 1, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK));
        step("check cut", 1, () -> check("shift click cuts the whole stack", total(this.openId, 2, 38, this.stoneBricks) == 4
                && empty(this.openId, 0) && empty(this.openId, 1)));
        step("close stonecutter", 15, this::close);
        serverCount("stone_bricks", 4);

        // -------------------------------------------------------------- enchanting table
        step("open enchanting table", 30, () -> use(enchanting));
        step("check enchanting table", 1, () -> check("enchanting table opens a Java enchantment screen",
                this.openType == ContainerType.ENCHANTMENT && size(this.openId) == 38));
        step("lapis in", 15, () -> shiftClick(2, 38, this.lapis));
        step("sword in", 30, () -> shiftClick(2, 38, this.sword));
        step("check options", 1, () -> check("enchantment options shown (level costs)",
                is(this.openId, 0, this.sword, 1) && is(this.openId, 1, this.lapis, 3) && property(this.openId, 0) > 0));
        step("enchant", 25, () -> this.context.send(new ServerboundContainerButtonClickPacket(this.openId, 0)));
        step("check enchanted", 1, () -> check("first option enchants the sword and takes one lapis",
                enchanted(item(this.openId, 0)) && is(this.openId, 1, this.lapis, 2)));
        step("sword out", 15, () -> click(this.openId, 0, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK));
        step("close enchanting table", 15, this::close);
        serverCount("lapis_lazuli", 2);

        // -------------------------------------------------------------- anvil
        step("open anvil", 30, () -> use(anvil));
        step("check anvil", 1, () -> check("anvil opens a Java anvil screen", this.openType == ContainerType.ANVIL && size(this.openId) == 39));
        step("plain sword in", 15, () -> {
            int slot = findSword(3, 39, false);
            click(this.openId, slot, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK);
        });
        step("rename", 20, () -> this.context.send(new ServerboundRenameItemPacket("Excalibur")));
        step("check rename result", 1, () -> check("renaming shows the named result and its cost",
                count(item(this.openId, 2), this.sword) == 1 && "Excalibur".equals(customName(item(this.openId, 2)))
                        && property(this.openId, 0) >= 1));
        step("take named", 2, () -> click(this.openId, 2, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("place named", 15, () -> click(this.openId, free(this.openId, 3, 39), ContainerActionType.CLICK_ITEM,
                ClickItemAction.LEFT_CLICK));
        step("check named", 1, () -> check("named sword taken, input consumed", findNamed(3, 39) >= 0 && empty(this.openId, 0)
                && cursorEmpty()));
        step("close anvil", 15, this::close);

        // -------------------------------------------------------------- grindstone
        step("open grindstone", 30, () -> use(grindstone));
        step("check grindstone", 1, () -> check("grindstone opens a Java grindstone screen",
                this.openType == ContainerType.GRINDSTONE && size(this.openId) == 39));
        step("enchanted sword in", 15, () -> {
            int slot = findSword(3, 39, true);
            click(this.openId, slot, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK);
        });
        step("check grindstone result", 1, () -> check("grindstone result is the disenchanted sword",
                count(item(this.openId, 2), this.sword) == 1 && !enchanted(item(this.openId, 2))));
        step("take disenchanted", 2, () -> click(this.openId, 2, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK));
        step("place disenchanted", 15, () -> click(this.openId, free(this.openId, 3, 39), ContainerActionType.CLICK_ITEM,
                ClickItemAction.LEFT_CLICK));
        step("check disenchanted", 1, () -> check("disenchanted sword taken", findSword(3, 39, true) < 0
                && total(this.openId, 3, 39, this.sword) == 2 && empty(this.openId, 0) && empty(this.openId, 1)));
        step("close grindstone", 15, this::close);

        // -------------------------------------------------------------- chest: what the server really has
        step("open chest", 30, () -> use(chest));
        step("store named", 15, () -> click(this.openId, findNamed(27, 63), ContainerActionType.SHIFT_CLICK_ITEM,
                ShiftClickItemAction.LEFT_CLICK));
        step("store plain", 15, () -> click(this.openId, find(this.openId, 27, 63, this.sword), ContainerActionType.SHIFT_CLICK_ITEM,
                ShiftClickItemAction.LEFT_CLICK));
        step("close chest", 20, this::close);
        step("reopen chest", 30, () -> use(chest));
        step("check chest", 1, () -> {
            check("server kept the anvil's name", "Excalibur".equals(customName(item(this.openId, 0))));
            check("server kept the grindstone's disenchanting", count(item(this.openId, 1), this.sword) == 1
                    && !enchanted(item(this.openId, 1)));
        });
        step("close chest 2", 15, this::close);

        // -------------------------------------------------------------- smithing table
        step("open smithing table", 30, () -> use(smithing));
        step("check smithing table", 1, () -> check("smithing table opens a Java smithing screen",
                this.openType == ContainerType.SMITHING && size(this.openId) == 40));
        step("template in", 15, () -> shiftClick(4, 40, this.template));
        step("base in", 15, () -> shiftClick(4, 40, this.pickaxe));
        step("addition in", 15, () -> shiftClick(4, 40, this.netherite));
        step("check smithing result", 1, () -> check("netherite upgrade: template, base and addition give the result",
                is(this.openId, 0, this.template, 1) && is(this.openId, 1, this.pickaxe, 1) && is(this.openId, 2, this.netherite, 1)
                        && is(this.openId, 3, this.netheritePickaxe, 1)));
        step("take upgrade", 20, () -> click(this.openId, 3, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK));
        step("check upgrade", 1, () -> check("upgraded pickaxe taken, inputs consumed",
                total(this.openId, 4, 40, this.netheritePickaxe) == 1 && empty(this.openId, 0) && empty(this.openId, 1)
                        && empty(this.openId, 2)));
        step("close smithing table", 15, this::close);
        serverCount("netherite_pickaxe", 1);

        // -------------------------------------------------------------- brewing stand
        step("open brewing stand", 30, () -> use(brewing));
        step("check brewing stand", 1, () -> check("brewing stand opens a Java brewing screen",
                this.openType == ContainerType.BREWING_STAND && size(this.openId) == 41));
        step("fuel in", 20, () -> shiftClick(5, 41, this.blazePowder));
        step("check fuel", 1, () -> {
            check("shift click: blaze powder to the fuel slot", is(this.openId, 4, this.blazePowder, 1) || property(this.openId, 1) > 0);
            this.context.log("Brewing fuel property: " + property(this.openId, 1));
        });
        step("close brewing stand", 15, this::close);

        // -------------------------------------------------------------- storage blocks
        step("open hopper", 30, () -> use(hopper));
        step("check hopper", 1, () -> check("hopper opens a Java hopper screen", this.openType == ContainerType.HOPPER && size(this.openId) == 41));
        step("store in hopper", 15, () -> shiftClick(5, 41, this.cobblestone));
        step("check hopper store", 1, () -> check("shift click stores in the hopper", is(this.openId, 0, this.cobblestone, 16)));
        step("close hopper", 15, this::close);
        step("open ender chest", 30, () -> use(enderChest));
        step("check ender chest", 1, () -> check("ender chest opens a Java 9x3 screen",
                this.openType == ContainerType.GENERIC_9X3 && size(this.openId) == 63));
        step("close ender chest", 15, this::close);
        step("open barrel", 30, () -> use(barrel));
        step("check barrel", 1, () -> check("barrel opens a Java 9x3 screen", this.openType == ContainerType.GENERIC_9X3
                && size(this.openId) == 63));
        step("close barrel", 15, this::close);
        step("done", 1, () -> this.context.log("Inventory menus scenario finished"));
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
