package io.github.wyze3306.jtb.form;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.nbt.NbtMap;
import org.jspecify.annotations.Nullable;

/** A message from the proxy itself, closed with one button; nothing is sent to the server. */
final class MessageDialog extends ShownDialog {

    private final Component title;
    private final Component message;

    MessageDialog(Component title, Component message) {
        this.title = title;
        this.message = message;
    }

    @Override
    NbtMap build(Key actionId) {
        return Dialogs.notice(this.title)
                .body(Dialogs.plainMessage(this.message, Dialogs.CONTENT_WIDTH))
                .noticeAction(Dialogs.button(Component.translatable("gui.ok"), Dialogs.dynamicCustom(actionId, null)))
                .build();
    }

    @Override
    Outcome click(@Nullable NbtMap payload) {
        return Outcome.DONE;
    }

    @Override
    public String toString() {
        return "message";
    }
}
