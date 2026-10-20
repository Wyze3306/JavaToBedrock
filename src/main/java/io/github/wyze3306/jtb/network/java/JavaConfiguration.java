package io.github.wyze3306.jtb.network.java;

import io.github.wyze3306.jtb.JavaToBedrock;
import io.github.wyze3306.jtb.mapping.JavaRegistries;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import io.netty.util.concurrent.ScheduledFuture;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.KnownPack;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;
import org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundUpdateTagsPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundSelectKnownPacks;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundUpdateEnabledFeaturesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundStartConfigurationPacket;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Holds the Java client in the configuration phase until everything it needs is ready, then lets it
 * into the game. The phase ends when all of these are true:
 * <ol>
 *     <li>the client answered the known packs negotiation;</li>
 *     <li>the Bedrock side is ready ({@link #markBedrockReady()}: StartGame processed, the Java login
 *     packet queued, server-defined registry entries such as custom biomes added);</li>
 *     <li>no resource pack conversion is still running ({@link #holdForResourcePacks()} /
 *     {@link #releaseResourcePacks()}) and every pushed pack reached a final status.</li>
 * </ol>
 * Registries are sent at the very end so subsystems can still contribute entries until then.
 */
public final class JavaConfiguration implements SessionState {

    public static final StateKey<JavaConfiguration> KEY = StateKey.of("java-configuration", JavaConfiguration::new);

    /** If the Bedrock server never says it is ready, let the player in anyway after this delay. */
    private static final long BEDROCK_READY_TIMEOUT_SECONDS = 60;

    private final ProxySession session;
    private final Map<Key, List<RegistryEntry>> extraEntries = new LinkedHashMap<>();
    private final Set<UUID> pendingPacks = new HashSet<>();
    private final Map<UUID, Boolean> requiredPacks = new HashMap<>();
    private final List<Runnable> gameStartListeners = new ArrayList<>();
    private @Nullable ServerboundClientInformationPacket clientInformation;
    private boolean knownPacksAnswered;
    private boolean clientKnowsVanilla;
    private boolean bedrockReady;
    private int resourcePackHolds;
    private boolean finishSent;
    private boolean reconfiguring;
    private boolean reconfigurationAcknowledged;
    private @Nullable ScheduledFuture<?> readyTimeout;

    private JavaConfiguration(ProxySession session) {
        this.session = session;
    }

    // ------------------------------------------------------------------ start

    void begin() {
        this.session.sendJavaDirect(new ClientboundCustomPayloadPacket(Key.key("minecraft:brand"),
                encodeString(JavaToBedrock.NAME)));
        this.session.sendJavaDirect(new ClientboundSelectKnownPacks(List.of(
                new KnownPack("minecraft", "core", JavaServer.javaVersionName()))));
        this.readyTimeout = this.session.schedule(() -> {
            if (!this.bedrockReady) {
                this.session.logger().warn("[{}] Bedrock server did not finish the join sequence in {}s, continuing anyway",
                        this.session.getName(), BEDROCK_READY_TIMEOUT_SECONDS);
                markBedrockReady();
            }
        }, BEDROCK_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    void onKnownPacks(List<KnownPack> packs) {
        this.knownPacksAnswered = true;
        this.clientKnowsVanilla = false;
        for (KnownPack pack : packs) {
            if ("minecraft".equals(pack.getNamespace()) && "core".equals(pack.getId())
                    && JavaServer.javaVersionName().equals(pack.getVersion())) {
                this.clientKnowsVanilla = true;
            }
        }
        tryFinish();
    }

    void onClientInformation(ServerboundClientInformationPacket information) {
        this.clientInformation = information;
        this.session.setLocale(information.getLocale());
    }

    /** The client settings sent during configuration (locale, view distance, skin parts...). */
    public @Nullable ServerboundClientInformationPacket clientInformation() {
        return this.clientInformation;
    }

    // ------------------------------------------------------------------ registries

    /**
     * Appends entries (with full data) to a synchronised registry, after the vanilla ones. Their ids
     * are {@code vanillaSize + index}. Must be called before {@link #markBedrockReady()}.
     *
     * @return the id of the first added entry
     */
    public int addRegistryEntries(Key registry, List<RegistryEntry> entries) {
        if (this.finishSent) {
            throw new IllegalStateException("Registries already sent");
        }
        List<RegistryEntry> list = this.extraEntries.computeIfAbsent(registry, k -> new ArrayList<>());
        int base = JavaRegistries.registries().get(registry).entries().size() + list.size();
        list.addAll(entries);
        return base;
    }

    private void sendRegistries() {
        for (JavaRegistries.Registry registry : JavaRegistries.registries().values()) {
            List<RegistryEntry> entries = JavaRegistries.entriesForClient(registry, this.clientKnowsVanilla);
            List<RegistryEntry> extra = this.extraEntries.get(registry.key());
            if (extra != null) {
                entries.addAll(extra);
            }
            this.session.sendJavaDirect(new ClientboundRegistryDataPacket(registry.key(), entries));
        }
        this.session.sendJavaDirect(new ClientboundUpdateTagsPacket(JavaRegistries.tags()));
        this.session.sendJavaDirect(new ClientboundUpdateEnabledFeaturesPacket(new Key[]{Key.key("minecraft:vanilla")}));
    }

    // ------------------------------------------------------------------ resource packs

    /**
     * Prevents the end of the configuration phase while a resource pack is being prepared. Each call
     * must be matched by {@link #releaseResourcePacks()}.
     */
    public void holdForResourcePacks() {
        this.resourcePackHolds++;
    }

    public void releaseResourcePacks() {
        if (this.resourcePackHolds > 0) {
            this.resourcePackHolds--;
        }
        tryFinish();
    }

    /**
     * Sends a resource pack to the client; the configuration phase waits until the client reports a
     * final status for it.
     */
    public void pushResourcePack(UUID id, String url, String sha1, boolean required, @Nullable Component prompt) {
        this.pendingPacks.add(id);
        this.requiredPacks.put(id, required);
        this.session.sendJavaDirect(new ClientboundResourcePackPushPacket(id, url, sha1, required, prompt));
    }

    void onResourcePackStatus(UUID id, ResourcePackStatus status) {
        switch (status) {
            case ACCEPTED, DOWNLOADED -> {
                return;
            }
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> {
                if (Boolean.TRUE.equals(this.requiredPacks.get(id)) && status == ResourcePackStatus.DECLINED) {
                    this.session.disconnect(Component.translatable("multiplayer.requiredTexturePrompt.disconnect"));
                    return;
                }
                this.session.logger().warn("[{}] Resource pack {} not applied: {}", this.session.getName(), id, status);
            }
            case SUCCESSFULLY_LOADED -> this.session.logger().debug("[{}] Resource pack {} loaded", this.session.getName(), id);
        }
        this.pendingPacks.remove(id);
        tryFinish();
    }

    // ------------------------------------------------------------------ bedrock readiness

    /**
     * Called by the join translators once the Bedrock server sent what the Java login packet and
     * the registries depend on (StartGame, biome definitions...). Idempotent.
     */
    public void markBedrockReady() {
        if (this.bedrockReady) {
            return;
        }
        this.bedrockReady = true;
        if (this.readyTimeout != null) {
            this.readyTimeout.cancel(false);
        }
        tryFinish();
    }

    public boolean isBedrockReady() {
        return this.bedrockReady;
    }

    /**
     * Registers a task run on the event loop right after the client entered the game (and queued play
     * packets were flushed).
     */
    public void onGameStart(Runnable listener) {
        this.gameStartListeners.add(listener);
    }

    private void tryFinish() {
        if (this.finishSent || !this.knownPacksAnswered || !this.bedrockReady
                || this.resourcePackHolds > 0 || !this.pendingPacks.isEmpty()
                || (this.reconfiguring && !this.reconfigurationAcknowledged)) {
            return;
        }
        this.finishSent = true;
        if (!this.reconfiguring) {
            sendRegistries();
        }
        this.session.sendJavaDirect(new ClientboundFinishConfigurationPacket());
    }

    void onClientFinished() {
        this.reconfiguring = false;
        this.session.enterGame();
        List<Runnable> listeners = List.copyOf(this.gameStartListeners);
        this.gameStartListeners.clear();
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    // ------------------------------------------------------------------ reconfiguration

    /**
     * Sends the client back to the configuration phase (e.g. to apply a new resource pack after a
     * server transfer). Play packets are queued meanwhile; registries are not resent.
     */
    public void startReconfiguration() {
        if (this.session.javaPhase() != ProxySession.JavaPhase.GAME) {
            return;
        }
        this.reconfiguring = true;
        this.reconfigurationAcknowledged = false;
        this.finishSent = false;
        this.session.enterConfiguration();
        MinecraftProtocol protocol = (MinecraftProtocol) this.session.javaSession().getPacketProtocol();
        // The outbound state must change right after this packet is written, before anything else.
        this.session.javaSession().send(new ClientboundStartConfigurationPacket(),
                () -> protocol.setOutboundState(ProtocolState.CONFIGURATION));
    }

    /**
     * True while the client is (re)configuring: callers may push resource packs with
     * {@link #pushResourcePack}.
     */
    public boolean isConfiguring() {
        return this.session.javaPhase() == ProxySession.JavaPhase.CONFIGURATION;
    }

    void onReconfigurationAcknowledged() {
        this.reconfigurationAcknowledged = true;
        tryFinish();
    }

    private static byte[] encodeString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 5);
        int length = bytes.length;
        while ((length & ~0x7F) != 0) {
            out.write((length & 0x7F) | 0x80);
            length >>>= 7;
        }
        out.write(length);
        out.writeBytes(bytes);
        return out.toByteArray();
    }

    @Override
    public void close() {
        if (this.readyTimeout != null) {
            this.readyTimeout.cancel(false);
        }
    }
}
