package io.github.wyze3306.jtb.sound;

import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.entity.EntityTracker;
import io.github.wyze3306.jtb.item.ItemRegistry;
import io.github.wyze3306.jtb.item.ItemTranslator;
import io.github.wyze3306.jtb.player.BlockBreaking;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.world.WorldState;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.LevelEvent;
import org.cloudburstmc.protocol.bedrock.data.LevelEventType;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.LevelEventGenericPacket;
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.SpawnParticleEffectPacket;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.ElderGuardianEffectValue;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.BlockParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ColorParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.DustColorTransitionParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ItemParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ParticleType;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ShriekParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.TrailParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.VibrationParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.positionsource.BlockPositionSource;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.positionsource.EntityPositionSource;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.positionsource.PositionSource;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.BuiltinSound;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.SoundCategory;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.ThreadLocalRandom;

/**
 * LevelEvent, LevelEventGeneric and SpawnParticleEffect: Bedrock world effects (sounds, particles, weather,
 * block cracking, music...) to Java level events, sounds, particles and game events.
 * <p>
 * The decoded {@link LevelEventType} is either a {@link LevelEvent} or, for ids {@code 0x4000 | n}, a Bedrock
 * particle type. Table driven ({@link SoundMappings}) except for the events whose payload needs code; the
 * generic-event translations follow ViaBedrock's {@code WorldEffectPackets} (GPL-3.0).
 */
public final class LevelEventTranslators {

    private static final JavaSound GUARDIAN_CURSE = JavaSound.builtin(BuiltinSound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.HOSTILE);
    private static final JavaSound CATALYST_BLOOM = JavaSound.builtin(BuiltinSound.BLOCK_SCULK_CATALYST_BLOOM, SoundCategory.BLOCK);
    private static final int JAVA_SCULK_CHARGE = org.geysermc.mcprotocollib.protocol.data.game.level.event.LevelEventType.PARTICLES_SCULK_CHARGE.getId();
    private static final int JAVA_INSTANT_POTION_SPLASH =
            org.geysermc.mcprotocollib.protocol.data.game.level.event.LevelEventType.PARTICLES_INSTANT_POTION_SPLASH.getId();
    /** Upper bound of particles one generic event may emit (they carry server-chosen counts). */
    private static final int MAX_PARTICLES = 256;

    private LevelEventTranslators() {
    }

    // ------------------------------------------------------------------ LevelEvent

    static void levelEvent(ProxySession session, LevelEventPacket packet) {
        LevelEventType type = packet.getType();
        Vector3f position = packet.getPosition() == null ? Vector3f.ZERO : packet.getPosition();
        int data = packet.getData();
        if (type instanceof org.cloudburstmc.protocol.bedrock.data.ParticleType particleType) {
            ParticleSpec spec = SoundMappings.levelEventParticle(particleType);
            if (spec == null) {
                session.get(SoundState.KEY).reportMissing("particle type " + particleType);
            } else {
                emit(session, spec, position, data);
            }
            return;
        }
        if (!(type instanceof LevelEvent event)) {
            return;
        }
        SoundState state = session.get(SoundState.KEY);
        switch (event) {
            case UNDEFINED -> {
            }
            case PARTICLE_GENERIC_SPAWN -> {
                // data is the particle type; the particle itself has no data.
                ParticleSpec spec = SoundMappings.levelEventParticle(data);
                if (spec == null) {
                    state.reportMissing("particle type id " + data);
                } else {
                    emit(session, spec, position, 0);
                }
            }
            case START_RAINING -> state.startRain();
            case STOP_RAINING -> state.stopRain();
            case START_THUNDERSTORM -> state.startThunder();
            case STOP_THUNDERSTORM -> state.stopThunder();
            case BLOCK_START_BREAK, BLOCK_UPDATE_BREAK, BLOCK_STOP_BREAK -> {
                Vector3i block = blockPosition(position);
                // The Java client draws the cracks of the block its player digs.
                if (!session.get(BlockBreaking.KEY).isDigging(block.getX(), block.getY(), block.getZ())) {
                    BlockCracking cracking = session.get(BlockCracking.KEY);
                    switch (event) {
                        case BLOCK_START_BREAK -> cracking.start(block, data);
                        case BLOCK_UPDATE_BREAK -> cracking.update(block, data);
                        default -> cracking.stop(block);
                    }
                }
            }
            case PARTICLE_SOUND_GUARDIAN_GHOST -> {
                session.sendJava(new ClientboundGameEventPacket(GameEvent.GUARDIAN_ELDER_EFFECT, new ElderGuardianEffectValue(1)));
                // MCProtocolLib writes 0 for the effect's "play the sound" flag: play the curse ourselves.
                JavaEffects.soundAtPlayer(session, GUARDIAN_CURSE, 1f, 1f);
            }
            case ALL_PLAYERS_SLEEPING -> actionBar(session, Component.translatable("sleep.skipping_night"));
            case PARTICLE_EXPLOSION -> {
                // data = explosion radius; Java uses the big emitter from radius 2 on.
                ParticleType particle = data >= 2 ? ParticleType.EXPLOSION_EMITTER : ParticleType.EXPLOSION;
                JavaEffects.particle(session, ParticleSpec.of(particle), position.getX(), position.getY(), position.getZ());
            }
            case PARTICLE_DESTROY_BLOCK, PARTICLE_DESTROY_BLOCK_NO_SOUND -> {
                if (!state.isSuppressed(SoundState.SUPPRESS_BREAK, position)) {
                    mapped(session, state, event, position, data);
                }
            }
            default -> mapped(session, state, event, position, data);
        }
    }

    private static void mapped(ProxySession session, SoundState state, LevelEvent event, Vector3f position, int data) {
        SoundMappings.LevelEventMapping mapping = SoundMappings.levelEvent(event);
        if (mapping == null) {
            state.reportMissing("level event " + event);
            return;
        }
        switch (mapping) {
            case SoundMappings.JavaLevelEvent javaEvent -> javaLevelEvent(session, event, javaEvent, position, data);
            case SoundMappings.SoundLevelEvent sound -> {
                if (data == 0 && sound.withoutData() != null) {
                    javaLevelEvent(session, event, sound.withoutData(), position, data);
                } else if (!state.isSuppressed(SoundState.SUPPRESS_SOUND, position)) {
                    JavaEffects.sound(session, sound.sound(), position.getX(), position.getY(), position.getZ(), 1f, soundPitch(event, data));
                }
            }
            case SoundMappings.ParticleLevelEvent particle -> {
                ParticleSpec spec = event == LevelEvent.PARTICLE_CRIT ? particle.particle().withCount(Math.max(1, data)) : particle.particle();
                emit(session, spec, facePosition(event, position), data);
            }
        }
    }

    private static void javaLevelEvent(ProxySession session, LevelEvent event, SoundMappings.JavaLevelEvent javaEvent,
                                       Vector3f position, int data) {
        int javaData;
        if (javaEvent.fixedData()) {
            javaData = javaEvent.data();
        } else {
            javaData = switch (event) {
                case PARTICLE_SHOOT, PARTICLE_SHOOT_WHITE_SMOKE -> shootDirection(data).ordinal();
                case PARTICLE_DESTROY_BLOCK, PARTICLE_DESTROY_BLOCK_NO_SOUND -> session.get(BlockMapper.KEY).toJava(data);
                // Bedrock sends ARGB with a zero alpha; Java wants RGB.
                case PARTICLE_POTION_SPLASH -> data & 0xFFFFFF;
                // Java: particle count, Bedrock leaves it 0.
                case PARTICLE_CROP_GROWTH -> data > 0 ? data : 15;
                default -> data;
            };
        }
        int javaId = javaEvent.id();
        if (event == LevelEvent.PARTICLE_POTION_SPLASH && data >>> 24 == 1) {
            // The alpha byte flags instant potions (PowerNukkitX PotionSplashInstantParticle): white sparkles on Java.
            javaId = JAVA_INSTANT_POTION_SPLASH;
        }
        Vector3i block = blockPosition(position);
        if (event == LevelEvent.PARTICLE_SMASH_ATTACK_GROUND_DUST) {
            // Java reads the block the attacker landed on.
            block = Vector3i.from(block.getX(), SoundState.floor(position.getY() - 0.2f), block.getZ());
        }
        JavaEffects.levelEvent(session, javaId, block, javaData);
    }

    /** Bedrock sound events carry pitch × 1000 in their data (0 = default). */
    private static float soundPitch(LevelEvent event, int data) {
        return switch (event) {
            case SOUND_CLICK, SOUND_CLICK_FAIL, SOUND_DOOR_OPEN, SOUND_FIZZ, SOUND_INFINITY_ARROW_PICKUP, SOUND_AMETHYST_RESONATE ->
                    data > 0 && data <= 256_000 ? data / 1000f : 1f;
            default -> 1f;
        };
    }

    /** ParticlesShoot data: 0-8 = a 3x3 grid of directions around the dispenser (ViaBedrock's table). */
    private static Direction shootDirection(int data) {
        return switch (Math.floorMod(data, 9)) {
            case 0, 3 -> Direction.WEST;
            case 4 -> Direction.UP;
            case 5, 8 -> Direction.EAST;
            case 6, 7 -> Direction.SOUTH;
            default -> Direction.NORTH;
        };
    }

    /** The face particles of a block being broken spawn on that face. */
    private static Vector3f facePosition(LevelEvent event, Vector3f position) {
        float x = SoundState.floor(position.getX());
        float y = SoundState.floor(position.getY());
        float z = SoundState.floor(position.getZ());
        return switch (event) {
            case PARTICLE_BREAK_BLOCK_DOWN -> Vector3f.from(x + 0.5f, y, z + 0.5f);
            case PARTICLE_BREAK_BLOCK_UP -> Vector3f.from(x + 0.5f, y + 1, z + 0.5f);
            case PARTICLE_BREAK_BLOCK_NORTH -> Vector3f.from(x + 0.5f, y + 0.5f, z);
            case PARTICLE_BREAK_BLOCK_SOUTH -> Vector3f.from(x + 0.5f, y + 0.5f, z + 1);
            case PARTICLE_BREAK_BLOCK_WEST -> Vector3f.from(x, y + 0.5f, z + 0.5f);
            case PARTICLE_BREAK_BLOCK_EAST -> Vector3f.from(x + 1, y + 0.5f, z + 0.5f);
            default -> position;
        };
    }

    /**
     * Emits a particle mapping, deriving the data the mapping leaves open from the event's {@code data}:
     * a block runtime id (terrain, cracks), {@code item id << 16 | aux} (item cracks) or an ARGB colour (spells,
     * falling dust).
     */
    private static void emit(ProxySession session, ParticleSpec spec, Vector3f position, int data) {
        ParticleData particleData = spec.data();
        if (spec.needsData()) {
            particleData = switch (spec.type()) {
                case BLOCK, BLOCK_MARKER, FALLING_DUST, DUST_PILLAR, BLOCK_CRUMBLE ->
                        new BlockParticleData(session.get(BlockMapper.KEY).toJava(data));
                case ITEM -> itemData(session, data);
                case ENTITY_EFFECT -> new ColorParticleData(0xFF000000 | data);
                case DUST_COLOR_TRANSITION -> new DustColorTransitionParticleData(data & 0xFFFFFF, 1f, data & 0xFFFFFF);
                default -> null;
            };
        }
        particleData = withTrailTarget(particleData, position);
        JavaEffects.particle(session, spec, particleData, position.getX(), position.getY(), position.getZ());
    }

    private static @Nullable ParticleData itemData(ProxySession session, int data) {
        ItemDefinition definition = session.get(ItemRegistry.KEY).byRuntimeId(data >> 16);
        if (definition == null) {
            return null;
        }
        ItemStack stack = ItemTranslator.toJava(session, ItemData.builder().definition(definition).damage(data & 0xFFFF).count(1).build());
        return stack == null ? null : new ItemParticleData(new ItemStack(stack.getId(), 1, stack.getDataComponentsPatch()));
    }

    /**
     * Trail templates from the mapping tables (their target is the {@link Vector3d#ZERO} instance) fly from the
     * emitter to a random point just above it, like the eyeblossom particles.
     */
    private static @Nullable ParticleData withTrailTarget(@Nullable ParticleData data, Vector3f position) {
        if (data instanceof TrailParticleData trail && trail.target() == Vector3d.ZERO) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            return new TrailParticleData(Vector3d.from(position.getX() + random.nextFloat() - 0.5,
                    position.getY() + random.nextFloat() + 1.5, position.getZ() + random.nextFloat() - 0.5),
                    trail.color(), trail.duration());
        }
        return data;
    }

    // ------------------------------------------------------------------ LevelEventGeneric

    static void levelEventGeneric(ProxySession session, LevelEventGenericPacket packet) {
        if (!(packet.getType() instanceof LevelEvent event) || !(packet.getTag() instanceof NbtMap tag)) {
            return;
        }
        SoundState state = session.get(SoundState.KEY);
        switch (event) {
            case PARTICLE_TELEPORT_TRAIL -> teleportTrail(session, tag);
            case PARTICLE_BLOCK_EXPLOSION -> {
                int count = Math.min(MAX_PARTICLES, num(tag, "size"));
                ParticleSpec smoke = ParticleSpec.of(ParticleType.SMOKE);
                for (int i = 0; i < count; i++) {
                    JavaEffects.particle(session, smoke, f(tag, "pos" + i + "x"), f(tag, "pos" + i + "y"), f(tag, "pos" + i + "z"));
                }
            }
            case PARTICLE_VIBRATION_SIGNAL -> vibration(session, tag);
            case PARTICLE_SCULK_SHRIEK -> {
                double x = num(tag, "originX") + 0.5;
                double y = num(tag, "originY") + 0.5;
                double z = num(tag, "originZ") + 0.5;
                for (int i = 0; i < 15; i++) {
                    JavaEffects.particle(session, ParticleSpec.of(ParticleType.SHRIEK, new ShriekParticleData(i * 5)), x, y, z);
                }
            }
            case SCULK_CATALYST_BLOOM -> {
                double x = f(tag, "originX");
                double y = f(tag, "originY");
                double z = f(tag, "originZ");
                JavaEffects.particle(session, ParticleSpec.of(ParticleType.SCULK_SOUL), x + 0.5, y + 1.15, z + 0.5);
                JavaEffects.sound(session, CATALYST_BLOOM, x, y, z, 2f, 0.6f + ThreadLocalRandom.current().nextFloat() * 0.4f);
            }
            case SCULK_CHARGE -> JavaEffects.levelEvent(session, JAVA_SCULK_CHARGE,
                    Vector3i.from(num(tag, "x"), num(tag, "y"), num(tag, "z")), (num(tag, "charge") << 6) | (num(tag, "facing") & 0x3F));
            case SCULK_CHARGE_POP -> JavaEffects.particle(session,
                    new ParticleSpec(ParticleType.SCULK_CHARGE_POP, null, 0, 0, 0, 0.04f, 20),
                    num(tag, "x") + 0.5, num(tag, "y") + 0.5, num(tag, "z") + 0.5);
            case SONIC_EXPLOSION -> JavaEffects.particle(session, ParticleSpec.of(ParticleType.SONIC_BOOM), f(tag, "x"), f(tag, "y"), f(tag, "z"));
            case DUST_PLUME -> JavaEffects.particle(session, ParticleSpec.of(ParticleType.DUST_PLUME).withCount(7), f(tag, "x"), f(tag, "y"), f(tag, "z"));
            case SLEEPING_PLAYERS -> sleepingPlayers(session, tag);
            case PARTICLE_CREAKING_HEART_TRIAL -> creakingHeartTrail(session, tag);
            case PLAY_CUSTOM_MUSIC -> music(session, state, tag, false);
            case QUEUE_CUSTOM_MUSIC -> music(session, state, tag, true);
            case STOP_CUSTOM_MUSIC -> state.stopMusic();
            case SET_MUSIC_VOLUME -> {
                // Java cannot change the volume of a playing sound.
            }
            default -> state.reportMissing("generic level event " + event);
        }
    }

    private static void teleportTrail(ProxySession session, NbtMap tag) {
        float startX = f(tag, "Startx");
        float startY = f(tag, "Starty");
        float startZ = f(tag, "Startz");
        float endX = f(tag, "Endx");
        float endY = f(tag, "Endy");
        float endZ = f(tag, "Endz");
        float dirScale = f(tag, "DirScale");
        float variationX = f(tag, "Variationx");
        float variationY = f(tag, "Variationy");
        int count = Math.min(MAX_PARTICLES, num(tag, "Count"));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            float progress = count == 1 ? 0 : i / (count - 1f);
            double x = startX + (endX - startX) * progress + (random.nextFloat() - 0.5f) * variationX * 2f;
            double y = startY + (endY - startY) * progress + (random.nextFloat() - 0.5f) * variationY * 2f - 1f;
            double z = startZ + (endZ - startZ) * progress + (random.nextFloat() - 0.5f) * variationX * 2f;
            // Count 0: the offsets are the particle's motion.
            ParticleSpec portal = new ParticleSpec(ParticleType.PORTAL, null, (random.nextFloat() - 0.5f) * dirScale,
                    (random.nextFloat() - 0.5f) * dirScale, (random.nextFloat() - 0.5f) * dirScale, 1f, 0);
            JavaEffects.particle(session, portal, x, y, z);
        }
    }

    private static void vibration(ProxySession session, NbtMap tag) {
        NbtMap origin = tag.getCompound("origin");
        NbtMap target = tag.getCompound("target");
        PositionSource destination = switch (target.getString("type")) {
            case "vec3" -> new BlockPositionSource(Vector3i.from(SoundState.floor(f(target, "x")),
                    SoundState.floor(f(target, "y")), SoundState.floor(f(target, "z"))));
            case "actor" -> {
                int entity = session.get(EntityTracker.KEY).javaId(target.getLong("uniqueID"));
                yield entity == -1 ? null : new EntityPositionSource(entity, 0f);
            }
            default -> null;
        };
        if (destination == null) {
            return;
        }
        int arrival = Math.max(1, Math.round(f(tag, "timeToLive") * 20f));
        JavaEffects.particle(session, ParticleSpec.of(ParticleType.VIBRATION, new VibrationParticleData(destination, arrival)),
                f(origin, "x"), f(origin, "y"), f(origin, "z"));
    }

    private static void creakingHeartTrail(ProxySession session, NbtMap tag) {
        Vector3f creaking = Vector3f.from(f(tag, "CreakingX"), f(tag, "CreakingY"), f(tag, "CreakingZ"));
        Vector3f heart = Vector3f.from(f(tag, "HeartX"), f(tag, "HeartY"), f(tag, "HeartZ"));
        trail(session, creaking, heart, 1f, 6250335, Math.min(MAX_PARTICLES, num(tag, "HeartAmount")), 0.5f, 1f);
        trail(session, heart, creaking, 2f, 16545810, Math.min(MAX_PARTICLES, num(tag, "CreakingAmount")), 0.5f, 0.5f);
    }

    private static void trail(ProxySession session, Vector3f from, Vector3f to, float spread, int color, int count,
                              float offsetXZ, float offsetY) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            TrailParticleData data = new TrailParticleData(Vector3d.from(to.getX() + random.nextFloat() * spread,
                    to.getY() + random.nextFloat() * spread, to.getZ() + random.nextFloat() * spread), color, random.nextInt(40) + 10);
            JavaEffects.particle(session, new ParticleSpec(ParticleType.TRAIL, data, offsetXZ, offsetY, offsetXZ, 0, 1),
                    from.getX(), from.getY(), from.getZ());
        }
    }

    /**
     * The sleeping players count, as Java's own (translated) action bar texts. Servers send it on every join and
     * death too; like a vanilla Java server, the bar only shows while someone sleeps or has just woken up.
     */
    private static void sleepingPlayers(ProxySession session, NbtMap tag) {
        int players = num(tag, "overworldPlayerCount");
        int sleeping = num(tag, "sleepingPlayerCount");
        if (!session.get(SoundState.KEY).updateSleeping(sleeping)) {
            return;
        }
        if (num(tag, "ableToSleep") == 0) {
            actionBar(session, Component.translatable("sleep.not_possible"));
            return;
        }
        actionBar(session, sleeping < players
                ? Component.translatable("sleep.players_sleeping", Component.text(sleeping), Component.text(players))
                : Component.translatable("sleep.skipping_night"));
    }

    /** Server music ({@code /music}): played on the player in its own category; a queued track waits for none. */
    private static void music(ProxySession session, SoundState state, NbtMap tag, boolean queue) {
        String track = tag.getString("trackName");
        if (track.isEmpty() || (queue && state.isMusicPlaying())) {
            return;
        }
        float volume = tag.containsKey("volume") ? Math.max(0f, Math.min(1f, f(tag, "volume"))) : 1f;
        state.playMusic(state.javaSound(track), volume);
    }

    private static void actionBar(ProxySession session, Component text) {
        session.sendJava(new ClientboundSystemChatPacket(text, true));
    }

    // ------------------------------------------------------------------ SpawnParticleEffect

    /** Bedrock particle effects by identifier; Molang variables are ignored (Java particles are not scriptable). */
    static void spawnParticleEffect(ProxySession session, SpawnParticleEffectPacket packet) {
        if (packet.getDimensionId() != null && packet.getDimensionId().getValue() != session.get(WorldState.KEY).dimension()) {
            return;
        }
        String identifier = packet.getEffectName();
        Vector3f position = packet.getPosition();
        if (identifier == null || position == null) {
            return;
        }
        ParticleSpec spec = SoundMappings.particleEffect(identifier);
        if (spec == null) {
            session.get(SoundState.KEY).reportMissing("particle effect " + identifier);
            return;
        }
        JavaEffects.particle(session, spec, withTrailTarget(spec.data(), position), position.getX(), position.getY(), position.getZ());
    }

    // ------------------------------------------------------------------ helpers

    private static Vector3i blockPosition(Vector3f position) {
        return Vector3i.from(SoundState.floor(position.getX()), SoundState.floor(position.getY()), SoundState.floor(position.getZ()));
    }

    /** Integer NBT value, whatever its numeric tag type. */
    private static int num(NbtMap tag, String key) {
        return tag.getNumber(key, 0).intValue();
    }

    /** Float NBT value, whatever its numeric tag type. */
    private static float f(NbtMap tag, String key) {
        return tag.getNumber(key, 0).floatValue();
    }
}
