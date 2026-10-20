package io.github.wyze3306.jtb.form;

import io.github.wyze3306.jtb.network.java.JavaConfiguration;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;

import java.util.List;

/**
 * Puts a "server settings" entry in the Java pause menu: a dialog registry entry listed in the
 * {@code minecraft:pause_screen_additions} tag (one entry: the pause menu shows a button with its
 * {@code external_title} that opens it) and in {@code minecraft:quick_actions} (the quick actions key
 * opens it). Its "open" button sends {@code ServerboundCustomClickAction(jtb:server_settings)}, which
 * {@link FormState#openServerSettings()} turns into the Bedrock {@code ServerSettingsRequest} the
 * Bedrock client sends when its settings screen opens.
 * <p>
 * The registry is sent once, during the first configuration phase, and does not depend on the
 * server; whether the server actually has settings forms is only known after asking.
 */
public final class ServerSettingsMenu {

    /** Action id of the pause menu dialog's button. */
    public static final Key ACTION = Key.key(FormState.NAMESPACE, "server_settings");
    /** Id of the dialog registry entry. */
    public static final Key DIALOG = Key.key(FormState.NAMESPACE, "server_settings");

    private static final Key DIALOG_REGISTRY = Key.key("minecraft:dialog");
    private static final Key PAUSE_SCREEN_ADDITIONS = Key.key("minecraft:pause_screen_additions");
    private static final Key QUICK_ACTIONS = Key.key("minecraft:quick_actions");

    private ServerSettingsMenu() {
    }

    /** {@link JavaConfiguration#addContributor} callback. */
    public static void contribute(JavaConfiguration configuration) {
        int id = configuration.addRegistryEntries(DIALOG_REGISTRY, List.of(new RegistryEntry(DIALOG, dialog())));
        configuration.addTagEntries(DIALOG_REGISTRY, PAUSE_SCREEN_ADDITIONS, id);
        configuration.addTagEntries(DIALOG_REGISTRY, QUICK_ACTIONS, id);
    }

    static NbtMap dialog() {
        return Dialogs.multiAction(Component.text("Paramètres du serveur"))
                .externalTitle(Component.text("Paramètres du serveur Bedrock..."))
                .body(Dialogs.plainMessage(Component.text("Ouvre les réglages proposés par le serveur Bedrock "
                        + "(l'écran « Paramètres > Serveur » des joueurs Bedrock)."), Dialogs.CONTENT_WIDTH))
                .action(Dialogs.button(Component.text("Ouvrir"), Dialogs.custom(ACTION, null)))
                .exitAction(Dialogs.button(Component.translatable("gui.back"), null))
                .build();
    }
}
