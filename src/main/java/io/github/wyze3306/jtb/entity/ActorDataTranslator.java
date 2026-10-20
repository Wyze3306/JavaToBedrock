package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.mapping.JavaRegistries;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.text.BedrockText;
import io.github.wyze3306.jtb.text.TextTranslator;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataMap;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataTypes;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorFlags;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Pose;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.SnifferState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.VillagerData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ColorParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.Particle;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ParticleType;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Bedrock entity data ({@code ActorDataMap} of {@code AddActor}, {@code AddPlayer},
 * {@code SetActorData}...) to Java 26.3 entity data.
 * <p>
 * Bedrock servers send either the full map (PowerNukkitX does on most changes) or only the changed
 * keys. The translator first stores what it needs in the {@link TrackedEntity}, then rewrites every
 * Java field depending on a key that was present; {@link MetadataWriter} drops the values the client
 * already has. Flag and variant ports follow ViaBedrock's {@code EntityMetadataRewriter} (GPL-3.0)
 * and the inverse of Geyser's entity setters (MIT).
 */
final class ActorDataTranslator {

    /** Java's smallest {@code minecraft:scale}. */
    static final float MIN_JAVA_SCALE = 0.0625f;
    /**
     * Bedrock entities scaled below this are invisible helpers (PowerNukkitX floating texts are armor
     * stands at scale 0.0001): Java shows them invisible, their name tag only.
     */
    static final float HIDDEN_SCALE = 0.05f;

    private static final int SHARED_ON_FIRE = 0x01;
    private static final int SHARED_CROUCHING = 0x02;
    private static final int SHARED_SPRINTING = 0x08;
    private static final int SHARED_SWIMMING = 0x10;
    private static final int SHARED_INVISIBLE = 0x20;
    private static final int SHARED_FALL_FLYING = 0x80;

    private static final String[] WOLF_VARIANTS = {"pale", "ashen", "black", "chestnut", "rusty", "snowy", "spotted",
            "striped", "woods"};
    private static final String[] CAT_VARIANTS = {"white", "black", "red", "siamese", "british_shorthair", "calico",
            "persian", "ragdoll", "tabby", "all_black", "jellie"};
    private static final String[] FROG_VARIANTS = {"temperate", "cold", "warm"};
    /** Bedrock axolotl variant -> Java (lucy, cyan, gold, wild, blue -> lucy 0, wild 1, gold 2, cyan 3, blue 4). */
    private static final int[] AXOLOTL_VARIANTS = {0, 3, 2, 1, 4};
    /** Bedrock villager profession -> Java villager_profession id (inverse of Geyser's VillagerEntity table). */
    private static final int[] VILLAGER_PROFESSIONS = {0, 5, 6, 12, 7, 9, 3, 4, 1, 14, 13, 2, 8, 10, 11};
    /** Bedrock villager region (MARK_VARIANT) -> Java villager_type id (desert 0, jungle 1, plains 2, ...). */
    private static final int[] VILLAGER_TYPES = {2, 0, 1, 3, 4, 5, 6};

    private static final String SHIELD = "minecraft:shield";
    private static final Optional<Component> NO_NAME = Optional.empty();
    private static final Optional<UUID> NO_UUID = Optional.empty();
    private static final Optional<Vector3i> NO_POS = Optional.empty();
    private static final List<Particle> NO_PARTICLES = List.of();

    private static int[] wolfVariants;
    private static int[] catVariants;
    private static int[] frogVariants;

    private ActorDataTranslator() {
    }

    /** Ids of the vanilla variant registries, resolved once the Java registries are loaded. */
    private static void resolveRegistries() {
        if (wolfVariants == null) {
            wolfVariants = registryIds("minecraft:wolf_variant", WOLF_VARIANTS);
            catVariants = registryIds("minecraft:cat_variant", CAT_VARIANTS);
            frogVariants = registryIds("minecraft:frog_variant", FROG_VARIANTS);
        }
    }

    private static int[] registryIds(String registry, String[] names) {
        JavaRegistries.Registry entries = JavaRegistries.registry(registry);
        int[] ids = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            ids[i] = Math.max(0, entries.id("minecraft:" + names[i]));
        }
        return ids;
    }

    private static int variantId(int[] ids, int variant) {
        return ids[variant >= 0 && variant < ids.length ? variant : 0];
    }

    /**
     * Applies {@code data} to {@code entity} and writes the changed Java fields to {@code out} (already
     * {@link MetadataWriter#begin begun} for this entity). Side packets (leash, scale attribute, saddle)
     * are sent directly.
     */
    static void translate(ProxySession session, EntityTracker tracker, TrackedEntity entity, ActorDataMap data,
                          MetadataWriter out) {
        resolveRegistries();
        // 1. Remember the Bedrock state.
        EnumSet<ActorFlags> flags = data.getFlags();
        boolean flagsChanged = flags != null;
        boolean wasSaddled = entity.flags.contains(ActorFlags.SADDLED);
        if (flagsChanged) {
            entity.flags.clear();
            entity.flags.addAll(flags);
        }
        String name = data.get(ActorDataTypes.NAME);
        boolean nameChanged = name != null && !name.equals(entity.nameTag);
        if (nameChanged) {
            entity.nameTag = name;
            entity.nameComponent = null;
        }
        Byte alwaysShow = data.get(ActorDataTypes.NAMETAG_ALWAYS_SHOW);
        if (alwaysShow != null) {
            nameChanged |= entity.nameTagAlwaysShow != (alwaysShow == 1);
            entity.nameTagAlwaysShow = alwaysShow == 1;
        }
        Float scale = data.get(ActorDataTypes.SCALE);
        boolean scaleChanged = scale != null && scale != entity.scale;
        if (scaleChanged) {
            entity.scale = scale;
        }
        Float width = data.get(ActorDataTypes.WIDTH);
        Float height = data.get(ActorDataTypes.HEIGHT);
        boolean sizeChanged = false;
        if (width != null && width != entity.bedrockWidth) {
            entity.bedrockWidth = width;
            sizeChanged = true;
        }
        if (height != null && height != entity.bedrockHeight) {
            entity.bedrockHeight = height;
            sizeChanged = true;
        }
        boolean variantChanged = false;
        Integer variant = data.get(ActorDataTypes.VARIANT);
        if (variant != null) {
            variantChanged = true;
            entity.variant = variant;
        }
        Integer markVariant = data.get(ActorDataTypes.MARK_VARIANT);
        if (markVariant != null) {
            variantChanged = true;
            entity.markVariant = markVariant;
        }
        Byte color = data.get(ActorDataTypes.COLOR_INDEX);
        if (color != null) {
            variantChanged = true;
            entity.color = color;
        }
        Byte color2 = data.get(ActorDataTypes.COLOR_2_INDEX);
        if (color2 != null) {
            variantChanged = true;
            entity.color2 = color2;
        }
        Integer tradeTier = data.get(ActorDataTypes.TRADE_TIER);
        if (tradeTier != null) {
            variantChanged = true;
            entity.tradeTier = tradeTier;
        }
        Long owner = data.get(ActorDataTypes.OWNER);
        if (owner != null) {
            entity.ownerUniqueId = owner;
        }
        Vector3i bed = data.get(ActorDataTypes.BED_POSITION);
        if (bed != null) {
            entity.bedPosition = bed;
        }

        // 2. Java fields.
        if (flagsChanged || nameChanged || scaleChanged) {
            writeBase(session, entity, out);
        }
        Short air = data.get(ActorDataTypes.AIR_SUPPLY);
        if (air != null) {
            out.putInt(MetaField.AIR_SUPPLY, air);
        }
        Float freezing = data.get(ActorDataTypes.FREEZING_EFFECT_STRENGTH);
        if (freezing != null) {
            out.putInt(MetaField.TICKS_FROZEN, Math.round(Math.max(0, Math.min(1, freezing)) * 140));
        }
        if (entity.definition().isLiving()) {
            writeLiving(entity, data, flagsChanged, bed != null, out);
        }
        if (flagsChanged && out.has(MetaField.MOB_FLAGS)) {
            out.putByte(MetaField.MOB_FLAGS, entity.flags.contains(ActorFlags.NO_AI) ? 0x01 : 0);
        }
        if (flagsChanged && out.has(MetaField.BABY)) {
            out.putBoolean(MetaField.BABY, entity.flags.contains(ActorFlags.BABY));
        }
        if (owner != null && out.has(MetaField.OWNERUUID)) {
            UUID ownerUuid = owner == -1 || owner == 0 ? null : tracker.javaUuidOfUnique(owner);
            out.putObject(MetaField.OWNERUUID, MetadataTypes.OPTIONAL_LIVING_ENTITY_REFERENCE,
                    ownerUuid == null ? NO_UUID : Optional.of(ownerUuid));
        }
        writeType(session, tracker, entity, data, flagsChanged, variantChanged, out);

        // 3. Side effects outside the entity data.
        Long leash = data.get(ActorDataTypes.LEASH_HOLDER);
        if (leash != null && leash != entity.leashHolder) {
            entity.leashHolder = leash;
            tracker.sendLeash(entity);
        }
        if ((scaleChanged || flagsChanged) && entity.definition().isLiving()) {
            tracker.sendScale(entity);
        }
        if (flagsChanged && wasSaddled != entity.flags.contains(ActorFlags.SADDLED)) {
            tracker.sendSaddle(entity);
        }
        if (entity.kind() == TrackedEntity.Kind.PLAYER && (nameChanged || flagsChanged)) {
            tracker.nametags().update(entity);
        }
        if (entity.kind() == TrackedEntity.Kind.CUSTOM && (nameChanged || scaleChanged || sizeChanged || flagsChanged)) {
            tracker.customRenderer().update(session, tracker, entity);
        }
    }

    // ------------------------------------------------------------------ Entity

    private static void writeBase(ProxySession session, TrackedEntity entity, MetadataWriter out) {
        EnumSet<ActorFlags> flags = entity.flags;
        boolean hidden = entity.scale < HIDDEN_SCALE;
        int shared = 0;
        if (flags.contains(ActorFlags.ON_FIRE)) {
            shared |= SHARED_ON_FIRE;
        }
        if (flags.contains(ActorFlags.SNEAKING)) {
            shared |= SHARED_CROUCHING;
        }
        if (flags.contains(ActorFlags.SPRINTING)) {
            shared |= SHARED_SPRINTING;
        }
        if (flags.contains(ActorFlags.SWIMMING)) {
            shared |= SHARED_SWIMMING;
        }
        if (hidden || flags.contains(ActorFlags.INVISIBLE)) {
            shared |= SHARED_INVISIBLE;
        }
        if (flags.contains(ActorFlags.GLIDING)) {
            shared |= SHARED_FALL_FLYING;
        }
        out.putByte(MetaField.SHARED_FLAGS, shared);
        out.putBoolean(MetaField.SILENT, flags.contains(ActorFlags.SILENT));
        // PowerNukkitX only sets HAS_GRAVITY on some entities; Java simulates gravity client-side for
        // non-living entities only, so living ones never get NO_GRAVITY from a missing flag.
        out.putBoolean(MetaField.NO_GRAVITY, !entity.definition().isLiving() && !flags.contains(ActorFlags.HAS_GRAVITY));
        if (entity.kind() != TrackedEntity.Kind.LOCAL_PLAYER) {
            out.putObject(MetaField.POSE, MetadataTypes.POSE, pose(entity));
        }
        if (!entity.isPlayer() && entity.kind() != TrackedEntity.Kind.CUSTOM) {
            boolean shown = !entity.nameTag.isEmpty() && (flags.contains(ActorFlags.CAN_SHOW_NAME)
                    || flags.contains(ActorFlags.ALWAYS_SHOW_NAME) || entity.nameTagAlwaysShow);
            out.putObject(MetaField.CUSTOM_NAME, MetadataTypes.OPTIONAL_COMPONENT,
                    shown ? Optional.of(nameComponent(session, entity)) : NO_NAME);
            out.putBoolean(MetaField.CUSTOM_NAME_VISIBLE, shown
                    && (flags.contains(ActorFlags.ALWAYS_SHOW_NAME) || entity.nameTagAlwaysShow));
        }
    }

    /** Java component of the entity's Bedrock name tag (cached until the name changes). */
    static Component nameComponent(ProxySession session, TrackedEntity entity) {
        Component component = entity.nameComponent;
        if (component == null) {
            String text = entity.nameTag;
            component = text.indexOf('%') >= 0 ? TextTranslator.translate(session, text) : BedrockText.legacyToComponent(text);
            entity.nameComponent = component;
        }
        return component;
    }

    static Pose pose(TrackedEntity entity) {
        EnumSet<ActorFlags> flags = entity.flags;
        if (entity.dead) {
            return Pose.DYING;
        }
        if (flags.contains(ActorFlags.SLEEPING)) {
            return Pose.SLEEPING;
        }
        if (flags.contains(ActorFlags.GLIDING)) {
            return Pose.FALL_FLYING;
        }
        if (flags.contains(ActorFlags.DAMAGE_NEARBY_MOBS)) {
            return Pose.SPIN_ATTACK;
        }
        if (flags.contains(ActorFlags.SWIMMING) || flags.contains(ActorFlags.CRAWLING)) {
            return Pose.SWIMMING;
        }
        EntityType type = entity.javaType();
        switch (type) {
            case WARDEN -> {
                if (flags.contains(ActorFlags.EMERGING)) {
                    return Pose.EMERGING;
                }
                if (flags.contains(ActorFlags.DIGGING)) {
                    return Pose.DIGGING;
                }
                if (flags.contains(ActorFlags.ROARING)) {
                    return Pose.ROARING;
                }
                if (flags.contains(ActorFlags.SNIFFING)) {
                    return Pose.SNIFFING;
                }
            }
            case FROG -> {
                if (flags.contains(ActorFlags.CROAKING)) {
                    return Pose.CROAKING;
                }
                if (flags.contains(ActorFlags.EAT_MOB)) {
                    return Pose.USING_TONGUE;
                }
                if (flags.contains(ActorFlags.JUMP_GOAL_JUMP)) {
                    return Pose.LONG_JUMPING;
                }
            }
            case GOAT -> {
                if (flags.contains(ActorFlags.JUMP_GOAL_JUMP)) {
                    return Pose.LONG_JUMPING;
                }
            }
            case CAMEL, CAMEL_HUSK -> {
                if (flags.contains(ActorFlags.SITTING)) {
                    return Pose.SITTING;
                }
            }
            default -> {
            }
        }
        if (flags.contains(ActorFlags.SNEAKING) && entity.definition().isPlayerLike()) {
            return Pose.SNEAKING;
        }
        return Pose.STANDING;
    }

    // ------------------------------------------------------------------ LivingEntity

    @SuppressWarnings("deprecation") // EFFECT_AMBIENCE is still sent by PowerNukkitX
    private static void writeLiving(TrackedEntity entity, ActorDataMap data, boolean flagsChanged, boolean bedChanged,
                                    MetadataWriter out) {
        EnumSet<ActorFlags> flags = entity.flags;
        if (flagsChanged) {
            int living = 0;
            if (flags.contains(ActorFlags.USING_ITEM) || flags.contains(ActorFlags.BLOCKING)) {
                living |= 0x01;
                if (flags.contains(ActorFlags.BLOCKING) && SHIELD.equals(entity.offHandItem) && !SHIELD.equals(entity.mainHandItem)) {
                    living |= 0x02;
                }
            }
            if (flags.contains(ActorFlags.DAMAGE_NEARBY_MOBS)) {
                living |= 0x04;
            }
            out.putByte(MetaField.LIVING_ENTITY_FLAGS, living);
        }
        if (flagsChanged || bedChanged) {
            Vector3i bed = entity.bedPosition;
            boolean sleeping = flags.contains(ActorFlags.SLEEPING) && bed != null;
            out.putObject(MetaField.SLEEPING_POS, MetadataTypes.OPTIONAL_BLOCK_POS, sleeping ? Optional.of(bed) : NO_POS);
        }
        Integer effectColor = data.get(ActorDataTypes.EFFECT_COLOR);
        Byte ambience = data.get(ActorDataTypes.EFFECT_AMBIENCE);
        if (effectColor != null || ambience != null) {
            if (effectColor != null) {
                entity.effectColor = effectColor;
            }
            if (ambience != null) {
                entity.effectAmbient = ambience != 0;
            }
            int rgb = entity.effectColor & 0xFFFFFF;
            out.putObject(MetaField.EFFECT_PARTICLES, MetadataTypes.PARTICLES, rgb == 0 || entity.effectColor == -1
                    ? NO_PARTICLES
                    : List.of(new Particle(ParticleType.ENTITY_EFFECT,
                    new ColorParticleData((entity.effectAmbient ? 0x26000000 : 0xFF000000) | rgb))));
            out.putBoolean(MetaField.EFFECT_AMBIENCE, entity.effectAmbient);
        }
    }

    // ------------------------------------------------------------------ per type

    @SuppressWarnings("deprecation") // CARRY_BLOCK_RUNTIME_ID is still sent by PowerNukkitX
    private static void writeType(ProxySession session, EntityTracker tracker, TrackedEntity entity, ActorDataMap data,
                                  boolean flagsChanged, boolean variantChanged, MetadataWriter out) {
        EnumSet<ActorFlags> flags = entity.flags;
        EntityType type = entity.javaType();
        switch (type) {
            case SHEEP -> {
                if (flagsChanged || variantChanged) {
                    out.putByte(MetaField.WOOL, (entity.color & 0x0F) | (flags.contains(ActorFlags.SHEARED) ? 0x10 : 0));
                }
            }
            case WOLF -> {
                if (flagsChanged) {
                    writeTamable(entity, out);
                    out.putBoolean(MetaField.INTERESTED, flags.contains(ActorFlags.INTERESTED));
                    out.putLong(MetaField.ANGER_END_TIME, flags.contains(ActorFlags.ANGRY) ? Long.MAX_VALUE : -1L);
                }
                if (variantChanged) {
                    out.putInt(MetaField.VARIANT, MetadataTypes.WOLF_VARIANT, variantId(wolfVariants, entity.variant));
                    out.putInt(MetaField.COLLAR_COLOR, entity.color & 0x0F);
                }
            }
            case CAT -> {
                if (flagsChanged) {
                    writeTamable(entity, out);
                    out.putBoolean(MetaField.IS_LYING, flags.contains(ActorFlags.RESTING));
                }
                if (variantChanged) {
                    out.putInt(MetaField.VARIANT, MetadataTypes.CAT_VARIANT, variantId(catVariants, entity.variant));
                    out.putInt(MetaField.COLLAR_COLOR, entity.color & 0x0F);
                }
            }
            case PARROT -> {
                if (flagsChanged) {
                    writeTamable(entity, out);
                }
                if (variantChanged) {
                    out.putInt(MetaField.VARIANT, Math.max(0, Math.min(4, entity.variant)));
                }
            }
            case NAUTILUS, ZOMBIE_NAUTILUS -> {
                if (flagsChanged) {
                    writeTamable(entity, out);
                }
            }
            case HORSE, SKELETON_HORSE, ZOMBIE_HORSE, DONKEY, MULE, LLAMA, TRADER_LLAMA, CAMEL, CAMEL_HUSK -> {
                if (flagsChanged) {
                    int horse = 0;
                    if (flags.contains(ActorFlags.TAMED)) {
                        horse |= 0x02;
                    }
                    if (flags.contains(ActorFlags.EATING)) {
                        horse |= 0x10;
                    }
                    if (flags.contains(ActorFlags.STANDING)) {
                        horse |= 0x20;
                    }
                    out.putByte(MetaField.FLAGS, horse);
                    out.putBoolean(MetaField.CHEST, flags.contains(ActorFlags.CHESTED));
                }
                if (variantChanged && type == EntityType.HORSE) {
                    out.putInt(MetaField.TYPE_VARIANT, (entity.variant & 0xFF) | ((entity.markVariant % 5) << 8));
                }
                if (variantChanged && (type == EntityType.LLAMA || type == EntityType.TRADER_LLAMA)) {
                    out.putInt(MetaField.VARIANT, Math.max(0, Math.min(3, entity.variant)));
                }
                Integer strength = data.get(ActorDataTypes.STRENGTH);
                if (strength != null) {
                    out.putInt(MetaField.STRENGTH, strength);
                }
            }
            case FOX -> {
                if (variantChanged) {
                    out.putInt(MetaField.TYPE, entity.variant == 1 ? 1 : 0);
                }
                if (flagsChanged) {
                    int fox = 0;
                    if (flags.contains(ActorFlags.SITTING)) {
                        fox |= 0x01;
                    }
                    if (flags.contains(ActorFlags.SNEAKING)) {
                        fox |= 0x04;
                    }
                    if (flags.contains(ActorFlags.INTERESTED)) {
                        fox |= 0x08;
                    }
                    if (flags.contains(ActorFlags.SLEEPING)) {
                        fox |= 0x20;
                    }
                    out.putByte(MetaField.FLAGS, fox);
                }
            }
            case PANDA -> {
                if (variantChanged) {
                    out.putByte(MetaField.MAIN_GENE, Math.max(0, Math.min(6, entity.variant)));
                    out.putByte(MetaField.HIDDEN_GENE, Math.max(0, Math.min(6, entity.variant)));
                }
                if (flagsChanged) {
                    int panda = 0;
                    if (flags.contains(ActorFlags.SNEEZING)) {
                        panda |= 0x02;
                    }
                    if (flags.contains(ActorFlags.ROLLING)) {
                        panda |= 0x04;
                    }
                    if (flags.contains(ActorFlags.SITTING)) {
                        panda |= 0x08;
                    }
                    if (flags.contains(ActorFlags.LAYING_DOWN)) {
                        panda |= 0x10;
                    }
                    out.putByte(MetaField.FLAGS, panda);
                }
                Integer eating = data.get(ActorDataTypes.EATING_COUNTER);
                if (eating != null) {
                    out.putInt(MetaField.EAT_COUNTER, eating);
                }
            }
            case RABBIT, MOOSHROOM -> {
                if (variantChanged) {
                    out.putInt(MetaField.TYPE, Math.max(0, entity.variant));
                }
            }
            case OCELOT -> {
                if (flagsChanged) {
                    out.putBoolean(MetaField.TRUSTING, flags.contains(ActorFlags.TRUSTING));
                }
            }
            case POLAR_BEAR -> {
                if (flagsChanged) {
                    out.putBoolean(MetaField.STANDING, flags.contains(ActorFlags.STANDING));
                }
            }
            case BEE -> {
                if (flagsChanged) {
                    entity.beeFlags = (entity.beeFlags & ~0x02) | (flags.contains(ActorFlags.ANGRY) ? 0x02 : 0);
                    out.putByte(MetaField.FLAGS, entity.beeFlags);
                    out.putLong(MetaField.ANGER_END_TIME, flags.contains(ActorFlags.ANGRY) ? Long.MAX_VALUE : -1L);
                }
            }
            case GOAT -> {
                Integer horns = data.get(ActorDataTypes.GOAT_HORN_COUNT);
                if (horns != null) {
                    // Bedrock goats always lose the right horn first.
                    out.putBoolean(MetaField.HAS_LEFT_HORN, horns > 0);
                    out.putBoolean(MetaField.HAS_RIGHT_HORN, horns > 1);
                }
                if (variantChanged) {
                    out.putBoolean(MetaField.IS_SCREAMING_GOAT, entity.variant == 1);
                }
            }
            case AXOLOTL -> {
                if (variantChanged) {
                    out.putInt(MetaField.VARIANT, AXOLOTL_VARIANTS[entity.variant >= 0 && entity.variant < 5 ? entity.variant : 0]);
                }
                if (flagsChanged) {
                    out.putBoolean(MetaField.PLAYING_DEAD, flags.contains(ActorFlags.PLAYING_DEAD));
                }
            }
            case FROG -> {
                if (variantChanged) {
                    out.putInt(MetaField.VARIANT, MetadataTypes.FROG_VARIANT, variantId(frogVariants, entity.variant));
                }
            }
            case TURTLE -> {
                if (flagsChanged) {
                    out.putBoolean(MetaField.HAS_EGG, flags.contains(ActorFlags.IS_PREGNANT));
                    out.putBoolean(MetaField.LAYING_EGG, flags.contains(ActorFlags.LAYING_EGG));
                }
            }
            case STRIDER -> {
                if (flagsChanged) {
                    out.putBoolean(MetaField.SUFFOCATING, flags.contains(ActorFlags.SHAKING));
                }
            }
            case SNIFFER -> {
                if (flagsChanged) {
                    out.putObject(MetaField.STATE, MetadataTypes.SNIFFER_STATE, snifferState(flags));
                }
            }
            case VILLAGER, ZOMBIE_VILLAGER -> {
                if (variantChanged) {
                    int profession = entity.variant >= 0 && entity.variant < VILLAGER_PROFESSIONS.length
                            ? VILLAGER_PROFESSIONS[entity.variant] : 0;
                    int region = entity.markVariant >= 0 && entity.markVariant < VILLAGER_TYPES.length
                            ? VILLAGER_TYPES[entity.markVariant] : 2;
                    int level = Math.max(1, Math.min(5, entity.tradeTier + 1));
                    out.putObject(MetaField.VILLAGER_DATA, MetadataTypes.VILLAGER_DATA, new VillagerData(region, profession, level));
                }
                if (flagsChanged && type == EntityType.ZOMBIE_VILLAGER) {
                    out.putBoolean(MetaField.CONVERTING, flags.contains(ActorFlags.CONVERTING));
                }
            }
            case CREEPER -> {
                if (flagsChanged) {
                    boolean ignited = flags.contains(ActorFlags.IGNITED);
                    out.putInt(MetaField.SWELL_DIR, ignited ? 1 : -1);
                    out.putBoolean(MetaField.IS_POWERED, flags.contains(ActorFlags.POWERED));
                    out.putBoolean(MetaField.IS_IGNITED, ignited);
                }
            }
            case ENDERMAN -> {
                BlockDefinition carried = data.get(ActorDataTypes.CARRY_BLOCK_RUNTIME_ID);
                if (carried != null) {
                    int state = session.get(BlockMapper.KEY).toJava(carried.getRuntimeId());
                    out.putInt(MetaField.CARRY_STATE, MetadataTypes.OPTIONAL_BLOCK_STATE, state);
                }
                if (flagsChanged) {
                    out.putBoolean(MetaField.CREEPY, flags.contains(ActorFlags.ANGRY));
                }
            }
            case GHAST -> {
                if (flagsChanged) {
                    out.putBoolean(MetaField.IS_CHARGING, flags.contains(ActorFlags.CHARGED));
                }
            }
            case BLAZE -> {
                if (flagsChanged) {
                    out.putByte(MetaField.FLAGS, flags.contains(ActorFlags.CHARGED) ? 0x01 : 0);
                }
            }
            case SPIDER, CAVE_SPIDER -> {
                if (flagsChanged) {
                    out.putByte(MetaField.FLAGS, flags.contains(ActorFlags.WALL_CLIMBING) ? 0x01 : 0);
                }
            }
            case BAT -> {
                if (flagsChanged) {
                    out.putByte(MetaField.FLAGS, flags.contains(ActorFlags.RESTING) ? 0x01 : 0);
                }
            }
            case SNOW_GOLEM -> {
                if (flagsChanged) {
                    out.putByte(MetaField.PUMPKIN, flags.contains(ActorFlags.SHEARED) ? 0 : 0x10);
                }
            }
            case BOGGED -> {
                if (flagsChanged) {
                    out.putBoolean(MetaField.SHEARED, flags.contains(ActorFlags.SHEARED));
                }
            }
            case ALLAY -> {
                if (flagsChanged) {
                    out.putBoolean(MetaField.DANCING, flags.contains(ActorFlags.DANCING));
                }
            }
            case PIGLIN -> {
                if (flagsChanged) {
                    out.putBoolean(MetaField.IS_DANCING, flags.contains(ActorFlags.DANCING));
                    out.putBoolean(MetaField.IS_CHARGING_CROSSBOW, flags.contains(ActorFlags.CHARGING));
                }
            }
            case PILLAGER -> {
                if (flagsChanged) {
                    out.putBoolean(MetaField.IS_CHARGING_CROSSBOW, flags.contains(ActorFlags.CHARGING));
                }
            }
            case EVOKER, ILLUSIONER -> {
                if (flagsChanged) {
                    out.putByte(MetaField.SPELL_CASTING, flags.contains(ActorFlags.CASTING) ? 2 : 0);
                }
            }
            case WITCH -> {
                if (flagsChanged) {
                    out.putBoolean(MetaField.USING_ITEM, flags.contains(ActorFlags.USING_ITEM));
                }
            }
            case SHULKER -> {
                Integer face = data.get(ActorDataTypes.ATTACH_FACE);
                if (face != null) {
                    out.putObject(MetaField.ATTACH_FACE, MetadataTypes.DIRECTION, Direction.from(face >= 0 && face < 6 ? face : 0));
                }
                Integer peek = data.get(ActorDataTypes.PEEK_ID);
                if (peek != null) {
                    out.putByte(MetaField.PEEK, Math.max(0, Math.min(100, peek)));
                }
                if (variantChanged) {
                    int v = entity.variant;
                    out.putByte(MetaField.COLOR, v < 0 || v >= 16 ? 16 : 15 - v);
                }
            }
            case SLIME, MAGMA_CUBE -> {
                if (variantChanged && entity.variant > 0) {
                    out.putInt(MetaField.SIZE, entity.variant);
                }
            }
            case WITHER -> {
                writeTarget(tracker, data.get(ActorDataTypes.TARGET_A), MetaField.TARGET_A, out);
                writeTarget(tracker, data.get(ActorDataTypes.TARGET_B), MetaField.TARGET_B, out);
                writeTarget(tracker, data.get(ActorDataTypes.TARGET_C), MetaField.TARGET_C, out);
                Integer invulnerable = data.get(ActorDataTypes.INV);
                if (invulnerable != null) {
                    out.putInt(MetaField.INV, invulnerable);
                }
            }
            case GUARDIAN, ELDER_GUARDIAN -> writeTarget(tracker, data.get(ActorDataTypes.TARGET), MetaField.ATTACK_TARGET, out);
            case PUFFERFISH -> {
                Byte puffed = data.get(ActorDataTypes.PUFFED_STATE);
                if (puffed != null) {
                    out.putInt(MetaField.PUFF_STATE, Math.max(0, Math.min(2, puffed)));
                }
            }
            case TROPICAL_FISH -> {
                if (variantChanged) {
                    int packed = Math.min(entity.variant & 0xFF, 1) | Math.min(entity.markVariant & 0xFF, 5) << 8
                            | (entity.color & 0x0F) << 16 | (entity.color2 & 0x0F) << 24;
                    out.putInt(MetaField.TYPE_VARIANT, packed);
                }
            }
            case ARMOR_STAND -> writeArmorStand(entity, data, flagsChanged, out);
            case END_CRYSTAL -> {
                if (flagsChanged) {
                    out.putBoolean(MetaField.SHOW_BOTTOM, flags.contains(ActorFlags.SHOW_BOTTOM));
                }
                Vector3i target = data.get(ActorDataTypes.BLOCK_TARGET);
                if (target != null) {
                    out.putObject(MetaField.BEAM_TARGET, MetadataTypes.OPTIONAL_BLOCK_POS,
                            target.equals(Vector3i.ZERO) ? NO_POS : Optional.of(target));
                }
            }
            case ARROW, SPECTRAL_ARROW, TRIDENT -> {
                if (flagsChanged) {
                    out.putByte(MetaField.FLAGS, flags.contains(ActorFlags.CRITICAL) ? 0x01 : 0);
                    if (type == EntityType.TRIDENT) {
                        out.putBoolean(MetaField.FOIL, flags.contains(ActorFlags.ENCHANTED));
                    }
                }
                Integer effectColor = data.get(ActorDataTypes.EFFECT_COLOR);
                if (effectColor != null && type == EntityType.ARROW) {
                    out.putInt(MetaField.EFFECT_COLOR, effectColor == 0 ? -1 : effectColor & 0xFFFFFF);
                }
            }
            case TNT -> {
                Integer fuse = data.get(ActorDataTypes.FUSE_TIME);
                if (fuse != null) {
                    out.putInt(MetaField.FUSE, fuse);
                }
            }
            case EXPERIENCE_ORB -> {
                Integer value = data.get(ActorDataTypes.VALUE);
                if (value != null) {
                    out.putInt(MetaField.VALUE, value);
                }
            }
            case AREA_EFFECT_CLOUD -> {
                Float radius = data.get(ActorDataTypes.DATA_RADIUS);
                if (radius != null) {
                    out.putFloat(MetaField.RADIUS, radius);
                }
                Integer waiting = data.get(ActorDataTypes.DATA_WAITING);
                if (waiting != null) {
                    out.putBoolean(MetaField.WAITING, waiting != 0);
                }
                Integer cloudColor = data.get(ActorDataTypes.EFFECT_COLOR);
                if (cloudColor != null) {
                    out.putObject(MetaField.PARTICLE, MetadataTypes.PARTICLE, new Particle(ParticleType.ENTITY_EFFECT,
                            new ColorParticleData(0xFF000000 | (cloudColor & 0xFFFFFF))));
                }
            }
            case FIREWORK_ROCKET -> {
                NbtMap firework = data.get(ActorDataTypes.DISPLAY_FIREWORK);
                if (firework != null) {
                    var stack = EntityItems.fireworkRocket(session, firework);
                    if (stack != null) {
                        out.putObject(MetaField.FIREWORKS_ITEM, MetadataTypes.ITEM_STACK, stack);
                    }
                }
            }
            case MINECART, CHEST_MINECART, COMMAND_BLOCK_MINECART, FURNACE_MINECART, HOPPER_MINECART, SPAWNER_MINECART,
                 TNT_MINECART -> writeMinecart(session, data, out);
            default -> {
                if (EntityTracker.isBoat(type)) {
                    writeBoat(data, out);
                }
            }
        }
    }

    private static void writeTamable(TrackedEntity entity, MetadataWriter out) {
        int tamable = 0;
        if (entity.flags.contains(ActorFlags.SITTING)) {
            tamable |= 0x01;
        }
        if (entity.flags.contains(ActorFlags.TAMED)) {
            tamable |= 0x04;
        }
        out.putByte(MetaField.FLAGS, tamable);
    }

    private static void writeTarget(EntityTracker tracker, @Nullable Long uniqueId, MetaField field, MetadataWriter out) {
        if (uniqueId == null) {
            return;
        }
        TrackedEntity target = uniqueId == 0 || uniqueId == -1 ? null : tracker.entityByUniqueId(uniqueId);
        out.putInt(field, target == null ? 0 : target.javaId());
    }

    @SuppressWarnings("deprecation") // the sniffer flags are deprecated upstream but still in the 1.26.50 table
    private static SnifferState snifferState(EnumSet<ActorFlags> flags) {
        if (flags.contains(ActorFlags.FEELING_HAPPY)) {
            return SnifferState.FEELING_HAPPY;
        }
        if (flags.contains(ActorFlags.SCENTING)) {
            return SnifferState.SCENTING;
        }
        if (flags.contains(ActorFlags.SNIFFING)) {
            return SnifferState.SNIFFING;
        }
        if (flags.contains(ActorFlags.SEARCHING)) {
            return SnifferState.SEARCHING;
        }
        if (flags.contains(ActorFlags.DIGGING)) {
            return SnifferState.DIGGING;
        }
        if (flags.contains(ActorFlags.RISING)) {
            return SnifferState.RISING;
        }
        return SnifferState.IDLING;
    }

    private static void writeMinecart(ProxySession session, ActorDataMap data, MetadataWriter out) {
        writeVehicleHurt(data, out);
        BlockDefinition display = data.get(ActorDataTypes.DISPLAY_TILE_RUNTIME_ID);
        Byte custom = data.get(ActorDataTypes.CUSTOM_DISPLAY);
        if (display != null || custom != null) {
            BlockMapper blocks = session.get(BlockMapper.KEY);
            boolean shown = display != null && display.getRuntimeId() != blocks.bedrockAir() && (custom == null || custom != 0);
            out.putInt(MetaField.CUSTOM_DISPLAY_BLOCK, MetadataTypes.OPTIONAL_BLOCK_STATE,
                    shown ? blocks.toJava(display.getRuntimeId()) : 0);
        }
        Integer offset = data.get(ActorDataTypes.DISPLAY_OFFSET);
        if (offset != null) {
            out.putInt(MetaField.DISPLAY_OFFSET, offset);
        }
        String command = data.get(ActorDataTypes.COMMAND_NAME);
        if (command != null) {
            out.putObject(MetaField.COMMAND_NAME, MetadataTypes.STRING, command);
        }
        String output = data.get(ActorDataTypes.LAST_COMMAND_OUTPUT);
        if (output != null) {
            out.putObject(MetaField.LAST_OUTPUT, MetadataTypes.COMPONENT, BedrockText.legacyToComponent(output));
        }
    }

    private static void writeBoat(ActorDataMap data, MetadataWriter out) {
        writeVehicleHurt(data, out);
        Float left = data.get(ActorDataTypes.ROW_TIME_LEFT);
        if (left != null) {
            out.putBoolean(MetaField.PADDLE_LEFT, left != 0);
        }
        Float right = data.get(ActorDataTypes.ROW_TIME_RIGHT);
        if (right != null) {
            out.putBoolean(MetaField.PADDLE_RIGHT, right != 0);
        }
        Integer bubble = data.get(ActorDataTypes.BUBBLE_TIME);
        if (bubble != null) {
            out.putInt(MetaField.BUBBLE_TIME, bubble);
        }
    }

    private static void writeVehicleHurt(ActorDataMap data, MetadataWriter out) {
        Integer hurt = data.get(ActorDataTypes.HURT);
        if (hurt != null) {
            out.putInt(MetaField.HURT, hurt);
            // Java rocks a hurt vehicle by its accumulated damage; Bedrock only sends the hurt time.
            out.putFloat(MetaField.DAMAGE, hurt > 0 ? 10f : 0f);
        }
        Integer direction = data.get(ActorDataTypes.HURT_DIR);
        if (direction != null) {
            out.putInt(MetaField.HURTDIR, direction);
        }
    }

    // ------------------------------------------------------------------ armor stand

    /**
     * Bedrock armor stand poses ({@code POSE_INDEX} 0-12) as Java head, body, left arm, right arm, left
     * leg and right leg rotations (degrees x, y, z). Same table as ViaBedrock (from lpsmods' armor stand
     * pose datapack).
     */
    private static final float[][][] ARMOR_STAND_POSES = {
            {{0, 0, 0}, {0, 0, 0}, {-10, 0, -10}, {-15, 0, 10}, {-1, 0, -1}, {1, 0, 1}}, // default
            {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}}, // none
            {{15, 0, 0}, {0, 0, 2}, {-30, 15, 15}, {-60, -20, -10}, {-1, 0, -1}, {1, 0, 1}}, // solemn
            {{-5, 0, 0}, {0, 0, 2}, {10, 0, -5}, {-60, 20, -10}, {-3, -3, -3}, {3, 3, 3}}, // athena
            {{-15, 0, 0}, {0, 0, -2}, {20, 0, -10}, {-110, 50, 0}, {5, -3, -3}, {-5, 3, 3}}, // brandish
            {{-15, 0, 0}, {0, 0, 0}, {-110, 35, 0}, {-110, -35, 0}, {5, -3, -3}, {-5, 3, 3}}, // honor
            {{-15, 0, 0}, {0, 0, 0}, {-110, -35, 0}, {-110, 35, 0}, {5, -3, -3}, {-5, 3, 3}}, // entertain
            {{0, 0, 0}, {0, 0, 0}, {10, 0, -5}, {-70, -40, 0}, {-1, 0, -1}, {1, 0, 1}}, // salute
            {{16, 20, 0}, {0, 0, 0}, {4, 8, 237}, {246, 0, 89}, {-14, -18, -16}, {8, 20, 4}}, // riposte
            {{-10, 0, -5}, {0, 0, 0}, {-105, 0, 0}, {-100, 0, 0}, {7, 0, 0}, {-46, 0, 0}}, // zombie
            {{-5, 18, 0}, {0, 22, 0}, {8, 0, -114}, {0, 84, 111}, {-111, 55, 0}, {0, 23, -13}}, // cancan A
            {{-10, -20, 0}, {0, -18, 0}, {0, 0, -112}, {8, 90, 111}, {0, 0, 13}, {-119, -42, 0}}, // cancan B
            {{-4, 67, 0}, {0, 8, 0}, {16, 32, -8}, {-99, 63, 0}, {0, -75, -8}, {4, 63, 8}}, // hero
    };
    private static final MetaField[] POSE_FIELDS = {MetaField.HEAD_POSE, MetaField.BODY_POSE, MetaField.LEFT_ARM_POSE,
            MetaField.RIGHT_ARM_POSE, MetaField.LEFT_LEG_POSE, MetaField.RIGHT_LEG_POSE};
    private static final Vector3f[][] ARMOR_STAND_POSE_VECTORS = new Vector3f[ARMOR_STAND_POSES.length][];

    static {
        for (int i = 0; i < ARMOR_STAND_POSES.length; i++) {
            Vector3f[] vectors = new Vector3f[POSE_FIELDS.length];
            for (int j = 0; j < vectors.length; j++) {
                float[] angles = ARMOR_STAND_POSES[i][j];
                vectors[j] = Vector3f.from(angles[0], angles[1], angles[2]);
            }
            ARMOR_STAND_POSE_VECTORS[i] = vectors;
        }
    }

    private static void writeArmorStand(TrackedEntity entity, ActorDataMap data, boolean flagsChanged, MetadataWriter out) {
        if (flagsChanged || data.containsKey(ActorDataTypes.SCALE)) {
            // Bedrock armor stands always show their arms; holograms become markers (no hit box).
            int clientFlags = 0x04;
            if (entity.scale < HIDDEN_SCALE) {
                clientFlags |= 0x10;
            }
            out.putByte(MetaField.CLIENT_FLAGS, clientFlags);
        }
        Integer pose = data.get(ActorDataTypes.POSE_INDEX);
        if (pose != null) {
            Vector3f[] vectors = ARMOR_STAND_POSE_VECTORS[pose >= 0 && pose < ARMOR_STAND_POSE_VECTORS.length ? pose : 0];
            for (int i = 0; i < POSE_FIELDS.length; i++) {
                out.putObject(POSE_FIELDS[i], MetadataTypes.ROTATIONS, vectors[i]);
            }
        }
    }
}
