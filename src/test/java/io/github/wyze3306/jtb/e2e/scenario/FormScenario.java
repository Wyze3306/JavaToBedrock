package io.github.wyze3306.jtb.e2e.scenario;

import io.github.wyze3306.jtb.e2e.ClientContext;
import io.github.wyze3306.jtb.e2e.E2eScenario;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundClearDialogPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundUpdateTagsPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomClickActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundShowDialogGamePacket;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bedrock forms as Java dialogs, against PowerNukkitX with the test plugin
 * ({@code src/test/pnx-plugin}, installed in {@code /tmp/jtb/pnx-inst/<N>/plugins}): answers a
 * simple form (third button, after a header and a label), a modal form (button 2), a custom form
 * (every input type), two forms sent at once (the second must wait for the first; closed with
 * Escape), an NPC dialogue (a button clicked) and a data-driven screen (values and a click), then opens the server settings from the pause
 * menu action and submits them, and lets the server close a last form. Checks the plugin received
 * exactly those values.
 */
public final class FormScenario implements E2eScenario {

    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private volatile boolean firstQueuedAnswered;
    private volatile boolean queueOrdered;
    private volatile boolean npcButtons;
    private volatile boolean dduiShape;
    private volatile boolean dduiClicked;
    private volatile boolean dduiCleared;
    private volatile boolean settingsRequested;
    private volatile boolean closingShown;
    private volatile boolean closingCleared;
    private volatile int pauseMenuEntry = -1;
    private volatile boolean pauseMenuTagged;

    @Override
    public void onPacket(ClientContext context, Packet packet) {
        if (packet instanceof ClientboundRegistryDataPacket registry && registry.getRegistry().asString().equals("minecraft:dialog")) {
            List<RegistryEntry> entries = registry.getEntries();
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).getId().asString().equals("jtb:server_settings") && entries.get(i).getData() != null) {
                    this.pauseMenuEntry = i;
                }
            }
        } else if (packet instanceof ClientboundUpdateTagsPacket tags) {
            int[] additions = tags.getTags().getOrDefault(Key.key("minecraft:dialog"), Map.of())
                    .get(Key.key("minecraft:pause_screen_additions"));
            this.pauseMenuTagged = additions != null && additions.length == 1 && additions[0] == this.pauseMenuEntry;
        } else if (packet instanceof ClientboundShowDialogGamePacket show && show.getDialog().isCustom()) {
            onDialog(context, show.getDialog().custom());
        } else if (packet instanceof ClientboundClearDialogPacket) {
            if (this.closingShown) {
                this.closingCleared = true;
            } else if (this.dduiClicked) {
                this.dduiCleared = true;
            }
        }
    }

    private void onDialog(ClientContext context, NbtMap dialog) {
        String title = DialogClicks.title(dialog);
        boolean first = this.seen.add(title);
        context.log("dialog '" + title + "'" + (first ? "" : " (again)"));
        switch (title) {
            case "Menu JTB" -> DialogClicks.click(context, dialog, DialogClicks.button(dialog, "Option C"), Map.of());
            case "Confirmer" -> DialogClicks.click(context, dialog, DialogClicks.button(dialog, "Non"), Map.of());
            case "Réglages" -> DialogClicks.click(context, dialog, DialogClicks.firstButton(dialog),
                    Map.of("Nom", "Alex", "PvP", false, "Volume", 75f, "Difficulté", "2", "Monde", "1"));
            case "File 1" -> {
                this.firstQueuedAnswered = DialogClicks.click(context, dialog, DialogClicks.button(dialog, "Second"), Map.of());
            }
            case "File 2" -> {
                this.queueOrdered = this.firstQueuedAnswered;
                DialogClicks.click(context, dialog, DialogClicks.exitButton(dialog), Map.of());
            }
            case "PNJ JTB" -> {
                this.npcButtons = DialogClicks.button(dialog, "Acheter") != null && DialogClicks.button(dialog, "Vendre") != null;
                DialogClicks.click(context, dialog, DialogClicks.button(dialog, "Vendre"), Map.of());
            }
            case "DDUI JTB" -> {
                if (!this.dduiClicked) {
                    this.dduiShape = DialogClicks.input(dialog, "Option", "minecraft:boolean") != null
                            && "Steve".equals(stringOrNull(DialogClicks.input(dialog, "Nom", "minecraft:text")))
                            && DialogClicks.bodyText(dialog).contains("Écran piloté par les données")
                            && DialogClicks.plain(DialogClicks.exitButton(dialog).get("label")).equals("Close");
                    this.dduiClicked = DialogClicks.click(context, dialog, DialogClicks.button(dialog, "Valider"),
                            Map.of("Option", true, "Nom", "Alex"));
                }
            }
            case "Paramètres JTB" -> DialogClicks.click(context, dialog, DialogClicks.firstButton(dialog), Map.of("Musique", false));
            case "Fermeture JTB" -> this.closingShown = true; // not answered: the server closes it
            default -> {
            }
        }
    }

    private static String stringOrNull(NbtMap input) {
        return input == null ? null : input.getString("initial", null);
    }

    @Override
    public void tick(ClientContext context, int tick) {
        if (this.dduiCleared && !this.settingsRequested) {
            // What the pause menu's "server settings" dialog button sends.
            this.settingsRequested = true;
            context.log("opening the server settings");
            context.send(new ServerboundCustomClickActionPacket(Key.key("jtb:server_settings"), null));
        }
    }

    @Override
    public void finish(ClientContext context) {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String log = DialogClicks.pnxLog();
        context.check("pause menu dialog registered", this.pauseMenuEntry >= 0);
        context.check("pause menu dialog in #pause_screen_additions", this.pauseMenuTagged);
        context.check("simple form: third button (buttons counted alone)", log.contains("JTBTEST simple=2:Option C"));
        context.check("modal form: button 2", log.contains("JTBTEST modal=false"));
        context.check("custom form: every input", log.contains("JTBTEST custom=Alex|false|75.0|2:Difficile|1:B"));
        context.check("queued forms: first answered", log.contains("JTBTEST queue1=1:Second"));
        context.check("queued forms: second shown after the first", this.queueOrdered);
        context.check("queued forms: Escape closes", log.contains("JTBTEST queue2=closed"));
        context.check("NPC dialogue: buttons shown", this.npcButtons);
        context.check("NPC dialogue: the clicked button reaches the server", log.contains("JTBTEST npc=EXECUTE_ACTION:Vendre"));
        context.check("data-driven screen as a dialog (label, toggle, text field, button)", this.dduiShape && this.dduiClicked);
        context.check("data-driven screen: the click and values reach its listeners", log.contains("JTBTEST ddui=true|Alex"));
        context.check("data-driven screen closed by the server", this.dduiCleared && log.contains("JTBTEST ddui=closed"));
        context.check("server settings form", log.contains("JTBTEST settings=false"));
        context.check("form closed by the server", this.closingShown && this.closingCleared
                && log.contains("JTBTEST closeform=sent") && !log.contains("JTBTEST closeform=answered"));
        context.check("no form handling error on the server", !log.contains("Packet handling error") && !log.contains("unknown form id"));
    }
}
