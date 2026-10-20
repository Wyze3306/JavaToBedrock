package io.github.wyze3306.jtb.resourcepack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Converts the server's Bedrock packs into one Java 26.3 resource pack.
 */
public final class BedrockToJavaConverter {

    /** Bumped whenever the conversion output changes, to invalidate cached packs. */
    public static final int VERSION = 1;
    /** Java 26.3 resource pack format. */
    public static final int PACK_FORMAT = 97;

    private BedrockToJavaConverter() {
    }

    public static ConvertedZip convert(List<BedrockPack> packs) throws IOException {
        ConversionArtifacts artifacts = new ConversionArtifacts();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta"));
            String mcmeta = "{\"pack\":{\"description\":\"Pack Bedrock converti par JavaToBedrock\","
                    + "\"min_format\":[" + PACK_FORMAT + ",0],\"max_format\":[" + PACK_FORMAT + ",1]}}";
            zip.write(mcmeta.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return new ConvertedZip(bytes.toByteArray(), artifacts);
    }

    public record ConvertedZip(byte[] zip, ConversionArtifacts artifacts) {
    }
}
