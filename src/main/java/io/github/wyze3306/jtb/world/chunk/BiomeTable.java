package io.github.wyze3306.jtb.world.chunk;

/**
 * Immutable Bedrock biome id to Java biome registry id table of a session (vanilla plus the server's
 * custom biomes), shared with the worker threads that convert chunks.
 *
 * @param javaByBedrockId index = Bedrock biome id, value = Java id or -1
 * @param registrySize    size of the Java biome registry the client knows (sets the global palette bits)
 */
public record BiomeTable(int[] javaByBedrockId, int registrySize) {

    /** Java id of a Bedrock id, {@code fallback} when unknown. */
    public int toJava(int bedrockId, int fallback) {
        if (bedrockId >= 0 && bedrockId < this.javaByBedrockId.length) {
            int java = this.javaByBedrockId[bedrockId];
            if (java >= 0) {
                return java;
            }
        }
        return fallback;
    }
}
