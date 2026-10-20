package io.github.wyze3306.jtb.e2e.scenario;

import io.github.wyze3306.jtb.e2e.ClientContext;
import io.github.wyze3306.jtb.e2e.E2eScenario;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.level.event.BreakBlockEventData;
import org.geysermc.mcprotocollib.protocol.data.game.level.event.LevelEventType;
import org.geysermc.mcprotocollib.protocol.data.game.level.event.UnknownLevelEvent;
import org.geysermc.mcprotocollib.protocol.data.game.level.event.UnknownLevelEventData;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.RainStrengthValue;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.ThunderStrengthValue;
import org.geysermc.mcprotocollib.protocol.data.game.level.particle.ParticleType;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.BuiltinSound;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.CustomSound;
import org.geysermc.mcprotocollib.protocol.data.game.level.sound.Sound;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSoundEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundStopSoundPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelParticlesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSoundPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sounds, particles and level events end to end: the (op) test player runs commands that make
 * PowerNukkitX send PlaySound, LevelSoundEvent, SpawnParticleEffect, LevelEvent (weather, block destroy
 * particles) and LevelEventGeneric (music), then checks the Java packets it got and that every sound,
 * particle and level event id is one a vanilla client accepts. Needs the test player in the server's
 * {@code ops.txt}.
 */
public final class SoundScenario implements E2eScenario {

    /** Particle types MCProtocolLib cannot write: receiving one would mean the proxy broke the stream. */
    private static final Set<ParticleType> UNWRITABLE = EnumSet.of(ParticleType.GEYSER, ParticleType.GEYSER_BASE,
            ParticleType.GEYSER_POOF, ParticleType.GEYSER_PLUME, ParticleType.DRAGON_BREATH, ParticleType.EFFECT,
            ParticleType.INSTANT_EFFECT, ParticleType.TINTED_LEAVES, ParticleType.FLASH);

    private static final Set<String> VANILLA = java.util.Arrays.stream(BuiltinSound.values())
            .map(sound -> "minecraft:" + sound.getName()).collect(java.util.stream.Collectors.toSet());

    private static final Map<Integer, String> COMMANDS = new TreeMap<>(Map.ofEntries(
            Map.entry(20, "playsound random.levelup @s"),
            Map.entry(24, "playsound mypack.custom_roar @s"),
            Map.entry(28, "particle minecraft:basic_flame_particle ~ ~1 ~"),
            Map.entry(32, "particle minecraft:heart_particle ~ ~1 ~"),
            Map.entry(36, "weather rain"),
            Map.entry(44, "weather thunder"),
            Map.entry(52, "setblock ~2 ~ ~ stone"),
            Map.entry(58, "setblock ~2 ~ ~ air destroy"),
            Map.entry(64, "summon fireworks_rocket ~ ~ ~3"),
            Map.entry(70, "playsound record.cat @s"),
            Map.entry(80, "stopsound @s"),
            Map.entry(84, "music play music.game"),
            Map.entry(90, "music stop"),
            Map.entry(96, "weather clear"),
            Map.entry(100, "summon splash_potion ~ ~3 ~2"),
            Map.entry(106, "summon xp_bottle ~2 ~3 ~"),
            Map.entry(112, "summon lightning_bolt ~ ~30 ~")));

    private final Set<String> sounds = ConcurrentHashMap.newKeySet();
    private final Set<ParticleType> particles = ConcurrentHashMap.newKeySet();
    private final Set<Integer> levelEvents = ConcurrentHashMap.newKeySet();
    private final Set<GameEvent> gameEvents = ConcurrentHashMap.newKeySet();
    private final List<String> invalid = new CopyOnWriteArrayList<>();
    private volatile float maxRain;
    private volatile float maxThunder;
    private volatile int destroyBlockState = -1;
    private volatile int stopSounds;
    private volatile double levelUpDistance = Double.NaN;

    @Override
    public void onPacket(ClientContext context, Packet packet) {
        if (packet instanceof ClientboundSoundPacket sound) {
            record(sound.getSound(), sound.getVolume(), sound.getPitch());
            if ("minecraft:entity.player.levelup".equals(name(sound.getSound()))) {
                // /playsound plays at the player's block: catches a position scaled by 8 once too often.
                this.levelUpDistance = Math.max(Math.abs(sound.getX() - context.x()),
                        Math.max(Math.abs(sound.getY() - context.y()), Math.abs(sound.getZ() - context.z())));
            }
        } else if (packet instanceof ClientboundSoundEntityPacket sound) {
            record(sound.getSound(), sound.getVolume(), sound.getPitch());
        } else if (packet instanceof ClientboundStopSoundPacket) {
            this.stopSounds++;
        } else if (packet instanceof ClientboundLevelParticlesPacket particle) {
            ParticleType type = particle.getParticle().getType();
            this.particles.add(type);
            if (UNWRITABLE.contains(type)) {
                this.invalid.add("unwritable particle " + type);
            }
        } else if (packet instanceof ClientboundLevelEventPacket event) {
            if (event.getEvent() instanceof UnknownLevelEvent || !(event.getEvent() instanceof LevelEventType type)) {
                this.invalid.add("unknown level event " + event.getEvent());
                return;
            }
            this.levelEvents.add(type.getId());
            if (type == LevelEventType.PARTICLES_AND_SOUND_DESTROY_BLOCK) {
                this.destroyBlockState = event.getData() instanceof BreakBlockEventData block ? block.getBlockState()
                        : event.getData() instanceof UnknownLevelEventData data ? data.getData() : -1;
            }
        } else if (packet instanceof ClientboundGameEventPacket event) {
            this.gameEvents.add(event.getNotification());
            if (event.getValue() instanceof RainStrengthValue rain) {
                this.maxRain = Math.max(this.maxRain, rain.getStrength());
            } else if (event.getValue() instanceof ThunderStrengthValue thunder) {
                this.maxThunder = Math.max(this.maxThunder, thunder.getStrength());
            }
        }
    }

    private static String name(Sound sound) {
        return sound instanceof BuiltinSound builtin ? "minecraft:" + builtin.getName() : sound.getName();
    }

    private void record(Sound sound, float volume, float pitch) {
        String name = name(sound);
        this.sounds.add(name);
        if (sound instanceof CustomSound && !name.startsWith("jtb:") && !VANILLA.contains(name)) {
            // Inline events are resource pack sounds (jtb:) or vanilla ones given a Bedrock range.
            this.invalid.add("unknown inline sound " + name);
        }
        if (!(volume >= 0) || !(pitch > 0) || Float.isInfinite(volume)) {
            this.invalid.add("bad volume/pitch " + name + " " + volume + "/" + pitch);
        }
    }

    @Override
    public void tick(ClientContext context, int tick) {
        String command = COMMANDS.get(tick);
        if (command != null) {
            context.log("command /" + command);
            context.send(new ServerboundChatCommandPacket(command));
        }
    }

    @Override
    public void finish(ClientContext context) {
        context.log("sounds: " + new TreeSet<>(this.sounds));
        context.log("particles: " + this.particles + ", level events: " + this.levelEvents + ", game events: " + this.gameEvents);
        context.check("playsound vanilla -> entity.player.levelup", this.sounds.contains("minecraft:entity.player.levelup"));
        context.check("playsound position is the player's block (" + this.levelUpDistance + ")", this.levelUpDistance <= 2);
        context.check("playsound resource pack sound -> jtb:mypack.custom_roar", this.sounds.contains("jtb:mypack.custom_roar"));
        context.check("particle effect -> flame", this.particles.contains(ParticleType.FLAME));
        context.check("particle effect -> heart", this.particles.contains(ParticleType.HEART));
        context.check("weather rain -> START_RAINING + rain level", this.gameEvents.contains(GameEvent.START_RAINING) && this.maxRain > 0.2f);
        context.check("weather thunder -> thunder level", this.maxThunder > 0f);
        context.check("weather clear -> STOP_RAINING", this.gameEvents.contains(GameEvent.STOP_RAINING));
        context.check("setblock destroy -> destroy block level event", this.levelEvents.contains(LevelEventType.PARTICLES_AND_SOUND_DESTROY_BLOCK.getId())
                && this.destroyBlockState >= 0);
        context.check("firework -> LevelSoundEvent launch", this.sounds.contains("minecraft:entity.firework_rocket.launch"));
        context.check("record -> music disc", this.sounds.contains("minecraft:music_disc.cat"));
        context.check("stopsound -> StopSound", this.stopSounds > 0);
        context.check("music play -> music.game", this.sounds.contains("minecraft:music.game"));
        context.check("splash potion -> potion splash level event", this.levelEvents.contains(LevelEventType.PARTICLES_SPELL_POTION_SPLASH.getId()));
        context.check("splash potion -> glass sound", this.sounds.contains("minecraft:block.glass.break"));
        context.check("lightning -> thunder sound", this.sounds.contains("minecraft:entity.lightning_bolt.thunder"));
        context.check("only valid sound/particle/level event ids", this.invalid.isEmpty());
        if (!this.invalid.isEmpty()) {
            context.log("invalid: " + this.invalid);
        }
    }
}
