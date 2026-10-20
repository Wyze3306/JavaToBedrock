package io.github.wyze3306.jtb.e2e.scenario;

import io.github.wyze3306.jtb.e2e.ClientContext;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.mcprotocollib.protocol.data.DefaultComponentSerializer;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomClickActionPacket;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Plays a Java 26.3 dialog like the vanilla client: reads labels, fills inputs, and runs a button's
 * action ({@code dynamic/custom}: every input value plus the static additions; {@code custom}: the
 * fixed payload). Also reads the PowerNukkitX log of the e2e instance for the test plugin's answers.
 */
final class DialogClicks {

    private DialogClicks() {
    }

    static String plain(@Nullable Object component) {
        if (component == null) {
            return "";
        }
        return PlainTextComponentSerializer.plainText().serialize(DefaultComponentSerializer.nbt().deserialize(component));
    }

    static String title(NbtMap dialog) {
        return plain(dialog.get("title"));
    }

    /** The button with this label among {@code actions} (multi_action) or the notice {@code action}. */
    static @Nullable NbtMap button(NbtMap dialog, String label) {
        for (NbtMap button : dialog.getList("actions", NbtType.COMPOUND, List.of())) {
            if (plain(button.get("label")).equals(label)) {
                return button;
            }
        }
        NbtMap notice = dialog.getCompound("action", null);
        return notice != null && plain(notice.get("label")).equals(label) ? notice : null;
    }

    /** The input with this label and type, or null. */
    static @Nullable NbtMap input(NbtMap dialog, String label, String type) {
        for (NbtMap input : dialog.getList("inputs", NbtType.COMPOUND, List.of())) {
            if (input.getString("type").equals(type) && plain(input.get("label")).equals(label)) {
                return input;
            }
        }
        return null;
    }

    /** The text of every body element, one per line. */
    static String bodyText(NbtMap dialog) {
        StringBuilder text = new StringBuilder();
        for (NbtMap body : dialog.getList("body", NbtType.COMPOUND, List.of())) {
            text.append(plain(body.get("contents"))).append('\n');
        }
        return text.toString();
    }

    /** The first button of a multi_action dialog (custom forms: the submit button). */
    static @Nullable NbtMap firstButton(NbtMap dialog) {
        List<NbtMap> actions = dialog.getList("actions", NbtType.COMPOUND, List.of());
        return actions.isEmpty() ? null : actions.get(0);
    }

    /** The button Escape runs. */
    static @Nullable NbtMap exitButton(NbtMap dialog) {
        NbtMap exit = dialog.getCompound("exit_action", null);
        return exit != null ? exit : dialog.getCompound("action", null);
    }

    /**
     * Clicks {@code button}. {@code values} overrides input values by input label: String for text
     * and single option (the option id), Boolean, Float.
     *
     * @return false when the button has no server action
     */
    static boolean click(ClientContext context, NbtMap dialog, @Nullable NbtMap button, Map<String, Object> values) {
        if (button == null) {
            context.log("dialog '" + title(dialog) + "': button not found");
            return false;
        }
        NbtMap action = button.getCompound("action", null);
        if (action == null) {
            return false;
        }
        Key id = Key.key(action.getString("id"));
        NbtMap payload;
        if (action.getString("type").equals("minecraft:dynamic/custom")) {
            NbtMapBuilder builder = NbtMap.builder();
            for (NbtMap input : dialog.getList("inputs", NbtType.COMPOUND, List.of())) {
                String key = input.getString("key");
                Object value = values.get(plain(input.get("label")));
                switch (input.getString("type")) {
                    case "minecraft:text" -> builder.putString(key, value instanceof String text ? text : input.getString("initial", ""));
                    case "minecraft:boolean" -> builder.putBoolean(key, value instanceof Boolean bool ? bool : input.getBoolean("initial", false));
                    case "minecraft:number_range" -> builder.putFloat(key, value instanceof Float number ? number : input.getFloat("initial", 0f));
                    case "minecraft:single_option" -> {
                        String selected = null;
                        for (NbtMap option : input.getList("options", NbtType.COMPOUND)) {
                            if (selected == null || option.getBoolean("initial", false)) {
                                selected = option.getString("id");
                            }
                        }
                        builder.putString(key, value instanceof String text ? text : selected);
                    }
                    default -> {
                    }
                }
            }
            builder.putAll(action.getCompound("additions", NbtMap.EMPTY));
            payload = builder.build();
        } else if (action.getString("type").equals("minecraft:custom")) {
            payload = action.getCompound("payload", null);
        } else {
            return false;
        }
        context.log("click '" + plain(button.get("label")) + "' on '" + title(dialog) + "' -> " + id + " " + payload);
        context.send(new ServerboundCustomClickActionPacket(id, payload));
        return true;
    }

    /** The PowerNukkitX log of this e2e instance (empty when unknown). */
    static String pnxLog() {
        String instance = System.getProperty("jtb-instance");
        if (instance == null) {
            return "";
        }
        try {
            return Files.readString(Path.of("/tmp/jtb/e2e-inst", instance, "pnx.log"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
