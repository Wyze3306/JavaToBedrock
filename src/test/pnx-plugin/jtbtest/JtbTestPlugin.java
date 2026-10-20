package jtbtest;

import org.powernukkitx.Player;
import org.powernukkitx.dialog.element.ElementDialogButton;
import org.powernukkitx.dialog.window.FormWindowDialog;
import org.powernukkitx.ddui.Observable;
import org.powernukkitx.event.EventHandler;
import org.powernukkitx.event.Listener;
import org.powernukkitx.event.player.PlayerJoinEvent;
import org.powernukkitx.form.element.ElementDivider;
import org.powernukkitx.form.element.ElementHeader;
import org.powernukkitx.form.element.simple.ButtonImage;
import org.powernukkitx.form.window.CustomForm;
import org.powernukkitx.form.window.ModalForm;
import org.powernukkitx.form.window.SimpleForm;
import org.powernukkitx.plugin.PluginBase;

import java.util.List;

/**
 * Test-only plugin for the JavaToBedrock end-to-end scenarios (FormScenario, UiScenario). When a
 * player joins it drives the UI through op commands run as that player (titles, scoreboards, camera),
 * a boss bar and a toast, then a chain of forms (simple, modal, custom, two queued forms, an NPC
 * dialogue, a data-driven screen) and registers a server settings form. Every answer is logged as
 * {@code JTBTEST <name>=<value>} for the scenarios to check.
 */
public final class JtbTestPlugin extends PluginBase implements Listener {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void log(String line) {
        getLogger().info("JTBTEST " + line);
    }

    private void later(int ticks, Runnable task) {
        getServer().getScheduler().scheduleDelayedTask(this, task, ticks);
    }

    private void run(Player player, String command) {
        getServer().executeCommand(player, command);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        CustomForm settings = new CustomForm("Paramètres JTB").addToggle("Musique", true);
        settings.onSubmit((p, response) -> {
            log("settings=" + response.getToggleResponse(0));
            later(10, () -> closedByServer(p));
        });
        settings.onClose(p -> log("settings=closed"));
        player.addServerSettings(settings);
        later(40, () -> ui(player));
        later(100, () -> simple(player));
    }

    // ------------------------------------------------------------------ ui

    private void ui(Player player) {
        run(player, "title @s subtitle Sous-titre JTB");
        run(player, "title @s title Titre JTB");
        run(player, "title @s actionbar Barre JTB");
        run(player, "scoreboard objectives add jtbside dummy JTBSide");
        run(player, "scoreboard objectives setdisplay sidebar jtbside descending");
        run(player, "scoreboard players set @s jtbside 42");
        run(player, "scoreboard players set Ligne jtbside 7");
        run(player, "scoreboard objectives add jtblist dummy JTBList");
        run(player, "scoreboard objectives setdisplay list jtblist ascending");
        run(player, "scoreboard players set @s jtblist 5");
        player.createBossBar("Boss JTB", 60);
        player.sendToast("Toast JTB", "Contenu du toast");
        run(player, "camera @s set minecraft:free pos ~ ~5 ~ rot 30 90");
        log("ui=sent");
        later(20, () -> {
            run(player, "scoreboard players reset Ligne jtbside");
            run(player, "scoreboard objectives remove jtblist");
            run(player, "scoreboard objectives add jtbasc dummy JTBAsc");
            run(player, "scoreboard objectives setdisplay sidebar jtbasc ascending");
            run(player, "scoreboard players set @s jtbasc 3");
            run(player, "camera @s clear");
            log("ui=updated");
        });
    }

    // ------------------------------------------------------------------ forms

    private void simple(Player player) {
        SimpleForm form = new SimpleForm("Menu JTB", "Choisissez\nune option");
        form.addButton("Option A");
        form.header("Section");
        form.addButton("Option B", new ButtonImage(ButtonImage.Type.PATH, "textures/items/diamond"));
        form.label("texte");
        form.divider();
        form.addButton("Option C");
        form.onSubmit((p, response) -> {
            log("simple=" + response.buttonId() + ":" + response.button().text());
            later(10, () -> modal(p));
        });
        form.onClose(p -> {
            log("simple=closed");
            later(10, () -> modal(p));
        });
        form.send(player);
    }

    private void modal(Player player) {
        ModalForm form = new ModalForm("Confirmer", "Êtes-vous sûr ?").text("Oui", "Non");
        form.onSubmit((p, response) -> {
            log("modal=" + response.yes());
            later(10, () -> custom(p));
        });
        form.onClose(p -> {
            log("modal=closed");
            later(10, () -> custom(p));
        });
        form.send(player);
    }

    private void custom(Player player) {
        CustomForm form = new CustomForm("Réglages")
                .addLabel("Intro")
                .addInput("Nom", "pseudo", "Steve")
                .addToggle("PvP", true)
                .addSlider("Volume", 0f, 100f, 5, 50f)
                .addStepSlider("Difficulté", List.of("Facile", "Normal", "Difficile"), 1)
                .addDropdown("Monde", List.of("A", "B", "C"), 0)
                .addElement(new ElementHeader("Fin"))
                .addElement(new ElementDivider(""));
        form.onSubmit((p, response) -> {
            log("custom=" + response.getInputResponse(1) + "|" + response.getToggleResponse(2) + "|"
                    + response.getSliderResponse(3) + "|" + response.getStepSliderResponse(4).elementId() + ":"
                    + response.getStepSliderResponse(4).elementText() + "|" + response.getDropdownResponse(5).elementId()
                    + ":" + response.getDropdownResponse(5).elementText());
            later(10, () -> queued(p));
        });
        form.onClose(p -> {
            log("custom=closed");
            later(10, () -> queued(p));
        });
        form.send(player);
    }

    /** Two forms sent at once: the proxy must show the second one only after the first is answered. */
    private void queued(Player player) {
        SimpleForm first = new SimpleForm("File 1", "Premier formulaire");
        first.addButton("Premier");
        first.addButton("Second");
        first.onSubmit((p, response) -> log("queue1=" + response.buttonId() + ":" + response.button().text()));
        first.onClose(p -> log("queue1=closed"));
        SimpleForm second = new SimpleForm("File 2", "Deuxième formulaire");
        second.addButton("Unique");
        second.onSubmit((p, response) -> {
            log("queue2=" + response.buttonId());
            later(10, () -> npc(p));
        });
        second.onClose(p -> {
            log("queue2=closed");
            later(10, () -> npc(p));
        });
        first.send(player);
        second.send(player);
    }

    /**
     * An NPC dialogue. The scenario closes it with Escape: clicking one of its buttons works (PowerNukkitX
     * receives EXECUTE_ACTION with the right button) but PowerNukkitX then fails to encode its own
     * NpcDialogue CLOSE answer (null strings) and disconnects the player, whatever the client.
     */
    private void npc(Player player) {
        FormWindowDialog dialog = new FormWindowDialog("PNJ JTB", "Bonjour voyageur", player);
        dialog.addButton(new ElementDialogButton("Acheter", "say acheter"));
        dialog.addButton(new ElementDialogButton("Vendre", "say vendre"));
        dialog.addHandler((p, response) -> {
            log("npc=" + response.getRequestType() + ":"
                    + (response.getClickedButton() == null ? "none" : response.getClickedButton().getName()));
            later(10, () -> ddui(p));
        });
        dialog.send(player);
    }

    /** A form the server closes itself (ClientboundCloseForm) a second later. */
    private void closedByServer(Player player) {
        SimpleForm form = new SimpleForm("Fermeture JTB", "Le serveur ferme ce formulaire");
        form.addButton("Attendre");
        form.onSubmit((p, response) -> log("closeform=answered"));
        form.onClose(p -> log("closeform=answered"));
        form.send(player);
        later(20, () -> {
            player.closeFormWindows();
            log("closeform=sent");
        });
    }

    /**
     * A data-driven screen, closed by the server two seconds later. (This PowerNukkitX build has the
     * ServerboundDataStore handler but does not register it, so button and toggle interactions are
     * not delivered to the screen's listeners.)
     */
    private void ddui(Player player) {
        Observable<Boolean> option = new Observable<>(false);
        Observable<String> name = new Observable<>("Steve");
        org.powernukkitx.ddui.CustomForm screen = new org.powernukkitx.ddui.CustomForm("DDUI JTB");
        screen.label("Écran piloté par les données");
        screen.toggle("Option", option);
        screen.textField("Nom", name);
        screen.button("Valider", p -> log("ddui=" + option.getValue() + "|" + name.getValue()));
        screen.closeButton();
        screen.show(player);
        later(40, () -> {
            screen.close(player);
            log("ddui=closed");
        });
    }
}
