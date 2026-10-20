package io.github.wyze3306.jtb.inventory;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;

/**
 * CONTRACT (owner: inventory subsystem). The player's inventory and open containers, mirrored
 * from the server, and the translation of Java clicks into Bedrock item stack requests.
 */
public final class InventoryState implements SessionState {

    public static final StateKey<InventoryState> KEY = StateKey.of("inventory", InventoryState::new);

    private final ProxySession session;

    private InventoryState(ProxySession session) {
        this.session = session;
    }

    /** Selected hotbar slot, 0-8. */
    public int selectedSlot() {
        return 0;
    }

    /** The item in the main hand, exactly as the server knows it (with its stack network id). */
    public ItemData heldItem() {
        return ItemData.AIR;
    }

    /** The item in the off hand. */
    public ItemData offhandItem() {
        return ItemData.AIR;
    }

    /** Drops one item, or the whole stack, from the main hand (Java Q / Ctrl+Q). */
    public void dropHeldItem(boolean wholeStack) {
    }

    /** Swaps main hand and off hand (Java F). */
    public void swapOffhand() {
    }
}
