package io.github.wyze3306.jtb.player;

import io.github.wyze3306.jtb.entity.EntityTracker;
import io.github.wyze3306.jtb.inventory.InventoryState;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.world.WorldState;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.ActorSwingSource;
import org.cloudburstmc.protocol.bedrock.data.PlayerActionType;
import org.cloudburstmc.protocol.bedrock.data.payload.inventory.transaction.ItemUseOnActorActionType;
import org.cloudburstmc.protocol.bedrock.packet.ActorPickRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.BlockPickRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundPickItemFromBlockPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundPickItemFromEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundAttackPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket;

/**
 * The Java player's actions on the world: digging, placing and using items, attacking and
 * interacting with entities, swinging, picking blocks.
 * <p>
 * Off-hand uses have no Bedrock equivalent (Bedrock always uses the main hand): the Java client sends
 * the main hand first and only falls back to the off hand when it did nothing, so those are dropped
 * (their sequence is still acknowledged).
 */
final class InteractionTranslators {

    /** Hotbar size the vanilla client announces when picking blocks and entities. */
    private static final int HOTBAR_SLOTS = 9;

    private InteractionTranslators() {
    }

    static void playerAction(ProxySession session, ServerboundPlayerActionPacket packet) {
        PlayerState player = session.get(PlayerState.KEY);
        Interactions interactions = player.interactions();
        switch (packet.getAction()) {
            case START_DIGGING, CANCEL_DIGGING, FINISH_DIGGING, CHANGE_DIGGING_DIRECTION -> {
                if (!player.isDead()) {
                    session.get(BlockBreaking.KEY).onDig(packet.getAction(), packet.getPosition(), packet.getFace());
                    if (packet.getAction() == PlayerAction.START_DIGGING) {
                        interactions.acted(ActorSwingSource.MINE);
                    }
                }
            }
            case DROP_ITEM -> session.get(InventoryState.KEY).dropHeldItem(false);
            case DROP_ITEM_STACK -> session.get(InventoryState.KEY).dropHeldItem(true);
            case SWAP_HANDS -> session.get(InventoryState.KEY).swapOffhand();
            case RELEASE_USE_ITEM -> interactions.releaseItem();
            case STAB -> interactions.stab();
            default -> {
            }
        }
        interactions.acknowledge(packet.getSequence());
    }

    /**
     * Click on a block: {@code PlayerAction(START_ITEM_USE_ON)}, the {@code ItemUse(PLACE)}
     * transaction, {@code PlayerAction(STOP_ITEM_USE_ON)}, like the vanilla client.
     */
    static void useItemOn(ProxySession session, ServerboundUseItemOnPacket packet) {
        PlayerState player = session.get(PlayerState.KEY);
        if (packet.getHand() == Hand.MAIN_HAND && !player.isDead()) {
            Vector3i position = packet.getPosition();
            int face = packet.getFace().ordinal();
            Vector3i result = packet.isInsideBlock() ? position : position.add(offset(face));
            sendAction(session, player, PlayerActionType.START_ITEM_USE_ON, position, result, face);
            int target = session.get(WorldState.KEY).bedrockBlockAt(position.getX(), position.getY(), position.getZ(), 0);
            player.interactions().useItemOn(position, face, packet.getCursorX(), packet.getCursorY(), packet.getCursorZ(), target);
            sendAction(session, player, PlayerActionType.STOP_ITEM_USE_ON, position, Vector3i.ZERO, 0);
        }
        player.interactions().acknowledge(packet.getSequence());
    }

    static void useItem(ProxySession session, ServerboundUseItemPacket packet) {
        PlayerState player = session.get(PlayerState.KEY);
        if (packet.getHand() == Hand.MAIN_HAND && !player.isDead()) {
            player.input().setRotation(packet.getYRot(), packet.getXRot());
            player.interactions().useItem();
        }
        player.interactions().acknowledge(packet.getSequence());
    }

    static void attack(ProxySession session, ServerboundAttackPacket packet) {
        PlayerState player = session.get(PlayerState.KEY);
        long target = session.get(EntityTracker.KEY).runtimeId(packet.getEntityId());
        if (target == -1 || player.isLocal(target) || player.isDead()) {
            return;
        }
        player.interactions().useOnEntity(target, ItemUseOnActorActionType.ATTACK, Vector3f.ZERO);
    }

    /**
     * Right click on an entity (mounting boats, minecarts and horses included). The Java location is
     * the hit point relative to the entity and is sent as such: PowerNukkitX, like the vanilla client,
     * uses the eye position ({@code fromPosition}) as the click position.
     */
    static void interact(ProxySession session, ServerboundInteractPacket packet) {
        PlayerState player = session.get(PlayerState.KEY);
        if (packet.getHand() != Hand.MAIN_HAND || player.isDead()) {
            return;
        }
        long target = session.get(EntityTracker.KEY).runtimeId(packet.getEntityId());
        if (target == -1 || player.isLocal(target)) {
            return;
        }
        Vector3d location = packet.getLocation();
        Vector3f hit = location == null ? Vector3f.ZERO : Vector3f.from(location.getX(), location.getY(), location.getZ());
        player.interactions().useOnEntity(target, ItemUseOnActorActionType.INTERACT, hit);
    }

    static void swing(ProxySession session) {
        PlayerState player = session.get(PlayerState.KEY);
        if (player.isSpawned() && !player.isDead()) {
            player.interactions().onSwing(session.get(BlockBreaking.KEY).digging());
        }
    }

    /** Middle click on a block; with Ctrl (creative) the block entity data is copied. */
    static void pickBlock(ProxySession session, ServerboundPickItemFromBlockPacket packet) {
        BlockPickRequestPacket request = new BlockPickRequestPacket();
        request.setPosition(packet.getPos());
        request.setWithData(packet.isIncludeData());
        request.setMaxSlots(HOTBAR_SLOTS);
        session.sendBedrock(request);
    }

    static void pickEntity(ProxySession session, ServerboundPickItemFromEntityPacket packet) {
        long target = session.get(EntityTracker.KEY).runtimeId(packet.getId());
        if (target == -1) {
            return;
        }
        ActorPickRequestPacket request = new ActorPickRequestPacket();
        request.setActorID(target);
        request.setWithData(packet.isIncludeData());
        request.setMaxSlots(HOTBAR_SLOTS);
        session.sendBedrock(request);
    }

    private static void sendAction(ProxySession session, PlayerState player, PlayerActionType type, Vector3i position, Vector3i result, int face) {
        PlayerActionPacket action = new PlayerActionPacket();
        action.setPlayerRuntimeID(player.runtimeId());
        action.setAction(type);
        action.setBlockPosition(position);
        action.setResultPos(result);
        action.setFace(face);
        session.sendBedrock(action);
    }

    /** Unit offset of a block face (Bedrock and Java face ids: down, up, north, south, west, east). */
    private static Vector3i offset(int face) {
        return switch (face) {
            case 0 -> Vector3i.from(0, -1, 0);
            case 1 -> Vector3i.from(0, 1, 0);
            case 2 -> Vector3i.from(0, 0, -1);
            case 3 -> Vector3i.from(0, 0, 1);
            case 4 -> Vector3i.from(-1, 0, 0);
            default -> Vector3i.from(1, 0, 0);
        };
    }
}
