package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.player.PlayerState;
import io.github.wyze3306.jtb.session.ProxySession;
import org.cloudburstmc.protocol.bedrock.data.payload.attribute.AttributeData;
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket;
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EntityEvent;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.Attribute;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.AttributeType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveMobEffectPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundUpdateAttributesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundUpdateMobEffectPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code UpdateAttributes} and {@code MobEffect} of other entities (the local player's go to
 * {@link PlayerState}). Health becomes the Java health entity data and {@code max_health}; movement,
 * jump strength, knockback resistance and absorption keep Java's horse riding, walk animations and
 * hearts right. Effects are mirrored (vanilla servers send other entities' effects too) and removed
 * when their Bedrock duration runs out, as Bedrock clients do.
 */
final class AttributeTranslators {

    private AttributeTranslators() {
    }

    static void updateAttributes(ProxySession session, UpdateAttributesPacket packet) {
        EntityTracker tracker = session.get(EntityTracker.KEY);
        TrackedEntity entity = tracker.entity(packet.getRuntimeID());
        if (entity == null) {
            return;
        }
        if (entity.kind() == TrackedEntity.Kind.LOCAL_PLAYER) {
            session.get(PlayerState.KEY).onLocalAttributes(packet.getAttributeList());
            return;
        }
        apply(session, tracker, entity, packet.getAttributeList());
    }

    /** Applies Bedrock attributes to a (non-local) entity. */
    static void apply(ProxySession session, EntityTracker tracker, TrackedEntity entity, List<AttributeData> attributes) {
        if (attributes == null || attributes.isEmpty() || !entity.definition().isLiving()) {
            return;
        }
        List<Attribute> java = null;
        boolean died = false;
        MetadataWriter writer = tracker.metadata().begin(entity);
        for (AttributeData attribute : attributes) {
            String name = attribute.getAttributeName();
            if (name == null) {
                continue;
            }
            float value = Math.max(attribute.getMinValue(), Math.min(attribute.getMaxValue(), attribute.getCurrentValue()));
            switch (name) {
                case "minecraft:health" -> {
                    entity.health = value;
                    if (attribute.getMaxValue() != entity.maxHealth && attribute.getMaxValue() > 0) {
                        entity.maxHealth = attribute.getMaxValue();
                        java = add(java, AttributeType.Builtin.MAX_HEALTH, entity.maxHealth);
                    }
                    writer.putFloat(MetaField.HEALTH, value);
                    if ((value <= 0) != entity.dead) {
                        entity.dead = value <= 0;
                        died = entity.dead;
                        writer.putObject(MetaField.POSE, MetadataTypes.POSE, ActorDataTranslator.pose(entity));
                    }
                }
                case "minecraft:absorption" -> {
                    entity.absorption = value;
                    writer.putFloat(MetaField.PLAYER_ABSORPTION, value);
                }
                case "minecraft:movement" -> java = add(java, AttributeType.Builtin.MOVEMENT_SPEED, value);
                case "minecraft:horse.jump_strength" -> java = add(java, AttributeType.Builtin.JUMP_STRENGTH, value);
                case "minecraft:knockback_resistance" -> java = add(java, AttributeType.Builtin.KNOCKBACK_RESISTANCE, value);
                case "minecraft:follow_range" -> java = add(java, AttributeType.Builtin.FOLLOW_RANGE, value);
                case "minecraft:attack_damage" -> java = add(java, AttributeType.Builtin.ATTACK_DAMAGE, value);
                case "minecraft:luck" -> java = add(java, AttributeType.Builtin.LUCK, value);
                default -> {
                }
            }
        }
        writer.flush(session);
        if (java != null) {
            session.sendJava(new ClientboundUpdateAttributesPacket(entity.javaId(), java));
        }
        if (died) {
            // PowerNukkitX's /kill only zeroes the health (no ActorEvent DEATH): the death sound still plays.
            session.sendJava(new ClientboundEntityEventPacket(entity.javaId(), EntityEvent.LIVING_DEATH));
        }
    }

    private static List<Attribute> add(List<Attribute> list, AttributeType type, double value) {
        List<Attribute> out = list == null ? new ArrayList<>(2) : list;
        out.add(new Attribute(type, value));
        return out;
    }

    static void mobEffect(ProxySession session, MobEffectPacket packet) {
        EntityTracker tracker = session.get(EntityTracker.KEY);
        TrackedEntity entity = tracker.entity(packet.getTargetRuntimeID());
        if (entity == null) {
            return;
        }
        if (entity.kind() == TrackedEntity.Kind.LOCAL_PLAYER) {
            session.get(PlayerState.KEY).onLocalEffect(packet);
            return;
        }
        Effect effect = EntityDefinitions.javaEffect(packet.getEffectID());
        if (effect == null || !entity.definition().isLiving() || packet.getEvent() == null) {
            return;
        }
        switch (packet.getEvent()) {
            case ADD, MODIFY -> {
                int duration = packet.getEffectDurationTicks();
                session.sendJava(new ClientboundUpdateMobEffectPacket(entity.javaId(), effect, packet.getEffectAmplifier(),
                        duration, packet.isAmbient(), packet.isShowParticles(), true, false));
                tracker.trackEffect(entity, packet.getEffectID(), duration < 0 ? -1 : duration);
            }
            case REMOVE -> {
                session.sendJava(new ClientboundRemoveMobEffectPacket(entity.javaId(), effect));
                tracker.untrackEffect(entity, packet.getEffectID());
            }
            default -> {
            }
        }
    }
}
