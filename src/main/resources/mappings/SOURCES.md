# Mapping data sources

Every generated table under `src/main/resources/mappings/` records where it comes from. Regenerate with the
tool named in each section (Python 3, standard library only) against the pinned inputs.

## items/ (items, enchantments, potions, recipe tags)

Generator: `tools/gen_item_mappings.py` (`python3 -I tools/gen_item_mappings.py --viabedrock <dir> --mcmeta <dir>
--geyser <dir> --gamedata <dir>`), output `items/item_mappings.json.gz` (gzip with a fixed timestamp, so the
build is reproducible). Coverage is checked by `item/ItemTranslatorTest#everyVanillaItemProjectsBackToItself`.

| Part | Content | Sources |
|---|---|---|
| `java_items` | Java 26.3 items in registry order (index = network id) with their default max stack size, max damage, cooking / brewing fuel flags and equipment slot | item order: ViaBedrock `data/java/via_mappings.json` (`items`); default components: misode mcmeta `26.3-summary/item_components/data.json` |
| `bedrock_items` | Bedrock item identifier (+ aux value) -> Java item, Bedrock name key, and the potion / block state / goat horn instrument / custom effects that tell the Java variant apart | ViaBedrock `data/custom/item_mappings.json` (Bedrock -> Java items, including data values), Bedrock names from the same file |
| `potions` | Java potion keys in registry order (network id) | GeyserMC `core/.../inventory/item/Potion.java` (Java order; `WIND_CHARGING` -> `wind_charged`, `INFESTATION` -> `infested`) |
| `enchantments` | Bedrock enchantment id -> Java enchantment key | ViaBedrock `data/custom/enchantment_mappings.json` and its generated `Enchant_Type` enum (Bedrock ids) |
| `effects` | Bedrock effect id -> Java effect id (custom potion effects, beacon powers) | ViaBedrock `data/custom/effect_mappings.json`, `data/bedrock/effects.json`, `data/java/effects.json` |
| `banner_patterns` | Bedrock banner pattern code -> Java banner pattern key | ViaBedrock `data/custom/banner_pattern_mappings.json` |
| `item_tags` | Bedrock item tags (recipe ingredients by tag: `minecraft:planks`, `minecraft:coals`...) | union of ViaBedrock `data/bedrock/item_tags.json` and PowerNukkitX gamedata `src/main/resources/gamedata/kaooot/item_tags.json` |

Pinned inputs: ViaBedrock `c765b78` (2026-10-19, GPL-3.0), misode mcmeta `26.3-summary` (Minecraft data
exports: only names and numbers are used), GeyserMC `02dbfac` (2026-10-18, MIT), PowerNukkitX gamedata
`9365be7` (branch `r/26_u5`, 2026-09-13; tags dumped from the vanilla game, no licence file: only tag names and
item identifiers are used). JavaToBedrock is GPL-3.0, which allows shipping tables
derived from ViaBedrock's data.
