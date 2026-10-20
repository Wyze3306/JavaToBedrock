package io.github.wyze3306.jtb.player;

import io.github.wyze3306.jtb.session.ProxySession;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataMap;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataTypes;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorFlags;
import org.cloudburstmc.protocol.bedrock.data.payload.attribute.AttributeData;
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.Attribute;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.AttributeModifier;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.AttributeType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.ModifierOperation;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ByteEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.FloatEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.IntEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ObjectEntityMetadata;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveMobEffectPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundUpdateAttributesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundUpdateMobEffectPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetExperiencePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Health, food, experience, attributes, effects and own entity data of the local player: the Bedrock
 * values as last received, and their Java packets.
 * <p>
 * Movement speed: the Bedrock {@code minecraft:movement} value already contains the sprint boost
 * (x1.3 while the server thinks the player sprints) and the speed/slowness effects. The Java client
 * applies its own sprint modifier ({@code minecraft:sprinting}, +30 % of the total), so the Java base
 * value is the Bedrock value without the sprint boost, and every sync carries the sprint modifier
 * while the Java player sprints (a sync replaces all modifiers client-side). Input locks
 * ({@code UpdateClientInputLocks}) become -100 % modifiers on movement speed and jump strength.
 */
final class LocalStats {

    private static final Key SPRINT_MODIFIER = Key.key("minecraft:sprinting");
    private static final Key LOCK_MODIFIER = Key.key("jtb:input_lock");
    private static final float BEDROCK_SPRINT_BOOST = 1.3f;
    private static final double JAVA_JUMP_STRENGTH = 0.42;
    /** Bedrock has no attack cooldown: the Java one is made instant (and its indicator never shows). */
    private static final double NO_ATTACK_COOLDOWN = 1024;
    /** Java ticks of powder snow before a player is fully frozen. */
    private static final int JAVA_TICKS_TO_FREEZE = 140;
    private static final int JAVA_MAX_AIR = 300;
    private static final int JAVA_SHARED_FLAGS = 0, JAVA_AIR = 1, JAVA_POSE = 6, JAVA_TICKS_FROZEN = 7,
            JAVA_SLEEPING_POS = 14, JAVA_ABSORPTION = 17;

    private final ProxySession session;
    private final PlayerState player;

    // Bedrock values
    private float health = 20, maxHealth = 20, saturation = 5, absorption, xpProgress, movement = 0.1f;
    private float knockbackResistance, luck;
    private int food = 20, xpLevel;
    private boolean serverSprinting;
    private boolean serverFlagsKnown;
    private boolean movementLocked, jumpLocked;

    // Own entity data, as last sent to Java
    private boolean onFire, invisible, sleeping;
    private int air = JAVA_MAX_AIR, ticksFrozen;
    private short airMax = (short) JAVA_MAX_AIR;
    private int sentFlags = -1;

    /** Remaining Java ticks of every active effect, by Java effect ordinal: 0 none, -1 infinite. */
    private final int[] effectTicks = new int[Effect.VALUES.length];

    LocalStats(ProxySession session, PlayerState player) {
        this.session = session;
        this.player = player;
    }

    float health() {
        return this.health;
    }

    // ------------------------------------------------------------------ attributes

    /** {@code UpdateAttributes} for the local player. */
    void onAttributes(List<AttributeData> attributes) {
        boolean vitals = false, experience = false, javaAttributes = false, absorptionChanged = false;
        for (int i = 0, size = attributes.size(); i < size; i++) {
            AttributeData attribute = attributes.get(i);
            float value = attribute.getCurrentValue();
            switch (attribute.getAttributeName()) {
                case "minecraft:health" -> {
                    if (attribute.getMaxValue() != this.maxHealth) {
                        this.maxHealth = Math.max(1, attribute.getMaxValue());
                        javaAttributes = true;
                    }
                    this.health = value;
                    vitals = true;
                }
                case "minecraft:player.hunger" -> {
                    this.food = (int) value;
                    vitals = true;
                }
                case "minecraft:player.saturation" -> {
                    this.saturation = value;
                    vitals = true;
                }
                case "minecraft:player.level" -> {
                    this.xpLevel = (int) value;
                    experience = true;
                }
                case "minecraft:player.experience" -> {
                    this.xpProgress = value;
                    experience = true;
                }
                case "minecraft:absorption" -> {
                    absorptionChanged = this.absorption != value;
                    this.absorption = value;
                }
                case "minecraft:movement" -> {
                    javaAttributes |= this.movement != value;
                    this.movement = value;
                }
                case "minecraft:knockback_resistance" -> {
                    javaAttributes |= this.knockbackResistance != value;
                    this.knockbackResistance = value;
                }
                case "minecraft:luck" -> {
                    javaAttributes |= this.luck != value;
                    this.luck = value;
                }
                default -> {
                    // exhaustion, underwater/lava movement, follow range...: no Java counterpart for players
                }
            }
        }
        if (javaAttributes) {
            sendAttributes();
        }
        if (vitals) {
            sendHealth();
        }
        if (experience) {
            sendExperience();
        }
        if (absorptionChanged) {
            this.session.sendJava(new ClientboundSetEntityDataPacket(this.player.javaEntityId(), new EntityMetadata<?, ?>[]{
                    new FloatEntityMetadata(JAVA_ABSORPTION, MetadataTypes.FLOAT, this.absorption)}));
        }
    }

    /** Bedrock {@code SetHealth}: health only. */
    void setHealth(float health) {
        this.health = health;
        sendHealth();
    }

    void sendHealth() {
        this.session.sendJava(new ClientboundSetHealthPacket(Math.max(0, this.health), this.food, this.saturation));
        if (this.health <= 0) {
            this.player.onLocalDeath(null);
        }
    }

    void sendExperience() {
        this.session.sendJava(new ClientboundSetExperiencePacket(this.xpProgress, this.xpLevel, totalExperience(this.xpLevel, this.xpProgress)));
    }

    /** Sends every Java attribute the proxy controls (also used after a respawn reset them). */
    void sendAttributes() {
        boolean boosted = this.serverFlagsKnown ? this.serverSprinting : this.player.input().sprinting();
        double base = boosted ? this.movement / BEDROCK_SPRINT_BOOST : this.movement;
        List<AttributeModifier> speedModifiers = new ArrayList<>(2);
        if (this.player.input().sprinting()) {
            speedModifiers.add(new AttributeModifier(SPRINT_MODIFIER, 0.3, ModifierOperation.ADD_MULTIPLIED_TOTAL));
        }
        if (this.movementLocked) {
            speedModifiers.add(new AttributeModifier(LOCK_MODIFIER, -1, ModifierOperation.ADD_MULTIPLIED_TOTAL));
        }
        List<AttributeModifier> jumpModifiers = this.movementLocked || this.jumpLocked
                ? List.of(new AttributeModifier(LOCK_MODIFIER, -1, ModifierOperation.ADD_MULTIPLIED_TOTAL))
                : List.of();
        this.session.sendJava(new ClientboundUpdateAttributesPacket(this.player.javaEntityId(), List.of(
                new Attribute(AttributeType.Builtin.MAX_HEALTH, this.maxHealth),
                new Attribute(AttributeType.Builtin.MOVEMENT_SPEED, base, speedModifiers),
                new Attribute(AttributeType.Builtin.JUMP_STRENGTH, JAVA_JUMP_STRENGTH, jumpModifiers),
                new Attribute(AttributeType.Builtin.KNOCKBACK_RESISTANCE, this.knockbackResistance),
                new Attribute(AttributeType.Builtin.LUCK, this.luck),
                new Attribute(AttributeType.Builtin.ATTACK_SPEED, NO_ATTACK_COOLDOWN))));
    }

    /**
     * The Java player started/stopped sprinting: resync so the sprint modifier matches. Whether the
     * Bedrock value includes the boost comes from the server's SPRINTING flag (PowerNukkitX sends it
     * before the new speed), or from the proxy's own state when no actor data was seen yet.
     */
    void onJavaSprint() {
        if (this.player.isSpawned()) {
            sendAttributes();
        }
    }

    /** Input locks from {@code UpdateClientInputLocks}. */
    void setLocks(boolean movementLocked, boolean jumpLocked) {
        if (movementLocked != this.movementLocked || jumpLocked != this.jumpLocked) {
            this.movementLocked = movementLocked;
            this.jumpLocked = jumpLocked;
            sendAttributes();
        }
    }

    /** Total experience points of a level and progress (vanilla formula, same in both editions). */
    static int totalExperience(int level, float progress) {
        int base;
        int toNext;
        if (level <= 16) {
            base = level * level + 6 * level;
            toNext = 2 * level + 7;
        } else if (level <= 31) {
            base = (int) (2.5 * level * level - 40.5 * level + 360);
            toNext = 5 * level - 38;
        } else {
            base = (int) (4.5 * level * level - 162.5 * level + 2220);
            toNext = 9 * level - 158;
        }
        return base + Math.round(progress * toNext);
    }

    // ------------------------------------------------------------------ effects

    /** {@code MobEffect} for the local player. */
    void onEffect(MobEffectPacket packet) {
        Effect effect = EffectMappings.toJava(packet.getEffectID());
        if (effect == null || packet.getEvent() == null) {
            return;
        }
        switch (packet.getEvent()) {
            case ADD, MODIFY -> {
                int duration = packet.getEffectDurationTicks() < 0 ? -1 : packet.getEffectDurationTicks();
                this.effectTicks[effect.ordinal()] = duration == 0 ? 1 : duration;
                this.session.sendJava(new ClientboundUpdateMobEffectPacket(this.player.javaEntityId(), effect,
                        packet.getEffectAmplifier(), duration, packet.isAmbient(), packet.isShowParticles(), true,
                        effect == Effect.DARKNESS));
            }
            case REMOVE -> removeEffect(effect);
            default -> {
            }
        }
    }

    private void removeEffect(Effect effect) {
        this.effectTicks[effect.ordinal()] = 0;
        this.session.sendJava(new ClientboundRemoveMobEffectPacket(this.player.javaEntityId(), effect));
    }

    /** The server forgets effects on respawn and so does the new Java player. */
    void clearEffects() {
        Arrays.fill(this.effectTicks, 0);
    }

    /**
     * Counts effects down: Bedrock clients drop expired effects themselves, so some servers never
     * send their removal, while the Java client waits for it.
     */
    void tick() {
        int[] ticks = this.effectTicks;
        for (int i = 0; i < ticks.length; i++) {
            if (ticks[i] > 0 && --ticks[i] == 0) {
                removeEffect(Effect.VALUES[i]);
            }
        }
    }

    // ------------------------------------------------------------------ own entity data

    /**
     * {@code SetActorData} for the local player: fire, invisibility, air, freezing and sleeping become
     * Java own-entity data. The poses the Java client controls itself (sneaking, sprinting, swimming,
     * gliding) are sent as the proxy last saw them from the client, so they are never overridden.
     */
    void onActorData(ActorDataMap data) {
        List<EntityMetadata<?, ?>> changes = null;
        EnumSet<ActorFlags> flags = data.getFlags();
        if (flags != null) {
            this.onFire = flags.contains(ActorFlags.ON_FIRE);
            this.invisible = flags.contains(ActorFlags.INVISIBLE);
            this.serverSprinting = flags.contains(ActorFlags.SPRINTING);
            this.serverFlagsKnown = true;
            boolean nowSleeping = flags.contains(ActorFlags.SLEEPING);
            if (nowSleeping != this.sleeping) {
                this.sleeping = nowSleeping;
                Vector3i bed = data.get(ActorDataTypes.BED_POSITION);
                changes = new ArrayList<>(4);
                changes.add(new ObjectEntityMetadata<>(JAVA_POSE, MetadataTypes.POSE, nowSleeping ? Pose.SLEEPING : Pose.STANDING));
                changes.add(new ObjectEntityMetadata<>(JAVA_SLEEPING_POS, MetadataTypes.OPTIONAL_BLOCK_POS,
                        nowSleeping && bed != null ? Optional.of(bed) : Optional.empty()));
            }
        }
        int javaFlags = sharedFlags();
        if (javaFlags != this.sentFlags) {
            this.sentFlags = javaFlags;
            changes = add(changes, new ByteEntityMetadata(JAVA_SHARED_FLAGS, MetadataTypes.BYTE, (byte) javaFlags));
        }
        Short maxAir = data.get(ActorDataTypes.AIR_SUPPLY_MAX);
        if (maxAir != null && maxAir > 0) {
            this.airMax = maxAir;
        }
        Short bedrockAir = data.get(ActorDataTypes.AIR_SUPPLY);
        if (bedrockAir != null) {
            int javaAir = Math.max(-20, Math.min(JAVA_MAX_AIR, Math.round(bedrockAir * (float) JAVA_MAX_AIR / this.airMax)));
            if (javaAir != this.air) {
                this.air = javaAir;
                changes = add(changes, new IntEntityMetadata(JAVA_AIR, MetadataTypes.INT, javaAir));
            }
        }
        Float freezing = data.get(ActorDataTypes.FREEZING_EFFECT_STRENGTH);
        if (freezing != null) {
            int frozen = Math.round(Math.max(0, Math.min(1, freezing)) * JAVA_TICKS_TO_FREEZE);
            if (frozen != this.ticksFrozen) {
                this.ticksFrozen = frozen;
                changes = add(changes, new IntEntityMetadata(JAVA_TICKS_FROZEN, MetadataTypes.INT, frozen));
            }
        }
        if (changes != null) {
            this.session.sendJava(new ClientboundSetEntityDataPacket(this.player.javaEntityId(), changes.toArray(new EntityMetadata<?, ?>[0])));
        }
    }

    private int sharedFlags() {
        AuthInputBuilder input = this.player.input();
        int flags = 0;
        if (this.onFire) {
            flags |= 0x01;
        }
        if (input.sneaking()) {
            flags |= 0x02;
        }
        if (input.sprinting()) {
            flags |= 0x08;
        }
        if (input.swimming()) {
            flags |= 0x10;
        }
        if (this.invisible) {
            flags |= 0x20;
        }
        if (input.gliding()) {
            flags |= 0x80;
        }
        return flags;
    }

    private static List<EntityMetadata<?, ?>> add(List<EntityMetadata<?, ?>> list, EntityMetadata<?, ?> entry) {
        List<EntityMetadata<?, ?>> result = list == null ? new ArrayList<>(3) : list;
        result.add(entry);
        return result;
    }

    /** Resets what the Java client forgets on respawn, then resends it. */
    void onRespawn() {
        clearEffects();
        this.sentFlags = -1;
        this.air = JAVA_MAX_AIR;
        this.ticksFrozen = 0;
        this.sleeping = false;
        sendAttributes();
        sendExperience();
        if (this.absorption > 0) {
            this.session.sendJava(new ClientboundSetEntityDataPacket(this.player.javaEntityId(), new EntityMetadata<?, ?>[]{
                    new FloatEntityMetadata(JAVA_ABSORPTION, MetadataTypes.FLOAT, this.absorption)}));
        }
    }
}
