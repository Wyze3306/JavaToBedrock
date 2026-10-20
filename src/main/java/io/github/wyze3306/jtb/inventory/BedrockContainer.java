package io.github.wyze3306.jtb.inventory;

import io.github.wyze3306.jtb.item.ItemTranslator;
import io.github.wyze3306.jtb.session.ProxySession;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Mirror of one Bedrock container: the items exactly as the server knows them (stack network ids
 * included), with their Java form cached until the slot changes.
 */
final class BedrockContainer {

    enum Kind {
        /** Bedrock container 0: hotbar 0-8, main inventory 9-35. */
        PLAYER,
        /** Bedrock container 120: head, chest, legs, feet. */
        ARMOR,
        /** Bedrock container 119. */
        OFFHAND,
        /**
         * Bedrock container 124, indexed by UI slot: 0 cursor, 1-3 anvil, 3 stonecutter, 4-5 trade,
         * 9-11 loom, 12-13 cartography, 14-15 enchanting, 16-17 grindstone, 27 beacon, 28-31 2x2 grid,
         * 32-40 3x3 grid, 51-53 smithing table.
         */
        UI,
        /** The storage of the open container (chest, furnace, hopper...), indexed by Bedrock slot. */
        WINDOW
    }

    final Kind kind;
    private ItemData[] items;
    private @Nullable ItemStack[] java;
    private boolean[] translated;

    BedrockContainer(Kind kind, int size) {
        this.kind = kind;
        this.items = new ItemData[size];
        Arrays.fill(this.items, ItemData.AIR);
        this.java = new ItemStack[size];
        this.translated = new boolean[size];
    }

    int size() {
        return this.items.length;
    }

    ItemData get(int index) {
        return index >= 0 && index < this.items.length ? this.items[index] : ItemData.AIR;
    }

    void set(int index, ItemData item) {
        if (index < 0) {
            return;
        }
        if (index >= this.items.length) {
            resize(index + 1);
        }
        ItemData value = ItemTranslator.isAir(item) ? ItemData.AIR : item;
        if (this.items[index] != value) {
            this.items[index] = value;
            this.translated[index] = false;
            this.java[index] = null;
        }
    }

    /** Java form of a slot, translated once per change. */
    @Nullable ItemStack java(ProxySession session, int index) {
        if (index < 0 || index >= this.items.length) {
            return null;
        }
        if (!this.translated[index]) {
            this.java[index] = ItemTranslator.toJava(session, this.items[index]);
            this.translated[index] = true;
        }
        return this.java[index];
    }

    void resize(int size) {
        int old = this.items.length;
        this.items = Arrays.copyOf(this.items, size);
        this.java = Arrays.copyOf(this.java, size);
        this.translated = Arrays.copyOf(this.translated, size);
        for (int i = old; i < size; i++) {
            this.items[i] = ItemData.AIR;
        }
    }

    void clear() {
        Arrays.fill(this.items, ItemData.AIR);
        Arrays.fill(this.java, null);
        Arrays.fill(this.translated, false);
    }

    /** Forgets every cached Java form (e.g. after the player's language changed). */
    void invalidateJava() {
        Arrays.fill(this.translated, false);
    }
}
