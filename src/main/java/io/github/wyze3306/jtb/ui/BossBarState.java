package io.github.wyze3306.jtb.ui;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.protocol.bedrock.data.payload.boss.BossBarOverlay;
import org.cloudburstmc.protocol.bedrock.packet.BossEventPacket;
import org.geysermc.mcprotocollib.protocol.data.game.BossBarColor;
import org.geysermc.mcprotocollib.protocol.data.game.BossBarDivision;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundBossEventPacket;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Bedrock boss bars ({@code BossEventPacket}) to Java boss bars. Bedrock bars belong to an entity
 * (its unique id); Java bars are keyed by a UUID derived from it. An update for a bar that was never
 * added creates it (PowerNukkitX bosses send health updates first). {@code PLAYER_ADDED},
 * {@code PLAYER_REMOVED} and {@code QUERY} are client-to-server messages and are ignored, and
 * {@code UPDATE_PROPERTIES} only carried the sky darkening flag, which the protocol no longer sends.
 * <p>
 * Colours: Bedrock {@code PINK, BLUE, RED, GREEN, YELLOW, PURPLE, REBECCA_PURPLE, WHITE} map to Java
 * {@code PINK, CYAN, RED, LIME, YELLOW, PURPLE, PURPLE, WHITE}; overlays map to divisions in order.
 */
public final class BossBarState implements SessionState {

    public static final StateKey<BossBarState> KEY = StateKey.of("ui-boss-bars", BossBarState::new);

    private static final long UUID_HIGH = 0x6A74625F626F7373L; // "jtb_boss"

    private final ProxySession session;
    private final Long2ObjectMap<Bar> bars = new Long2ObjectOpenHashMap<>();

    private static final class Bar {
        final UUID uuid;
        Component title;
        float health;
        BossBarColor color;
        BossBarDivision division;

        Bar(UUID uuid, Component title, float health, BossBarColor color, BossBarDivision division) {
            this.uuid = uuid;
            this.title = title;
            this.health = health;
            this.color = color;
            this.division = division;
        }
    }

    private BossBarState(ProxySession session) {
        this.session = session;
    }

    /** The Java UUID of the boss bar of a Bedrock entity. */
    public static UUID uuid(long uniqueId) {
        return new UUID(UUID_HIGH, uniqueId);
    }

    public void onBossEvent(BossEventPacket packet) {
        long id = packet.getTargetActorID();
        Bar bar = this.bars.get(id);
        switch (packet.getEventType()) {
            case ADD -> {
                if (bar == null) {
                    add(id, packet);
                } else {
                    updateTitle(bar, packet.getName());
                    updateHealth(bar, packet.getHealthPercent());
                    updateStyle(bar, color(packet), division(packet.getOverlay()));
                }
            }
            case REMOVE -> {
                if (bar != null) {
                    this.bars.remove(id);
                    this.session.sendJava(new ClientboundBossEventPacket(bar.uuid));
                }
            }
            case UPDATE_PERCENT -> {
                if (bar == null) {
                    add(id, packet);
                } else {
                    updateHealth(bar, packet.getHealthPercent());
                }
            }
            case UPDATE_NAME -> {
                if (bar == null) {
                    add(id, packet);
                } else {
                    updateTitle(bar, packet.getName());
                }
            }
            case UPDATE_STYLE -> {
                if (bar == null) {
                    add(id, packet);
                } else {
                    updateStyle(bar, color(packet), division(packet.getOverlay()));
                }
            }
            case UPDATE_PROPERTIES, PLAYER_ADDED, PLAYER_REMOVED, QUERY -> {
            }
        }
    }

    /** The entity owning a bar is gone (Bedrock drops its bar with it). For the entity subsystem. */
    public void onEntityRemoved(long uniqueId) {
        Bar bar = this.bars.remove(uniqueId);
        if (bar != null) {
            this.session.sendJava(new ClientboundBossEventPacket(bar.uuid));
        }
    }

    private void add(long id, BossEventPacket packet) {
        Bar bar = new Bar(uuid(id), title(packet.getName()), clamp(packet.getHealthPercent()), color(packet),
                division(packet.getOverlay()));
        this.bars.put(id, bar);
        this.session.sendJava(new ClientboundBossEventPacket(bar.uuid, bar.title, bar.health, bar.color, bar.division,
                false, false, false));
    }

    private void updateTitle(Bar bar, @Nullable String name) {
        Component title = title(name);
        if (!title.equals(bar.title)) {
            bar.title = title;
            this.session.sendJava(new ClientboundBossEventPacket(bar.uuid, title));
        }
    }

    private void updateHealth(Bar bar, float health) {
        float value = clamp(health);
        if (value != bar.health) {
            bar.health = value;
            this.session.sendJava(new ClientboundBossEventPacket(bar.uuid, value));
        }
    }

    private void updateStyle(Bar bar, BossBarColor color, BossBarDivision division) {
        if (color != bar.color || division != bar.division) {
            bar.color = color;
            bar.division = division;
            this.session.sendJava(new ClientboundBossEventPacket(bar.uuid, color, division));
        }
    }

    private Component title(@Nullable String name) {
        return name == null || name.isEmpty() ? Component.empty() : RawText.translator(this.session).apply(name);
    }

    private static float clamp(float health) {
        return Float.isNaN(health) ? 0f : Math.max(0f, Math.min(1f, health));
    }

    static BossBarColor color(BossEventPacket packet) {
        if (packet.getColor() == null) {
            return BossBarColor.PINK;
        }
        return switch (packet.getColor()) {
            case PINK -> BossBarColor.PINK;
            case BLUE -> BossBarColor.CYAN;
            case RED -> BossBarColor.RED;
            case GREEN -> BossBarColor.LIME;
            case YELLOW -> BossBarColor.YELLOW;
            case PURPLE, REBECCA_PURPLE -> BossBarColor.PURPLE;
            case WHITE -> BossBarColor.WHITE;
        };
    }

    static BossBarDivision division(@Nullable BossBarOverlay overlay) {
        if (overlay == null) {
            return BossBarDivision.NONE;
        }
        return switch (overlay) {
            case PROGRESS -> BossBarDivision.NONE;
            case NOTCHED_6 -> BossBarDivision.NOTCHES_6;
            case NOTCHED_10 -> BossBarDivision.NOTCHES_10;
            case NOTCHED_12 -> BossBarDivision.NOTCHES_12;
            case NOTCHED_20 -> BossBarDivision.NOTCHES_20;
        };
    }
}
