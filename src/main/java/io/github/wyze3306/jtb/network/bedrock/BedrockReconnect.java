package io.github.wyze3306.jtb.network.bedrock;

import io.github.wyze3306.jtb.config.ProxyConfig;
import io.github.wyze3306.jtb.network.java.JavaConfiguration;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.cloudburstmc.nbt.NbtList;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.protocol.bedrock.data.DisconnectFailReason;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundClearDialogPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundShowDialogConfigurationPacket;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The waiting room: when the Bedrock server shuts down, restarts or stops answering, the Java player
 * stays connected in the configuration screen with a dialog, and the proxy rejoins the server as soon
 * as it is back (a new Java login then rebuilds the world, as for a server transfer). Kicks, bans and
 * every other disconnect reason still disconnect the Java player.
 */
public final class BedrockReconnect implements SessionState {

    public static final StateKey<BedrockReconnect> KEY = StateKey.of("bedrock-reconnect", BedrockReconnect::new);

    /** The {@code minecraft:custom} action of the dialog's leave button. */
    public static final Key LEAVE_ACTION = Key.key("javatobedrock", "leave");

    private static final long RETRY_DELAY_SECONDS = 5;

    private final ProxySession session;
    private @Nullable InetSocketAddress target;
    private boolean waiting;
    private long deadlineMillis;
    private int attempts;
    private Component reason = Component.empty();
    private @Nullable ScheduledFuture<?> retry;

    private BedrockReconnect(ProxySession session) {
        this.session = session;
    }

    /** Remembers the server the session is connecting to: the one to rejoin. */
    void connecting(InetSocketAddress target) {
        this.target = target;
    }

    /**
     * Whether a Bedrock disconnect packet means the server is going down (so the player should wait)
     * rather than a kick: the {@code SHUTDOWN} reasons, or a message listed in
     * {@code bedrock.reconnect.shutdown-messages} (PowerNukkitX sends "Server closed").
     */
    public boolean isShutdown(DisconnectPacket packet) {
        DisconnectFailReason reason = packet.getReason();
        if (reason == DisconnectFailReason.SHUTDOWN || reason == DisconnectFailReason.INTERNAL_REQUEST_SERVER_SHUTDOWN) {
            return true;
        }
        String message = packet.getMessages() == null ? null : packet.getMessages().getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.startsWith("%") ? message.substring(1) : message;
        for (String shutdown : config().shutdownMessages()) {
            if (normalized.trim().equalsIgnoreCase(shutdown.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The Bedrock connection was lost (shutdown, crash, timeout) or could not be opened. Puts the
     * player in the waiting room and returns {@code true}, or returns {@code false} when the caller
     * must disconnect the Java player: waiting room disabled, wait time exhausted, Java side closing.
     */
    public boolean onConnectionLost(Component reason) {
        if (this.session.isClosed() || this.target == null || config().timeoutSeconds() <= 0) {
            return false;
        }
        if (!this.waiting) {
            this.waiting = true;
            this.attempts = 0;
            this.deadlineMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(config().timeoutSeconds());
            this.session.logger().info("[{}] Bedrock server {}:{} unavailable ({}): waiting up to {}s to rejoin",
                    this.session.getName(), this.target.getHostString(), this.target.getPort(),
                    PlainTextComponentSerializer.plainText().serialize(reason), config().timeoutSeconds());
        } else if (System.currentTimeMillis() >= this.deadlineMillis) {
            this.waiting = false;
            return false;
        }
        this.reason = reason;
        this.session.detachBedrock("Waiting for the server");
        JavaConfiguration configuration = this.session.get(JavaConfiguration.KEY);
        configuration.whenConfiguring(this::showDialog);
        cancelRetry();
        this.retry = this.session.schedule(this::attempt, RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
        return true;
    }

    /** True while the player waits for the server: the configuration must not time out. */
    public boolean isWaiting() {
        return this.waiting;
    }

    /** The Bedrock server started the game: the player is back in. */
    public void onBedrockReady() {
        if (this.waiting) {
            this.waiting = false;
            cancelRetry();
            this.session.sendJavaDirect(new ClientboundClearDialogPacket());
            this.session.logger().info("[{}] Rejoined the Bedrock server after {} attempt(s)", this.session.getName(), this.attempts);
        }
    }

    private void attempt() {
        if (!this.waiting || this.session.isClosed() || this.target == null) {
            return;
        }
        this.attempts++;
        showDialog();
        this.session.proxy().bedrockConnector().start(this.session, this.target);
    }

    private void showDialog() {
        if (!this.waiting || this.session.isClosed() || !this.session.get(JavaConfiguration.KEY).isConfiguring()) {
            return;
        }
        long remaining = Math.max(0, (this.deadlineMillis - System.currentTimeMillis()) / 1000);
        String detail = PlainTextComponentSerializer.plainText().serialize(this.reason);
        NbtMap body = NbtMap.builder()
                .putString("type", "minecraft:plain_message")
                .putString("contents", "Le serveur Bedrock redémarre ou ne répond plus"
                        + (detail.isEmpty() ? "" : " (" + detail + ")") + ".\n\n"
                        + "Vous serez reconnecté automatiquement dès son retour.\n\n"
                        + (this.attempts == 0 ? "" : "Tentative " + this.attempts + " · ")
                        + "attente maximale encore " + formatDuration(remaining) + ".")
                .putInt("width", 300)
                .build();
        NbtMap leave = NbtMap.builder()
                .putString("label", "Se déconnecter")
                .putCompound("action", NbtMap.builder()
                        .putString("type", "minecraft:custom")
                        .putString("id", LEAVE_ACTION.asString())
                        .build())
                .build();
        NbtMap dialog = NbtMap.builder()
                .putString("type", "minecraft:notice")
                .putString("title", "En attente du serveur Bedrock…")
                .putList("body", NbtType.COMPOUND, new NbtList<>(NbtType.COMPOUND, body))
                .putCompound("action", leave)
                .putBoolean("can_close_with_escape", true)
                .build();
        this.session.sendJavaDirect(new ClientboundShowDialogConfigurationPacket(dialog));
    }

    private static String formatDuration(long seconds) {
        return seconds >= 60 ? String.format(Locale.ROOT, "%d min %02d s", seconds / 60, seconds % 60) : seconds + " s";
    }

    private ProxyConfig.Reconnect config() {
        return this.session.config().bedrock().reconnect();
    }

    private void cancelRetry() {
        if (this.retry != null) {
            this.retry.cancel(false);
            this.retry = null;
        }
    }

    @Override
    public boolean survivesServerSwitch() {
        return true;
    }

    @Override
    public void close() {
        cancelRetry();
    }
}
