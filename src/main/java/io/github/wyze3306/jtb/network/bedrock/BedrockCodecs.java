package io.github.wyze3306.jtb.network.bedrock;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168;
import org.cloudburstmc.protocol.bedrock.codec.v2192.Bedrock_v2192;
import org.cloudburstmc.protocol.bedrock.codec.v898.Bedrock_v898;
import org.cloudburstmc.protocol.bedrock.codec.v924.Bedrock_v924;
import org.cloudburstmc.protocol.bedrock.codec.v944.Bedrock_v944;
import org.cloudburstmc.protocol.bedrock.codec.v975.Bedrock_v975;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Bedrock codecs the proxy can speak, by protocol number. Translation targets the newest packet
 * model; older codecs work for everything those versions share with it.
 */
public final class BedrockCodecs {

    private static final Map<Integer, BedrockCodec> CODECS = Map.of(
            2193, Bedrock_v2192.CODEC.toBuilder().protocolVersion(2193).build(),
            2192, Bedrock_v2192.CODEC,
            2169, Bedrock_v2168.CODEC.toBuilder().protocolVersion(2169).build(),
            2168, Bedrock_v2168.CODEC,
            975, Bedrock_v975.CODEC,
            944, Bedrock_v944.CODEC,
            924, Bedrock_v924.CODEC,
            898, Bedrock_v898.CODEC
    );

    private BedrockCodecs() {
    }

    public static @Nullable BedrockCodec byProtocol(int protocolVersion) {
        return CODECS.get(protocolVersion);
    }
}
