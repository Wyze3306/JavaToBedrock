package io.github.wyze3306.jtb.form;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.github.wyze3306.jtb.text.BedrockText;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.ModalFormCancelReason;
import org.geysermc.mcprotocollib.protocol.data.game.Holder;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundShowDialogGamePacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bedrock form JSON (as PowerNukkitX's {@code Form.toJson()} writes it) -> Java dialog NBT -> the
 * response JSON, checked the way PowerNukkitX's {@code respond} methods parse it.
 */
class FormTranslationTest {

    private static final Key ACTION = Key.key("jtb", "form/7");
    private static final Function<String, Component> TEXT = BedrockText::legacyToComponent;

    // Exactly PNX output (Gson, field order of toJson()).
    private static final String SIMPLE = "{\"type\":\"form\",\"title\":\"§6Menu\",\"content\":\"Choisissez\\nune option\","
            + "\"elements\":[{\"type\":\"button\",\"text\":\"Option A\"},{\"type\":\"header\",\"text\":\"Section\"},"
            + "{\"type\":\"button\",\"text\":\"Option B\",\"image\":{\"type\":\"path\",\"data\":\"textures/items/diamond\"}},"
            + "{\"type\":\"label\",\"text\":\"texte\"},{\"type\":\"divider\",\"text\":\"\"},{\"type\":\"button\",\"text\":\"Option C\"}]}";
    private static final String MODAL = "{\"type\":\"modal\",\"title\":\"Confirmer\",\"content\":\"Sûr ?\",\"button1\":\"Oui\",\"button2\":\"Non\"}";
    private static final String CUSTOM = "{\"type\":\"custom_form\",\"title\":\"Réglages\",\"content\":["
            + "{\"type\":\"label\",\"text\":\"Intro\"},"
            + "{\"type\":\"input\",\"text\":\"Nom\",\"placeholder\":\"pseudo\",\"default\":\"Steve\"},"
            + "{\"type\":\"toggle\",\"text\":\"PvP\",\"default\":true},"
            + "{\"type\":\"slider\",\"text\":\"Volume\",\"min\":0.0,\"max\":100.0,\"step\":5,\"default\":50.0},"
            + "{\"type\":\"step_slider\",\"text\":\"Difficulté\",\"default\":1,\"steps\":[\"Facile\",\"Normal\",\"Difficile\"]},"
            + "{\"type\":\"dropdown\",\"text\":\"Monde\",\"default\":0,\"options\":[\"A\",\"B\",\"C\"]},"
            + "{\"type\":\"header\",\"text\":\"Fin\"},{\"type\":\"divider\",\"text\":\"\"}],\"submit\":\"Envoyer\"}";

    @Test
    void parsesThePowerNukkitXFormats() {
        BedrockForm.Simple simple = assertInstanceOf(BedrockForm.Simple.class, BedrockForm.parse(SIMPLE));
        assertEquals(6, simple.elements().size());
        assertEquals(3, simple.buttonCount());
        BedrockForm.Button b = assertInstanceOf(BedrockForm.Button.class, simple.elements().get(2));
        assertEquals("path", b.imageType());
        assertEquals("textures/items/diamond", b.imageData());

        BedrockForm.Modal modal = assertInstanceOf(BedrockForm.Modal.class, BedrockForm.parse(MODAL));
        assertEquals("Oui", modal.button1());

        BedrockForm.Custom custom = assertInstanceOf(BedrockForm.Custom.class, BedrockForm.parse(CUSTOM));
        assertEquals(8, custom.elements().size());
        assertEquals("Envoyer", custom.submit());
        BedrockForm.Slider slider = assertInstanceOf(BedrockForm.Slider.class, custom.elements().get(3));
        assertEquals(5f, slider.step());
        assertEquals(50f, slider.defaultValue());
        assertEquals(1, assertInstanceOf(BedrockForm.StepSlider.class, custom.elements().get(4)).defaultIndex());
    }

    @Test
    void acceptsLegacyButtonsKeyAndRejectsGarbage() {
        BedrockForm.Simple legacy = assertInstanceOf(BedrockForm.Simple.class,
                BedrockForm.parse("{\"type\":\"form\",\"title\":\"t\",\"content\":\"\",\"buttons\":[{\"text\":\"x\"}]}"));
        assertEquals(1, legacy.buttonCount());
        assertThrows(IllegalArgumentException.class, () -> BedrockForm.parse("not json"));
        assertThrows(IllegalArgumentException.class, () -> BedrockForm.parse("{\"type\":\"other\"}"));
    }

    @Test
    void simpleFormDialogAndResponses() {
        BedrockForm form = BedrockForm.parse(SIMPLE);
        NbtMap dialog = FormDialogs.build(form, ACTION, TEXT);
        assertEquals("minecraft:multi_action", dialog.getString("type"));
        assertFalse(dialog.getBoolean("pause", true));
        List<NbtMap> actions = dialog.getList("actions", NbtType.COMPOUND);
        // 3 buttons + the label rendered as a (reopening) button; the divider is dropped; the header
        // comes after the first button so it is a button too.
        assertEquals(5, actions.size());
        assertEquals(0, additions(actions.get(0)).getInt(FormDialogs.BUTTON_ID));
        assertTrue(additions(actions.get(1)).getBoolean(FormDialogs.REOPEN));
        assertEquals(1, additions(actions.get(2)).getInt(FormDialogs.BUTTON_ID));
        assertEquals(2, additions(actions.get(4)).getInt(FormDialogs.BUTTON_ID));
        assertEquals("minecraft:dynamic/custom", actions.get(0).getCompound("action").getString("type"));
        assertEquals(ACTION.asString(), actions.get(0).getCompound("action").getString("id"));
        assertTrue(additions(dialog.getCompound("exit_action")).getBoolean(FormDialogs.EXIT));
        roundTrip(dialog);

        // The button index counts buttons only: "Option C" is the third button.
        FormResponses.Result result = FormResponses.respond(form, additions(actions.get(4)));
        assertEquals("2\n", result.json());
        assertNull(result.cancel());
        assertEquals(2, Integer.parseInt(result.json().trim()));

        assertTrue(FormResponses.respond(form, additions(actions.get(1))).reopen());
        FormResponses.Result closed = FormResponses.respond(form, additions(dialog.getCompound("exit_action")));
        assertNull(closed.json());
        assertEquals(ModalFormCancelReason.USER_CLOSED, closed.cancel());
    }

    @Test
    void simpleFormWithoutButtonsIsANotice() {
        BedrockForm form = BedrockForm.parse("{\"type\":\"form\",\"title\":\"Info\",\"content\":\"Bonjour\",\"elements\":[]}");
        NbtMap dialog = FormDialogs.build(form, ACTION, TEXT);
        assertEquals("minecraft:notice", dialog.getString("type"));
        assertTrue(additions(dialog.getCompound("action")).getBoolean(FormDialogs.EXIT));
        roundTrip(dialog);
    }

    @Test
    void modalFormDialogAndResponses() {
        BedrockForm form = BedrockForm.parse(MODAL);
        NbtMap dialog = FormDialogs.build(form, ACTION, TEXT);
        List<NbtMap> actions = dialog.getList("actions", NbtType.COMPOUND);
        assertEquals(2, actions.size());
        assertEquals("true\n", FormResponses.respond(form, additions(actions.get(0))).json());
        assertEquals("false\n", FormResponses.respond(form, additions(actions.get(1))).json());
        // PNX: yes = formData.trim().equals("true")
        assertFalse(FormResponses.respond(form, additions(actions.get(1))).json().trim().equals("true"));
        assertEquals(ModalFormCancelReason.USER_CLOSED,
                FormResponses.respond(form, additions(dialog.getCompound("exit_action"))).cancel());
        roundTrip(dialog);
    }

    @Test
    void customFormDialog() {
        NbtMap dialog = FormDialogs.build(BedrockForm.parse(CUSTOM), ACTION, TEXT);
        List<NbtMap> body = dialog.getList("body", NbtType.COMPOUND);
        assertEquals(1, body.size()); // the leading label
        List<NbtMap> inputs = dialog.getList("inputs", NbtType.COMPOUND);
        assertEquals(7, inputs.size()); // 5 inputs + header and divider as label lines
        assertEquals("minecraft:text", inputs.get(0).getString("type"));
        assertEquals("1", inputs.get(0).getString("key"));
        assertEquals("Steve", inputs.get(0).getString("initial"));
        assertEquals("minecraft:boolean", inputs.get(1).getString("type"));
        assertTrue(inputs.get(1).getBoolean("initial"));
        assertEquals("minecraft:number_range", inputs.get(2).getString("type"));
        assertEquals(0f, inputs.get(2).getFloat("start"));
        assertEquals(100f, inputs.get(2).getFloat("end"));
        assertEquals(5f, inputs.get(2).getFloat("step"));
        assertEquals(50f, inputs.get(2).getFloat("initial"));
        assertEquals("minecraft:single_option", inputs.get(3).getString("type"));
        List<NbtMap> steps = inputs.get(3).getList("options", NbtType.COMPOUND);
        assertEquals(3, steps.size());
        assertTrue(steps.get(1).getBoolean("initial"));
        assertFalse(steps.get(0).getBoolean("initial"));
        assertEquals("minecraft:single_option", inputs.get(4).getString("type"));
        assertEquals("l6", inputs.get(5).getString("key"));
        assertEquals(16, inputs.get(5).getInt("width"));
        List<NbtMap> actions = dialog.getList("actions", NbtType.COMPOUND);
        assertEquals(1, actions.size());
        assertFalse(actions.get(0).getCompound("action").containsKey("additions"));
        roundTrip(dialog);
    }

    @Test
    void customFormResponseIsWhatPowerNukkitXParses() {
        BedrockForm form = BedrockForm.parse(CUSTOM);
        // What the Java client sends: every input value, typed like dialog inputs report them.
        NbtMap payload = NbtMap.builder()
                .putString("1", "Alex")
                .putBoolean("2", false)
                .putFloat("3", 75f)
                .putString("4", "2")
                .putString("5", "1")
                .putString("l6", "")
                .putString("l7", "")
                .build();
        FormResponses.Result result = FormResponses.respond(form, payload);
        assertNull(result.cancel());
        assertEquals("[null,\"Alex\",false,75.0,2,1,null,null]\n", result.json());

        // PowerNukkitX: JSONUtils.from(formData.trim(), List<String>) then per-element parsing.
        List<String> values = new Gson().fromJson(result.json().trim(), new TypeToken<List<String>>() {
        }.getType());
        assertEquals(8, values.size());
        assertNull(values.get(0));
        assertEquals("Alex", values.get(1));
        assertFalse(Boolean.parseBoolean(values.get(2)));
        assertEquals(75f, Float.parseFloat(values.get(3)));
        assertEquals(2, Integer.parseInt(values.get(4)));
        assertEquals(1, Integer.parseInt(values.get(5)));
    }

    @Test
    void customFormResponseKeepsDefaultsAndClampsValues() {
        BedrockForm form = BedrockForm.parse(CUSTOM);
        String json = FormResponses.respond(form, NbtMap.builder().putFloat("3", 500f).putString("4", "9").build()).json();
        assertEquals("[null,\"Steve\",true,100.0,1,0,null,null]\n", json);
    }

    @Test
    void customFormResponseNeverExceedsTheServerLimit() {
        BedrockForm form = BedrockForm.parse("{\"type\":\"custom_form\",\"title\":\"t\",\"content\":["
                + "{\"type\":\"input\",\"text\":\"a\",\"placeholder\":\"\",\"default\":\"\"},"
                + "{\"type\":\"input\",\"text\":\"b\",\"placeholder\":\"\",\"default\":\"\"}]}");
        String longText = "é\"".repeat(400);
        String json = FormResponses.respond(form, NbtMap.builder().putString("0", longText).putString("1", "court").build()).json();
        assertTrue(json.length() <= FormResponses.MAX_LENGTH, "length " + json.length());
        List<String> values = new Gson().fromJson(json.trim(), new TypeToken<List<String>>() {
        }.getType());
        assertEquals("court", values.get(1));
        assertTrue(longText.startsWith(values.get(0)));
    }

    @Test
    void degenerateCustomElementsStillBuildAValidDialog() {
        BedrockForm form = BedrockForm.parse("{\"type\":\"custom_form\",\"title\":\"t\",\"content\":["
                + "{\"type\":\"slider\",\"text\":\"s\",\"min\":5,\"max\":5,\"step\":0,\"default\":9},"
                + "{\"type\":\"dropdown\",\"text\":\"d\",\"options\":[],\"default\":3},"
                + "{\"type\":\"input\",\"text\":\"i\",\"default\":\"" + "x".repeat(600) + "\"}]}");
        NbtMap dialog = FormDialogs.build(form, ACTION, TEXT);
        List<NbtMap> inputs = dialog.getList("inputs", NbtType.COMPOUND);
        NbtMap slider = inputs.get(0);
        assertTrue(slider.getFloat("end") > slider.getFloat("start"));
        assertTrue(slider.getFloat("initial") <= slider.getFloat("end"));
        assertFalse(slider.containsKey("step"));
        assertEquals("minecraft:text", inputs.get(1).getString("type")); // empty dropdown: label only
        assertTrue(inputs.get(2).getInt("max_length") >= 600);
        roundTrip(dialog);
        assertEquals("[5.0,0,\"" + "x".repeat(600) + "\"]\n", FormResponses.respond(form, NbtMap.EMPTY).json());
    }

    @Test
    void npcActionsKeepTheirIndexInTheWholeList() {
        List<NpcDialog.Action> actions = NpcDialog.parseActions("[{\"button_name\":\"Acheter\",\"text\":\"say a\",\"data\":[],\"mode\":0,\"type\":1},"
                + "{\"button_name\":\"onclose\",\"text\":\"\",\"mode\":1,\"type\":1},"
                + "{\"button_name\":\"Vendre\",\"text\":\"say b\",\"mode\":0,\"type\":1}]");
        assertEquals(List.of(new NpcDialog.Action(0, "Acheter"), new NpcDialog.Action(2, "Vendre")), actions);
        assertTrue(NpcDialog.parseActions("").isEmpty());
        assertTrue(NpcDialog.parseActions("{oops").isEmpty());
    }

    @Test
    void serverSettingsMenuDialogIsValid() {
        NbtMap dialog = ServerSettingsMenu.dialog();
        assertEquals("minecraft:multi_action", dialog.getString("type"));
        NbtMap action = dialog.getList("actions", NbtType.COMPOUND).get(0).getCompound("action");
        assertEquals("minecraft:custom", action.getString("type"));
        assertEquals(ServerSettingsMenu.ACTION.asString(), action.getString("id"));
        assertFalse(dialog.getCompound("exit_action").containsKey("action"));
        roundTrip(dialog);
    }

    private static NbtMap additions(NbtMap button) {
        return button.getCompound("action").getCompound("additions");
    }

    /** The dialog survives MCProtocolLib's encoder and decoder. */
    private static void roundTrip(NbtMap dialog) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            new ClientboundShowDialogGamePacket(Holder.ofCustom(dialog)).serialize(buffer);
            ClientboundShowDialogGamePacket decoded = new ClientboundShowDialogGamePacket(buffer);
            assertEquals(dialog, decoded.getDialog().custom());
        } finally {
            buffer.release();
        }
    }
}
