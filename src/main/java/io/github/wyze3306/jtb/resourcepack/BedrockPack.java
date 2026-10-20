package io.github.wyze3306.jtb.resourcepack;

import java.util.UUID;

/**
 * A Bedrock pack as downloaded from the server: the raw zip (possibly encrypted) and how to read it.
 *
 * @param contentKey AES key of an encrypted pack ({@code PackInfoData.contentKey}), empty when the
 *                   pack is not encrypted
 * @param contentId  content identity announced with the key (checked against {@code contents.json})
 * @param subpack    selected subpack folder name, empty for none
 */
public record BedrockPack(UUID id, String version, String contentKey, String contentId, String subpack, byte[] zip) {

    public BedrockPack {
        contentKey = contentKey == null ? "" : contentKey;
        contentId = contentId == null ? "" : contentId;
        subpack = subpack == null ? "" : subpack;
    }

    public BedrockPack withSubpack(String subpack) {
        if (subpack == null || subpack.isEmpty() || subpack.equals(this.subpack)) {
            return this;
        }
        return new BedrockPack(this.id, this.version, this.contentKey, this.contentId, subpack, this.zip);
    }

    public boolean encrypted() {
        return !this.contentKey.isEmpty();
    }
}
