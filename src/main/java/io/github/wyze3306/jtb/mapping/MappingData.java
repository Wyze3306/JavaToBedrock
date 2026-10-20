package io.github.wyze3306.jtb.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads every static Bedrock <-> Java table once at startup.
 */
public final class MappingData {

    private static final Logger LOGGER = LoggerFactory.getLogger(MappingData.class);
    private static volatile boolean loaded;

    private MappingData() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        long start = System.nanoTime();
        JavaRegistries.load();
        loaded = true;
        LOGGER.info("Mappings loaded in {} ms", (System.nanoTime() - start) / 1_000_000);
    }
}
