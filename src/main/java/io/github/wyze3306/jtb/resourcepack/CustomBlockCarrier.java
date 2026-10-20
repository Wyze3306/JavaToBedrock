package io.github.wyze3306.jtb.resourcepack;

import org.cloudburstmc.nbt.NbtMap;

import java.util.Map;

/**
 * One state of a server-defined (custom) Bedrock block and the unused Java block state the block
 * mapper allocated to show it (a note block, mushroom block, tripwire... state). The pack converter
 * writes the carrier's blockstate file so that this Java state renders the custom block's model.
 * <p>
 * Provided by the block subsystem through {@link ResourcePackState#setCustomBlockCarriers} while it
 * handles {@code StartGame} (before the server's item registry arrives).
 *
 * @param bedrockIdentifier custom block name ({@code demo:ruby_block})
 * @param bedrockStates     values of the block's properties in this state ({@code {"demo:lit": 1b}}),
 *                          empty for a block without properties; used to evaluate permutation conditions
 * @param definition        the block's definition from {@code StartGamePacket.blockProperties}
 *                          ({@code components}, {@code permutations}, {@code properties}...)
 * @param javaBlock         carrier block ({@code minecraft:note_block})
 * @param javaProperties    every property of the carrier state ({@code instrument=banjo, note=3, powered=true})
 */
public record CustomBlockCarrier(String bedrockIdentifier, NbtMap bedrockStates, NbtMap definition, String javaBlock,
                                 Map<String, String> javaProperties) {

    public CustomBlockCarrier {
        javaProperties = Map.copyOf(javaProperties);
    }
}
