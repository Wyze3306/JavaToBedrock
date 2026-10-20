package io.github.wyze3306.jtb.block;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.common.DefinitionRegistry;

/**
 * Block definition registry for the protocol library's codec: accepts every runtime id as is. The
 * proxy maps runtime ids itself (hashed or ordered), so the codec only needs an object carrying
 * the id. Used from the Netty thread while decoding, hence the synchronisation.
 */
public final class PassThroughBlockDefinitions implements DefinitionRegistry<BlockDefinition> {

    private record Definition(int runtimeId) implements BlockDefinition {
        @Override
        public int getRuntimeId() {
            return this.runtimeId;
        }
    }

    private final Int2ObjectMap<BlockDefinition> cache = new Int2ObjectOpenHashMap<>();

    @Override
    public synchronized BlockDefinition getDefinition(int runtimeId) {
        BlockDefinition definition = this.cache.get(runtimeId);
        if (definition == null) {
            definition = new Definition(runtimeId);
            this.cache.put(runtimeId, definition);
        }
        return definition;
    }

    @Override
    public int getRuntimeIdByName(String name) {
        return -1;
    }

    @Override
    public boolean isRegistered(BlockDefinition definition) {
        return true;
    }
}
