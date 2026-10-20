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
     * Called once when the session closes, or when the player switches Bedrock server and this state
     * does not {@linkplain #survivesServerSwitch() survive it}. Must release resources (scheduled
     * tasks, buffers...).
     */
    default void close() {
    }

    /**
     * Whether this state is about the Java connection itself and must be kept when the player is moved
     * to another Bedrock server ({@code TransferPacket}). Everything describing the Bedrock world is
     * discarded and rebuilt from the new server.
     */
    default boolean survivesServerSwitch() {
        return false;
    }
}
