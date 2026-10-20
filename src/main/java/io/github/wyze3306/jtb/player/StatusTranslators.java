package io.github.wyze3306.jtb.player;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.text.TextTranslator;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemUseMethod;
import org.cloudburstmc.protocol.bedrock.packet.AdventureSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundControlSchemeSetPacket;
import org.cloudburstmc.protocol.bedrock.packet.CompletedUsingItemPacket;
import org.cloudburstmc.protocol.bedrock.packet.DeathInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerStartItemCooldownPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetDefaultGameTypePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetHealthPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetPlayerGameTypePacket;
import org.cloudburstmc.protocol.bedrock.packet.ShowCreditsPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAdventureSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdatePlayerGameTypePacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EntityEvent;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundAwardStatsPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundCooldownPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundChangeGameModePacket;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Abilities, game modes, vitals, death and the small status packets of the local player.
 */
final class StatusTranslators {

    private StatusTranslators() {
    }

    // ------------------------------------------------------------------ abilities & game mode

    /** Layered abilities; broadcast for every player, only the local player's matter here. */
    static void updateAbilities(ProxySession session, UpdateAbilitiesPacket packet) {
        PlayerState player = session.get(PlayerState.KEY);
        long target = packet.getData().getTargetPlayerRawId();
        if (target != player.uniqueId() && target != player.runtimeId()) {
            return;
        }
        player.abilities().update(packet);
        player.sendAbilities();
    }

    @SuppressWarnings("deprecation")
    static void adventureSettings(ProxySession session, AdventureSettingsPacket packet) {
        PlayerState player = session.get(PlayerState.KEY);
        if (packet.getUniqueEntityId() != player.uniqueId() && packet.getUniqueEntityId() != player.runtimeId()) {
            return;
        }
        player.abilities().update(packet);
        player.sendAbilities();
    }

    static void updateAdventureSettings(ProxySession session, UpdateAdventureSettingsPacket packet) {
        session.get(PlayerState.KEY).abilities().setImmutableWorld(packet.isImmutableWorld());
    }

    static void setPlayerGameType(ProxySession session, SetPlayerGameTypePacket packet) {
        session.get(PlayerState.KEY).setGameType(packet.getPlayerGameType());
    }

    /** Game type of a player; PowerNukkitX uses it for the others, other servers also for us. */
    static void updatePlayerGameType(ProxySession session, UpdatePlayerGameTypePacket packet) {
        PlayerState player = session.get(PlayerState.KEY);
        if (packet.getTargetPlayer() == player.uniqueId() || packet.getTargetPlayer() == player.runtimeId()) {
            player.setGameType(packet.getPlayerGameType());
        }
    }

    static void setDefaultGameType(ProxySession session, SetDefaultGameTypePacket packet) {
        session.get(PlayerState.KEY).setWorldGameType(packet.getGameType());
    }

    /** Java game mode switcher (F3+F4); the server checks the permission. */
    static void changeGameMode(ProxySession session, ServerboundChangeGameModePacket packet) {
        SetPlayerGameTypePacket request = new SetPlayerGameTypePacket();
        request.setPlayerGameType(GameModes.toBedrock(packet.getMode()));
        session.sendBedrock(request);
    }

    // ------------------------------------------------------------------ vitals, death, respawn

    static void setHealth(ProxySession session, SetHealthPacket packet) {
        session.get(PlayerState.KEY).stats().setHealth(packet.getHealth());
    }

    /** The death screen, with the Bedrock death message in the Java player's language. */
    static void deathInfo(ProxySession session, DeathInfoPacket packet) {
        PlayerState player = session.get(PlayerState.KEY);
        String cause = packet.getDeathCauseAttackName() == null ? "death.attack.generic" : packet.getDeathCauseAttackName();
        player.onLocalDeath(TextTranslator.translate(session, cause, packet.getDeathCauseMessageList()));
        player.stats().setHealth(0);
    }

    static void clientCommand(ProxySession session, ServerboundClientCommandPacket packet) {
        switch (packet.getRequest()) {
            case PERFORM_RESPAWN -> session.get(PlayerState.KEY).requestRespawn();
            // Bedrock keeps no statistics the client can ask for: answer with none so the screen opens.
            case REQUEST_STATS -> session.sendJava(new ClientboundAwardStatsPacket());
            default -> {
                // Game rule values are not readable by a Bedrock client.
            }
        }
    }

    static void showCredits(ProxySession session, ShowCreditsPacket packet) {
        if (packet.getCreditsState() == ShowCreditsPacket.CreditsState.START_CREDITS) {
            session.get(PlayerState.KEY).showCredits();
        }
    }

    // ------------------------------------------------------------------ items

    /**
     * Item cooldowns by category: vanilla categories are item names (Java cooldown groups default to
     * the item id); custom items should carry a {@code use_cooldown} group named after the category.
     */
    static void itemCooldown(ProxySession session, PlayerStartItemCooldownPacket packet) {
        Key group = cooldownGroup(packet.getItemCategory());
        if (group != null) {
            session.sendJava(new ClientboundCooldownPacket(group, Math.max(0, packet.getDurationTicks())));
        }
    }

    static @Nullable Key cooldownGroup(@Nullable String category) {
        if (category == null || category.isEmpty()) {
            return null;
        }
        String lower = category.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        String namespace = colon < 0 ? Key.MINECRAFT_NAMESPACE : sanitize(lower.substring(0, colon), false);
        String value = sanitize(colon < 0 ? lower : lower.substring(colon + 1), true);
        return namespace.isEmpty() || value.isEmpty() ? null : Key.key(namespace, value);
    }

    private static String sanitize(String text, boolean allowSlash) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean valid = c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.' || allowSlash && c == '/';
            result.append(valid ? c : '_');
        }
        return result.toString();
    }

    /** The server finished an item use: Java expects "finish using item" for food and drinks. */
    static void completedUsingItem(ProxySession session, CompletedUsingItemPacket packet) {
        PlayerState player = session.get(PlayerState.KEY);
        player.interactions().onUseCompleted();
        ItemUseMethod method = packet.getItemUseMethod();
        if (method == ItemUseMethod.EAT || method == ItemUseMethod.CONSUME) {
            session.sendJava(new ClientboundEntityEventPacket(player.javaEntityId(), EntityEvent.PLAYER_FINISH_USING_ITEM));
        }
    }

    static void controlScheme(ProxySession session, ClientboundControlSchemeSetPacket packet) {
        session.logger().debug("[{}] Ignoring control scheme {}: a Java client always steers with the mouse",
                session.getName(), packet.getControlScheme());
    }
}
