package io.github.wyze3306.jtb.resourcepack.convert;

import io.github.wyze3306.jtb.resourcepack.BedrockPack;
import io.github.wyze3306.jtb.resourcepack.ServerDefinitions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Converts a whole vanilla-sized pack and logs the time. Runs only when the environment variable
 * {@code JTB_BENCHMARK_PACK} points to an extracted Bedrock resource pack, e.g. the
 * {@code resource_pack} folder of Mojang's bedrock-samples (its {@code .fsb} sounds are left out).
 */
class VanillaSizedConversionTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(VanillaSizedConversionTest.class);

    @Test
    void convertsAVanillaSizedPack() throws IOException {
        String directory = System.getenv("JTB_BENCHMARK_PACK");
        Assumptions.assumeTrue(directory != null && Files.isDirectory(Path.of(directory)), "JTB_BENCHMARK_PACK not set");
        Path root = Path.of(directory);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int count = 0;
        try (ZipOutputStream zip = new ZipOutputStream(bytes); Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = root.relativize(file).toString().replace('\\', '/');
                if (name.endsWith(".fsb")) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry(name));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
                count++;
            }
        }
        BedrockPack pack = new BedrockPack(UUID.randomUUID(), "1.0.0", "", "", "", bytes.toByteArray());
        long start = System.nanoTime();
        BedrockToJavaConverter.StackConversion stack = BedrockToJavaConverter.convertStack(List.of(pack));
        long phaseA = System.nanoTime();
        BedrockToJavaConverter.ConvertedZip converted = BedrockToJavaConverter.complete(stack, ServerDefinitions.EMPTY);
        long end = System.nanoTime();
        LOGGER.info("Vanilla-sized pack: {} files, {} MiB zipped -> Java pack {} MiB, {} entries; phase 1 {} ms, phase 2 + zip {} ms; {}",
                count, bytes.size() >> 20, converted.zip().length >> 20, ConverterTest.unzip(converted.zip()).size(),
                (phaseA - start) / 1_000_000, (end - phaseA) / 1_000_000, converted.counters());
        assertTrue(converted.counters().getOrDefault("vanilla texture overrides", 0) > 2000);
        assertArrayEquals(converted.zip(), BedrockToJavaConverter.complete(stack, ServerDefinitions.EMPTY).zip());
    }
}
