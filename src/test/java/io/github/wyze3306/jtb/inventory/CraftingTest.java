package io.github.wyze3306.jtb.inventory;

import io.github.wyze3306.jtb.item.TestItems;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.ItemTagDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.MolangDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.NameDescriptor;
import org.cloudburstmc.protocol.bedrock.data.inventory.descriptor.RecipeIngredient;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.RecipeNetId;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.ShapedRecipePayload;
import org.cloudburstmc.protocol.bedrock.data.payload.crafting.ShapelessRecipePayload;
import org.cloudburstmc.protocol.bedrock.packet.CraftingDataPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingTest {

    static final int PLANKS = 1, STICKS = 2, AXE = 3, FLINT_AND_STEEL = 4, TORCH = 5, TABLE = 6, CAKE = 7, STONE_SLAB = 8;

    private static TestItems.TestSession test;
    private static Crafting crafting;

    @BeforeAll
    static void setUp() {
        test = TestItems.session();
        crafting = new Crafting(test.session());
        crafting.load(recipes(test));
    }

    /** A few vanilla-like recipes, written the way PowerNukkitX sends them. */
    static CraftingDataPacket recipes(TestItems.TestSession test) {
        CraftingDataPacket packet = new CraftingDataPacket();
        packet.getShapelessRecipes().add(shapeless(test, PLANKS, "crafting_table", test.item("minecraft:oak_planks", 4),
                new RecipeIngredient(new NameDescriptor(test.definition("minecraft:oak_log"), -1), 1)));
        packet.getShapedRecipes().add(shaped(test, STICKS, 1, 2, false, test.item("minecraft:stick", 4),
                tag("minecraft:planks"), tag("minecraft:planks")));
        RecipeIngredient planks = tag("minecraft:planks");
        RecipeIngredient stick = name(test, "minecraft:stick");
        RecipeIngredient none = new RecipeIngredient(null, 0);
        packet.getShapedRecipes().add(shaped(test, AXE, 2, 3, true, test.item("minecraft:wooden_axe", 1),
                planks, planks, planks, stick, none, stick));
        packet.getShapelessRecipes().add(shapeless(test, FLINT_AND_STEEL, "crafting_table", test.item("minecraft:flint_and_steel", 1),
                name(test, "minecraft:iron_ingot"), name(test, "minecraft:flint")));
        packet.getShapedRecipes().add(shaped(test, TORCH, 1, 2, false, test.item("minecraft:torch", 4),
                new RecipeIngredient(new MolangDescriptor("query.any_tag('minecraft:coals')", 10), 1), stick));
        packet.getShapedRecipes().add(shaped(test, TABLE, 2, 2, false, test.item("minecraft:crafting_table", 1),
                planks, planks, planks, planks));
        packet.getShapelessRecipes().add(shapeless(test, STONE_SLAB, "stonecutter", test.item("minecraft:stone_bricks", 1),
                name(test, "minecraft:stone")));
        return packet;
    }

    private static RecipeIngredient tag(String tag) {
        return new RecipeIngredient(new ItemTagDescriptor(tag), 1);
    }

    private static RecipeIngredient name(TestItems.TestSession test, String id) {
        return new RecipeIngredient(new NameDescriptor(test.definition(id), 0), 1);
    }

    private static ShapedRecipePayload shaped(TestItems.TestSession test, int netId, int width, int height, boolean mirror,
                                              ItemData result, RecipeIngredient... ingredients) {
        ShapedRecipePayload recipe = new ShapedRecipePayload();
        recipe.setRecipeId("test:" + netId);
        recipe.setTag("crafting_table");
        recipe.setWidth(width);
        recipe.setHeight(height);
        recipe.setAssumeSymmetry(mirror);
        recipe.setNetId(new RecipeNetId(netId));
        recipe.getIngredients().addAll(List.of(ingredients));
        recipe.getResults().add(result);
        return recipe;
    }

    private static ShapelessRecipePayload shapeless(TestItems.TestSession test, int netId, String tag, ItemData result,
                                                    RecipeIngredient... ingredients) {
        ShapelessRecipePayload recipe = new ShapelessRecipePayload();
        recipe.setRecipeId("test:" + netId);
        recipe.setTag(tag);
        recipe.setNetId(new RecipeNetId(netId));
        recipe.getIngredients().addAll(List.of(ingredients));
        recipe.getResults().add(result);
        return recipe;
    }

    private static ItemData[] grid(int size, Object... slotAndItem) {
        ItemData[] grid = new ItemData[size * size];
        Arrays.fill(grid, ItemData.AIR);
        for (int i = 0; i < slotAndItem.length; i += 2) {
            grid[(Integer) slotAndItem[i]] = (ItemData) slotAndItem[i + 1];
        }
        return grid;
    }

    @Test
    void shapelessAnywhere() {
        ItemData log = test.item("minecraft:oak_log", 3);
        for (int slot = 0; slot < 4; slot++) {
            Crafting.GridMatch match = crafting.matchGrid(grid(2, slot, log), 2);
            assertNotNull(match);
            assertEquals(PLANKS, match.recipe().netId());
            assertEquals(1, match.perSlot()[slot]);
        }
        assertNull(crafting.matchGrid(grid(2, 0, log, 1, log), 2), "two logs match no recipe");
    }

    @Test
    void shapelessInAnyOrder() {
        Crafting.GridMatch match = crafting.matchGrid(grid(3, 8, test.item("minecraft:flint", 1), 0, test.item("minecraft:iron_ingot", 1)), 3);
        assertNotNull(match);
        assertEquals(FLINT_AND_STEEL, match.recipe().netId());
    }

    @Test
    void shapedByTagAtAnyOffset() {
        ItemData oak = test.item("minecraft:oak_planks", 1);
        ItemData birch = test.item("minecraft:birch_planks", 1);
        Crafting.GridMatch match = crafting.matchGrid(grid(3, 2, oak, 5, birch), 3);
        assertNotNull(match);
        assertEquals(STICKS, match.recipe().netId());
        assertNull(crafting.matchGrid(grid(3, 0, oak, 4, oak), 3), "diagonal planks are no sticks");
        assertNull(crafting.matchGrid(grid(3, 0, oak, 3, test.item("minecraft:stone", 1)), 3));
        // 2x2 grid: the stick recipe also fits.
        assertEquals(STICKS, crafting.matchGrid(grid(2, 1, oak, 3, oak), 2).recipe().netId());
        // The square of planks.
        assertEquals(TABLE, crafting.matchGrid(grid(2, 0, oak, 1, oak, 2, birch, 3, oak), 2).recipe().netId());
    }

    @Test
    void shapedMirrored() {
        ItemData planks = test.item("minecraft:oak_planks", 1);
        ItemData stick = test.item("minecraft:stick", 1);
        // XX / XS / .S  (as declared)
        Crafting.GridMatch normal = crafting.matchGrid(grid(3, 0, planks, 1, planks, 3, planks, 4, stick, 7, stick), 3);
        assertNotNull(normal);
        assertEquals(AXE, normal.recipe().netId());
        // XX / SX / S.  (mirrored)
        Crafting.GridMatch mirrored = crafting.matchGrid(grid(3, 1, planks, 2, planks, 5, planks, 4, stick, 7, stick), 3);
        assertNotNull(mirrored);
        assertEquals(AXE, mirrored.recipe().netId());
        int[] expected = new int[9];
        expected[1] = expected[2] = expected[5] = expected[4] = expected[7] = 1;
        assertArrayEquals(expected, mirrored.perSlot());
    }

    @Test
    void molangTagQuery() {
        Crafting.GridMatch coal = crafting.matchGrid(grid(3, 1, test.item("minecraft:coal", 1), 4, test.item("minecraft:stick", 1)), 3);
        assertNotNull(coal);
        assertEquals(TORCH, coal.recipe().netId());
        Crafting.GridMatch charcoal = crafting.matchGrid(grid(3, 1, test.item("minecraft:charcoal", 1), 4, test.item("minecraft:stick", 1)), 3);
        assertNotNull(charcoal);
        assertNull(crafting.matchGrid(grid(3, 1, test.item("minecraft:stone", 1), 4, test.item("minecraft:stick", 1)), 3));
    }

    @Test
    void stonecutterRecipesAreSeparate() {
        assertNull(crafting.matchGrid(grid(2, 0, test.item("minecraft:stone", 1)), 2));
        assertEquals(1, crafting.javaStonecutterRecipes(test.item("minecraft:stone", 1)).size());
        assertTrue(crafting.javaStonecutterRecipes(test.item("minecraft:oak_log", 1)).isEmpty());
    }
}
