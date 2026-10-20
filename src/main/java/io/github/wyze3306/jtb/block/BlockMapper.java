package io.github.wyze3306.jtb.block;

import io.github.wyze3306.jtb.session.ProxySession;
import io.github.wyze3306.jtb.session.SessionState;
import io.github.wyze3306.jtb.session.StateKey;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.jspecify.annotations.Nullable;

/**
 * CONTRACT (owner: block/mapping subsystem). Bedrock block runtime ids of this session's server
 * (hashed or ordered, vanilla and custom blocks) to Java 26.3 global block state ids, and back.
 * All methods are called on the session event loop, many of them per block of every chunk: they must
 * be O(1) and allocation free.
 */
public final class BlockMapper implements SessionState {

    public static final StateKey<BlockMapper> KEY = StateKey.of("block-mapper", BlockMapper::new);

    private final ProxySession session;

    private BlockMapper(ProxySession session) {
        this.session = session;
    }

    /** Reads {@code blockNetworkIdsAreHashes} and the custom block definitions ({@code blockProperties}). */
    public void onStartGame(StartGamePacket packet) {
    }

    /** Java state id for a Bedrock runtime id of the block layer (layer 0). Unknown ids map to a visible placeholder. */
    public int toJava(int bedrockRuntimeId) {
        return 0;
    }

    /**
     * Java state id for a block position holding {@code layer0} and, in the liquid layer, {@code layer1}
     * (water there makes waterloggable Java blocks waterlogged).
     */
    public int toJava(int layer0, int layer1) {
        return toJava(layer0);
    }

    /** The Bedrock state ({@code name} + {@code states}) of a runtime id, or null when unknown. */
    public @Nullable NbtMap bedrockState(int bedrockRuntimeId) {
        return null;
    }

    /** Runtime id of {@code minecraft:air} on this server. */
    public int bedrockAir() {
        return 0;
    }

    /** Best Bedrock runtime id for a Java state id (block picking, placement prediction), or air. */
    public int toBedrock(int javaStateId) {
        return bedrockAir();
    }
}
