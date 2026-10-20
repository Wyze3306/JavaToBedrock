package io.github.wyze3306.jtb.form;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.packet.NpcRequestPacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * An NPC dialogue ({@code NpcDialoguePacket OPEN}) as a dialog: the NPC name as title, the dialogue
 * as message and one button per action of mode 0 ("button"; modes 1 and 2 run on close/open and are
 * not shown). The action JSON is the Bedrock NPC format
 * {@code [{"button_name":..,"text":<commands>,"data":[{"cmd_line":..,"cmd_ver":..}],"mode":0,"type":1}]};
 * the label is {@code button_name}.
 * <p>
 * A click answers {@code NpcRequest(EXECUTE_ACTION, actionIndex = index in the whole action list)}.
 * Escape answers {@code EXECUTE_CLOSING_COMMANDS} with an action index no action has (255), so the
 * server sees no clicked button. The request is addressed with the dialogue's NPC id: PowerNukkitX
 * uses the same number for unique and runtime ids.
 */
final class NpcDialog extends ShownDialog {

    private static final int NO_ACTION = -1;

    private final FormState state;
    final long npcId;
    private String sceneName;
    private String npcName;
    private String dialogue;
    private List<Action> actions;

    record Action(int index, String label) {
    }

    NpcDialog(FormState state, long npcId, String sceneName, String npcName, String dialogue, String actionJson) {
        this.state = state;
        this.npcId = npcId;
        this.sceneName = sceneName;
        this.npcName = npcName;
        this.dialogue = dialogue;
        this.actions = parseActions(actionJson);
    }

    /** Takes the content of a newer dialogue of the same NPC (scrolling text, next scene). */
    void replaceWith(NpcDialog other) {
        this.sceneName = other.sceneName;
        this.npcName = other.npcName;
        this.dialogue = other.dialogue;
        this.actions = other.actions;
    }

    boolean matches(long npcId, @Nullable String sceneName) {
        return this.npcId == npcId && (sceneName == null || sceneName.isEmpty() || sceneName.equals(this.sceneName));
    }

    /** Buttons of an NPC action list (mode 0 entries), with their index in the whole list. */
    static List<Action> parseActions(String json) {
        List<Action> actions = new ArrayList<>();
        if (json.isBlank()) {
            return actions;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return actions;
        }
        if (!root.isJsonArray()) {
            return actions;
        }
        int index = 0;
        for (JsonElement element : root.getAsJsonArray()) {
            if (element instanceof JsonObject action) {
                int mode = action.get("mode") != null && action.get("mode").isJsonPrimitive() ? action.get("mode").getAsInt() : 0;
                if (mode == 0) {
                    String label = action.get("button_name") != null && action.get("button_name").isJsonPrimitive()
                            ? action.get("button_name").getAsString() : "";
                    if (label.isEmpty() && action.get("text") != null && action.get("text").isJsonPrimitive()) {
                        label = action.get("text").getAsString();
                    }
                    actions.add(new Action(index, label));
                }
            }
            index++;
        }
        return actions;
    }

    @Override
    NbtMap build(Key actionId) {
        Component title = this.npcName.isEmpty() ? Component.text("PNJ") : this.state.text(this.npcName);
        NbtMap exit = FormDialogs.exitButton(actionId);
        Dialogs.Builder dialog;
        if (this.actions.isEmpty()) {
            dialog = Dialogs.notice(title).noticeAction(exit);
        } else {
            dialog = Dialogs.multiAction(title).exitAction(exit);
            for (Action action : this.actions) {
                dialog.action(Dialogs.button(this.state.text(action.label()),
                        Dialogs.dynamicCustom(actionId, NbtMap.builder().putInt(FormDialogs.BUTTON_ID, action.index()).build())));
            }
        }
        if (!this.dialogue.isEmpty()) {
            dialog.body(Dialogs.plainMessage(this.state.text(this.dialogue), Dialogs.CONTENT_WIDTH));
        }
        return dialog.build();
    }

    @Override
    Outcome click(@Nullable NbtMap payload) {
        NbtMap data = payload == null ? NbtMap.EMPTY : payload;
        NpcRequestPacket request = new NpcRequestPacket();
        request.setNpcRuntimeID(this.npcId);
        request.setActions("");
        request.setSceneName(this.sceneName);
        if (data.getBoolean(FormDialogs.EXIT, false) || !(data.get(FormDialogs.BUTTON_ID) instanceof Number index)) {
            request.setRequestType(NpcRequestPacket.RequestType.EXECUTE_CLOSING_COMMANDS);
            request.setActionIndex(NO_ACTION);
        } else {
            request.setRequestType(NpcRequestPacket.RequestType.EXECUTE_ACTION);
            request.setActionIndex(index.intValue());
        }
        this.state.session().sendBedrock(request);
        return Outcome.DONE;
    }

    @Override
    public String toString() {
        return "NPC dialogue " + this.npcId + "/" + this.sceneName;
    }
}
