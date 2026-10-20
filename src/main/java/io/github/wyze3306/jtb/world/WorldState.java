package io.github.wyze3306.jtb.world;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;

/**
 * CONTRACT (owner: world subsystem). The world as the Java client sees it: current dimension and
 * the loaded chunks, kept so other subsystems can look blocks up (interaction, light, sounds...).
 * Coordinates are absolute block coordinates; the same in both editions.
 */
public final class WorldState implements SessionState {

    public static final StateKey<WorldState> KEY = StateKey.of("world", WorldState::new);

    private final ProxySession session;

    private WorldState(ProxySession session) {
        this.session = session;
    }

    /** Bedrock dimension id: 0 overworld, 1 nether, 2 end. */
    public int dimension() {
        return 0;
    }

    /** Lowest block Y of the current dimension (overworld -64). */
    public int minY() {
        return -64;
    }

    /** World height in blocks of the current dimension (overworld 384). */
    public int height() {
        return 384;
    }

    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        return false;
    }

    /** Java state id at a position (air when not loaded). */
    public int javaBlockAt(int x, int y, int z) {
        return 0;
    }

    /** Bedrock runtime id at a position in layer 0 (block) or 1 (liquid); air when not loaded. */
    public int bedrockBlockAt(int x, int y, int z, int layer) {
        return 0;
    }
}
