package io.github.wyze3306.jtb.block;

import org.cloudburstmc.nbt.NbtMap;

import java.util.List;

/**
 * A block defined by the Bedrock server ({@code StartGamePacket.blockProperties}) that the vanilla
 * palette does not know, with every state it can take.
 *
 * @param identifier namespaced Bedrock identifier, e.g. {@code mymod:ruby_block}
 * @param definition the server's definition NBT ({@code components}, {@code permutations},
 *                   {@code properties}, {@code menu_category}, {@code vanilla_block_data}...), as sent
 * @param states     the states, in permutation order (the cartesian product of the properties and
 *                   trait states, first property varying slowest)
 */
public record CustomBlock(String identifier, NbtMap definition, List<State> states) {

    /**
     * One state of a custom block.
     *
     * @param runtimeId   the runtime id the server uses for it
     * @param states      the {@code states} compound (exact tag types)
     * @param javaState   the Java state shown for it (a {@linkplain CustomBlockAllocator carrier} or a fallback)
     * @param permutation index of this state in {@link CustomBlock#states()}
     */
    public record State(int runtimeId, NbtMap states, int javaState, int permutation) {
    }
}
