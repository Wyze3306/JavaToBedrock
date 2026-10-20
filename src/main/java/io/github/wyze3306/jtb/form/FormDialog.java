package io.github.wyze3306.jtb.form;

import net.kyori.adventure.key.Key;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.ModalFormCancelReason;
import org.jspecify.annotations.Nullable;

/**
 * A Bedrock form ({@code ModalFormRequest}, or a server settings form) shown as a dialog; answered
 * with {@code ModalFormResponse} under the form's id.
 */
final class FormDialog extends ShownDialog {

    private final FormState state;
    final int formId;
    final boolean settings;
    BedrockForm form;

    FormDialog(FormState state, int formId, BedrockForm form, boolean settings) {
        this.state = state;
        this.formId = formId;
        this.form = form;
        this.settings = settings;
    }

    @Override
    NbtMap build(Key actionId) {
        return FormDialogs.build(this.form, actionId, this.state.textFunction());
    }

    @Override
    Outcome click(@Nullable NbtMap payload) {
        FormResponses.Result result = FormResponses.respond(this.form, payload);
        if (result.reopen()) {
            return Outcome.REOPEN;
        }
        this.state.sendFormResponse(this.formId, result.json(), result.cancel());
        return Outcome.DONE;
    }

    @Override
    void failed() {
        this.state.sendFormResponse(this.formId, null, ModalFormCancelReason.USER_CLOSED);
    }

    @Override
    public String toString() {
        return (this.settings ? "server settings form " : "form ") + this.formId;
    }
}
