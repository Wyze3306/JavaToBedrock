package io.github.wyze3306.jtb.world.chunk;

/**
 * Vertical layout of a dimension on both sides. Bedrock: overworld -64..319 (24 sections), nether
 * 0..127 (8), end 0..255 (16), unless {@code DimensionData} says otherwise. Java (vanilla dimension
 * types): overworld -64..319 (24), nether and end 0..255 (16). Java sections Bedrock has no data
 * for are air; Bedrock sections outside the Java range are dropped.
 *
 * @param bedrockMinSection lowest Bedrock section y (block y >> 4)
 * @param bedrockSections   number of Bedrock sections, also the number of biome palettes a level chunk carries
 * @param javaMinSection    lowest Java section y
 * @param javaSections      number of Java sections
 * @param skyLight          whether the Java dimension type has sky light
 * @param defaultBiome      Java biome id for missing or unknown biomes
 */
public record ChunkLayout(int bedrockMinSection, int bedrockSections, int javaMinSection, int javaSections,
                          boolean skyLight, int defaultBiome) {

    /** Lowest Java block y. */
    public int javaMinY() {
        return this.javaMinSection << 4;
    }

    /** Java height in blocks. */
    public int javaHeight() {
        return this.javaSections << 4;
    }

    /** Java section index (0-based) of a Bedrock section y, or -1 when outside the Java world. */
    public int javaIndexOfBedrockSection(int bedrockSectionY) {
        int index = bedrockSectionY - this.javaMinSection;
        return index >= 0 && index < this.javaSections ? index : -1;
    }

    /** Java section index of a block y, or -1 when outside the world. */
    public int sectionIndex(int blockY) {
        int index = (blockY >> 4) - this.javaMinSection;
        return index >= 0 && index < this.javaSections ? index : -1;
    }
}
