package io.github.wyze3306.jtb.form;

import net.kyori.adventure.key.Key;
import org.cloudburstmc.nbt.NbtMap;
import org.jspecify.annotations.Nullable;

/**
 * Something the proxy shows as a Java dialog on behalf of the Bedrock server (a form, an NPC
 * dialogue, a data-driven screen, a proxy message). {@link FormState} shows one at a time; each
 * display gets a fresh action id so stale clicks are ignored.
 */
abstract class ShownDialog {

    /** What happens after a click. */
    enum Outcome {
        /** Answered: the dialog is finished, the next queued one is shown. */
        DONE,
        /** Show the same dialog again (e.g. a text line rendered as a button was clicked). */
        REOPEN,
        /** Still open on the client (dialogs with {@code after_action: none}). */
        KEEP
    }

    /** Proxy-side display number, part of the action id; 0 until shown. */
    int displayId;

    /** Builds the dialog NBT; buttons must use {@code actionId}. May throw on unusable data. */
    abstract NbtMap build(Key actionId);

    /** Handles a click on one of this dialog's buttons. */
    abstract Outcome click(@Nullable NbtMap payload);

    /**
     * Called when the dialog cannot be shown (its definition failed to build) so the server is not
     * left waiting for an answer.
     */
    void failed() {
    }

    /** True when the client closes the dialog by itself after a button ({@code after_action: close}). */
    boolean closesItself() {
        return true;
    }
}
