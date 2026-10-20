# Mapping data sources

Every generated table under `src/main/resources/mappings/` records where it comes from. Regenerate with the
tool named in each section (Python 3, standard library only) against the pinned inputs.

## sound/ (sounds, particles, level events)

Generator: `tools/gen_sound_mappings.py` (`python3 -I tools/gen_sound_mappings.py --ref <dir with the clones>`).

| File | Content | Sources |
|---|---|---|
| `sounds.json` | Bedrock sound name (`sound_definitions.json` key, PlaySound) -> Java 26.3 sound event + Java category | ViaBedrock `custom/sound_mappings.json`, `bedrock/sounds.json`, `custom/sound_category_mappings.json`; categories cross-checked with Mojang bedrock-samples `sounds/sound_definitions.json` |
| `sound_events.json` | LevelSoundEvent string -> entity / block-sound-type / generic variants (Bedrock sound, volume and pitch ranges); block -> sound type; note block instruments | ViaBedrock `bedrock/level_sound_event_mappings.json`, `bedrock/block_sounds.json`, `custom/note_block_instrument_mappings.json` (themselves derived from bedrock-samples `sounds.json` / `blocks.json`); bedrock-samples `blocks.json` |
| `particles.json` | SpawnParticleEffect identifiers and LevelEvent particle types (Bedrock wire id) -> Java particle with data | ViaBedrock `custom/particle_mappings.json`, `custom/level_event_particle_mappings.json`, `bedrock/particles.json`, ViaBedrock `ParticleType` enum (wire ids); hand-written replacements (generator) for particles MCProtocolLib cannot write and for ids ViaBedrock leaves unmapped |
| `level_events.json` | LevelEvent (Bedrock wire id) -> Java level event / sound / particle | ViaBedrock `custom/level_event_mappings.json` and its `LevelEvent` enums (Bedrock and Java wire ids); hand-written overrides (generator) |

Java ids are validated against MCProtocolLib 26.3 (`BuiltinSound`, `ParticleType`, `LevelEventType`, MIT) and
ViaBedrock `java/via_mappings.json` (26.3 block state and item ids, same order as the vanilla registries).
Bedrock enum names differ between ViaBedrock and the protocol library, so level events and particle types are
keyed by numeric wire id and joined at run time through the codec's type maps (`sound.BedrockIds`).

Pinned inputs: ViaBedrock `c765b78` (2026-10-19, GPL-3.0), MCProtocolLib `b4fa8b2` (26.3, MIT),
Mojang bedrock-samples `46ba6ea9` (1.26.50.4, Minecraft EULA: only names and numbers are used).
JavaToBedrock is GPL-3.0, which allows shipping tables derived from ViaBedrock's data.
