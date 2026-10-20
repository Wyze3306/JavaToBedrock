package io.github.wyze3306.jtb.ui;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.geysermc.mcprotocollib.protocol.data.game.advancement.Advancement;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundUpdateAdvancementsPacket;

import java.util.List;
import java.util.Map;

/**
 * Bedrock toasts ({@code ToastRequest}) on Java, which has no free-form toast packet: a hidden
 * advancement is added already completed (the client shows its toast), then removed in the next
 * packet. Each toast uses a fresh id because the client never toasts the same completed advancement
 * twice. The toast shows the title (header line: the frame type's "Goal Reached!"); the content is
 * appended to the title in grey and wraps.
 */
public final class ToastState implements SessionState {

    public static final StateKey<ToastState> KEY = StateKey.of("ui-toasts", ToastState::new);

    /** {@code minecraft:paper} in the 26.3 item registry. */
    static final int ICON_PAPER = 1145;
    /** {@code minecraft:knowledge_book}. */
    static final int ICON_KNOWLEDGE_BOOK = 1458;
    private static final String CRITERION = "shown";

    private final ProxySession session;
    private int next;

    private ToastState(ProxySession session) {
        this.session = session;
    }

    /** Shows a toast; {@code content} may be empty. */
    public void show(Component title, Component content, int iconItem, Advancement.DisplayData.AdvancementType frame) {
        Component text = Component.empty().append(title);
        if (!content.equals(Component.empty())) {
            text = text.append(Component.text(" ")).append(content.colorIfAbsent(NamedTextColor.GRAY));
        }
        String id = "jtb:toast/" + (++this.next);
        Advancement advancement = new Advancement(id, List.of(List.of(CRITERION)), null,
                new Advancement.DisplayData(text, Component.empty(), new ItemStack(iconItem, 1), frame, true, true),
                false, 0f, 0f);
        this.session.sendJava(new ClientboundUpdateAdvancementsPacket(false, new Advancement[]{advancement}, new String[0],
                Map.of(id, Map.of(CRITERION, System.currentTimeMillis())), true));
        this.session.sendJava(new ClientboundUpdateAdvancementsPacket(false, new Advancement[0], new String[]{id}, Map.of(), true));
    }
}
