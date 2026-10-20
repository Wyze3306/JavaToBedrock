# Mapping data sources

Every generated table under `src/main/resources/mappings/` records where it comes from. Regenerate with the
tool named in each section (Python 3, standard library only) against the pinned inputs.

## block/ and world/ (block states, light, biomes, block entities)

Generator: `tools/gen_world_mappings.py` (`python3 -I tools/gen_world_mappings.py --ref <dir with the clones>`).
It fails when a Bedrock palette hash cannot be recomputed, when the Java state order differs from
`via_mappings.json`, or when a palette state has no Java state.

| File | Content | Sources |
|---|---|---|
| `block/blocks.bin.gz` | Java 26.3 blocks, properties and the 35723 global state ids (order rebuilt from the properties and checked state by state); per Java state: light emission and opacity, faces its light occlusion shape covers, motion-blocking / fluid / air flags, waterlogged twin, block entity type; the 22091 Bedrock 1.26.50 states (names, typed properties, hashed network ids recomputed with FNV-1a over little-endian NBT) with their Java state; the best Bedrock state per Java state; potted plants | PowerNukkitX gamedata `r/26_u5` @9365be7 `kaooot/block_palette.nbt` (Bedrock states, hashes, palette order); ViaBedrock @c765b78 (GPL-3.0) `custom/blockstate_mappings.json` (Bedrock -> Java, 22091/22091), `java/via_mappings.json` (Java ids), `custom/pre_waterlogged_blockstates.json`, `custom/potted_blockstates.json`, `java/heightmap_blockstates.nbt`; GeyserMC mappings @81700ab (MIT) `blocks.nbt` (Java -> Bedrock, and 4 Bedrock states where it is right and ViaBedrock is not: hanging sulfur spike, downward jigsaw, unstable TNT, all-pores mushroom stem), `block_shapes.nbt` (outline shapes for light occlusion); mcmeta `26.3-summary/blocks` (properties, value order, defaults); `tools/data/java_light_26_1.json` |
| `tools/data/java_light_26_1.json` (generator input) | light emission and filter of each block's default state | PrismarineJS minecraft-data (MIT) `data/pc/26.1/blocks.json`, fetched 2026-10-20. The generator derives per-state values: lit furnaces/ores/lamps/torches/campfires, candles, sea pickles, respawn anchors, light blocks, glow berries, glow lichen, copper bulbs, trial spawners and vaults (vanilla rules), double slabs and full snow opaque, waterlogged states at least 1, and rules for the 118 blocks newer than 26.1 |
| `world/biomes.json` | Bedrock biome ids (89) and names -> Java biome keys | PowerNukkitX gamedata `kaooot/biomes.json`; GeyserMC mappings `biomes.json` inverted (62 ids); the 27 legacy Bedrock-only biomes map to their closest Java biome (hand-written table in the generator, no upstream source) |
| `world/block_entities.json` | Bedrock banner pattern codes -> Java pattern ids | ViaBedrock `custom/banner_pattern_mappings.json` |

Coverage: every one of the 22091 vanilla Bedrock 1.26.50 states maps to a Java 26.3 state (unknown/`minecraft:unknown`
shows as air); 35723/35723 Java states have a Bedrock state for the reverse direction, 28019 of them round-trip exactly
(the others are Java-only variants such as fence connections). Custom server blocks get unused vanilla Java states
(1516 carrier states: note block 1349, then the free mushroom block states) chosen at run time by `block.CustomBlockAllocator`.
