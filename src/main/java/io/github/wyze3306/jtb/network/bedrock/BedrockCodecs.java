package io.github.wyze3306.jtb.network.bedrock;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Bedrock codecs the proxy can speak, by protocol number: Bedrock 1.26.60 (protocol 2225) only, the
 * version the mapping tables and the translators target.
 */
public final class BedrockCodecs {

    private static final Map<Integer, BedrockCodec> CODECS = Map.of(2225, Bedrock_v2225.CODEC);

    private BedrockCodecs() {
    }

    public static @Nullable BedrockCodec byProtocol(int protocolVersion) {
        return CODECS.get(protocolVersion);
    }
}
