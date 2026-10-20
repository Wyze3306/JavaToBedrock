package io.github.wyze3306.jtb.player;

import io.github.wyze3306.jtb.translator.TranslatorModule;
import io.github.wyze3306.jtb.translator.TranslatorRegistry;
import org.cloudburstmc.protocol.bedrock.packet.AdventureSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ClientboundControlSchemeSetPacket;
import org.cloudburstmc.protocol.bedrock.packet.CompletedUsingItemPacket;
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket;
import org.cloudburstmc.protocol.bedrock.packet.DeathInfoPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket;
import org.cloudburstmc.protocol.bedrock.packet.MovementEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerStartItemCooldownPacket;
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerPlayerPostMovePositionPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetDefaultGameTypePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetHealthPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetLastHurtByPacket;
import org.cloudburstmc.protocol.bedrock.packet.SetPlayerGameTypePacket;
import org.cloudburstmc.protocol.bedrock.packet.SetPlayerInventoryOptionsPacket;
import org.cloudburstmc.protocol.bedrock.packet.ShowCreditsPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAbilitiesPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAdventureSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateClientInputLocksPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdatePlayerGameTypePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundPickItemFromBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundPickItemFromEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPaddleBoatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundTeleportToEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundAttackPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundChangeGameModePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerStatusOnlyPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerAbilitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPunchPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSpectatorActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket;

/**
 * Translators of the player subsystem. See docs/DEVELOPING.md for the packets it owns. In 26.3 the
 * Java "swing" is {@code ServerboundPunchPacket} and attacking has its own {@code ServerboundAttackPacket};
 * both belong here, as do the game mode switcher and spectator packets.
 * <p>
 * Consumed without effect: Bedrock {@code ServerPlayerPostMovePosition} (reconciliation hint for
 * Bedrock's own client prediction), {@code MovementEffect} (firework/dolphin boosts, simulated by the
 * Java client from the entities), {@code SetLastHurtBy}, {@code SetPlayerInventoryOptions}; Java
 * {@code PlayerLoaded}, {@code PaddleBoat} (Bedrock boats paddle from the move vector),
 * {@code TeleportToEntity} and {@code SpectatorAction} (no Bedrock spectator teleport).
 */
public final class PlayerModule implements TranslatorModule {

    @Override
    @SuppressWarnings("deprecation") // AdventureSettings: still sent by servers without layered abilities
    public void register(TranslatorRegistry registry) {
        // Spawn & bookkeeping
        registry.bedrock(PlayStatusPacket.class, SpawnTranslators::playStatus);
        registry.bedrock(RespawnPacket.class, SpawnTranslators::respawn);
        registry.bedrock(NetworkStackLatencyPacket.class, SpawnTranslators::networkStackLatency);

        // Movement
        registry.bedrock(MovePlayerPacket.class, MovementTranslators::movePlayer);
        registry.bedrock(CorrectPlayerMovePredictionPacket.class, MovementTranslators::correctPrediction);
        registry.bedrock(UpdateClientInputLocksPacket.class, MovementTranslators::inputLocks);
        registry.bedrock(ServerPlayerPostMovePositionPacket.class, (session, packet) -> {
        });
        registry.bedrock(MovementEffectPacket.class, (session, packet) -> {
        });
        registry.java(ServerboundMovePlayerPosPacket.class, MovementTranslators::movePos);
        registry.java(ServerboundMovePlayerPosRotPacket.class, MovementTranslators::movePosRot);
        registry.java(ServerboundMovePlayerRotPacket.class, MovementTranslators::moveRot);
        registry.java(ServerboundMovePlayerStatusOnlyPacket.class, MovementTranslators::moveStatus);
        registry.java(ServerboundAcceptTeleportationPacket.class, MovementTranslators::acceptTeleport);
        registry.java(ServerboundPlayerInputPacket.class, MovementTranslators::playerInput);
        registry.java(ServerboundClientTickEndPacket.class, (session, packet) -> MovementTranslators.clientTickEnd(session));
        registry.java(ServerboundPlayerAbilitiesPacket.class, MovementTranslators::abilities);
        registry.java(ServerboundPlayerCommandPacket.class, MovementTranslators::playerCommand);
        registry.java(ServerboundMoveVehiclePacket.class, MovementTranslators::moveVehicle);
        registry.java(ServerboundPaddleBoatPacket.class, (session, packet) -> {
        });
        registry.java(ServerboundPlayerLoadedPacket.class, (session, packet) -> {
        });
        registry.java(ServerboundTeleportToEntityPacket.class, (session, packet) -> {
        });
        registry.java(ServerboundSpectatorActionPacket.class, (session, packet) -> {
        });

        // Abilities, game mode, vitals, death
        registry.bedrock(UpdateAbilitiesPacket.class, StatusTranslators::updateAbilities);
        registry.bedrock(UpdateAdventureSettingsPacket.class, StatusTranslators::updateAdventureSettings);
        registry.bedrock(AdventureSettingsPacket.class, StatusTranslators::adventureSettings);
        registry.bedrock(SetPlayerGameTypePacket.class, StatusTranslators::setPlayerGameType);
        registry.bedrock(UpdatePlayerGameTypePacket.class, StatusTranslators::updatePlayerGameType);
        registry.bedrock(SetDefaultGameTypePacket.class, StatusTranslators::setDefaultGameType);
        registry.bedrock(SetHealthPacket.class, StatusTranslators::setHealth);
        registry.bedrock(DeathInfoPacket.class, StatusTranslators::deathInfo);
        registry.bedrock(ShowCreditsPacket.class, StatusTranslators::showCredits);
        registry.bedrock(PlayerStartItemCooldownPacket.class, StatusTranslators::itemCooldown);
        registry.bedrock(CompletedUsingItemPacket.class, StatusTranslators::completedUsingItem);
        registry.bedrock(ClientboundControlSchemeSetPacket.class, StatusTranslators::controlScheme);
        registry.bedrock(SetLastHurtByPacket.class, (session, packet) -> {
        });
        registry.bedrock(SetPlayerInventoryOptionsPacket.class, (session, packet) -> {
        });
        registry.java(ServerboundClientCommandPacket.class, StatusTranslators::clientCommand);
        registry.java(ServerboundChangeGameModePacket.class, StatusTranslators::changeGameMode);

        // Interaction
        registry.java(ServerboundPlayerActionPacket.class, InteractionTranslators::playerAction);
        registry.java(ServerboundUseItemOnPacket.class, InteractionTranslators::useItemOn);
        registry.java(ServerboundUseItemPacket.class, InteractionTranslators::useItem);
        registry.java(ServerboundAttackPacket.class, InteractionTranslators::attack);
        registry.java(ServerboundInteractPacket.class, InteractionTranslators::interact);
        registry.java(ServerboundPunchPacket.class, (session, packet) -> InteractionTranslators.swing(session));
        registry.java(ServerboundPickItemFromBlockPacket.class, InteractionTranslators::pickBlock);
        registry.java(ServerboundPickItemFromEntityPacket.class, InteractionTranslators::pickEntity);
    }
}
