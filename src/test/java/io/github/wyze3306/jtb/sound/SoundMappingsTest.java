package io.github.wyze3306.jtb.sound;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.protocol.bedrock.data.LevelEvent;
import org.cloudburstmc.protocol.bedrock.data.SoundEvent;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.BlockParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ColorParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.DustColorTransitionParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.DustParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ItemParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.Particle;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ParticleType;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.SculkChargeParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ShriekParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.TrailParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.VibrationParticleData;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.positionsource.BlockPositionSource;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.BuiltinSound;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.CustomSound;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.SoundCategory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundMappingsTest {

    /** Level events {@link LevelEventTranslators#levelEvent} handles in code only (weather, cracking...). */
    private static final Set<LevelEvent> HANDLED_IN_CODE = EnumSet.of(LevelEvent.UNDEFINED, LevelEvent.PARTICLE_GENERIC_SPAWN,
            LevelEvent.START_RAINING, LevelEvent.STOP_RAINING, LevelEvent.START_THUNDERSTORM, LevelEvent.STOP_THUNDERSTORM,
            LevelEvent.BLOCK_START_BREAK, LevelEvent.BLOCK_UPDATE_BREAK, LevelEvent.BLOCK_STOP_BREAK,
            LevelEvent.PARTICLE_SOUND_GUARDIAN_GHOST, LevelEvent.ALL_PLAYERS_SLEEPING, LevelEvent.PARTICLE_EXPLOSION);

    /** Level events whose payload comes in a LevelEventGeneric (handled there; a plain LevelEvent may also be mapped). */
    private static final Set<LevelEvent> GENERIC_PAYLOAD = EnumSet.of(LevelEvent.PARTICLE_TELEPORT_TRAIL,
            LevelEvent.PARTICLE_BLOCK_EXPLOSION, LevelEvent.PARTICLE_VIBRATION_SIGNAL, LevelEvent.PARTICLE_SCULK_SHRIEK,
            LevelEvent.SCULK_CATALYST_BLOOM, LevelEvent.SCULK_CHARGE, LevelEvent.SCULK_CHARGE_POP, LevelEvent.SONIC_EXPLOSION,
            LevelEvent.DUST_PLUME, LevelEvent.SLEEPING_PLAYERS, LevelEvent.PARTICLE_CREAKING_HEART_TRIAL,
            LevelEvent.PLAY_CUSTOM_MUSIC, LevelEvent.QUEUE_CUSTOM_MUSIC, LevelEvent.STOP_CUSTOM_MUSIC, LevelEvent.SET_MUSIC_VOLUME);

    /** Level events without a Java equivalent. */
    private static final Set<LevelEvent> NO_JAVA_EQUIVALENT = EnumSet.of(LevelEvent.SOUND_PLAY_RECORDING, LevelEvent.GLOBAL_PAUSE,
            LevelEvent.SIM_TIME_STEP, LevelEvent.SIM_TIME_SCALE, LevelEvent.SET_DATA, LevelEvent.JUMP_PREVENTED,
            LevelEvent.PARTICLE_POINT_CLOUD);

    /** LevelSoundEvents the Bedrock data itself leaves silent. */
    private static final Set<String> SILENT_SOUND_EVENTS = Set.of("undefined", "default", "haggle.idle", "item.fizz",
            "jump.prevent", "mob.warning.baby", "scared", "pushed_by_player", "note", "record.null");

    /** LevelSoundEvents a Bedrock server sends all the time; every one must sound on Java. */
    private static final List<String> COMMON_SOUND_EVENTS = List.of("hit", "step", "break", "place", "hurt", "death", "ambient",
            "attack.strong", "attack.nodamage", "fall", "fall.big", "fall.small", "jump", "explode", "fuse", "bow", "shoot", "eat",
            "drink", "burp", "levelup", "thunder", "item.use.on", "power.on", "power.off", "door.open", "door.close", "chest.open",
            "chest.closed", "trapdoor.open", "trapdoor.close", "fence_gate.open", "fence_gate.close", "bucket.fill.water",
            "bucket.empty.water", "bucket.fill.lava", "bucket.empty.lava", "fizz", "extinguish.fire", "pop", "throw", "splash",
            "swim", "glass", "shear", "teleport", "ignite", "hurt.baby", "ambient.baby", "death.baby", "step.baby", "heavy.step",
            "gallop", "attack", "saddle", "spawn", "land", "mad", "angry", "roar", "random.anvil_use");

    @BeforeAll
    static void load() {
        SoundMappings.load();
    }

    @Test
    void everyVanillaBedrockSoundHasABuiltinJavaSound() {
        assertTrue(SoundMappings.sounds().size() >= 1851, "vanilla sound_definitions.json has 1851 sounds");
        for (Map.Entry<String, JavaSound> entry : SoundMappings.sounds().entrySet()) {
            assertTrue(entry.getValue().sound() instanceof BuiltinSound, entry.getKey());
        }
        assertSame(BuiltinSound.ENTITY_PLAYER_LEVELUP, SoundMappings.vanillaSound("random.levelup").sound());
        assertSame(BuiltinSound.ENTITY_GENERIC_EXPLODE, SoundMappings.vanillaSound("random.explode").sound());
        assertEquals(SoundCategory.RECORD, SoundMappings.vanillaSound("record.cat").category());
        assertEquals("minecraft:cat", SoundMappings.vanillaSound("record.cat").jukeboxSong());
        assertEquals(SoundCategory.HOSTILE, SoundMappings.vanillaSound("mob.zombie.say").category());
        assertEquals(SoundCategory.WEATHER, SoundMappings.vanillaSound("ambient.weather.thunder").category());
        assertNull(SoundMappings.vanillaSound("my_pack.custom_roar"));
    }

    @Test
    void commonLevelSoundEventsAreMapped() {
        for (String id : COMMON_SOUND_EVENTS) {
            SoundEvent event = SoundEvent.from(id);
            assertTrue(event != SoundEvent.UNDEFINED, "protocol library knows " + id);
            assertNotNull(SoundMappings.levelSound(event), "mapped: " + id);
        }
    }

    @Test
    void everyLevelSoundEventIsMappedOrKnownSilent() {
        List<String> unmapped = new ArrayList<>();
        int mapped = 0;
        for (SoundEvent event : SoundEvent.values()) {
            if (SoundMappings.levelSound(event) != null) {
                mapped++;
            } else if (!SILENT_SOUND_EVENTS.contains(event.getId())) {
                unmapped.add(event.getId());
            }
        }
        System.out.printf("LevelSoundEvent coverage: %d/%d mapped, %d silent or in code%n", mapped, SoundEvent.values().length,
                SoundEvent.values().length - mapped);
        assertTrue(unmapped.isEmpty(), "unmapped LevelSoundEvents: " + unmapped);
    }

    @Test
    void lookupOrderIsEntityThenBlockThenGeneric() {
        SoundMappings.LevelSound hurt = SoundMappings.levelSound(SoundEvent.HURT);
        SoundMappings.Variant zombie = SoundTranslators.resolve(hurt, null, "minecraft:zombie", -1, false, id -> "stone");
        assertSame(BuiltinSound.ENTITY_ZOMBIE_HURT, zombie.sound().sound());
        SoundMappings.Variant baby = SoundTranslators.resolve(hurt, null, "minecraft:zombie", -1, true, id -> "stone");
        assertEquals(zombie.minPitch() + 0.5f, baby.minPitch(), 1e-6);

        SoundMappings.LevelSound place = SoundMappings.levelSound(SoundEvent.PLACE);
        SoundMappings.Variant wood = SoundTranslators.resolve(place, null, null, 42, false, id -> "wood");
        assertSame(BuiltinSound.BLOCK_WOOD_PLACE, wood.sound().sound());
        // Unknown block sound types sound like stone.
        SoundMappings.Variant unknown = SoundTranslators.resolve(place, null, null, 42, false, id -> "no_such_type");
        assertSame(BuiltinSound.BLOCK_STONE_PLACE, unknown.sound().sound());

        SoundMappings.LevelSound levelUp = SoundMappings.levelSound(SoundEvent.LEVEL_UP);
        assertSame(BuiltinSound.ENTITY_PLAYER_LEVELUP, SoundTranslators.resolve(levelUp, null, null, -1, false, id -> "stone").sound().sound());

        SoundMappings.Variant pack = new SoundMappings.Variant(JavaSound.custom("mob.custom.hurt", SoundCategory.HOSTILE), 1, 1, 1, 1);
        assertSame(pack, SoundTranslators.resolve(hurt, pack, "custom:boss", -1, false, id -> "stone"));
        assertNull(SoundTranslators.resolve(hurt, null, "custom:boss", -1, false, id -> "stone"));
    }

    @Test
    void noteInstrumentsUseJavaNoteBlockSounds() {
        assertSame(BuiltinSound.BLOCK_NOTE_BLOCK_HARP, SoundMappings.noteInstrument(0).sound());
        assertSame(BuiltinSound.BLOCK_NOTE_BLOCK_BASEDRUM, SoundMappings.noteInstrument(1).sound());
        // Bedrock order is exposed, weathered, oxidized (Java's differs): mapped by name.
        assertSame(BuiltinSound.BLOCK_NOTE_BLOCK_TRUMPET_WEATHERED, SoundMappings.noteInstrument(18).sound());
        assertSame(BuiltinSound.BLOCK_NOTE_BLOCK_TRUMPET_OXIDIZED, SoundMappings.noteInstrument(19).sound());
        assertSame(BuiltinSound.BLOCK_NOTE_BLOCK_IMITATE_PIGLIN, SoundMappings.noteInstrument(25).sound());
        assertNull(SoundMappings.noteInstrument(26));
    }

    @Test
    void everyLevelEventIsMappedHandledOrWithoutEquivalent() {
        List<String> unmapped = new ArrayList<>();
        int[] counts = new int[3];
        BedrockIds.levelEvents().forEach((id, type) -> {
            if (!(type instanceof LevelEvent event)) {
                return;
            }
            if (SoundMappings.levelEvent(event) != null) {
                counts[0]++;
            } else if (HANDLED_IN_CODE.contains(event) || GENERIC_PAYLOAD.contains(event)) {
                counts[1]++;
            } else if (NO_JAVA_EQUIVALENT.contains(event)) {
                counts[2]++;
            } else {
                unmapped.add(event + "(" + id + ")");
            }
        });
        System.out.printf("LevelEvent coverage: %d by table, %d in code, %d without Java equivalent%n", counts[0], counts[1], counts[2]);
        assertTrue(unmapped.isEmpty(), "unmapped level events: " + unmapped);
        for (LevelEvent event : HANDLED_IN_CODE) {
            assertNull(SoundMappings.levelEvent(event), "handled in code, must not be in the table: " + event);
        }
    }

    @Test
    void everyBedrockParticleTypeIsMapped() {
        Set<String> unmapped = new TreeSet<>();
        int[] mapped = new int[1];
        BedrockIds.particleTypes().forEach((id, type) -> {
            if (type == org.cloudburstmc.protocol.bedrock.data.ParticleType.UNDEFINED) {
                return;
            }
            if (SoundMappings.levelEventParticle(type) != null) {
                mapped[0]++;
                assertSame(SoundMappings.levelEventParticle(type), SoundMappings.levelEventParticle((int) id));
            } else {
                unmapped.add(type.name());
            }
        });
        System.out.printf("Particle type coverage: %d mapped, unmapped %s%n", mapped[0], unmapped);
        // The vibration particle needs a destination only LevelEventGeneric carries.
        assertEquals(Set.of("VIBRATION_SIGNAL"), unmapped);
    }

    @Test
    void particleEffectsCoverVanillaIdentifiers() {
        Map<String, ParticleSpec> effects = SoundMappings.particleEffects();
        System.out.printf("Particle effect coverage: %d identifiers%n", effects.size());
        assertTrue(effects.size() >= 185);
        assertSame(ParticleType.FLAME, SoundMappings.particleEffect("minecraft:basic_flame_particle").type());
        assertSame(ParticleType.HEART, SoundMappings.particleEffect("minecraft:heart_particle").type());
        assertNull(SoundMappings.particleEffect("mypack:sparkles"));
    }

    @Test
    void noMappingUsesAParticleMcProtocolLibCannotWrite() {
        List<ParticleSpec> specs = new ArrayList<>(SoundMappings.particleEffects().values());
        specs.addAll(SoundMappings.levelEventParticles().values());
        for (SoundMappings.LevelEventMapping mapping : SoundMappings.levelEventsById().values()) {
            if (mapping instanceof SoundMappings.ParticleLevelEvent particle) {
                specs.add(particle.particle());
            }
        }
        for (ParticleSpec spec : specs) {
            assertFalse(ParticleSpec.UNWRITABLE.contains(spec.type()), "unwritable particle " + spec);
            if (spec.isComplete()) {
                assertRoundTrip(new Particle(spec.type(), spec.data()));
            } else {
                assertTrue(spec.needsData(), "incomplete spec must only lack event data: " + spec);
            }
        }
        // Every SpawnParticleEffect mapping must be complete: that packet carries no data.
        for (Map.Entry<String, ParticleSpec> effect : SoundMappings.particleEffects().entrySet()) {
            assertTrue(effect.getValue().isComplete(), effect.getKey());
        }
    }

    @Test
    void unwritableSetMatchesMcProtocolLibWriter() {
        // Writes each type with plausible data followed by a marker, reads it back: types whose writer drops the
        // data desynchronise the stream. That set must be exactly ParticleSpec.UNWRITABLE.
        Set<ParticleType> broken = EnumSet.noneOf(ParticleType.class);
        for (ParticleType type : ParticleType.values()) {
            if (!roundTrips(new Particle(type, sampleData(type)))) {
                broken.add(type);
            }
        }
        assertEquals(ParticleSpec.UNWRITABLE, broken);
        for (ParticleType type : ParticleSpec.UNWRITABLE) {
            assertFalse(ParticleSpec.isWritable(type, sampleData(type)));
        }
        assertFalse(ParticleSpec.isWritable(ParticleType.BLOCK, null), "data required");
        assertFalse(ParticleSpec.isWritable(ParticleType.FLAME, new BlockParticleData(1)), "no data expected");
        assertTrue(ParticleSpec.isWritable(ParticleType.ENTITY_EFFECT, new ColorParticleData(-1)));
    }

    @Test
    void bedrockHearingDistancesBecomeJavaRanges() {
        JavaSound levelUp = SoundMappings.vanillaSound("random.levelup");
        assertSame(BuiltinSound.ENTITY_PLAYER_LEVELUP, levelUp.wireSound(1f));
        // Discs: fixed 64 blocks whatever the volume.
        CustomSound disc = (CustomSound) SoundMappings.vanillaSound("record.cat").wireSound(1f);
        assertEquals("minecraft:music_disc.cat", disc.getName());
        assertTrue(disc.isNewSystem());
        assertEquals(64f, disc.getRange());
        // Note blocks at Java's volume 3 already reach Bedrock's 48 blocks: plain event.
        assertSame(BuiltinSound.BLOCK_NOTE_BLOCK_HARP, SoundMappings.vanillaSound("note.harp").wireSound(3f));
        // Thunder: full volume within 100 blocks on Bedrock.
        JavaSound thunder = SoundMappings.vanillaSound("ambient.weather.thunder");
        assertEquals(200f, thunder.wireSound(1f).getRange());
        assertSame(BuiltinSound.ENTITY_LIGHTNING_BOLT_THUNDER, thunder.wireSound(1000f));
    }

    @Test
    void customSoundsUseTheJtbNamespace() {
        assertEquals("mob.boss_roar", CustomSounds.path("Mob.Boss:Roar"));
        assertEquals("ambient/cave-1_x", CustomSounds.path("ambient/cave-1 x"));
        assertEquals("_", CustomSounds.path(""));
        assertEquals("jtb:mob.boss_roar", CustomSounds.key("Mob.Boss:Roar").asString());
        JavaSound custom = JavaSound.custom("Mob.Boss:Roar", SoundCategory.HOSTILE);
        assertEquals("jtb:mob.boss_roar", ((CustomSound) custom.sound()).getName());
        assertNull(custom.jukeboxSong());
    }

    @Test
    void bedrockCategoriesMapToJavaCategories() {
        assertEquals(SoundCategory.BLOCK, SoundState.javaCategory("block"));
        assertEquals(SoundCategory.NEUTRAL, SoundState.javaCategory("bottle"));
        assertEquals(SoundCategory.PLAYER, SoundState.javaCategory("player"));
        assertEquals(SoundCategory.UI, SoundState.javaCategory("ui"));
        assertEquals(SoundCategory.MASTER, SoundState.javaCategory(null));
        assertEquals(SoundCategory.MASTER, SoundState.javaCategory("whatever"));
    }

    private static ParticleData sampleData(ParticleType type) {
        return switch (type) {
            case BLOCK, BLOCK_MARKER, FALLING_DUST, DUST_PILLAR, BLOCK_CRUMBLE -> new BlockParticleData(1);
            case DUST -> new DustParticleData(0xFF0000, 1f);
            case DUST_COLOR_TRANSITION -> new DustColorTransitionParticleData(0xFF0000, 1f, 0x00FF00);
            case ENTITY_EFFECT, TINTED_LEAVES, FLASH -> new ColorParticleData(-1);
            case ITEM -> new ItemParticleData(new ItemStack(1, 1));
            case SCULK_CHARGE -> new SculkChargeParticleData(0.5f);
            case SHRIEK -> new ShriekParticleData(5);
            case TRAIL -> new TrailParticleData(Vector3d.from(1, 2, 3), 0xFFFFFF, 20);
            case VIBRATION -> new VibrationParticleData(new BlockPositionSource(Vector3i.from(1, 2, 3)), 10);
            case DRAGON_BREATH -> new org.geysermc.mcprotocollib.protocol.data.game.level.particle.PowerParticleData(1f);
            case EFFECT, INSTANT_EFFECT -> new org.geysermc.mcprotocollib.protocol.data.game.level.particle.SpellParticleData(-1, 1f);
            case GEYSER, GEYSER_PLUME -> new org.geysermc.mcprotocollib.protocol.data.game.level.particle.GeyserParticleData(3);
            case GEYSER_BASE, GEYSER_POOF -> new org.geysermc.mcprotocollib.protocol.data.game.level.particle.GeyserBaseParticleData(3, 1f);
            default -> null;
        };
    }

    private static void assertRoundTrip(Particle particle) {
        assertTrue(roundTrips(particle), "particle does not survive MCProtocolLib encoding: " + particle);
    }

    private static boolean roundTrips(Particle particle) {
        ByteBuf buf = Unpooled.buffer();
        try {
            MinecraftTypes.writeParticle(buf, particle);
            buf.writeInt(0x5EED5EED);
            Particle read = MinecraftTypes.readParticle(buf);
            return read.getType() == particle.getType() && buf.readableBytes() == 4 && buf.readInt() == 0x5EED5EED;
        } catch (RuntimeException e) {
            return false;
        } finally {
            buf.release();
        }
    }
}
