package io.github.wyze3306.jtb.resourcepack;

import java.util.UUID;

/**
 * A Bedrock pack as downloaded from the server: the raw zip (possibly encrypted) and how to read it.
 *
 * @param contentKey AES key of an encrypted pack, empty when the pack is not encrypted
 * @param subpack    selected subpack folder name, empty for none
 */
public record BedrockPack(UUID id, String version, String contentKey, String subpack, byte[] zip) {

    public BedrockPack {
        contentKey = contentKey == null ? "" : contentKey;
        subpack = subpack == null ? "" : subpack;
    }

    public BedrockPack withSubpack(String subpack) {
        if (subpack == null || subpack.isEmpty() || subpack.equals(this.subpack)) {
            return this;
        }
        return new BedrockPack(this.id, this.version, this.contentKey, subpack, this.zip);
    }

    public boolean encrypted() {
        return !this.contentKey.isEmpty();
    }
}
