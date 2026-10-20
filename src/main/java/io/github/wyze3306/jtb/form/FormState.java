package io.github.wyze3306.jtb.form;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import io.github.wyze3306.jtb.ui.RawText;
import io.netty.util.concurrent.ScheduledFuture;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.ModalFormCancelReason;
import org.cloudburstmc.protocol.bedrock.data.ddui.DataDrivenScreenClosedReason;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundDataStorePacket;
import org.cloudburstmc.protocol.bedrock.packet.ModalFormResponsePacket;
import org.cloudburstmc.protocol.bedrock.packet.NpcDialoguePacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerSettingsRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerboundDataDrivenScreenClosedPacket;
import org.geysermc.mcprotocollib.protocol.data.game.Holder;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundClearDialogPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundShowDialogGamePacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Everything the Java player sees as a dialog on behalf of the Bedrock server: forms
 * ({@code ModalFormRequest}), the server settings form, NPC dialogues and data-driven screens.
 * <p>
 * Like the Bedrock client, one dialog is shown at a time and forms that arrive meanwhile wait in a
 * queue (beyond {@value #MAX_QUEUED} pending forms the new one is refused with {@code USER_BUSY}).
 * Every display gets its own action id ({@code jtb:form/<n>}), so a click on a dialog that has been
 * replaced in the meantime is ignored. Form responses are paced to at most
 * {@value #RESPONSES_PER_SECOND} per second (PowerNukkitX disconnects above 20).
 * <p>
 * Other subsystems may claim their own {@code ServerboundCustomClickAction} ids with
 * {@link #registerClickHandler} (e.g. {@code custom} click events in chat). The chat subsystem should
 * expose {@link #openServerSettings()} as a command (e.g. {@code /jtb settings}); the pause menu has a
 * button for it too ({@link ServerSettingsMenu}).
 */
public final class FormState implements SessionState {

    public static final StateKey<FormState> KEY = StateKey.of("form", FormState::new);

    /** Namespace of the proxy's dialog action ids. */
    public static final String NAMESPACE = "jtb";
    private static final String ACTION_PREFIX = "form/";
    static final int MAX_QUEUED = 8;
    static final int RESPONSES_PER_SECOND = 10;
    private static final long SETTINGS_WAIT_MILLIS = 3000;

    private final ProxySession session;
    private final Function<String, Component> text;
    private final ArrayDeque<ShownDialog> queue = new ArrayDeque<>();
    private final Map<Key, BiConsumer<ProxySession, @Nullable NbtMap>> clickHandlers = new HashMap<>();
    private final DataStore dataStore = new DataStore();
    private @Nullable ShownDialog current;
    private int nextDisplayId;

    private final ArrayDeque<BedrockPacket> pendingResponses = new ArrayDeque<>();
    private final long[] responseTimes = new long[RESPONSES_PER_SECOND];
    private int responseIndex;

    private long settingsRequestedAt = -1;
    private int settingsShown;
    private @Nullable ScheduledFuture<?> settingsTimeout;
    private boolean screenDirty;

    private FormState(ProxySession session) {
        this.session = session;
        this.text = RawText.translator(session);
    }

    ProxySession session() {
        return this.session;
    }

    /** Renders a Bedrock text (formatting codes, translations, rawtext JSON) in the player's language. */
    Component text(String bedrockText) {
        return this.text.apply(bedrockText);
    }

    Function<String, Component> textFunction() {
        return this.text;
    }

    DataStore dataStore() {
        return this.dataStore;
    }

    // ------------------------------------------------------------------ forms

    /** {@code ModalFormRequest}: shows the form, or queues it behind the current dialog. */
    public void showForm(int formId, String json) {
        BedrockForm form;
        try {
            form = BedrockForm.parse(json);
        } catch (IllegalArgumentException e) {
            // The Bedrock client shows an error instead; answer so the server does not keep it open.
            this.session.logger().warn("[{}] Invalid form {} from the server ({}): {}", this.session.getName(), formId,
                    e.getMessage(), json.length() > 300 ? json.substring(0, 300) + "..." : json);
            sendFormResponse(formId, null, ModalFormCancelReason.USER_CLOSED);
            return;
        }
        if (this.current instanceof FormDialog shown && shown.formId == formId && !shown.settings) {
            shown.form = form;
            refresh();
            return;
        }
        if (this.queue.size() >= MAX_QUEUED) {
            sendFormResponse(formId, null, ModalFormCancelReason.USER_BUSY);
            return;
        }
        show(new FormDialog(this, formId, form, false));
    }

    /**
     * Replaces the content of a shown or queued form ({@code ServerSettingsResponse} with the id of an
     * open form is how PowerNukkitX updates a form in place).
     *
     * @return false when no such form is open
     */
    boolean updateForm(int formId, String json) {
        FormDialog target = null;
        if (this.current instanceof FormDialog shown && shown.formId == formId) {
            target = shown;
        } else {
            for (ShownDialog dialog : this.queue) {
                if (dialog instanceof FormDialog queued && queued.formId == formId) {
                    target = queued;
                    break;
                }
            }
        }
        if (target == null) {
            return false;
        }
        try {
            target.form = BedrockForm.parse(json);
        } catch (IllegalArgumentException e) {
            this.session.logger().debug("[{}] Ignoring invalid form update {}: {}", this.session.getName(), formId, e.getMessage());
            return true;
        }
        if (target == this.current) {
            refresh();
        }
        return true;
    }

    /** {@code ClientboundCloseForm}: the server closed every form (no response is expected). */
    public void closeForms() {
        this.queue.removeIf(dialog -> dialog instanceof FormDialog);
        if (this.current instanceof FormDialog) {
            dismissCurrent();
        }
    }

    /** Sends a form answer, paced to stay under the server's rate limit. */
    void sendFormResponse(int formId, @Nullable String json, @Nullable ModalFormCancelReason cancel) {
        ModalFormResponsePacket packet = new ModalFormResponsePacket();
        packet.setFormID(formId);
        packet.setJsonResponse(json);
        packet.setFormCancelReason(Optional.ofNullable(cancel));
        if (this.pendingResponses.isEmpty() && tryAcquireResponse()) {
            this.session.sendBedrock(packet);
        } else {
            this.pendingResponses.add(packet);
        }
    }

    private boolean tryAcquireResponse() {
        long now = System.nanoTime();
        long oldest = this.responseTimes[this.responseIndex];
        if (oldest != 0 && now - oldest < TimeUnit.SECONDS.toNanos(1)) {
            return false;
        }
        this.responseTimes[this.responseIndex] = now;
        this.responseIndex = (this.responseIndex + 1) % this.responseTimes.length;
        return true;
    }

    // ------------------------------------------------------------------ server settings

    /**
     * Asks the server for its settings forms ({@code ServerSettingsRequest}), like the Bedrock client
     * does when its settings screen opens, and shows them. When the server has none, a short message
     * says so.
     */
    public void openServerSettings() {
        if (this.settingsTimeout != null) {
            this.settingsTimeout.cancel(false);
        }
        this.settingsRequestedAt = System.currentTimeMillis();
        this.settingsShown = 0;
        this.session.sendBedrock(new ServerSettingsRequestPacket());
        this.settingsTimeout = this.session.schedule(() -> {
            this.settingsTimeout = null;
            this.settingsRequestedAt = -1;
            if (this.settingsShown == 0) {
                show(new MessageDialog(Component.text("Paramètres du serveur"),
                        Component.text("Ce serveur Bedrock ne propose pas de paramètres.")));
            }
        }, SETTINGS_WAIT_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** {@code ServerSettingsResponse}: a settings form after a request, or an in-place form update. */
    public void onServerSettings(int formId, String json) {
        if (updateForm(formId, json)) {
            return;
        }
        if (this.settingsRequestedAt < 0 || System.currentTimeMillis() - this.settingsRequestedAt > SETTINGS_WAIT_MILLIS) {
            this.session.logger().debug("[{}] Unrequested server settings form {}", this.session.getName(), formId);
            return;
        }
        BedrockForm form;
        try {
            form = BedrockForm.parse(json);
        } catch (IllegalArgumentException e) {
            this.session.logger().warn("[{}] Invalid server settings form {}: {}", this.session.getName(), formId, e.getMessage());
            return;
        }
        this.settingsShown++;
        show(new FormDialog(this, formId, form, true));
    }

    // ------------------------------------------------------------------ NPC dialogues

    /** {@code NpcDialogue OPEN}: replaces a shown dialogue of the same NPC (scrolling text re-sends it). */
    public void showNpcDialogue(NpcDialoguePacket packet) {
        NpcDialog dialog = new NpcDialog(this, packet.getNpcId(), nullToEmpty(packet.getSceneName()),
                nullToEmpty(packet.getNpcName()), nullToEmpty(packet.getDialogue()), nullToEmpty(packet.getActionJson()));
        if (this.current instanceof NpcDialog shown && shown.npcId == packet.getNpcId()) {
            shown.replaceWith(dialog);
            refresh();
            return;
        }
        this.queue.removeIf(queued -> queued instanceof NpcDialog npc && npc.npcId == packet.getNpcId());
        show(dialog);
    }

    /** {@code NpcDialogue CLOSE}. */
    public void closeNpcDialogue(long npcId, @Nullable String sceneName) {
        this.queue.removeIf(dialog -> dialog instanceof NpcDialog npc && npc.matches(npcId, sceneName));
        if (this.current instanceof NpcDialog npc && npc.matches(npcId, sceneName)) {
            dismissCurrent();
        }
    }

    // ------------------------------------------------------------------ data-driven screens

    /** {@code ClientboundDataStore}: keeps the screen data; a shown screen is redrawn on the next tick. */
    public void onDataStore(ClientboundDataStorePacket packet) {
        boolean changed = false;
        for (var change : packet.getUpdates()) {
            changed |= this.dataStore.apply(change);
        }
        if (changed && this.current instanceof DduiDialog) {
            this.screenDirty = true;
        }
    }

    /** {@code ClientboundDataDrivenUIShowScreen}. */
    public void showScreen(String screenId, int formId, @Nullable Integer dataInstanceId) {
        String store = DduiDialog.storeName(screenId);
        String property = this.dataStore.findProperty(store, dataInstanceId != null ? dataInstanceId : formId);
        if (property == null) {
            this.session.logger().debug("[{}] Data-driven screen {} ({}) has no data", this.session.getName(), screenId, formId);
            closedScreen(formId, DataDrivenScreenClosedReason.INVALID_FORM);
            return;
        }
        this.queue.removeIf(dialog -> dialog instanceof DduiDialog screen && screen.formId == formId);
        if (this.current instanceof DduiDialog shown) {
            // A new screen replaces the open one, as on Bedrock.
            if (shown.formId != formId) {
                closedScreen(shown.formId, DataDrivenScreenClosedReason.PROGRAMMATIC_CLOSE);
            }
            this.current = null;
            display(new DduiDialog(this, screenId, formId, store, property));
            return;
        }
        show(new DduiDialog(this, screenId, formId, store, property));
    }

    /** {@code ClientboundDataDrivenUICloseScreen} ({@code formId} null: all screens). */
    public void closeScreen(@Nullable Integer formId) {
        this.queue.removeIf(dialog -> dialog instanceof DduiDialog screen && (formId == null || screen.formId == formId));
        if (this.current instanceof DduiDialog screen && (formId == null || screen.formId == formId)) {
            dismissCurrent();
        }
    }

    /** {@code ClientboundDataDrivenUIReload}: redraws the shown screen. */
    public void reloadScreens() {
        if (this.current instanceof DduiDialog) {
            refresh();
        }
    }

    void closedScreen(int formId, DataDrivenScreenClosedReason reason) {
        ServerboundDataDrivenScreenClosedPacket packet = new ServerboundDataDrivenScreenClosedPacket();
        packet.setFormId(formId);
        packet.setCloseReason(reason);
        this.session.sendBedrock(packet);
    }

    // ------------------------------------------------------------------ clicks

    /**
     * Lets another subsystem handle {@code ServerboundCustomClickAction} packets with this id
     * (session loop). Ids in the {@value #NAMESPACE} namespace starting with {@code form/} are reserved.
     */
    public void registerClickHandler(Key id, BiConsumer<ProxySession, @Nullable NbtMap> handler) {
        this.clickHandlers.put(id, handler);
    }

    /** {@code ServerboundCustomClickAction} from the Java client. */
    public void onCustomClick(Key id, @Nullable NbtMap payload) {
        if (id.equals(ServerSettingsMenu.ACTION)) {
            openServerSettings();
            return;
        }
        BiConsumer<ProxySession, @Nullable NbtMap> handler = this.clickHandlers.get(id);
        if (handler != null) {
            handler.accept(this.session, payload);
            return;
        }
        if (!id.namespace().equals(NAMESPACE) || !id.value().startsWith(ACTION_PREFIX)) {
            this.session.logger().debug("[{}] Unknown dialog action {}", this.session.getName(), id);
            return;
        }
        int displayId;
        try {
            displayId = Integer.parseInt(id.value().substring(ACTION_PREFIX.length()));
        } catch (NumberFormatException e) {
            return;
        }
        ShownDialog dialog = this.current;
        if (dialog == null || dialog.displayId != displayId) {
            this.session.logger().debug("[{}] Click on a dialog that is no longer shown ({})", this.session.getName(), id);
            return;
        }
        switch (dialog.click(payload)) {
            case DONE -> {
                if (!dialog.closesItself()) {
                    this.session.sendJava(new ClientboundClearDialogPacket());
                }
                this.current = null;
                showNext();
            }
            case REOPEN -> refresh();
            case KEEP -> {
            }
        }
    }

    // ------------------------------------------------------------------ display

    /** Shows a dialog now if none is shown, else queues it. */
    void show(ShownDialog dialog) {
        if (this.current == null) {
            display(dialog);
        } else {
            this.queue.add(dialog);
        }
    }

    private void display(ShownDialog dialog) {
        this.current = dialog;
        dialog.displayId = ++this.nextDisplayId;
        send(dialog);
    }

    /** Sends the shown dialog again (new content, or after a click that must not close it). */
    private void refresh() {
        ShownDialog dialog = this.current;
        if (dialog != null) {
            send(dialog);
        }
    }

    private void send(ShownDialog dialog) {
        NbtMap definition;
        try {
            definition = dialog.build(Key.key(NAMESPACE, ACTION_PREFIX + dialog.displayId));
        } catch (RuntimeException e) {
            this.session.logger().warn("[{}] Could not build a dialog for {}", this.session.getName(), dialog, e);
            dialog.failed();
            if (this.current == dialog) {
                this.current = null;
                showNext();
            }
            return;
        }
        this.session.sendJava(new ClientboundShowDialogGamePacket(Holder.ofCustom(definition)));
    }

    /** Closes the shown dialog on the client without answering it, then shows the next one. */
    private void dismissCurrent() {
        this.current = null;
        this.session.sendJava(new ClientboundClearDialogPacket());
        showNext();
    }

    private void showNext() {
        ShownDialog next = this.queue.poll();
        if (next != null) {
            display(next);
        }
    }

    /** The dialog currently shown, for tests and diagnostics. */
    @Nullable ShownDialog current() {
        return this.current;
    }

    int queued() {
        return this.queue.size();
    }

    @Override
    public void tick() {
        while (!this.pendingResponses.isEmpty() && tryAcquireResponse()) {
            this.session.sendBedrock(this.pendingResponses.poll());
        }
        if (this.screenDirty) {
            this.screenDirty = false;
            if (this.current instanceof DduiDialog) {
                refresh();
            }
        }
    }

    @Override
    public void close() {
        if (this.settingsTimeout != null) {
            this.settingsTimeout.cancel(false);
        }
        this.queue.clear();
        this.current = null;
    }

    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }
}
