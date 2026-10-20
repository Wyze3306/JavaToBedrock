package io.github.wyze3306.jtb.world;

import io.github.wyze3306.jtb.mapping.JavaRegistries;
import io.github.wyze3306.jtb.network.java.JavaConfiguration;
import io.github.wyze3306.jtb.world.chunk.BiomeTable;
import it.unimi.dsi.fastutil.Pair;
import net.kyori.adventure.key.Key;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtMapBuilder;
import org.cloudburstmc.protocol.bedrock.data.biome.BiomeDefinitionData;
import org.cloudburstmc.protocol.bedrock.packet.BiomeDefinitionListPacket;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The biomes of the session's server: vanilla Bedrock biomes map to vanilla Java biomes
 * ({@link BiomeMappings}); custom biomes ({@code BiomeDefinitionListPacket} entries with an id that
 * is not vanilla) become extra entries of the Java biome registry when the configuration phase is
 * still open, built from {@code minecraft:plains} with the custom biome's climate and water colour,
 * otherwise they show as plains.
 */
final class WorldBiomes {

    private static final Key BIOME_REGISTRY = Key.key("minecraft:worldgen/biome");

    private BiomeTable table;

    WorldBiomes() {
        this.table = new BiomeTable(BiomeMappings.table(), JavaRegistries.registry(BIOME_REGISTRY.asString()).entries().size());
    }

    BiomeTable table() {
        return this.table;
    }

    /**
     * Registers the server's custom biomes.
     *
     * @return true when the table changed
     */
    boolean onDefinitions(BiomeDefinitionListPacket packet, JavaConfiguration configuration, Logger logger, String player) {
        List<String> names = packet.getBiomeStringList();
        List<String> customNames = new ArrayList<>();
        List<Integer> customIds = new ArrayList<>();
        List<RegistryEntry> entries = new ArrayList<>();
        JavaRegistries.Registry registry = JavaRegistries.registry(BIOME_REGISTRY.asString());
        NbtMap plains = registry.entries().get(BiomeMappings.plains()).getData() instanceof NbtMap map ? map : NbtMap.EMPTY;
        for (Pair<Short, BiomeDefinitionData> pair : packet.getBiomes()) {
            int nameIndex = pair.key() & 0xFFFF;
            BiomeDefinitionData data = pair.value();
            if (nameIndex >= names.size() || data.getId() == null || data.getId() < 0) {
                continue;
            }
            String name = names.get(nameIndex);
            if (BiomeMappings.bedrockId(name) >= 0) {
                continue;
            }
            customNames.add(name);
            customIds.add(data.getId() & 0xFFFF);
            entries.add(new RegistryEntry(javaKey(name), element(plains, data)));
        }
        if (customIds.isEmpty()) {
            return false;
        }
        int max = customIds.stream().mapToInt(Integer::intValue).max().orElse(0);
        int[] ids = Arrays.copyOf(this.table.javaByBedrockId(), Math.max(max + 1, this.table.javaByBedrockId().length));
        Arrays.fill(ids, this.table.javaByBedrockId().length, ids.length, -1);
        int size = this.table.registrySize();
        if (configuration.isConfiguring() && !configuration.isBedrockReady()) {
            int first = configuration.addRegistryEntries(BIOME_REGISTRY, entries);
            for (int i = 0; i < customIds.size(); i++) {
                ids[customIds.get(i)] = first + i;
            }
            size = first + entries.size();
            logger.info("[{}] {} custom biomes added to the Java biome registry: {}", player, entries.size(), customNames);
        } else {
            for (int id : customIds) {
                ids[id] = BiomeMappings.plains();
            }
            logger.info("[{}] {} custom biomes shown as plains (registries already sent)", player, customIds.size());
        }
        this.table = new BiomeTable(ids, size);
        return true;
    }

    private static Key javaKey(String name) {
        String key = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.:/-]", "_");
        if (!key.contains(":")) {
            key = "jtb:" + key;
        }
        return Key.key(key);
    }

    private static NbtMap element(NbtMap plains, BiomeDefinitionData data) {
        NbtMapBuilder builder = plains.toBuilder()
                .putFloat("temperature", data.getTemperature())
                .putFloat("downfall", Math.clamp(data.getDownfall(), 0f, 1f))
                .putBoolean("has_precipitation", data.isRain());
        if (data.getMapWaterColor() != null) {
            int rgb = data.getMapWaterColor().getRGB() & 0xFFFFFF;
            NbtMap effects = plains.getCompound("effects", NbtMap.EMPTY);
            builder.putCompound("effects", effects.toBuilder().putString("water_color", String.format("#%06x", rgb)).build());
        }
        return builder.build();
    }
}
