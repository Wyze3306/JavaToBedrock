package io.github.wyze3306.jtb.form;

import io.github.wyze3306.jtb.inventory.InventoryState;
import io.github.wyze3306.jtb.item.ItemMappings;
import io.github.wyze3306.jtb.resourcepack.ConversionArtifacts;
import io.github.wyze3306.jtb.resourcepack.ResourcePackState;
import io.github.wyze3306.jtb.session.ProxySession;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * A form with buttons (simple form, modal form) shown as a chest menu, the way Java servers do menus:
 * each button is an item named after it, with the button image as its model when the converted pack
 * has it ({@code ConversionArtifacts.formIcon}), else the vanilla item of the same name, else paper. The
 * form's text (content, labels) is the lore of a book in the top row. A click on a button answers the
 * form like a click on the Bedrock button; closing the chest closes the form. Forms with inputs, NPC
 * dialogues, data-driven screens and server settings stay dialogs.
 */
final class ChestMenu extends FormDialog {

    /** Java container id of chest menus (Bedrock window ids, which the inventory uses, stay far below). */
    static final int CONTAINER_ID = 120;
    private static final ContainerType[] TYPES = {ContainerType.GENERIC_9X1, ContainerType.GENERIC_9X2,
            ContainerType.GENERIC_9X3, ContainerType.GENERIC_9X4, ContainerType.GENERIC_9X5, ContainerType.GENERIC_9X6};

    private final FormState state;
    /** Button index of each menu slot, -1 for the others. */
    private int[] buttons = new int[0];
    private ItemStack[] items = new ItemStack[0];
    private int stateId;

    ChestMenu(FormState state, int formId, BedrockForm form) {
        super(state, formId, form, false);
        this.state = state;
    }

    /** Whether a form can be a chest menu: buttons only (labels allowed), and at most 6 rows. */
    static boolean fits(BedrockForm form) {
        return switch (form) {
            case BedrockForm.Simple simple -> simple.buttonCount() > 0 && rows(simple) <= 6;
            case BedrockForm.Modal ignored -> true;
            case BedrockForm.Custom ignored -> false;
        };
    }

    private static boolean hasInfo(BedrockForm.Simple simple) {
        if (!simple.content().isBlank()) {
            return true;
        }
        for (BedrockForm.SimpleElement element : simple.elements()) {
            if (element instanceof BedrockForm.Text text && !text.text().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static int rows(BedrockForm.Simple simple) {
        return (simple.buttonCount() + 8) / 9 + (hasInfo(simple) ? 1 : 0);
    }

    /** Opens (or redraws) the menu on the client. */
    void open() {
        ProxySession session = this.state.session();
        Function<String, Component> text = this.state.textFunction();
        Component title;
        int size;
        List<ItemStack> slots = new ArrayList<>();
        List<Integer> buttonSlots = new ArrayList<>();
        switch (this.form) {
            case BedrockForm.Simple simple -> {
                title = text.apply(simple.title());
                size = rows(simple) * 9;
                for (int i = 0; i < size; i++) {
                    slots.add(null);
                    buttonSlots.add(-1);
                }
                int next = 0;
                if (hasInfo(simple)) {
                    List<String> lines = new ArrayList<>();
                    if (!simple.content().isBlank()) {
                        lines.add(simple.content());
                    }
                    for (BedrockForm.SimpleElement element : simple.elements()) {
                        if (element instanceof BedrockForm.Text label && !label.text().isBlank()) {
                            lines.add(label.text());
                        }
                    }
                    slots.set(4, item(session, "book", null, simple.title(), String.join("\n", lines), text));
                    next = 9;
                }
                int button = 0;
                for (BedrockForm.SimpleElement element : simple.elements()) {
                    if (element instanceof BedrockForm.Button b) {
                        slots.set(next, item(session, null, b, b.text(), null, text));
                        buttonSlots.set(next, button++);
                        next++;
                    }
                }
            }
            case BedrockForm.Modal modal -> {
                title = text.apply(modal.title());
                size = 27;
                for (int i = 0; i < size; i++) {
                    slots.add(null);
                    buttonSlots.add(-1);
                }
                if (!modal.content().isBlank()) {
                    slots.set(4, item(session, "book", null, modal.title(), modal.content(), text));
                }
                slots.set(11, item(session, "lime_concrete", null, modal.button1(), null, text));
                buttonSlots.set(11, 0);
                slots.set(15, item(session, "red_concrete", null, modal.button2(), null, text));
                buttonSlots.set(15, 1);
            }
            case BedrockForm.Custom ignored -> throw new IllegalStateException("custom forms are dialogs");
        }
        this.items = slots.toArray(new ItemStack[0]);
        this.buttons = buttonSlots.stream().mapToInt(Integer::intValue).toArray();
        session.sendJava(new ClientboundOpenScreenPacket(CONTAINER_ID, TYPES[Math.min(size / 9, 6) - 1], title));
        sendContent();
    }

    /** Sends the menu and the player's inventory again (undoes what the client predicted for a click). */
    void sendContent() {
        ProxySession session = this.state.session();
        InventoryState inventory = session.get(InventoryState.KEY);
        ItemStack[] content = new ItemStack[this.items.length + 36];
        System.arraycopy(this.items, 0, content, 0, this.items.length);
        // Below the menu: main inventory (player slots 9-35), then the hotbar (0-8).
        for (int i = 0; i < 27; i++) {
            content[this.items.length + i] = inventory.javaInventoryItem(9 + i);
        }
        for (int i = 0; i < 9; i++) {
            content[this.items.length + 27 + i] = inventory.javaInventoryItem(i);
        }
        session.sendJava(new ClientboundContainerSetContentPacket(CONTAINER_ID, ++this.stateId, content, null));
    }

    /** The button a menu slot holds, or -1. */
    int button(int slot) {
        return slot >= 0 && slot < this.buttons.length ? this.buttons[slot] : -1;
    }

    /** Answers the form with the button of {@code slot}; KEEP when the slot holds no button. */
    Outcome clickSlot(int slot) {
        int button = button(slot);
        if (button < 0) {
            return Outcome.KEEP;
        }
        return click(NbtMap.builder().putInt(FormDialogs.BUTTON_ID, button).build());
    }

    /** Closing the chest: the form is closed (cancelled). */
    void closed() {
        click(NbtMap.builder().putBoolean(FormDialogs.EXIT, true).build());
    }

    private static @Nullable ItemStack item(ProxySession session, @Nullable String vanilla, BedrockForm.@Nullable Button button,
                                            String name, @Nullable String lore, Function<String, Component> text) {
        Key model = null;
        String javaItem = vanilla;
        if (button != null && "path".equalsIgnoreCase(button.imageType()) && button.imageData() != null) {
            String path = texturePath(button.imageData());
            ConversionArtifacts artifacts = session.get(ResourcePackState.KEY).artifacts();
            String icon = artifacts == null ? null : artifacts.formIcon(path);
            if (icon != null) {
                model = Key.key(icon);
            } else {
                String base = path.substring(path.lastIndexOf('/') + 1);
                if (ItemMappings.get().javaItem(Key.key("minecraft", base)) != null) {
                    javaItem = base;
                }
            }
        }
        if (javaItem == null) {
            javaItem = "paper";
        }
        ItemMappings.JavaItem definition = ItemMappings.get().javaItem(Key.key("minecraft", javaItem));
        if (definition == null) {
            definition = ItemMappings.get().javaItem(Key.key("minecraft", "paper"));
        }
        DataComponents components = new DataComponents(new HashMap<>());
        String[] lines = name.split("\n");
        components.put(DataComponentTypes.CUSTOM_NAME, plain(text.apply(lines[0]), NamedTextColor.WHITE));
        List<Component> loreLines = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            loreLines.add(plain(text.apply(lines[i]), NamedTextColor.GRAY));
        }
        if (lore != null) {
            for (String line : lore.split("\n")) {
                loreLines.add(plain(text.apply(line), NamedTextColor.GRAY));
            }
        }
        if (!loreLines.isEmpty()) {
            components.put(DataComponentTypes.LORE, loreLines);
        }
        if (model != null) {
            components.put(DataComponentTypes.ITEM_MODEL, model);
        }
        return new ItemStack(definition.id(), 1, components);
    }

    /** Not italic (custom names are by default) and {@code color} where the text sets none. */
    private static Component plain(Component component, NamedTextColor color) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE).colorIfAbsent(color);
    }

    /** {@code textures/ui/shop} for {@code /textures/ui/shop.png} and the other spellings servers use. */
    static String texturePath(String imageData) {
        String path = imageData.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        for (String extension : new String[]{".png", ".tga", ".jpg", ".jpeg"}) {
            if (path.endsWith(extension)) {
                path = path.substring(0, path.length() - extension.length());
            }
        }
        return path;
    }

    @Override
    public String toString() {
        return "chest menu of form " + this.formId;
    }
}
