package io.github.wyze3306.jtb.network.bedrock;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.codec.v2192.Bedrock_v2192;
import org.cloudburstmc.protocol.bedrock.codec.v2207.Bedrock_v2207;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.codec.v898.Bedrock_v898;
import org.cloudburstmc.protocol.bedrock.codec.v924.Bedrock_v924;
import org.cloudburstmc.protocol.bedrock.codec.v944.Bedrock_v944;
import org.cloudburstmc.protocol.bedrock.codec.v975.Bedrock_v975;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Bedrock codecs the proxy can speak, by protocol number. Translation targets the newest packet
 * model; older codecs work for everything those versions share with it. The mapping tables are those of
 * 1.26.50 (protocol 2193): a newer server (1.26.60, protocol 2225, as PowerNukkitX now speaks) works for
 * everything 1.26.50 already had, its new blocks and items fall back like unknown ones.
 */
public final class BedrockCodecs {

    private static final Map<Integer, BedrockCodec> CODECS = Map.ofEntries(
            Map.entry(2225, Bedrock_v2225.CODEC),
            Map.entry(2207, Bedrock_v2207.CODEC),
            Map.entry(2193, Bedrock_v2192.CODEC.toBuilder().protocolVersion(2193).build()),
            Map.entry(2192, Bedrock_v2192.CODEC),
            Map.entry(2169, Bedrock_v2168.CODEC.toBuilder().protocolVersion(2169).build()),
            Map.entry(2168, Bedrock_v2168.CODEC),
            Map.entry(975, Bedrock_v975.CODEC),
            Map.entry(944, Bedrock_v944.CODEC),
            Map.entry(924, Bedrock_v924.CODEC),
            Map.entry(898, Bedrock_v898.CODEC)
    );

    private BedrockCodecs() {
    }

    public static @Nullable BedrockCodec byProtocol(int protocolVersion) {
        return CODECS.get(protocolVersion);
    }
}
