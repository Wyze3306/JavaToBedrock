package io.github.wyze3306.jtb.player;

import org.cloudburstmc.protocol.bedrock.data.AbilitiesIndex;
import org.cloudburstmc.protocol.bedrock.data.AdventureSetting;
import org.cloudburstmc.protocol.bedrock.data.command.CommandPermissionLevel;
import org.cloudburstmc.protocol.bedrock.data.PlayerPermissionLevel;
import org.cloudburstmc.protocol.bedrock.data.payload.abilities.SerializedAbilitiesData;
import org.cloudburstmc.protocol.bedrock.data.payload.abilities.SerializedAbilitiesDataSerializedLayer;
import org.cloudburstmc.protocol.bedrock.packet.AdventureSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EntityEvent;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerAbilitiesPacket;

import java.util.List;

/**
 * The local player's Bedrock abilities, resolved from the layered {@code UpdateAbilities} data (or
 * the legacy {@code AdventureSettings}), and their Java form.
 * <p>
 * Layers are looked up in {@link org.cloudburstmc.protocol.bedrock.data.payload.abilities.SerializedLayer}
 * order: the first layer that defines an ability wins (the order ViaBedrock verified against BDS).
 * The Java client toggling flight is remembered here, because the server does not always echo it.
 */
final class Abilities {

    /** Bedrock base layer defaults. */
    private static final float DEFAULT_WALK_SPEED = 0.1f;
    private static final float DEFAULT_FLY_SPEED = 0.05f;

    private boolean mine = true;
    private boolean invulnerable;
    private boolean flying;
    private boolean mayFly;
    private boolean instabuild;
    private boolean noClip;
    private boolean operatorCommands;
    private float flySpeed = DEFAULT_FLY_SPEED;
    private float walkSpeed = DEFAULT_WALK_SPEED;
    private PlayerPermissionLevel playerPermission = PlayerPermissionLevel.MEMBER;
    private CommandPermissionLevel commandPermission = CommandPermissionLevel.ANY;
    private boolean immutableWorld;

    /** Applies an {@code UpdateAbilities} packet. */
    void update(UpdateAbilitiesPacket packet) {
        SerializedAbilitiesData data = packet.getData();
        if (data.getPlayerPermissions() != null) {
            this.playerPermission = data.getPlayerPermissions();
        }
        if (data.getCommandPermissions() != null) {
            this.commandPermission = data.getCommandPermissions();
        }
        List<SerializedAbilitiesDataSerializedLayer> layers = data.getLayers();
        this.mine = resolve(layers, AbilitiesIndex.MINE, true);
        this.invulnerable = resolve(layers, AbilitiesIndex.INVULNERABLE, false);
        this.flying = resolve(layers, AbilitiesIndex.FLYING, false);
        this.mayFly = resolve(layers, AbilitiesIndex.MAY_FLY, false);
        this.instabuild = resolve(layers, AbilitiesIndex.INSTABUILD, false);
        this.noClip = resolve(layers, AbilitiesIndex.NO_CLIP, false);
        this.operatorCommands = resolve(layers, AbilitiesIndex.OPERATOR_COMMANDS, false);
        SerializedAbilitiesDataSerializedLayer flyLayer = layerDefining(layers, AbilitiesIndex.FLY_SPEED);
        this.flySpeed = flyLayer == null ? DEFAULT_FLY_SPEED : flyLayer.getFlySpeed();
        SerializedAbilitiesDataSerializedLayer walkLayer = layerDefining(layers, AbilitiesIndex.WALK_SPEED);
        this.walkSpeed = walkLayer == null ? DEFAULT_WALK_SPEED : walkLayer.getWalkSpeed();
    }

    /** Applies the legacy {@code AdventureSettings} packet (servers older than the layered abilities). */
    @SuppressWarnings("deprecation")
    void update(AdventureSettingsPacket packet) {
        var settings = packet.getSettings();
        this.immutableWorld = settings.contains(AdventureSetting.WORLD_IMMUTABLE);
        this.mayFly = settings.contains(AdventureSetting.MAY_FLY);
        this.flying = settings.contains(AdventureSetting.FLYING) && this.mayFly;
        this.noClip = settings.contains(AdventureSetting.NO_CLIP);
        this.mine = settings.contains(AdventureSetting.MINE);
        this.operatorCommands = settings.contains(AdventureSetting.OPERATOR);
        this.playerPermission = packet.getPlayerPermission();
        this.commandPermission = packet.getCommandPermission();
    }

    private static boolean resolve(List<SerializedAbilitiesDataSerializedLayer> layers, AbilitiesIndex ability, boolean fallback) {
        SerializedAbilitiesDataSerializedLayer layer = layerDefining(layers, ability);
        return layer == null ? fallback : layer.getAbilityValues().contains(ability);
    }

    private static SerializedAbilitiesDataSerializedLayer layerDefining(List<SerializedAbilitiesDataSerializedLayer> layers, AbilitiesIndex ability) {
        SerializedAbilitiesDataSerializedLayer best = null;
        for (int i = 0, size = layers.size(); i < size; i++) {
            SerializedAbilitiesDataSerializedLayer layer = layers.get(i);
            if (layer.getSerializedLayer() != null && layer.getAbilitiesSet().contains(ability)
                    && (best == null || layer.getSerializedLayer().ordinal() < best.getSerializedLayer().ordinal())) {
                best = layer;
            }
        }
        return best;
    }

    void setImmutableWorld(boolean immutableWorld) {
        this.immutableWorld = immutableWorld;
    }

    /** The Java client toggled flight (already checked against {@link #mayFly()}). */
    void setFlying(boolean flying) {
        this.flying = flying;
    }

    boolean mayFly() {
        return this.mayFly;
    }

    boolean flying() {
        return this.flying;
    }

    boolean noClip() {
        return this.noClip;
    }

    /** Whether the player may break blocks at all. */
    boolean canMine() {
        return this.mine && !this.immutableWorld;
    }

    /**
     * The Java abilities. Java spectators always fly; the Java walk speed only drives the field of
     * view, the movement itself follows the {@code movement_speed} attribute.
     */
    ClientboundPlayerAbilitiesPacket toJava(GameMode mode) {
        boolean spectator = mode == GameMode.SPECTATOR;
        return new ClientboundPlayerAbilitiesPacket(this.invulnerable || mode == GameMode.CREATIVE || spectator,
                this.mayFly || spectator, this.flying || spectator, this.instabuild,
                this.flySpeed, this.walkSpeed);
    }

    /**
     * Java operator level (0-4) for the entity event the client uses to unlock the game mode
     * switcher (F3+F4) and operator items.
     */
    int operatorLevel() {
        int level = switch (this.commandPermission) {
            case ANY -> 0;
            case GAME_DIRECTORS -> 2;
            case ADMIN -> 3;
            default -> 4;
        };
        if (this.playerPermission == PlayerPermissionLevel.OPERATOR || this.operatorCommands) {
            level = Math.max(level, 2);
        }
        return level;
    }

    static EntityEvent operatorEvent(int level) {
        return switch (level) {
            case 0 -> EntityEvent.PLAYER_SET_NO_PERMISSIONS;
            case 1 -> EntityEvent.PLAYER_SET_MODERATOR;
            case 2 -> EntityEvent.PLAYER_SET_GAMEMASTER;
            case 3 -> EntityEvent.PLAYER_SET_ADMIN;
            default -> EntityEvent.PLAYER_SET_OWNER;
        };
    }
}
