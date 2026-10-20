package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.block.BlockMapper;
import io.github.wyze3306.jtb.player.PlayerState;
import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.sound.SoundTranslators;
import io.github.wyze3306.jtb.world.WorldState;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.data.SoundEvent;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorFlags;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The mob sounds a Bedrock client makes by itself and a Java client expects from the server:
 * <ul>
 *     <li><b>ambient</b> (moo, groan...): Bedrock clients play the {@code AMBIENT_SOUND_EVENT_NAME} event of
 *     an entity every {@code AMBIENT_SOUND_INTERVAL} + random {@code AMBIENT_SOUND_INTERVAL_RANGE} seconds
 *     (entity data the server sends); the proxy does the same;</li>
 *     <li><b>steps</b> of mobs and other players: one step sound per 1/0.6 block walked on the ground, the
 *     Java server's pace, with the sound of the block below (the entity's own step sound first).</li>
 * </ul>
 * Only entities near the Java player are heard (Java attenuates sounds to silence at 16 blocks).
 * Session event loop.
 */
final class MobSounds {

    /** Java plays a step every time the walked distance (x0.6) passes a whole block. */
    private static final double STEP_DISTANCE = 1 / 0.6;
    private static final float HEARING_RANGE_SQUARED = 24 * 24;
    private static final Map<String, SoundEvent> EVENTS = new ConcurrentHashMap<>();

    private final ProxySession session;
    private final EntityTracker tracker;
    private final LongOpenHashSet withAmbient = new LongOpenHashSet();

    MobSounds(ProxySession session, EntityTracker tracker) {
        this.session = session;
        this.tracker = tracker;
    }

    /** Entity data changed its ambient sound settings. */
    void onAmbientData(TrackedEntity entity) {
        if (entity.ambientEvent == null || entity.ambientEvent.isEmpty() || !entity.definition().isLiving()) {
            this.withAmbient.remove(entity.runtimeId());
            return;
        }
        if (this.withAmbient.add(entity.runtimeId())) {
            entity.ambientTicks = nextAmbient(entity);
        }
    }

    void forget(TrackedEntity entity) {
        this.withAmbient.remove(entity.runtimeId());
    }

    void clear() {
        this.withAmbient.clear();
    }

    void tick() {
        if (this.withAmbient.isEmpty()) {
            return;
        }
        LongIterator iterator = this.withAmbient.iterator();
        while (iterator.hasNext()) {
            TrackedEntity entity = this.tracker.entity(iterator.nextLong());
            if (entity == null || entity.ambientEvent == null) {
                iterator.remove();
                continue;
            }
            if (--entity.ambientTicks > 0) {
                continue;
            }
            entity.ambientTicks = nextAmbient(entity);
            if (entity.dead || entity.flags.contains(ActorFlags.SILENT) || !inHearingRange(entity)) {
                continue;
            }
            SoundEvent event = EVENTS.computeIfAbsent(entity.ambientEvent, SoundEvent::from);
            if (event == SoundEvent.UNDEFINED || !SoundTranslators.playLevelSound(this.session, event,
                    Vector3f.from(entity.x, entity.y, entity.z), -1, entity.identifier(),
                    entity.flags.contains(ActorFlags.BABY), false)) {
                // This entity has no such sound: stop trying.
                entity.ambientEvent = null;
                iterator.remove();
            }
        }
    }

    /**
     * The entity moved to ({@code x}, {@code y}, {@code z}) from its previous position: counts the
     * distance walked on the ground and plays a step when due.
     */
    void onMoved(TrackedEntity entity, double x, double y, double z, boolean onGround) {
        if (!onGround || entity.vehicle != -1 || !entity.definition().isLiving()
                || entity.kind() == TrackedEntity.Kind.CUSTOM || entity.kind() == TrackedEntity.Kind.LOCAL_PLAYER) {
            return;
        }
        double dx = x - entity.x;
        double dz = z - entity.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance > 4) {
            // A teleport, not a walk.
            return;
        }
        entity.stepDistance += distance;
        if (entity.stepDistance < STEP_DISTANCE) {
            return;
        }
        entity.stepDistance -= STEP_DISTANCE * Math.floor(entity.stepDistance / STEP_DISTANCE);
        if (entity.flags.contains(ActorFlags.SNEAKING) || entity.flags.contains(ActorFlags.SILENT) || !inHearingRange(entity)) {
            return;
        }
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y - 0.2);
        int bz = (int) Math.floor(z);
        int block = this.session.get(WorldState.KEY).bedrockBlockAt(bx, by, bz, 0);
        if (block == this.session.get(BlockMapper.KEY).bedrockAir()) {
            return;
        }
        SoundTranslators.playLevelSound(this.session, SoundEvent.STEP, Vector3f.from(x, y, z), block,
                entity.identifier(), entity.flags.contains(ActorFlags.BABY), false);
    }

    private boolean inHearingRange(TrackedEntity entity) {
        Vector3f player = this.session.get(PlayerState.KEY).position();
        double dx = entity.x - player.getX();
        double dy = entity.y - player.getY();
        double dz = entity.z - player.getZ();
        return dx * dx + dy * dy + dz * dz <= HEARING_RANGE_SQUARED;
    }

    /** Ticks until the next ambient sound: interval + random range, in seconds (Bedrock defaults 8 and 16). */
    private static int nextAmbient(TrackedEntity entity) {
        float interval = entity.ambientInterval >= 0 ? entity.ambientInterval : 8;
        float range = entity.ambientRange >= 0 ? entity.ambientRange : 16;
        return Math.max(20, Math.round((interval + ThreadLocalRandom.current().nextFloat() * range) * 20));
    }
}
