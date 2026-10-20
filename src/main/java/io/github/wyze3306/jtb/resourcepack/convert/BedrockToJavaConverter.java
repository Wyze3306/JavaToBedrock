package io.github.wyze3306.jtb.resourcepack.convert;

import io.github.wyze3306.jtb.resourcepack.BedrockPack;
import io.github.wyze3306.jtb.resourcepack.ConversionArtifacts;
import io.github.wyze3306.jtb.resourcepack.ServerDefinitions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Converts the server's Bedrock packs into one Java 26.3 resource pack, in two phases:
 * <ol>
 *     <li>{@link #convertStack} as soon as the pack stack is known: everything that only depends on
 *     the packs (pack metadata and icon, vanilla texture overrides, glyph fonts, sounds,
 *     translations, custom entity models);</li>
 *     <li>{@link #complete} once the session knows the server's custom items (item registry) and
 *     custom block states (with their Java carrier states): item models, armour equipment assets,
 *     custom block models and carrier blockstates. One phase-1 result serves every set of
 *     definitions.</li>
 * </ol>
 * Pure functions of their inputs (no I/O besides reading the shipped tables), safe from any thread;
 * the output zip is deterministic. Bump {@link #VERSION} on any change of the output so cached
 * conversions are redone.
 */
public final class BedrockToJavaConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(BedrockToJavaConverter.class);

    /** Bumped whenever the conversion output changes, to invalidate cached packs. */
    public static final int VERSION = 2;
    /** Java 26.3 resource pack format. */
    public static final int PACK_FORMAT = PackMetaConverter.PACK_FORMAT;
    /** Where the artifacts are stored inside the zip (outside {@code assets/}: ignored by the client). */
    public static final String ARTIFACTS_PATH = "jtb/artifacts.json";

    private BedrockToJavaConverter() {
    }

    /** Result of the first phase, shared by every completion of the same stack. */
    public static final class StackConversion {
        private final ConversionContext context;
        private final int packs;
        private final long nanos;

        private StackConversion(ConversionContext context, int packs, long nanos) {
            this.context = context;
            this.packs = packs;
            this.nanos = nanos;
        }

        /** Number of packs converted (resource packs that could be read). */
        public int packs() {
            return this.packs;
        }

        /** Duration of the first phase. */
        public long millis() {
            return this.nanos / 1_000_000;
        }

        public ConversionArtifacts artifacts() {
            return this.context.artifacts();
        }
    }

    /**
     * A finished Java pack.
     *
     * @param counters what was converted, for the log
     */
    public record ConvertedZip(byte[] zip, ConversionArtifacts artifacts, Map<String, Integer> counters) {
    }

    /**
     * Phase 1. Packs that cannot be read, or that are not resource packs, are skipped with a
     * warning; the result may hold no pack at all.
     *
     * @param packs the server's packs, top of the stack first
     */
    public static StackConversion convertStack(List<BedrockPack> packs) {
        long start = System.nanoTime();
        List<PackFiles> files = packs.parallelStream().map(pack -> {
            try {
                PackFiles opened = PackFiles.open(pack);
                if (!opened.manifest().isResourcePack()) {
                    LOGGER.debug("Skipping {}: not a resource pack ({})", opened.name(), opened.manifest().moduleTypes());
                    return null;
                }
                return opened;
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Skipping Bedrock pack {}: {}", pack.id(), e.getMessage());
                return null;
            }
        }).filter(Objects::nonNull).toList();
        ResourceTables tables = ResourceTables.get();
        PackStack stack = new PackStack(files);
        BedrockDefinitions definitions = new BedrockDefinitions(stack, tables);
        BedrockDefinitions.logSummary(definitions);
        ConversionContext context = new ConversionContext(stack, definitions, tables, new JavaPackWriter(),
                new ConversionArtifacts());
        PackMetaConverter.convert(context);
        if (!stack.isEmpty()) {
            VanillaTextureConverter.convert(context);
            GlyphConverter.convert(context);
            SoundConverter.convert(context);
            LangConverter.convert(context);
            EntityModelConverter.convert(context);
        }
        return new StackConversion(context, files.size(), System.nanoTime() - start);
    }

    /** Phase 2: adds what depends on the server's definitions and builds the zip. */
    public static ConvertedZip complete(StackConversion stack, ServerDefinitions definitions) {
        ConversionContext context = stack.context.derive();
        if (!stack.context.stack().isEmpty()) {
            CustomBlockConverter.convert(context, definitions.blocks());
            CustomItemConverter.convert(context, definitions.items());
        }
        context.writer().putJson(ARTIFACTS_PATH, context.artifacts().toJson());
        Map<String, Integer> counters = new TreeMap<>(stack.context.counters());
        context.counters().forEach((k, v) -> counters.merge(k, v, Integer::sum));
        return new ConvertedZip(context.writer().toZip(), context.artifacts(), counters);
    }

    /**
     * Loads the static tables and initialises the image codecs, so the first player's conversion
     * does not pay for it (a few hundred milliseconds). Blocking: call from a worker.
     */
    public static void warmUp() {
        ResourceTables.get();
        byte[] png = PackImages.encodePng(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
        PackImages.decode("warm-up.png", png);
        complete(convertStack(List.of()), ServerDefinitions.EMPTY);
    }

    /** Both phases at once (tests, tools). */
    public static ConvertedZip convert(List<BedrockPack> packs, ServerDefinitions definitions) {
        return complete(convertStack(packs), definitions);
    }

    /** Lists the converted packs' names (for logs). */
    public static List<String> packNames(StackConversion stack) {
        List<String> names = new ArrayList<>();
        stack.context.stack().topToBottom().forEach(pack -> names.add(pack.name()));
        return names;
    }
}
