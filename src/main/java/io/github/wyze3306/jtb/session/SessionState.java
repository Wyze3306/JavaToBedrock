package io.github.wyze3306.jtb.session;

/**
 * A piece of per-session state owned by one subsystem. Instances are only touched on the session
 * event loop.
 */
public interface SessionState {

    /**
     * Called every proxy tick (default 50 ms) on the session event loop, once the session is in game,
     * for every state that has been created.
     */
    default void tick() {
    }

    /**
     * Called once when the session closes. Must release resources (scheduled tasks, buffers...).
     */
    default void close() {
    }
}
