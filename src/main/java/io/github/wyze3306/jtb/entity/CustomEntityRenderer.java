package io.github.wyze3306.jtb.entity;

import io.github.wyze3306.jtb.session.ProxySession;

/**
 * Draws a Bedrock entity that has no Java counterpart (custom entities of behaviour/resource packs,
 * namespace other than {@code minecraft}).
 * <p>
 * The entity subsystem spawns nothing itself for such an entity: {@link #spawn} must create a Java
 * entity with id {@link TrackedEntity#javaId()} (the one Java players hit and use: interactions with it
 * are sent to the Bedrock entity) and may add more Java entities with
 * {@link EntityTracker#addPart(TrackedEntity)}; parts are removed with the entity, and their ids also map
 * back to the Bedrock entity. Movement of the main entity is sent by the entity subsystem; parts that
 * ride it (see {@link EntityTracker#sendPassengers(TrackedEntity)}, which lists them first) follow it.
 * <p>
 * The resource pack subsystem can install a renderer that shows converted models
 * ({@link EntityTracker#setCustomEntityRenderer}); {@link DefaultCustomEntityRenderer} is used otherwise.
 * All methods run on the session event loop.
 */
public interface CustomEntityRenderer {

    /** Spawns the Java entities of {@code entity} at its current position. */
    void spawn(ProxySession session, EntityTracker tracker, TrackedEntity entity);

    /** The entity's name tag, flags, scale or Bedrock hit box changed. */
    void update(ProxySession session, EntityTracker tracker, TrackedEntity entity);

    /** The entity moved or turned (position and rotation are already updated). */
    default void moved(ProxySession session, EntityTracker tracker, TrackedEntity entity) {
    }

    /** Bedrock {@code AnimateEntity}: a client animation (resource pack animation id) should play. */
    default void animate(ProxySession session, EntityTracker tracker, TrackedEntity entity, String animation) {
    }

    /** Bedrock entity property ({@code SyncActorProperty} schema) changed; {@code value} is an int, float or enum name. */
    default void property(ProxySession session, EntityTracker tracker, TrackedEntity entity, String name, Object value) {
    }

    /** The entity is being removed; its main entity and parts are removed by the tracker afterwards. */
    default void remove(ProxySession session, EntityTracker tracker, TrackedEntity entity) {
    }
}
