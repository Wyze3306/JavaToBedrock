package io.github.wyze3306.jtb.item;

import io.github.wyze3306.jtb.session.ProxySession;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * CONTRACT (owner: inventory/item subsystem). Bedrock item stacks to Java item stacks and back.
 * A converted stack carries its exact Bedrock identity in {@code custom_data} ({@code {jtb:{...}}}) so
 * converting it back is lossless.
 */
public final class ItemTranslator {

    private ItemTranslator() {
    }

    /** Java stack for a Bedrock stack, or null for air. */
    public static @Nullable ItemStack toJava(ProxySession session, ItemData item) {
        return null;
    }

    /** Bedrock stack for a Java stack (air for null). */
    public static ItemData toBedrock(ProxySession session, @Nullable ItemStack item) {
        return ItemData.AIR;
    }
}
