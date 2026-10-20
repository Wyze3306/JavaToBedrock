package io.github.wyze3306.jtb.world;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;

/**
 * CONTRACT (owner: world subsystem). The world as the Java client sees it: current dimension and
 * the loaded chunks, kept so other subsystems can look blocks up (interaction, light, sounds...).
 * Coordinates are absolute block coordinates; the same in both editions.
 */
public final class WorldState implements SessionState {

    public static final StateKey<WorldState> KEY = StateKey.of("world", WorldState::new);

    private final ProxySession session;
    private int dimension;

    private WorldState(ProxySession session) {
        this.session = session;
    }

    /** Reads the initial dimension. */
    public void onStartGame(StartGamePacket packet) {
        this.dimension = packet.getSettings().getSpawnSettings().getDimension().getValue();
    }

    /** Bedrock dimension id: 0 overworld, 1 nether, 2 end. */
    public int dimension() {
        return this.dimension;
    }

    /** Lowest block Y of the current dimension (overworld -64). */
    public int minY() {
        return JavaDimensions.minY(this.dimension);
    }

    /** World height in blocks of the current dimension (overworld 384). */
    public int height() {
        return JavaDimensions.height(this.dimension);
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
