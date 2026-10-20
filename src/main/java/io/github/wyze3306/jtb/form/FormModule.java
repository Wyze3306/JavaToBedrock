package io.github.wyze3306.jtb.form;

import io.github.wyze3306.jtb.network.java.JavaConfiguration;
import io.github.wyze3306.jtb.translator.TranslatorModule;
import io.github.wyze3306.jtb.translator.TranslatorRegistry;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundCloseFormPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataDrivenUICloseAllScreensPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataDrivenUICloseScreenPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataDrivenUIReloadPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataDrivenUIShowScreenPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataStorePacket;
import org.cloudburstmc.protocol.bedrock.packet.ModalFormRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.NpcDialoguePacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerSettingsResponsePacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomClickActionPacket;

/**
 * Translators of the form subsystem (see docs/DEVELOPING.md): Bedrock forms, server settings, NPC
 * dialogues and data-driven screens become Java 26.3 dialogs ({@link FormState}); the Java client's
 * dialog clicks come back as {@code ServerboundCustomClickAction}. Also claims the data-driven UI
 * data store packets ({@code ClientboundDataStore}, {@code ClientboundDataDrivenUICloseAllScreens}),
 * which only exist for those screens, adds the server settings entry to the pause menu
 * ({@link ServerSettingsMenu}) and fixes the decoding of dialog clicks ({@link CustomClickActionCodec}).
 */
public final class FormModule implements TranslatorModule {

    @Override
    @SuppressWarnings("deprecation") // ClientboundDataDrivenUICloseAllScreens: still sent by older servers
    public void register(TranslatorRegistry registry) {
        CustomClickActionCodec.install();
        JavaConfiguration.addContributor("form:server-settings-menu", ServerSettingsMenu::contribute);

        registry.bedrock(ModalFormRequestPacket.class, (session, packet) ->
                session.get(FormState.KEY).showForm(packet.getFormID(), packet.getFormData() == null ? "" : packet.getFormData()));
        registry.bedrock(ServerSettingsResponsePacket.class, (session, packet) ->
                session.get(FormState.KEY).onServerSettings(packet.getFormID(), packet.getFormData() == null ? "" : packet.getFormData()));
        registry.bedrock(ClientboundCloseFormPacket.class, (session, packet) -> session.get(FormState.KEY).closeForms());
        registry.bedrock(NpcDialoguePacket.class, (session, packet) -> {
            FormState forms = session.get(FormState.KEY);
            if (packet.getActionType() == NpcDialoguePacket.Action.CLOSE) {
                forms.closeNpcDialogue(packet.getNpcId(), packet.getSceneName());
            } else {
                forms.showNpcDialogue(packet);
            }
        });
        registry.bedrock(ClientboundDataStorePacket.class, (session, packet) -> session.get(FormState.KEY).onDataStore(packet));
        registry.bedrock(ClientboundDataDrivenUIShowScreenPacket.class, (session, packet) ->
                session.get(FormState.KEY).showScreen(packet.getScreenId() == null ? "" : packet.getScreenId(),
                        packet.getFormId(), packet.getDataInstanceId()));
        registry.bedrock(ClientboundDataDrivenUICloseScreenPacket.class, (session, packet) ->
                session.get(FormState.KEY).closeScreen(packet.getFormId()));
        registry.bedrock(ClientboundDataDrivenUICloseAllScreensPacket.class, (session, packet) ->
                session.get(FormState.KEY).closeScreen(null));
        registry.bedrock(ClientboundDataDrivenUIReloadPacket.class, (session, packet) -> session.get(FormState.KEY).reloadScreens());

        registry.java(ServerboundCustomClickActionPacket.class, (session, packet) ->
                session.get(FormState.KEY).onCustomClick(packet.getId(), packet.getPayload()));
    }
}
