package io.github.wyze3306.jtb.inventory;

import io.github.wyze3306.jtb.item.ItemMappings;
import io.github.wyze3306.jtb.item.ItemRegistry;
import io.github.wyze3306.jtb.item.ItemTranslator;
import io.github.wyze3306.jtb.session.ProxySession;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.DeferredDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemTagDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.MolangDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.NameDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.RecipeIngredient;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.PotionMixDataEntry;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.ShapedRecipePayload;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.ShapelessRecipePayload;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.SmithingTransformRecipePayload;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.SmithingTrimRecipePayload;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.HolderSet;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.Ingredient;
import org.geysermc.mcprotocollib.protocol.data.game.recipe.display.slot.ItemStackSlotDisplay;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundUpdateRecipesPacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server's recipes ({@code CraftingDataPacket}) and the matching the Java client relies on the
 * server for: Java never computes crafting results, so the proxy finds the Bedrock recipe the grid
 * matches (exactly like PowerNukkitX validates it: shaped recipes trimmed to their bounding box and
 * optionally mirrored, shapeless recipes as multisets, ingredients by item, aux value, tag or molang
 * tag query), and sends Java the item sets its menus filter on.
 */
final class Crafting {

    /** A shaped or shapeless grid recipe; {@code width == 0} for shapeless ones. */
    record GridRecipe(int netId, int width, int height, List<RecipeIngredient> ingredients, ItemData result,
                      boolean mirror, int priority) {

        boolean shaped() {
            return this.width > 0;
        }
    }

    /** A grid matching a recipe: how many items each grid slot gives to one craft. */
    record GridMatch(GridRecipe recipe, int[] perSlot) {
    }

    private static final Pattern MOLANG_TAG = Pattern.compile("'([^']+)'");

    private final ProxySession session;
    private final List<GridRecipe> crafting = new ArrayList<>();
    private final List<GridRecipe> stonecutter = new ArrayList<>();
    private final List<SmithingTransformRecipePayload> smithingTransforms = new ArrayList<>();
    private final List<SmithingTrimRecipePayload> smithingTrims = new ArrayList<>();
    private final Map<String, List<RecipeIngredient>> cookingInputs = new HashMap<>();
    private final List<PotionMixDataEntry> potionMixes = new ArrayList<>();
    private int cartographyNetId;

    Crafting(ProxySession session) {
        this.session = session;
    }

    void load(CraftingDataPacket packet) {
        if (packet.isClearRecipes()) {
            this.crafting.clear();
            this.stonecutter.clear();
            this.smithingTransforms.clear();
            this.smithingTrims.clear();
            this.cookingInputs.clear();
            this.potionMixes.clear();
        }
        for (ShapedRecipePayload recipe : packet.getShapedRecipes()) {
            if ("crafting_table".equals(recipe.getTag()) && !recipe.getResults().isEmpty()) {
                this.crafting.add(new GridRecipe(recipe.getNetId().getRawId(), recipe.getWidth(), recipe.getHeight(),
                        List.copyOf(recipe.getIngredients()), recipe.getResults().getFirst(), recipe.isAssumeSymmetry(),
                        recipe.getPriority()));
            }
        }
        for (ShapelessRecipePayload recipe : packet.getShapelessRecipes()) {
            if (recipe.getResults().isEmpty()) {
                continue;
            }
            String tag = recipe.getTag() == null ? "" : recipe.getTag();
            GridRecipe grid = new GridRecipe(recipe.getNetId().getRawId(), 0, 0, List.copyOf(recipe.getIngredients()),
                    recipe.getResults().getFirst(), false, recipe.getPriority());
            switch (tag) {
                case "crafting_table" -> this.crafting.add(grid);
                case "stonecutter" -> this.stonecutter.add(grid);
                case "cartography_table" -> {
                    if (this.cartographyNetId == 0) {
                        this.cartographyNetId = grid.netId();
                    }
                }
                case "furnace", "smoker", "blast_furnace", "campfire", "soul_campfire" -> {
                    if (!recipe.getIngredients().isEmpty()) {
                        this.cookingInputs.computeIfAbsent(tag, k -> new ArrayList<>()).add(recipe.getIngredients().getFirst());
                    }
                }
                default -> {
                }
            }
        }
        this.smithingTransforms.addAll(packet.getSmithingTransformRecipes());
        this.smithingTrims.addAll(packet.getSmithingTrimRecipes());
        this.potionMixes.addAll(packet.getPotionMixes());
        // Shaped recipes first (vanilla checks them first), then by priority.
        this.crafting.sort(Comparator.comparing((GridRecipe r) -> !r.shaped()).thenComparingInt(GridRecipe::priority));
    }

    int cartographyNetId() {
        return this.cartographyNetId;
    }

    // ------------------------------------------------------------------ ingredient matching

    boolean matches(RecipeIngredient ingredient, ItemData item) {
        ItemDescriptor descriptor = ingredient.getDescriptor();
        if (ItemTranslator.isAir(item)) {
            return descriptor == null || descriptor.getType() == org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemDescriptorType.EMPTY
                    || ingredient.getStackSize() <= 0;
        }
        if (descriptor == null || ingredient.getStackSize() <= 0) {
            return false;
        }
        String identifier = item.getDefinition().getIdentifier();
        return switch (descriptor) {
            case NameDescriptor name -> name.getItemId() != null && identifier.equals(name.getItemId().getIdentifier())
                    && auxMatches(name.getAuxValue(), item);
            case DeferredDescriptor deferred -> identifier.equals(deferred.getFullName()) && auxMatches(deferred.getAuxValue(), item);
            case ItemTagDescriptor tag -> hasTag(item.getDefinition(), tag.getItemTag());
            case MolangDescriptor molang -> molangMatches(molang.getTagExpression(), item.getDefinition());
            default -> false;
        };
    }

    private static boolean auxMatches(int aux, ItemData item) {
        return aux == -1 || aux == Short.MAX_VALUE || aux == item.getDamage();
    }

    boolean hasTag(ItemDefinition definition, String tag) {
        if (ItemMappings.get().hasTag(definition.getIdentifier(), tag)) {
            return true;
        }
        NbtMap data = definition.getComponentData();
        if (data == null) {
            return false;
        }
        NbtMap tags = data.getCompound("components", NbtMap.EMPTY).getCompound("minecraft:tags", NbtMap.EMPTY);
        return tags.getList("tags", NbtType.STRING, List.of()).contains(tag);
    }

    /** Supports the tag queries recipes use: {@code q.any_tag('a', 'b')} and {@code q.all_tags(...)}. */
    private boolean molangMatches(String expression, ItemDefinition definition) {
        boolean all = expression.contains("all_tags");
        if (!all && !expression.contains("any_tag")) {
            return false;
        }
        Matcher matcher = MOLANG_TAG.matcher(expression);
        boolean any = false;
        while (matcher.find()) {
            boolean has = hasTag(definition, matcher.group(1));
            if (all && !has) {
                return false;
            }
            any |= has;
        }
        return any;
    }

    // ------------------------------------------------------------------ grids

    /**
     * The recipe a square crafting grid matches, or null.
     *
     * @param grid  row-major, {@code size * size} items
     */
    @Nullable GridMatch matchGrid(ItemData[] grid, int size) {
        int minX = size, minY = size, maxX = -1, maxY = -1;
        int count = 0;
        for (int i = 0; i < grid.length; i++) {
            if (!ItemTranslator.isAir(grid[i])) {
                int x = i % size;
                int y = i / size;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                count++;
            }
        }
        if (count == 0) {
            return null;
        }
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        for (GridRecipe recipe : this.crafting) {
            if (recipe.shaped()) {
                if (recipe.width() != width || recipe.height() != height) {
                    continue;
                }
                int[] perSlot = matchShaped(recipe, grid, size, minX, minY, false);
                if (perSlot == null && recipe.mirror()) {
                    perSlot = matchShaped(recipe, grid, size, minX, minY, true);
                }
                if (perSlot != null) {
                    return new GridMatch(recipe, perSlot);
                }
            } else if (recipe.ingredients().size() == count && count <= size * size) {
                int[] perSlot = matchShapeless(recipe, grid);
                if (perSlot != null) {
                    return new GridMatch(recipe, perSlot);
                }
            }
        }
        return null;
    }

    private int @Nullable [] matchShaped(GridRecipe recipe, ItemData[] grid, int size, int minX, int minY, boolean mirrored) {
        int[] perSlot = new int[grid.length];
        for (int y = 0; y < recipe.height(); y++) {
            for (int x = 0; x < recipe.width(); x++) {
                int sourceX = mirrored ? recipe.width() - 1 - x : x;
                RecipeIngredient ingredient = recipe.ingredients().get(y * recipe.width() + sourceX);
                int slot = (minY + y) * size + minX + x;
                ItemData item = grid[slot];
                if (!matches(ingredient, item)) {
                    return null;
                }
                if (!ItemTranslator.isAir(item)) {
                    int needed = Math.max(1, ingredient.getStackSize());
                    if (item.getCount() < needed) {
                        return null;
                    }
                    perSlot[slot] = needed;
                }
            }
        }
        return perSlot;
    }

    private int @Nullable [] matchShapeless(GridRecipe recipe, ItemData[] grid) {
        int[] slots = new int[grid.length];
        int n = 0;
        for (int i = 0; i < grid.length; i++) {
            if (!ItemTranslator.isAir(grid[i])) {
                slots[n++] = i;
            }
        }
        int[] assignment = new int[recipe.ingredients().size()];
        boolean[] used = new boolean[n];
        if (!assign(recipe.ingredients(), 0, grid, slots, n, used, assignment)) {
            return null;
        }
        int[] perSlot = new int[grid.length];
        for (int i = 0; i < assignment.length; i++) {
            perSlot[assignment[i]] = Math.max(1, recipe.ingredients().get(i).getStackSize());
        }
        return perSlot;
    }

    /** Backtracking assignment of ingredients to distinct grid slots (at most 9, so cheap). */
    private boolean assign(List<RecipeIngredient> ingredients, int index, ItemData[] grid, int[] slots, int n,
                           boolean[] used, int[] assignment) {
        if (index == ingredients.size()) {
            return true;
        }
        RecipeIngredient ingredient = ingredients.get(index);
        for (int i = 0; i < n; i++) {
            ItemData item = grid[slots[i]];
            if (!used[i] && matches(ingredient, item) && item.getCount() >= Math.max(1, ingredient.getStackSize())) {
                used[i] = true;
                assignment[index] = slots[i];
                if (assign(ingredients, index + 1, grid, slots, n, used, assignment)) {
                    return true;
                }
                used[i] = false;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ other stations

    /** Stonecutter recipes taking {@code input}, in the order the Java client lists them. */
    List<GridRecipe> stonecutterRecipes(ItemData input) {
        List<GridRecipe> recipes = new ArrayList<>();
        if (ItemTranslator.isAir(input)) {
            return recipes;
        }
        for (GridRecipe recipe : this.stonecutter) {
            if (!recipe.ingredients().isEmpty() && matches(recipe.ingredients().getFirst(), input)) {
                recipes.add(recipe);
            }
        }
        return recipes;
    }

    @Nullable SmithingTransformRecipePayload smithingTransform(ItemData template, ItemData base, ItemData addition) {
        for (SmithingTransformRecipePayload recipe : this.smithingTransforms) {
            if (matches(recipe.getTemplateIngredient(), template) && matches(recipe.getBaseIngredient(), base)
                    && matches(recipe.getAdditionIngredient(), addition)) {
                return recipe;
            }
        }
        return null;
    }

    @Nullable SmithingTrimRecipePayload smithingTrim(ItemData template, ItemData base, ItemData addition) {
        for (SmithingTrimRecipePayload recipe : this.smithingTrims) {
            if (matches(recipe.getTemplateIngredient(), template) && matches(recipe.getBaseIngredient(), base)
                    && matches(recipe.getAdditionIngredient(), addition)) {
                return recipe;
            }
        }
        return null;
    }

    /** Whether a furnace of this kind ({@code furnace}, {@code smoker}, {@code blast_furnace}) can cook the item. */
    boolean cookable(String station, ItemData item) {
        for (RecipeIngredient input : this.cookingInputs.getOrDefault(station, List.of())) {
            if (matches(input, item)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the item is a brewing reagent of some potion mix. */
    boolean brewingReagent(ItemData item) {
        if (ItemTranslator.isAir(item)) {
            return false;
        }
        int runtimeId = item.getDefinition().getRuntimeId();
        for (PotionMixDataEntry mix : this.potionMixes) {
            if (mix.getReagentItemId() == runtimeId) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ Java side

    /**
     * The Java recipe data its menus filter on: furnace/smoker/blast furnace/campfire inputs, smithing
     * and brewing item sets, and the stonecutter recipe list (whose order defines the stonecutter button
     * ids, see {@link #stonecutterRecipes}).
     */
    ClientboundUpdateRecipesPacket javaRecipes() {
        Map<Key, int[]> sets = new HashMap<>();
        sets.put(Key.key("furnace_input"), javaIds(this.cookingInputs.getOrDefault("furnace", List.of())));
        sets.put(Key.key("smoker_input"), javaIds(this.cookingInputs.getOrDefault("smoker", List.of())));
        sets.put(Key.key("blast_furnace_input"), javaIds(this.cookingInputs.getOrDefault("blast_furnace", List.of())));
        sets.put(Key.key("campfire_input"), javaIds(this.cookingInputs.getOrDefault("campfire", List.of())));
        List<RecipeIngredient> templates = new ArrayList<>();
        List<RecipeIngredient> bases = new ArrayList<>();
        List<RecipeIngredient> additions = new ArrayList<>();
        for (SmithingTransformRecipePayload recipe : this.smithingTransforms) {
            templates.add(recipe.getTemplateIngredient());
            bases.add(recipe.getBaseIngredient());
            additions.add(recipe.getAdditionIngredient());
        }
        for (SmithingTrimRecipePayload recipe : this.smithingTrims) {
            templates.add(recipe.getTemplateIngredient());
            bases.add(recipe.getBaseIngredient());
            additions.add(recipe.getAdditionIngredient());
        }
        sets.put(Key.key("smithing_template"), javaIds(templates));
        sets.put(Key.key("smithing_base"), javaIds(bases));
        sets.put(Key.key("smithing_addition"), javaIds(additions));
        IntSet reagents = new IntLinkedOpenHashSet();
        IntSet inputs = new IntLinkedOpenHashSet();
        ItemRegistry registry = this.session.get(ItemRegistry.KEY);
        for (PotionMixDataEntry mix : this.potionMixes) {
            addJavaId(reagents, registry.byRuntimeId(mix.getReagentItemId()), mix.getReagentItemAux());
            addJavaId(inputs, registry.byRuntimeId(mix.getFromPotionId()), mix.getFromItemAux());
        }
        sets.put(Key.key("brewing_reagent"), reagents.toIntArray());
        sets.put(Key.key("brewing_input"), inputs.toIntArray());

        List<ClientboundUpdateRecipesPacket.SelectableRecipe> stonecutter = new ArrayList<>(this.stonecutter.size());
        for (GridRecipe recipe : this.stonecutter) {
            ItemStack result = ItemTranslator.toJava(this.session, recipe.result());
            int[] input = javaIds(recipe.ingredients().isEmpty() ? List.of() : List.of(recipe.ingredients().getFirst()));
            if (result != null && input.length > 0) {
                stonecutter.add(new ClientboundUpdateRecipesPacket.SelectableRecipe(
                        new Ingredient(new HolderSet(IntArrayList.wrap(input))), new ItemStackSlotDisplay(result)));
            }
        }
        return new ClientboundUpdateRecipesPacket(sets, stonecutter);
    }

    /** The stonecutter recipes as the Java client numbers them for this input: only those it was sent. */
    List<GridRecipe> javaStonecutterRecipes(ItemData input) {
        int javaId = ItemTranslator.javaItem(this.session, input).id();
        List<GridRecipe> recipes = new ArrayList<>();
        for (GridRecipe recipe : this.stonecutter) {
            if (recipe.ingredients().isEmpty() || ItemTranslator.toJava(this.session, recipe.result()) == null) {
                continue;
            }
            int[] ids = javaIds(List.of(recipe.ingredients().getFirst()));
            for (int id : ids) {
                if (id == javaId) {
                    recipes.add(recipe);
                    break;
                }
            }
        }
        return recipes;
    }

    private int[] javaIds(List<RecipeIngredient> ingredients) {
        IntSet ids = new IntLinkedOpenHashSet();
        ItemRegistry registry = this.session.get(ItemRegistry.KEY);
        for (RecipeIngredient ingredient : ingredients) {
            switch (ingredient.getDescriptor()) {
                case NameDescriptor name -> addJavaId(ids, name.getItemId(), name.getAuxValue());
                case DeferredDescriptor deferred -> addJavaId(ids, registry.byIdentifier(deferred.getFullName()), deferred.getAuxValue());
                case ItemTagDescriptor tag -> {
                    for (String identifier : ItemMappings.get().tagItems(tag.getItemTag())) {
                        addJavaId(ids, registry.byIdentifier(identifier), -1);
                    }
                }
                case null, default -> {
                }
            }
        }
        return ids.toIntArray();
    }

    private void addJavaId(IntSet ids, @Nullable ItemDefinition definition, int aux) {
        if (definition == null || definition.getRuntimeId() == 0) {
            return;
        }
        ItemData item = ItemData.builder().definition(definition).damage(aux < 0 || aux == Short.MAX_VALUE ? 0 : aux).count(1).build();
        int id = ItemTranslator.javaItem(this.session, item).id();
        if (id != 0) {
            ids.add(id);
        }
    }
}
