package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.item.ItemRegistry;
import io.github.wyze3306.jtb.item.ItemTranslator;
import io.github.wyze3306.jtb.session.ProxySession;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Java item stacks the entity subsystem builds from Bedrock data that is not an item stack itself
 * (saddle flag, firework entity data). Conversion goes through {@link ItemTranslator} so the stacks
 * are the ones the inventory subsystem would produce.
 */
final class EntityItems {

    private EntityItems() {
    }

    /** Java stack of one Bedrock item, by identifier, with an optional NBT tag; null when unknown. */
    static @Nullable ItemStack javaItem(ProxySession session, String identifier, @Nullable NbtMap tag) {
        ItemDefinition definition = session.get(ItemRegistry.KEY).byIdentifier(identifier);
        if (definition == null) {
            return null;
        }
        ItemData.Builder builder = ItemData.builder().definition(definition).count(1);
        if (tag != null && !tag.isEmpty()) {
            builder.tag(tag);
        }
        return ItemTranslator.toJava(session, builder.build());
    }

    /** The firework rocket a Bedrock firework entity shows ({@code DISPLAY_FIREWORK} is its item tag). */
    static @Nullable ItemStack fireworkRocket(ProxySession session, NbtMap tag) {
        return javaItem(session, "minecraft:firework_rocket", tag);
    }

    static @Nullable ItemStack saddle(ProxySession session) {
        return javaItem(session, "minecraft:saddle", null);
    }
}
