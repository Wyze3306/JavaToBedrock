package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.mapping.JavaRegistries;
import io.github.wyze3306.jtb.player.PlayerState;
import io.github.wyze3306.jtb.session.ProxySession;
import it.unimi.dsi.fastutil.longs.LongList;
import org.cloudburstmc.protocol.bedrock.data.HandSlot;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorEvent;
import org.cloudburstmc.protocol.bedrock.packet.ActorEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.AnimateEntityPacket;
import org.cloudburstmc.protocol.bedrock.packet.AnimatePacket;
import org.cloudburstmc.protocol.bedrock.packet.EmotePacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EntityEvent;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Animation;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.SwingAnimation;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAnimatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundDamageEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundHurtAnimationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSwingAnimationPacket;
import org.jspecify.annotations.Nullable;

/**
 * Animations and events: {@code ActorEvent} (hurt, death, taming, hearts, shaking, fireworks,
 * totems...), {@code Animate} (arm swing, critical hits, waking up), {@code AnimateEntity} (resource pack
 * animations, for custom entity renderers) and {@code Emote} (shown as an arm swing: Java has no emotes).
 * Events of the local player go to {@link PlayerState#onLocalEvent}.
 */
final class EventTranslators {

    /** Vanilla's default swing (6 ticks). */
    private static final SwingAnimation SWING = new SwingAnimation(SwingAnimation.Type.WHACK, 6);

    private EventTranslators() {
    }

    static void actorEvent(ProxySession session, ActorEventPacket packet) {
        EntityTracker tracker = session.get(EntityTracker.KEY);
        TrackedEntity entity = tracker.entity(packet.getTargetRuntimeID());
        ActorEvent type = packet.getType();
        if (entity == null || type == null) {
            return;
        }
        if (entity.kind() == TrackedEntity.Kind.LOCAL_PLAYER) {
            session.get(PlayerState.KEY).onLocalEvent(packet);
            return;
        }
        int id = entity.javaId();
        EntityType javaType = entity.javaType();
        switch (type) {
            case HURT -> {
                if (entity.definition().isLiving() || EntityTracker.isBoat(javaType) || javaType == EntityType.ARMOR_STAND) {
                    int damageType = Math.max(0, JavaRegistries.registry("minecraft:damage_type")
                            .id(EntityDefinitions.damageType(packet.getData())));
                    session.sendJava(new ClientboundDamageEventPacket(id, damageType, -1, -1, null));
                }
            }
            case HURT_WITHOUT_RECEIVING_DAMAGE -> session.sendJava(new ClientboundHurtAnimationPacket(id, 0));
            case DEATH, INSTANT_DEATH, DRAGON_START_DEATH_ANIM -> die(session, tracker, entity);
            case START_ATTACKING -> event(session, id, EntityEvent.ATTACK);
            case STOP_ATTACKING -> event(session, id, EntityEvent.STOP_ATTACK);
            case TAMING_FAILED -> event(session, id, javaType == EntityType.OCELOT
                    ? EntityEvent.OCELOT_TAMING_FAILED : EntityEvent.TAMEABLE_TAMING_FAILED);
            case TAMING_SUCCEEDED -> event(session, id, javaType == EntityType.OCELOT
                    ? EntityEvent.OCELOT_TAMING_SUCCEEDED : EntityEvent.TAMEABLE_TAMING_SUCCEEDED);
            case SHAKE_WETNESS -> event(session, id, EntityEvent.WOLF_SHAKE_WATER);
            case SHAKE_WETNESS_STOP -> event(session, id, EntityEvent.WOLF_SHAKE_WATER_STOP);
            case EAT_GRASS, PRIME_TNTCART -> event(session, id, EntityEvent.SHEEP_GRAZE_OR_TNT_CART_EXPLODE);
            case ZOMBIE_CONVERTING -> event(session, id, EntityEvent.ZOMBIE_VILLAGER_CURE);
            case START_OFFER_FLOWER -> event(session, id, EntityEvent.IRON_GOLEM_HOLD_POPPY);
            case STOP_OFFER_FLOWER -> event(session, id, EntityEvent.IRON_GOLEM_EMPTY_HAND);
            case LOVE_HEARTS, IN_LOVE_HEARTS -> event(session, id, EntityEvent.ANIMAL_EMIT_HEARTS);
            case VILLAGER_ANGRY -> event(session, id, EntityEvent.VILLAGER_ANGRY);
            case VILLAGER_HAPPY -> event(session, id, EntityEvent.VILLAGER_HAPPY);
            case WITCH_HAT_MAGIC -> event(session, id, EntityEvent.WITCH_EMIT_PARTICLES);
            case FIREWORKS_EXPLODE -> event(session, id, EntityEvent.FIREWORK_EXPLODE);
            case GUARDIAN_ATTACK_SOUND -> event(session, id, EntityEvent.GUARDIAN_MAKE_SOUND);
            case TALISMAN_ACTIVATE -> event(session, id, EntityEvent.TOTEM_OF_UNDYING_MAKE_SOUND);
            case JUMP -> {
                if (javaType == EntityType.RABBIT) {
                    event(session, id, EntityEvent.RABBIT_JUMP_OR_MINECART_SPAWNER_DELAY_RESET);
                }
            }
            case VIBRATION_DETECTED -> {
                if (javaType == EntityType.WARDEN) {
                    event(session, id, EntityEvent.WARDEN_RECEIVE_SIGNAL);
                }
            }
            default -> {
            }
        }
    }

    /** Death: health 0 and the dying pose (Java plays the fall-over animation), then the death event (sound). */
    private static void die(ProxySession session, EntityTracker tracker, TrackedEntity entity) {
        boolean alreadyDead = entity.dead;
        entity.dead = true;
        MetadataWriter writer = tracker.metadata().begin(entity);
        writer.putFloat(MetaField.HEALTH, 0);
        writer.putObject(MetaField.POSE, MetadataTypes.POSE, Pose.DYING);
        writer.flush(session);
        // A health update to 0 may have come first and played the death already.
        if (entity.definition().isLiving() && !alreadyDead) {
            event(session, entity.javaId(), EntityEvent.LIVING_DEATH);
        }
    }

    private static void event(ProxySession session, int javaId, EntityEvent event) {
        session.sendJava(new ClientboundEntityEventPacket(javaId, event));
    }

    static void animate(ProxySession session, AnimatePacket packet) {
        EntityTracker tracker = session.get(EntityTracker.KEY);
        TrackedEntity entity = tracker.entity(packet.getTargetRuntimeID());
        AnimatePacket.Action action = packet.getAction();
        if (entity == null || action == null) {
            return;
        }
        int id = entity.javaId();
        switch (action) {
            case SWING -> {
                // The Java client animates its own swings.
                if (entity.kind() != TrackedEntity.Kind.LOCAL_PLAYER) {
                    session.sendJava(new ClientboundSwingAnimationPacket(id, hand(packet.getHand()), SWING));
                }
            }
            case WAKE_UP -> session.sendJava(new ClientboundAnimatePacket(id, Animation.WAKE_UP));
            case CRITICAL_HIT -> session.sendJava(new ClientboundAnimatePacket(id, Animation.CRITICAL_HIT));
            case MAGIC_CRITICAL_HIT -> session.sendJava(new ClientboundAnimatePacket(id, Animation.MAGIC_CRITICAL_HIT));
            default -> {
            }
        }
    }

    private static Hand hand(@Nullable HandSlot slot) {
        return slot == HandSlot.OFFHAND ? Hand.OFF_HAND : Hand.MAIN_HAND;
    }

    /** Resource pack animations: only custom entity renderers can play them. */
    static void animateEntity(ProxySession session, AnimateEntityPacket packet) {
        EntityTracker tracker = session.get(EntityTracker.KEY);
        LongList runtimeIds = packet.getRuntimeIds();
        for (int i = 0; i < runtimeIds.size(); i++) {
            TrackedEntity entity = tracker.entity(runtimeIds.getLong(i));
            if (entity != null && entity.kind() == TrackedEntity.Kind.CUSTOM && packet.getAnimation() != null) {
                tracker.customRenderer().animate(session, tracker, entity, packet.getAnimation());
            }
        }
    }

    /** Another player emotes: Java has no emotes, the player waves (swings its arm). */
    static void emote(ProxySession session, EmotePacket packet) {
        EntityTracker tracker = session.get(EntityTracker.KEY);
        TrackedEntity entity = tracker.entity(packet.getActorRuntimeId());
        if (entity != null && entity.kind() != TrackedEntity.Kind.LOCAL_PLAYER) {
            session.sendJava(new ClientboundSwingAnimationPacket(entity.javaId(), Hand.MAIN_HAND, SWING));
        }
    }
}
