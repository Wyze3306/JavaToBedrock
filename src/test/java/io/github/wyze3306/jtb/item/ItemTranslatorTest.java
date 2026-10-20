package io.github.wyze3306.jtb.item;

import io.github.wyze3306.jtb.mapping.JavaRegistries;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.Fireworks;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ItemEnchantments;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.PotionContents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.WrittenBookContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemTranslatorTest {

    private static TestItems.TestSession test;

    @BeforeAll
    static void setUp() {
        test = TestItems.session(TestItems.custom("mymod:ruby_sword", 9001, NbtMap.builder()
                .putCompound("components", NbtMap.builder()
                        .putCompound("item_properties", NbtMap.builder().putInt("max_stack_size", 16).build())
                        .putCompound("minecraft:display_name", NbtMap.builder().putString("value", "Ruby Sword").build())
                        .putCompound("minecraft:durability", NbtMap.builder().putShort("max_durability", (short) 250).build())
                        .putCompound("minecraft:wearable", NbtMap.builder().putString("slot", "slot.armor.chest").build())
                        .build())
                .build()));
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static ItemData roundTrip(ItemData item) {
        ItemStack java = ItemTranslator.toJava(test.session(), item);
        assertNotNull(java, () -> "no Java item for " + item);
        return ItemTranslator.toBedrock(test.session(), java);
    }

    private static void assertSame(ItemData expected, ItemData actual) {
        assertTrue(expected.equals(actual, true, true, true), () -> "expected " + expected + " but got " + actual);
        assertArrayEquals(expected.getCanPlace(), actual.getCanPlace());
        assertArrayEquals(expected.getCanBreak(), actual.getCanBreak());
        assertEquals(expected.getBlockingTicks(), actual.getBlockingTicks());
    }

    @Test
    void lossless() {
        List<ItemData> items = new ArrayList<>();
        items.add(test.item("minecraft:oak_log", 64));
        items.add(test.item("minecraft:diamond_sword", 0, 1, NbtMap.builder()
                .putInt("Damage", 10)
                .putInt("RepairCost", 3)
                .putCompound("display", NbtMap.builder().putString("Name", "§6Excalibur")
                        .putList("Lore", NbtType.STRING, List.of("first", "second")).build())
                .putList("ench", NbtType.COMPOUND, List.of(NbtMap.builder().putShort("id", (short) 9).putShort("lvl", (short) 3).build()))
                .build()));
        items.add(test.item("minecraft:potion", 5, 1, null));
        items.add(test.item("minecraft:leather_helmet", 0, 1, NbtMap.builder().putInt("customColor", 0xFF3366CC).build()));
        items.add(test.item("minecraft:written_book", 0, 1, NbtMap.builder()
                .putString("author", "Steve").putString("title", "Notes").putInt("generation", 0)
                .putList("pages", NbtType.COMPOUND, List.of(NbtMap.builder().putString("text", "Hello").putString("photoname", "").build()))
                .build()));
        items.add(test.item("minecraft:firework_rocket", 0, 3, NbtMap.builder()
                .putCompound("Fireworks", NbtMap.builder().putByte("Flight", (byte) 2)
                        .putList("Explosions", NbtType.COMPOUND, List.of(NbtMap.builder().putByte("FireworkType", (byte) 1)
                                .putByteArray("FireworkColor", new byte[]{1}).putByteArray("FireworkFade", new byte[0])
                                .putByte("FireworkFlicker", (byte) 1).putByte("FireworkTrail", (byte) 0).build()))
                        .build())
                .build()));
        items.add(test.item("minecraft:stone", 0, 1, NbtMap.builder().putString("unknown_plugin_data", "kept").build()));
        items.add(test.item("minecraft:diamond_pickaxe", 0, 1, null).toBuilder()
                .canBreak(new String[]{"minecraft:stone"}).canPlace(new String[]{"minecraft:dirt"}).build());
        items.add(test.item("mymod:ruby_sword", 1));
        for (ItemData item : items) {
            assertSame(item, roundTrip(item));
        }
    }

    @Test
    void componentsOfANamedEnchantedDamagedSword() {
        ItemStack java = ItemTranslator.toJava(test.session(), test.item("minecraft:diamond_sword", 0, 1, NbtMap.builder()
                .putInt("Damage", 10)
                .putCompound("display", NbtMap.builder().putString("Name", "Excalibur")
                        .putList("Lore", NbtType.STRING, List.of("first", "second")).build())
                .putList("ench", NbtType.COMPOUND, List.of(NbtMap.builder().putShort("id", (short) 9).putShort("lvl", (short) 3).build()))
                .build()));
        assertNotNull(java);
        assertEquals(ItemMappings.get().javaId("diamond_sword"), java.getId());
        DataComponents components = java.getDataComponentsPatch();
        assertNotNull(components);
        assertEquals(10, components.get(DataComponentTypes.DAMAGE));
        assertEquals("Excalibur", plain(components.get(DataComponentTypes.CUSTOM_NAME)));
        assertEquals(2, components.get(DataComponentTypes.LORE).size());
        ItemEnchantments enchantments = components.get(DataComponentTypes.ENCHANTMENTS);
        int sharpness = JavaRegistries.registry("minecraft:enchantment").id(Key.key("minecraft:sharpness"));
        assertEquals(3, enchantments.getEnchantments().get(sharpness));
        assertNotNull(components.get(DataComponentTypes.CUSTOM_DATA));
    }

    @Test
    void componentsOfOtherItems() {
        DataComponents helmet = ItemTranslator.toJava(test.session(), test.item("minecraft:leather_helmet", 0, 1,
                NbtMap.builder().putInt("customColor", 0xFF3366CC).build())).getDataComponentsPatch();
        assertEquals(0x3366CC, helmet.get(DataComponentTypes.DYED_COLOR) & 0xFFFFFF);

        DataComponents book = ItemTranslator.toJava(test.session(), test.item("minecraft:written_book", 0, 1, NbtMap.builder()
                .putString("author", "Steve").putString("title", "Notes")
                .putList("pages", NbtType.COMPOUND, List.of(NbtMap.builder().putString("text", "Hello").build()))
                .build())).getDataComponentsPatch();
        WrittenBookContent content = book.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        assertEquals("Steve", content.getAuthor());
        assertEquals("Notes", content.getTitle().getRaw());
        assertEquals("Hello", plain(content.getPages().getFirst().getRaw()));

        DataComponents rocket = ItemTranslator.toJava(test.session(), test.item("minecraft:firework_rocket", 0, 1, NbtMap.builder()
                .putCompound("Fireworks", NbtMap.builder().putByte("Flight", (byte) 3).build()).build())).getDataComponentsPatch();
        Fireworks fireworks = rocket.get(DataComponentTypes.FIREWORKS);
        assertEquals(3, fireworks.getFlightDuration());

        ItemMappings.Mapping potionMapping = ItemMappings.get().bedrock("minecraft:potion", 5);
        assertNotNull(potionMapping);
        assertNotNull(potionMapping.potion());
        PotionContents potion = ItemTranslator.toJava(test.session(), test.item("minecraft:potion", 5, 1, null))
                .getDataComponentsPatch().get(DataComponentTypes.POTION_CONTENTS);
        assertEquals(ItemMappings.get().potionId(potionMapping.potion()), potion.getPotionId());

        DataComponents shulker = ItemTranslator.toJava(test.session(), test.item("minecraft:red_shulker_box", 0, 1, NbtMap.builder()
                .putList("Items", NbtType.COMPOUND, List.of(NbtMap.builder().putString("Name", "minecraft:diamond")
                        .putByte("Count", (byte) 5).putShort("Damage", (short) 0).putByte("Slot", (byte) 2).build()))
                .build())).getDataComponentsPatch();
        List<Optional<ItemStack>> contents = shulker.get(DataComponentTypes.CONTAINER);
        assertEquals(ItemMappings.get().javaId("diamond"), contents.get(2).orElseThrow().getId());
        assertEquals(5, contents.get(2).orElseThrow().getAmount());
    }

    @Test
    void customItems() {
        assertEquals(Key.key("jtb", "mymod/ruby_sword"), CustomItems.modelKey("mymod:ruby_sword"));
        assertEquals(Key.key("jtb", "my_mod/ruby_sword_2"), CustomItems.modelKey("My Mod:Ruby Sword#2"));
        assertEquals(Key.key("jtb", "minecraft/thing"), CustomItems.modelKey("thing"));

        ItemStack java = ItemTranslator.toJava(test.session(), test.item("mymod:ruby_sword", 1));
        assertNotNull(java);
        assertEquals(ItemMappings.get().javaId("paper"), java.getId());
        DataComponents components = java.getDataComponentsPatch();
        assertEquals(Key.key("jtb", "mymod/ruby_sword"), components.get(DataComponentTypes.ITEM_MODEL));
        assertEquals("Ruby Sword", plain(components.get(DataComponentTypes.ITEM_NAME)));
        // Damageable: Java requires a stack size of 1.
        assertEquals(1, components.get(DataComponentTypes.MAX_STACK_SIZE));
        assertEquals(250, components.get(DataComponentTypes.MAX_DAMAGE));
        assertEquals(EquipmentSlot.CHESTPLATE, components.get(DataComponentTypes.EQUIPPABLE).slot());
        assertEquals(1, ItemTranslator.maxStackSize(test.session(), test.item("mymod:ruby_sword", 1)));
    }

    /**
     * Every Bedrock item of the mapping shows as a Java item whose vanilla Bedrock counterpart shows as
     * the same Java item variant: Java stacks without identity data (creative tabs) map back sensibly.
     */
    @Test
    void everyVanillaItemProjectsBackToItself() {
        ItemMappings mappings = ItemMappings.get();
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (int id = 1; id < mappings.javaItemCount(); id++) {
            for (ItemMappings.BedrockVariant variant : mappings.bedrockVariants(id)) {
                ItemData item = ItemData.builder().definition(test.definition(variant.identifier()))
                        .damage(Math.max(variant.aux(), 0)).count(1).build();
                ItemStack java = ItemTranslator.toJava(test.session(), item, false);
                if (java == null) {
                    failures.add(variant.identifier() + ":" + variant.aux() + " -> nothing");
                    continue;
                }
                ItemData back = ItemTranslator.fromVanilla(test.session(), java);
                ItemStack again = ItemTranslator.toJava(test.session(), back, false);
                checked++;
                if (again == null || !ItemTranslator.variantKey(java).equals(ItemTranslator.variantKey(again))) {
                    failures.add(variant.identifier() + ":" + variant.aux() + " -> " + ItemTranslator.variantKey(java)
                            + " -> " + (back.getDefinition() == null ? "air" : back.getDefinition().getIdentifier() + ":" + back.getDamage())
                            + " -> " + (again == null ? "nothing" : ItemTranslator.variantKey(again)));
                }
            }
        }
        assertTrue(checked > 1500, "checked " + checked);
        assertTrue(failures.isEmpty(), failures.size() + " failures: " + failures.subList(0, Math.min(40, failures.size())));
    }

    @Test
    void javaStacksWithoutIdentity() {
        // A Java creative-tab stack: no custom_data, mapped through the tables.
        ItemData sword = ItemTranslator.toBedrock(test.session(), new ItemStack(ItemMappings.get().javaId("diamond_sword"), 1));
        assertEquals("minecraft:diamond_sword", sword.getDefinition().getIdentifier());
        assertNull(sword.getTag());
        assertTrue(ItemTranslator.isAir(ItemTranslator.toBedrock(test.session(), null)));
    }
}
