package io.github.wyze3306.jtb.resourcepack;

/**
 * Result of converting a server's Bedrock pack stack: the Java pack plus what translators need to
 * know about it (custom item models, glyphs, sounds, entity models...).
 */
public record PackConversion(JavaPack pack, ConversionArtifacts artifacts) {
}
