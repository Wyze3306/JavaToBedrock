package io.github.wyze3306.jtb.e2e.scenario;

import io.github.wyze3306.jtb.e2e.ClientContext;
import io.github.wyze3306.jtb.e2e.E2eScenario;

/**
 * The waiting room: the harness stops the Bedrock server while the player is in game and starts it
 * again; the player must stay connected, see the waiting dialog and join the world again.
 */
public final class ReconnectScenario implements E2eScenario {

    @Override
    public void finish(ClientContext context) {
        context.check("sent back to configuration", context.count("ClientboundStartConfigurationPacket") >= 1);
        context.check("waiting dialog shown", context.count("ClientboundShowDialogConfigurationPacket") >= 1);
        context.check("dialog cleared on rejoin", context.count("ClientboundClearDialogPacket") >= 1);
        context.check("joined the world again", context.count("ClientboundLoginPacket") >= 2);
    }
}
