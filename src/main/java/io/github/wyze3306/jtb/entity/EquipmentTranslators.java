package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.item.ItemTranslator;
import io.github.wyze3306.jtb.session.ProxySession;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.MobArmorEquipmentPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Equipment;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * {@code MobEquipment} (held items) and {@code MobArmorEquipment} (armour and body armour) of other
 * entities as Java {@code SetEquipment}. The local player's equipment is its inventory (inventory
 * subsystem).
 */
final class EquipmentTranslators {

    private static final int CONTAINER_INVENTORY = 0;
    private static final int CONTAINER_OFFHAND = 119;
    private static final int HOTBAR_SIZE = 9;

    private EquipmentTranslators() {
    }

    /**
     * Players send their selected hotbar slot from the inventory container, or the off hand container.
     * PowerNukkitX mobs use slot 0 (main hand) and slot 1 (off hand) of container 0.
     */
    static void mobEquipment(ProxySession session, MobEquipmentPacket packet) {
        EntityTracker tracker = session.get(EntityTracker.KEY);
        TrackedEntity entity = tracker.entity(packet.getTargetRuntimeID());
        if (entity == null || entity.kind() == TrackedEntity.Kind.LOCAL_PLAYER || !entity.definition().isLiving()) {
            return;
        }
        EquipmentSlot slot;
        if (packet.getContainerId() == CONTAINER_OFFHAND || (!entity.isPlayer() && packet.getSlot() == 1)) {
            slot = EquipmentSlot.OFF_HAND;
        } else if (packet.getContainerId() == CONTAINER_INVENTORY && packet.getSlot() >= 0 && packet.getSlot() < HOTBAR_SIZE
                && (packet.getSlot() == packet.getSelectedSlot() || packet.getSelectedSlot() < 0)) {
            slot = EquipmentSlot.MAIN_HAND;
        } else {
            return;
        }
        ItemData item = packet.getItem();
        String identifier = identifier(item);
        if (slot == EquipmentSlot.MAIN_HAND) {
            entity.mainHandItem = identifier;
        } else {
            entity.offHandItem = identifier;
        }
        tracker.sendEquipment(entity, new Equipment[]{new Equipment(slot, javaItem(session, item))});
    }

    static void mobArmorEquipment(ProxySession session, MobArmorEquipmentPacket packet) {
        EntityTracker tracker = session.get(EntityTracker.KEY);
        TrackedEntity entity = tracker.entity(packet.getTargetRuntimeID());
        if (entity == null || entity.kind() == TrackedEntity.Kind.LOCAL_PLAYER || !entity.definition().isLiving()) {
            return;
        }
        tracker.sendEquipment(entity, new Equipment[]{
                new Equipment(EquipmentSlot.HELMET, javaItem(session, packet.getHead())),
                new Equipment(EquipmentSlot.CHESTPLATE, javaItem(session, packet.getTorso())),
                new Equipment(EquipmentSlot.LEGGINGS, javaItem(session, packet.getLegs())),
                new Equipment(EquipmentSlot.BOOTS, javaItem(session, packet.getFeet())),
                new Equipment(EquipmentSlot.BODY, javaItem(session, packet.getBody()))});
    }

    private static @Nullable ItemStack javaItem(ProxySession session, @Nullable ItemData item) {
        return item == null || item.isNull() ? null : ItemTranslator.toJava(session, item);
    }

    private static @Nullable String identifier(@Nullable ItemData item) {
        return item == null || item.isNull() || item.getDefinition() == null ? null : item.getDefinition().getIdentifier();
    }
}
