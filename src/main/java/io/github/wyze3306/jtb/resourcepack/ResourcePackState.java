package io.github.wyze3306.jtb.resourcepack;

import io.github.wyze3306.jtb.inventory.InventoryState;
import io.github.wyze3306.jtb.item.ItemRegistry;
import io.github.wyze3306.jtb.network.java.JavaConfiguration;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import io.github.wyze3306.jtb.sound.SoundState;
import io.github.wyze3306.jtb.text.BedrockLang;
import io.github.wyze3306.jtb.text.BedrockText;
import io.netty.util.concurrent.ScheduledFuture;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPopPacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The converted pack of this session: drives its conversion, pushes it to the Java client and tells
 * translators what it provides.
 * <p>
 * Sequence: at {@code ResourcePackStack} ({@link #onStack}) the configuration phase is held and the
 * stack-only conversion starts; the block subsystem hands over its custom block carrier states
 * while handling {@code StartGame} ({@link #setCustomBlockCarriers}); once the server's item
 * registry is loaded ({@link #onItemRegistry}) the conversion is completed with those definitions,
 * the pack is pushed and the hold released. The client then reports a status
 * ({@link #onClientStatus}); only {@code SUCCESSFULLY_LOADED} makes {@link #isLoaded()} true.
 * <p>
 * Session event loop only.
 */
public final class ResourcePackState implements SessionState {

    public static final StateKey<ResourcePackState> KEY = StateKey.of("resource-pack", ResourcePackState::new);

    /** Servers that never send an item registry still get their pack after this delay. */
    private static final long DEFINITIONS_TIMEOUT_SECONDS = 15;

    private final ProxySession session;
    private JavaPackService.@Nullable StackHandle stack;
    private List<CustomBlockCarrier> carriers = List.of();
    private boolean definitionsReady;
    /** The pack was completed without the item registry (it came too late): redo it when it comes. */
    private boolean completedWithoutRegistry;
    private boolean completing;
    private boolean holding;
    private @Nullable ScheduledFuture<?> definitionsTimeout;
    private @Nullable PackConversion conversion;
    private @Nullable UUID pushed;
    private boolean loaded;

    private ResourcePackState(ProxySession session) {
        this.session = session;
    }

    // ------------------------------------------------------------------ inputs

    /**
     * The server's pack stack is known (top first): starts converting and holds the Java
     * configuration phase until the pack is pushed. Called by {@link BedrockPackDownloader}.
     */
    void onStack(List<BedrockPack> packs) {
        if (packs.isEmpty() || !this.session.config().resourcePacks().enabled()) {
            return;
        }
        this.stack = this.session.proxy().service(JavaPackService.class).prepareStack(packs);
        this.completing = false;
        if (!this.holding) {
            this.holding = true;
            this.session.get(JavaConfiguration.KEY).holdForResourcePacks();
        }
        if (!this.definitionsReady && this.definitionsTimeout == null) {
            this.definitionsTimeout = this.session.schedule(() -> {
                if (!this.definitionsReady) {
                    this.session.logger().warn("[{}] No item registry from the Bedrock server after {}s: converting the "
                            + "resource pack without custom items", this.session.getName(), DEFINITIONS_TIMEOUT_SECONDS);
                    this.definitionsReady = true;
                    this.completedWithoutRegistry = true;
                    tryComplete();
                }
            }, DEFINITIONS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        tryComplete();
    }

    /**
     * Custom block states of the server with the Java carrier states allocated to them. To be called
     * by the block subsystem while it handles {@code StartGame} (before the item registry arrives),
     * so that the converted pack contains their models.
     */
    public void setCustomBlockCarriers(List<CustomBlockCarrier> carriers) {
        this.carriers = List.copyOf(carriers);
    }

    /**
     * The server's item registry was loaded into {@link ItemRegistry}: the conversion can complete.
     * When it arrives after the pack was already sent without it, the pack is converted again and
     * replaces the first one.
     */
    public void onItemRegistry() {
        this.definitionsReady = true;
        if (this.definitionsTimeout != null) {
            this.definitionsTimeout.cancel(false);
            this.definitionsTimeout = null;
        }
        if (this.completedWithoutRegistry) {
            this.completedWithoutRegistry = false;
            this.completing = false;
        }
        tryComplete();
    }

    /** A status the Java client reported for a pushed pack. */
    public void onClientStatus(UUID id, ResourcePackStatus status) {
        if (!id.equals(this.pushed)) {
            return;
        }
        switch (status) {
            case SUCCESSFULLY_LOADED -> {
                this.loaded = true;
                registerSounds();
                // Stacks translated before the pack was loaded lack their models: translate them again.
                this.session.get(InventoryState.KEY).retranslate();
                this.session.logger().info("[{}] The Java client loaded the converted resource pack", this.session.getName());
            }
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> this.loaded = false;
            default -> {
                // ACCEPTED, DOWNLOADED: not final.
            }
        }
    }

    // ------------------------------------------------------------------ conversion

    private void tryComplete() {
        JavaPackService.StackHandle stack = this.stack;
        if (stack == null || !this.definitionsReady || this.completing) {
            return;
        }
        this.completing = true;
        ServerDefinitions definitions = new ServerDefinitions(customItems(), this.carriers);
        this.session.proxy().service(JavaPackService.class).complete(stack, definitions)
                .whenComplete((conversion, error) -> this.session.execute(() -> {
                    if (stack != this.stack) {
                        // A newer stack replaced this one: its own completion releases the hold.
                        return;
                    }
                    try {
                        if (error != null) {
                            this.session.logger().error("[{}] Resource pack conversion failed", this.session.getName(), error);
                        } else {
                            apply(conversion);
                        }
                    } finally {
                        release();
                    }
                }));
    }

    private List<ServerDefinitions.CustomItem> customItems() {
        List<ServerDefinitions.CustomItem> items = new ArrayList<>();
        for (Map.Entry<String, ItemDefinition> entry : this.session.get(ItemRegistry.KEY).definitions().entrySet()) {
            ItemDefinition definition = entry.getValue();
            String identifier = entry.getKey();
            if (definition.getComponentData() != null && !identifier.toLowerCase(Locale.ROOT).startsWith("minecraft:")) {
                items.add(new ServerDefinitions.CustomItem(identifier, definition.getComponentData()));
            }
        }
        return items;
    }

    private void release() {
        if (this.holding) {
            this.holding = false;
            this.session.get(JavaConfiguration.KEY).releaseResourcePacks();
        }
    }

    /** Publishes the translations to {@link BedrockLang} and pushes the pack to the Java client. */
    private void apply(PackConversion conversion) {
        for (Map.Entry<String, Map<String, String>> language : conversion.artifacts().languages().entrySet()) {
            BedrockLang.forCode(language.getKey()).addPackEntries(language.getValue());
        }
        UUID id = conversion.pack().id();
        if (id.equals(this.pushed)) {
            this.conversion = conversion;
            return;
        }
        if (this.pushed != null) {
            this.session.sendJavaDirect(new ClientboundResourcePackPopPacket(this.pushed));
        }
        this.conversion = conversion;
        this.pushed = id;
        this.loaded = false;
        var config = this.session.config().resourcePacks();
        String url = this.session.proxy().service(JavaPackService.class).url(this.session, conversion.pack());
        this.session.logger().info("[{}] Sending the converted resource pack {} ({} KiB)", this.session.getName(),
                conversion.pack().sha1(), conversion.pack().zip().length / 1024);
        this.session.get(JavaConfiguration.KEY).pushResourcePack(id, url, conversion.pack().sha1(),
                config.required(), BedrockText.legacyToComponent(config.prompt()));
    }

    /** The pack's sounds can play now: tell the sound subsystem their categories and distances. */
    private void registerSounds() {
        if (this.conversion == null) {
            return;
        }
        ConversionArtifacts artifacts = this.conversion.artifacts();
        SoundState sounds = this.session.get(SoundState.KEY);
        for (String name : artifacts.soundEvents().keySet()) {
            ConversionArtifacts.SoundProperties properties = artifacts.soundProperties(name);
            sounds.registerPackSound(name, properties == null ? null : properties.category(),
                    properties == null ? 0 : properties.maxDistance(), properties == null ? 0 : properties.minDistance());
        }
    }

    // ------------------------------------------------------------------ outputs

    /**
     * True once the Java client reported the converted pack as successfully loaded. While false,
     * translators must not reference the pack's assets (custom item models, sounds, glyphs...).
     */
    public boolean isLoaded() {
        return this.loaded;
    }

    /**
     * What the converted pack provides, or null when there is none or the client has not loaded it
     * (declined, failed): callers then fall back to vanilla assets.
     */
    public @Nullable ConversionArtifacts artifacts() {
        return this.loaded && this.conversion != null ? this.conversion.artifacts() : null;
    }

    /** The conversion pushed to the client, whether or not the client loaded it. */
    public @Nullable PackConversion conversion() {
        return this.conversion;
    }

    @Override
    public void close() {
        if (this.definitionsTimeout != null) {
            this.definitionsTimeout.cancel(false);
        }
    }
}
