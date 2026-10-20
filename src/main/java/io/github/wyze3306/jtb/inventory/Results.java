package io.github.wyze3306.jtb.inventory;

import io.github.wyze3306.jtb.item.ItemMappings;
import io.github.wyze3306.jtb.item.ItemTranslator;
import io.github.wyze3306.jtb.mapping.JavaRegistries;
import io.github.wyze3306.jtb.session.ProxySession;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftGrindstoneAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftLoomAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftRecipeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftRecipeOptionalAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.RecipeNetId;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.SmithingTransformRecipePayload;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.SmithingTrimRecipePayload;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Computes the content of the result slots Java leaves to the server (crafting grids, stonecutter,
 * anvil, grindstone, loom, cartography table, smithing table, trades, crafter preview) from the
 * mirrored inputs, and how taking it is sent ({@link Craft}). Results follow PowerNukkitX's own rules
 * where it computes them itself (anvil, grindstone, cartography, loom); the server's answer always
 * replaces the prediction afterwards.
 */
final class Results {

    private static final String[] DYE_COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    private Results() {
    }

    /** Recomputes {@code menu.result} and {@code menu.craft}. */
    static void update(InventoryState state, Menu menu) {
        ItemData result = ItemData.AIR;
        Craft craft = null;
        ProxySession session = state.session();
        switch (menu.kind) {
            case PLAYER, CRAFTING, CRAFTER -> {
                int size = menu.kind == Menu.Kind.PLAYER ? 2 : 3;
                int first = menu.kind == Menu.Kind.CRAFTER ? 0 : 1;
                List<SlotRef> grid = new ArrayList<>(size * size);
                ItemData[] items = new ItemData[size * size];
                for (int i = 0; i < size * size; i++) {
                    SlotRef slot = Objects.requireNonNull(menu.slot(first + i));
                    grid.add(slot);
                    items[i] = slot.container().get(slot.index());
                }
                Crafting.GridMatch match = state.crafting().matchGrid(items, size);
                if (match != null) {
                    result = match.recipe().result();
                    if (menu.kind != Menu.Kind.CRAFTER) {
                        craft = gridCraft(match, grid, items);
                    }
                }
            }
            case STONECUTTER -> {
                SlotRef input = Objects.requireNonNull(menu.slot(0));
                ItemData item = input.container().get(input.index());
                List<Crafting.GridRecipe> recipes = state.crafting().javaStonecutterRecipes(item);
                if (menu.selectedRecipe >= 0 && menu.selectedRecipe < recipes.size()) {
                    Crafting.GridRecipe recipe = recipes.get(menu.selectedRecipe);
                    result = recipe.result();
                    int netId = recipe.netId();
                    craft = new Craft(Math.max(1, item.getCount()), times -> List.of(craftRecipe(netId, times)),
                            List.of(input), new int[]{1}, new String[0]);
                }
            }
            case ANVIL -> {
                SlotRef input = Objects.requireNonNull(menu.slot(0));
                SlotRef material = Objects.requireNonNull(menu.slot(1));
                Anvil anvil = anvil(session, input.container().get(input.index()), material.container().get(material.index()),
                        menu.renameText);
                if (anvil != null) {
                    result = anvil.result();
                    String[] filter = menu.renameText.isEmpty() ? new String[0] : new String[]{menu.renameText};
                    craft = new Craft(1, times -> List.of(new CraftRecipeOptionalAction(new RecipeNetId(0), 0)),
                            List.of(input, material), new int[]{input.container().get(input.index()).getCount(), anvil.materialUsed()},
                            filter);
                    state.setAnvilCost(menu, anvil.cost());
                } else {
                    state.setAnvilCost(menu, 0);
                }
            }
            case GRINDSTONE -> {
                SlotRef top = Objects.requireNonNull(menu.slot(0));
                SlotRef bottom = Objects.requireNonNull(menu.slot(1));
                ItemData first = top.container().get(top.index());
                ItemData second = bottom.container().get(bottom.index());
                result = grindstone(session, first, second);
                if (!ItemTranslator.isAir(result)) {
                    craft = new Craft(1, times -> List.of(new CraftGrindstoneAction(new RecipeNetId(0), 1, 0)),
                            List.of(top, bottom), new int[]{first.getCount(), second.getCount()}, new String[0]);
                }
            }
            case LOOM -> {
                SlotRef banner = Objects.requireNonNull(menu.slot(0));
                SlotRef dye = Objects.requireNonNull(menu.slot(1));
                SlotRef pattern = Objects.requireNonNull(menu.slot(2));
                ItemData bannerItem = banner.container().get(banner.index());
                ItemData dyeItem = dye.container().get(dye.index());
                String code = loomPattern(session, menu.selectedPattern, pattern.container().get(pattern.index()));
                int color = dyeColor(session, dyeItem);
                if (code != null && color >= 0 && !ItemTranslator.isAir(bannerItem)) {
                    result = ItemTranslator.withCount(withBannerLayer(bannerItem, code, color), 1);
                    craft = new Craft(Math.min(bannerItem.getCount(), dyeItem.getCount()),
                            times -> List.of(new CraftLoomAction(code, times)), List.of(banner, dye), new int[]{1, 1}, new String[0]);
                }
            }
            case CARTOGRAPHY -> {
                SlotRef input = Objects.requireNonNull(menu.slot(0));
                SlotRef additional = Objects.requireNonNull(menu.slot(1));
                result = cartography(state, input.container().get(input.index()), additional.container().get(additional.index()));
                if (!ItemTranslator.isAir(result)) {
                    int netId = state.crafting().cartographyNetId();
                    craft = new Craft(1, times -> List.of(new CraftRecipeOptionalAction(new RecipeNetId(netId), 0)),
                            List.of(input, additional), new int[]{1, 1}, new String[0]);
                }
            }
            case SMITHING -> {
                SlotRef template = Objects.requireNonNull(menu.slot(0));
                SlotRef base = Objects.requireNonNull(menu.slot(1));
                SlotRef addition = Objects.requireNonNull(menu.slot(2));
                ItemData templateItem = template.container().get(template.index());
                ItemData baseItem = base.container().get(base.index());
                ItemData additionItem = addition.container().get(addition.index());
                int netId = 0;
                SmithingTransformRecipePayload transform = state.crafting().smithingTransform(templateItem, baseItem, additionItem);
                if (transform != null && transform.getResult() != null) {
                    result = transform.getResult().toBuilder().tag(baseItem.getTag()).count(1).build();
                    netId = transform.getNetId().getRawId();
                } else {
                    SmithingTrimRecipePayload trim = state.crafting().smithingTrim(templateItem, baseItem, additionItem);
                    if (trim != null) {
                        result = trimmed(state, baseItem, templateItem, additionItem);
                        netId = trim.getNetId().getRawId();
                    }
                }
                if (!ItemTranslator.isAir(result)) {
                    int recipe = netId;
                    craft = new Craft(1, times -> List.of(craftRecipe(recipe, 1)), List.of(template, base, addition),
                            new int[]{1, 1, 1}, new String[0]);
                }
            }
            case MERCHANT -> {
                Merchant merchant = menu.merchant;
                SlotRef first = Objects.requireNonNull(menu.slot(0));
                SlotRef second = Objects.requireNonNull(menu.slot(1));
                Merchant.Offer offer = merchant == null ? null : merchant.offer(menu.selectedTrade);
                if (offer != null) {
                    int times = offer.timesSatisfied(first.container().get(first.index()), second.container().get(second.index()));
                    if (times > 0) {
                        result = offer.sell();
                        int netId = offer.netId();
                        craft = new Craft(times, t -> List.of(craftRecipe(netId, t)), List.of(first, second),
                                new int[]{offer.buyA().getCount(), ItemTranslator.isAir(offer.buyB()) ? 0 : offer.buyB().getCount()},
                                new String[0]);
                    }
                }
            }
            default -> {
                return;
            }
        }
        menu.result = result;
        menu.craft = craft;
    }

    private static ItemStackRequestAction craftRecipe(int netId, int times) {
        return new CraftRecipeAction(new RecipeNetId(netId), times);
    }

    private static Craft gridCraft(Crafting.GridMatch match, List<SlotRef> grid, ItemData[] items) {
        int maxTimes = 64;
        List<SlotRef> inputs = new ArrayList<>();
        List<Integer> per = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            int perCraft = match.perSlot()[i];
            if (perCraft > 0) {
                maxTimes = Math.min(maxTimes, items[i].getCount() / perCraft);
                inputs.add(grid.get(i));
                per.add(perCraft);
            }
        }
        int[] perCraft = per.stream().mapToInt(Integer::intValue).toArray();
        int netId = match.recipe().netId();
        return new Craft(Math.max(1, maxTimes), times -> List.of(craftRecipe(netId, times)), inputs, perCraft, new String[0]);
    }

    // ------------------------------------------------------------------ anvil

    record Anvil(ItemData result, int cost, int materialUsed) {
    }

    /**
     * PowerNukkitX's anvil, simplified: renaming, combining two of the same damageable item and applying
     * an enchanted book. Repairs with a material item are left to the server (no prediction).
     */
    static @Nullable Anvil anvil(ProxySession session, ItemData input, ItemData material, String rename) {
        if (ItemTranslator.isAir(input)) {
            return null;
        }
        NbtMap tag = input.getTag() == null ? NbtMap.EMPTY : input.getTag();
        NbtMapBuilder result = tag.toBuilder();
        int cost = repairCost(tag);
        int extra = 0;
        int used = 0;
        if (!ItemTranslator.isAir(material)) {
            NbtMap materialTag = material.getTag() == null ? NbtMap.EMPTY : material.getTag();
            boolean book = "minecraft:enchanted_book".equals(material.getDefinition().getIdentifier());
            boolean same = material.getDefinition() == input.getDefinition()
                    && ItemTranslator.javaItem(session, input).maxDamage() > 0;
            if (!book && !same) {
                return null;
            }
            List<NbtMap> merged = mergeEnchantments(tag.getList("ench", NbtType.COMPOUND, List.of()),
                    materialTag.getList("ench", NbtType.COMPOUND, List.of()));
            extra += merged.size();
            if (same) {
                int maxDamage = ItemTranslator.javaItem(session, input).maxDamage();
                int durability = (maxDamage - tag.getNumber("Damage", 0).intValue())
                        + (maxDamage - materialTag.getNumber("Damage", 0).intValue()) + maxDamage * 12 / 100;
                int damage = Math.max(0, maxDamage - durability);
                if (damage < tag.getNumber("Damage", 0).intValue()) {
                    result.putInt("Damage", damage);
                    extra += 2;
                }
            }
            if (!merged.isEmpty()) {
                result.putList("ench", NbtType.COMPOUND, merged);
            }
            cost += repairCost(materialTag);
            used = 1;
        }
        String currentName = tag.getCompound("display", NbtMap.EMPTY).getString("Name", "");
        if (!rename.equals(currentName)) {
            NbtMapBuilder display = tag.getCompound("display", NbtMap.EMPTY).toBuilder();
            if (rename.isEmpty()) {
                display.remove("Name");
            } else {
                display.putString("Name", rename);
            }
            NbtMap built = display.build();
            if (built.isEmpty()) {
                result.remove("display");
            } else {
                result.putCompound("display", built);
            }
            extra += 1;
        }
        if (extra <= 0) {
            return null;
        }
        result.putInt("RepairCost", repairCost(tag) * 2 + 1);
        NbtMap built = result.build();
        ItemData item = input.toBuilder().tag(built.isEmpty() ? null : built).usingNetId(false).netId(0).build();
        return new Anvil(item, Math.clamp(cost + extra, 1, 39), used);
    }

    private static int repairCost(NbtMap tag) {
        return tag.getNumber("RepairCost", 0).intValue();
    }

    private static List<NbtMap> mergeEnchantments(List<NbtMap> target, List<NbtMap> sacrifice) {
        List<NbtMap> merged = new ArrayList<>(target);
        for (NbtMap enchantment : sacrifice) {
            int id = enchantment.getNumber("id", -1).intValue();
            int level = enchantment.getNumber("lvl", 1).intValue();
            boolean found = false;
            for (int i = 0; i < merged.size(); i++) {
                NbtMap existing = merged.get(i);
                if (existing.getNumber("id", -1).intValue() == id) {
                    int current = existing.getNumber("lvl", 1).intValue();
                    int newLevel = current == level ? current + 1 : Math.max(current, level);
                    merged.set(i, NbtMap.builder().putShort("id", (short) id).putShort("lvl", (short) newLevel).build());
                    found = true;
                }
            }
            if (!found) {
                merged.add(enchantment);
            }
        }
        return merged;
    }

    // ------------------------------------------------------------------ grindstone, cartography, loom, smithing

    /** PowerNukkitX's grindstone: removes enchantments, combines durability, turns enchanted books into books. */
    static ItemData grindstone(ProxySession session, ItemData first, ItemData second) {
        if (!ItemTranslator.isAir(first) && !ItemTranslator.isAir(second) && first.getDefinition() != second.getDefinition()) {
            return ItemData.AIR;
        }
        ItemData main = ItemTranslator.isAir(first) ? second : first;
        ItemData other = ItemTranslator.isAir(first) ? ItemData.AIR : second;
        if (ItemTranslator.isAir(main)) {
            return ItemData.AIR;
        }
        if ("minecraft:enchanted_book".equals(main.getDefinition().getIdentifier())) {
            if (!ItemTranslator.isAir(other)) {
                return ItemData.AIR;
            }
            var book = session.get(io.github.wyze3306.jtb.item.ItemRegistry.KEY).byIdentifier("minecraft:book");
            return book == null ? ItemData.AIR : ItemData.builder().definition(book).count(main.getCount()).build();
        }
        NbtMapBuilder tag = (main.getTag() == null ? NbtMap.EMPTY : main.getTag()).toBuilder();
        tag.remove("ench");
        tag.remove("custom_ench");
        int maxDamage = ItemTranslator.javaItem(session, main).maxDamage();
        if (!ItemTranslator.isAir(other) && maxDamage > 0) {
            int firstLeft = maxDamage - damage(main);
            int secondLeft = maxDamage - damage(other);
            tag.putInt("Damage", Math.max(maxDamage - (firstLeft + secondLeft + maxDamage * 5 / 100) + 1, 0));
        }
        NbtMap built = tag.build();
        return main.toBuilder().tag(built.isEmpty() ? null : built).usingNetId(false).netId(0).build();
    }

    private static int damage(ItemData item) {
        return item.getTag() == null ? 0 : item.getTag().getNumber("Damage", 0).intValue();
    }

    /** PowerNukkitX's cartography table rules. */
    static ItemData cartography(InventoryState state, ItemData input, ItemData additional) {
        if (ItemTranslator.isAir(input)) {
            return ItemData.AIR;
        }
        String in = input.getDefinition().getIdentifier();
        String add = ItemTranslator.isAir(additional) ? "" : additional.getDefinition().getIdentifier();
        boolean filled = in.equals("minecraft:filled_map");
        ItemData result = ItemData.AIR;
        if (in.equals("minecraft:paper") && add.isEmpty()) {
            var empty = state.session().get(io.github.wyze3306.jtb.item.ItemRegistry.KEY).byIdentifier("minecraft:empty_map");
            result = empty == null ? ItemData.AIR : ItemData.builder().definition(empty).count(1).build();
        } else if ((in.equals("minecraft:empty_map") || filled) && add.isEmpty()) {
            result = ItemTranslator.withCount(input, 1);
        } else if (add.equals("minecraft:compass") && (in.equals("minecraft:empty_map") || filled)) {
            result = input.toBuilder().damage(2).count(1).build();
        } else if (filled && add.equals("minecraft:glass_pane")) {
            result = input.toBuilder().damage(6).count(1).build();
        } else if (filled && add.equals("minecraft:empty_map")) {
            result = ItemTranslator.withCount(input, 2);
        } else if (filled && add.equals("minecraft:paper")) {
            result = ItemTranslator.withCount(input, 1);
        }
        return ItemTranslator.isAir(result) ? result : ItemTranslator.withNetId(result, 0);
    }

    /**
     * The Bedrock pattern code of the loom entry the Java client selected: Java lists the patterns of the
     * pattern item's tag, or the {@code minecraft:no_item_required} ones without a pattern item.
     */
    static @Nullable String loomPattern(ProxySession session, int selected, ItemData patternItem) {
        if (selected < 0) {
            return null;
        }
        Key tag = Key.key("minecraft:no_item_required");
        if (!ItemTranslator.isAir(patternItem)) {
            String path = ItemTranslator.javaItem(session, patternItem).key().value();
            if (!path.endsWith("_banner_pattern")) {
                return null;
            }
            tag = Key.key("minecraft:pattern_item/" + path.substring(0, path.length() - "_banner_pattern".length()));
        }
        Map<Key, int[]> tags = JavaRegistries.tags().get(Key.key("minecraft:banner_pattern"));
        int[] ids = tags == null ? null : tags.get(tag);
        if (ids == null || selected >= ids.length) {
            return null;
        }
        JavaRegistries.Registry registry = JavaRegistries.registry("minecraft:banner_pattern");
        int id = ids[selected];
        if (id < 0 || id >= registry.entries().size()) {
            return null;
        }
        return ItemMappings.get().bedrockBannerPattern(registry.entries().get(id).getId());
    }

    /** Java dye colour id of a dye item, or -1. */
    static int dyeColor(ProxySession session, ItemData dye) {
        if (ItemTranslator.isAir(dye)) {
            return -1;
        }
        String path = ItemTranslator.javaItem(session, dye).key().value();
        switch (path) {
            case "ink_sac":
                return 15;
            case "cocoa_beans":
                return 12;
            case "lapis_lazuli":
                return 11;
            case "bone_meal":
                return 0;
            default:
                break;
        }
        if (path.endsWith("_dye")) {
            String color = path.substring(0, path.length() - 4);
            for (int i = 0; i < DYE_COLORS.length; i++) {
                if (DYE_COLORS[i].equals(color)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static ItemData withBannerLayer(ItemData banner, String code, int javaColor) {
        NbtMap tag = banner.getTag() == null ? NbtMap.EMPTY : banner.getTag();
        List<NbtMap> patterns = new ArrayList<>(tag.getList("Patterns", NbtType.COMPOUND, List.of()));
        // Bedrock numbers banner colours in reverse dye order.
        patterns.add(NbtMap.builder().putInt("Color", 15 - javaColor).putString("Pattern", code).build());
        return banner.toBuilder().tag(tag.toBuilder().putList("Patterns", NbtType.COMPOUND, patterns).build())
                .usingNetId(false).netId(0).build();
    }

    private static ItemData trimmed(InventoryState state, ItemData base, ItemData template, ItemData addition) {
        String pattern = state.trimPattern(template.getDefinition().getIdentifier());
        String material = state.trimMaterial(addition.getDefinition().getIdentifier());
        if (pattern == null || material == null) {
            return ItemData.AIR;
        }
        NbtMap tag = base.getTag() == null ? NbtMap.EMPTY : base.getTag();
        NbtMap trim = NbtMap.builder().putString("Material", material).putString("Pattern", pattern).build();
        return base.toBuilder().tag(tag.toBuilder().putCompound("Trim", trim).build()).count(1).usingNetId(false).netId(0).build();
    }
}
