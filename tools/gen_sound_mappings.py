#!/usr/bin/env python3
"""Generates the sound subsystem mapping tables (src/main/resources/mappings/sound/).

Bedrock 1.26.50 (protocol 2193) -> Java 26.3 (protocol 777):

* sounds.json        Bedrock sound name (PlaySound, sound_definitions.json key) -> [Java sound event, Java category]
* sound_events.json  LevelSoundEvent string -> variants ("" generic, "e:<entity>", "b:<block sound type>")
                     -> [Bedrock sound name, min volume, max volume, min pitch, max pitch];
                     plus the block -> block sound type table and the note block instrument sounds
* particles.json     SpawnParticleEffect identifiers and LevelEvent particle types (by numeric id) -> Java particle
* level_events.json  LevelEvent numeric id -> Java level event / sound / particle

Sources (read-only inputs, see src/main/resources/mappings/SOURCES.md):
  ViaBedrock data + enums (GPL-3.0), MCProtocolLib 26.3 enums (MIT), Mojang bedrock-samples (cross-check only).

Every Java name is validated against MCProtocolLib 26.3 (BuiltinSound, ParticleType, LevelEventType) and every
block state / item against the 26.3 registries, so the proxy never sends an id the client does not know.
Particle types whose MCProtocolLib writer is missing (GEYSER*, DRAGON_BREATH, EFFECT, INSTANT_EFFECT,
TINTED_LEAVES, FLASH: writing them corrupts the stream) are replaced by safe look-alikes and the build fails if
one is left.

Usage: python3 -I tools/gen_sound_mappings.py [--ref /tmp/jtb/ref] [--out src/main/resources/mappings/sound]
Python 3 standard library only.
"""

import argparse
import json
import os
import re
import sys

# --------------------------------------------------------------------------------------------- hand overrides

# Java particle types MCProtocolLib 26.3 cannot write (MinecraftTypes.writeParticleData has no branch for them).
UNWRITABLE_PARTICLES = {"GEYSER", "GEYSER_BASE", "GEYSER_POOF", "GEYSER_PLUME", "DRAGON_BREATH", "EFFECT",
                        "INSTANT_EFFECT", "TINTED_LEAVES", "FLASH"}

# Particle types that need data, and the data kind they take (MCProtocolLib writer).
PARTICLE_DATA_KIND = {
    "BLOCK": "block", "BLOCK_MARKER": "block", "FALLING_DUST": "block", "DUST_PILLAR": "block", "BLOCK_CRUMBLE": "block",
    "DUST": "dust", "DUST_COLOR_TRANSITION": "transition", "ENTITY_EFFECT": "color", "ITEM": "item",
    "SCULK_CHARGE": "roll", "SHRIEK": "shriek", "TRAIL": "trail", "VIBRATION": "vibration",
}

# Dragon breath is purple; DUST is the closest writable look-alike.
DRAGON_BREATH_DUST = {"type": "DUST", "data": {"dust": [0xB21FE3, 1.5]}}

# Safe replacements for particle mappings that would use an unwritable type, by source key.
UNSAFE_REPLACEMENTS = {
    "minecraft:dragon_breath_fire": DRAGON_BREATH_DUST,
    "minecraft:dragon_breath_lingering": DRAGON_BREATH_DUST,
    "minecraft:dragon_breath_trail": DRAGON_BREATH_DUST,
    # Biome-tinted falling leaves: green falling specks (falling_dust takes the block's map colour).
    "minecraft:biome_tinted_leaves_particle": {"type": "FALLING_DUST",
                                               "data": {"block": "oak_leaves[distance=7,persistent=false,waterlogged=false]"}},
    "minecraft:geyser_base": {"type": "CLOUD", "speed": 0.05, "count": 4, "ox": 0.3, "oz": 0.3},
    "minecraft:geyser_plume": {"type": "CLOUD", "speed": 0.15, "count": 8, "ox": 0.2, "oy": 0.6, "oz": 0.2},
    "minecraft:geyser_poof": {"type": "POOF", "speed": 0.05, "count": 6, "ox": 0.3, "oy": 0.3, "oz": 0.3},
    "particle:DragonBreath": DRAGON_BREATH_DUST,
    "particle:DragonBreathFire": DRAGON_BREATH_DUST,
    "particle:DragonBreathTrail": DRAGON_BREATH_DUST,
}

# SpawnParticleEffect identifiers ViaBedrock leaves unmapped but Java 26.3 has a match for.
EXTRA_PARTICLE_EFFECTS = {
    "minecraft:orange_poplar_leaves_particle": {"type": "ORANGE_POPLAR_LEAVES"},
    "minecraft:red_poplar_leaves_particle": {"type": "RED_POPLAR_LEAVES"},
    "minecraft:yellow_poplar_leaves_particle": {"type": "YELLOW_POPLAR_LEAVES"},
    "minecraft:breaking_item_icon": {"type": "ITEM", "speed": 0.1, "count": 8, "ox": 0.1, "oy": 0.1, "oz": 0.1,
                                     "data": {"item": "stick"}},
    "minecraft:breaking_item_terrain": {"type": "BLOCK", "speed": 0.1, "count": 8, "ox": 0.1, "oy": 0.1, "oz": 0.1,
                                        "data": {"block": "dirt"}},
    "minecraft:block_destruct": {"type": "POOF", "count": 1},
    "minecraft:breeze_ground_particle": {"type": "SMALL_GUST", "count": 1},
    "minecraft:mobspell_lingering": {"type": "ENTITY_EFFECT", "ox": 0.25, "oy": 0.25, "oz": 0.25, "count": 4,
                                     "data": {"color": -1}},
    "minecraft:cauldron_spell_emitter": {"type": "ENTITY_EFFECT", "ox": 0.2, "oy": 0.1, "oz": 0.2, "count": 4,
                                         "data": {"color": -1}},
    "minecraft:fish_hook_particle": {"type": "FISHING", "count": 1},
    "minecraft:fish_pos_particle": {"type": "BUBBLE", "count": 1},
}

# LevelEvent particle types (Bedrock numeric ParticleType id) missing in ViaBedrock.
EXTRA_LEVEL_EVENT_PARTICLES = {
    102: {"type": "ORANGE_POPLAR_LEAVES"},
    103: {"type": "RED_POPLAR_LEAVES"},
    104: {"type": "YELLOW_POPLAR_LEAVES"},
    22: {"type": "MYCELIUM"},           # TownAura
    43: {"type": "CRIT"},               # TrackingEmitter
    47: {"type": "ELDER_GUARDIAN"},     # MobAppearance
    53: {"type": "FIREWORK"},           # FireworksStarter
    62: {"type": "SNEEZE"},             # Sneeze
}

# Bedrock LevelEvent numeric id -> mapping, overriding or completing ViaBedrock. Java level events are given by
# MCProtocolLib LevelEventType name.
LEVEL_EVENT_OVERRIDES = {
    1050: {"sound": "camera.take_picture"},                                  # SoundCamera
    2007: {"particle": {"type": "POOF", "speed": 0.02, "count": 20, "ox": 0.3, "oy": 0.5, "oz": 0.3}},  # DeathSmoke
    2011: {"particle": {"type": "HAPPY_VILLAGER", "count": 6, "ox": 0.3, "oy": 0.3, "oz": 0.3}},        # CropEaten
    # 2021 ParticlesDestroyBlockNoSound: 26.3 has a particles-only destroy event (ViaBedrock uses brushing).
    2021: {"event": "PARTICLES_DESTROY_BLOCK"},
    3615: {"event": "PARTICLES_TRIAL_SPAWNER_DETECT_PLAYER_OMINOUS", "data": 1},  # TrialSpawnerDetectionCharged
    9815: {"event": "PARTICLES_SMASH_ATTACK", "data": 750},                  # SmashAttackGroundDust
}

# Bedrock LevelEvents translated in code (SoundModule) or deliberately ignored: kept out of the table.
LEVEL_EVENTS_IN_CODE = {
    1006,              # SoundPlayRecording: legacy, jukeboxes use PlaySound + RecordStarted
    1900, 1901, 1902, 1903,  # custom music: LevelEventGeneric
    2006,              # GuardianGhost: game event + curse sound
    2009,              # ParticleGenericSpawn: data is a particle type
    2023, 2024, 2027,  # teleport trail, point cloud, vibration: LevelEventGeneric payloads
    2025,              # explosion: emitter size depends on the radius in data
    2035, 2036, 2037, 2038, 2039, 2040,  # sculk / sonic / dust plume: LevelEventGeneric payloads
    3001, 3002, 3003, 3004,  # weather
    3005, 3006, 3007,  # pause / sim time: no Java equivalent
    3600, 3601, 3602,  # block cracking
    3617,              # all players sleeping
    4000,              # SetData (legacy block extra data)
    9801, 9810,        # sleeping players (generic), jump prevented
    9816,              # creaking heart trail (generic)
}

# LevelSoundEvents completed or added by hand: event -> variant -> [bedrock sound, vmin, vmax, pmin, pmax].
EXTRA_SOUND_EVENTS = {
    "note.bass": {"": ["note.bass", 1.0, 1.0, 1.0, 1.0]},
}

# LevelSoundEvents handled in code or without any sound.
SOUND_EVENTS_IN_CODE = {"note", "record.null", "undefined", "default", "pushed_by_player"}

BEDROCK_CATEGORY_TO_JAVA = {
    "ambient": "ambient", "block": "block", "bottle": "neutral", "hostile": "hostile", "music": "music",
    "neutral": "neutral", "player": "player", "record": "record", "sparkler": "neutral", "ui": "ui",
    "weather": "weather", "voice": "voice", "master": "master",
}


# --------------------------------------------------------------------------------------------- helpers

def fail(message):
    print("ERROR: " + message, file=sys.stderr)
    sys.exit(1)


def read_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def read_text(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def enum_with_values(path):
    """`Name(123)` constants of a ViaBedrock enum, in declaration order."""
    body = read_text(path)
    body = body[body.index("public enum"):]
    body = body[body.index("{") + 1:body.index(";")]
    return [(name, int(value, 0)) for name, value in re.findall(r"([A-Za-z0-9_]+)\(\s*(-?(?:0x)?[0-9A-Fa-f]+)\s*\)", body)]


def mcpl_enum_names(path):
    body = read_text(path)
    body = body[body.index("public enum"):]
    body = body[body.index("{") + 1:body.index(";")]
    return [name.strip().split("(")[0].strip() for name in body.split(",") if name.strip()]


def strip_ns(key):
    return key[len("minecraft:"):] if key.startswith("minecraft:") else key


class Java:
    """Java 26.3 ids: MCProtocolLib enums + ViaBedrock's ordered registries (verified identical by r7)."""

    def __init__(self, ref):
        mcpl = os.path.join(ref, "GeyserMC_MCProtocolLib/protocol/src/main/java/org/geysermc/mcprotocollib/protocol/data/game")
        sounds = re.findall(r'^\s+[A-Z0-9_]+\("([^"]+)"\)', read_text(os.path.join(mcpl, "level/sound/BuiltinSound.java")), re.M)
        self.sounds = set(sounds)
        self.particles = mcpl_enum_names(os.path.join(mcpl, "level/particle/ParticleType.java"))
        events = re.findall(r"^\s+([A-Z0-9_]+)\((\d+)\)", read_text(os.path.join(mcpl, "level/event/LevelEventType.java")), re.M)
        self.level_events = {name: int(value) for name, value in events}
        via = read_json(os.path.join(ref, "ViaBedrock/src/main/resources/assets/viabedrock/data/java/via_mappings.json"))
        if len(via["sounds"]) != len(sounds) or any(a != b for a, b in zip(via["sounds"], sounds)):
            fail("BuiltinSound order differs from the 26.3 sound registry")
        if [p.upper() for p in via["particles"]] != [p for p in self.particles] and len(via["particles"]) != len(self.particles):
            fail("ParticleType count differs from the 26.3 particle registry")
        self.block_states = {s: i for i, s in enumerate(via["blockstates"])}
        self.items = {s: i for i, s in enumerate(via["items"])}
        # Registry name -> MCProtocolLib constant (two trial spawner particles are named differently).
        self.particle_by_key = {}
        for key, constant in zip(via["particles"], self.particles):
            self.particle_by_key[key] = constant

    def particle(self, key):
        constant = self.particle_by_key.get(strip_ns(key))
        if constant is None:
            fail("unknown Java particle " + key)
        return constant

    def block_state(self, state):
        state = strip_ns(state)
        if "[" in state:
            name, props = state[:-1].split("[", 1)
            state = name + "[" + ",".join(sorted(props.split(","))) + "]"
        if state not in self.block_states:
            fail("unknown Java block state " + state)
        return self.block_states[state]

    def item(self, item):
        item = strip_ns(item)
        if item not in self.items:
            fail("unknown Java item " + item)
        return self.items[item]

    def level_event(self, name):
        if name not in self.level_events:
            fail("unknown Java level event " + name)
        return self.level_events[name]


# --------------------------------------------------------------------------------------------- particles

def convert_particle(java, value, key):
    """ViaBedrock particle (string or object) -> our spec {type, ox, oy, oz, speed, count, data}."""
    if key in UNSAFE_REPLACEMENTS:
        value = UNSAFE_REPLACEMENTS[key]
        return finish_spec(java, dict(value), key)
    if isinstance(value, str):
        return finish_spec(java, {"type": java.particle(value)}, key)
    spec = {"type": java.particle(value["particle"])}
    for src, dst in (("offset_x", "ox"), ("offset_y", "oy"), ("offset_z", "oz"), ("speed", "speed"), ("count", "count")):
        if src in value and value[src] != 0:
            spec[dst] = value[src]
    args = value.get("arguments", [])
    kind = PARTICLE_DATA_KIND.get(spec["type"])
    if args:
        values = [a["value"] for a in args]
        types = [a["type"] for a in args]
        if kind == "block" and types == ["block_state"]:
            spec["data"] = {"block": values[0]}
        elif kind == "color" and types == ["int"]:
            spec["data"] = {"color": values[0]}
        elif kind == "dust" and types == ["int", "float"]:
            spec["data"] = {"dust": [values[0] & 0xFFFFFF, values[1]]}
        elif kind == "transition" and types == ["int", "int", "float"]:
            spec["data"] = {"transition": [values[0] & 0xFFFFFF, values[1] & 0xFFFFFF, values[2]]}
        elif kind == "item" and types == ["item_stack"]:
            spec["data"] = {"item": values[0]}
        elif kind == "shriek" and types == ["var_int"]:
            spec["data"] = {"shriek": values[0]}
        elif kind == "roll" and types == ["float"]:
            spec["data"] = {"roll": values[0]}
        elif kind == "trail" and types == ["double", "double", "double", "int", "var_int"]:
            spec["data"] = {"trail": [values[3] & 0xFFFFFF, values[4]]}
        elif spec["type"] in UNWRITABLE_PARTICLES:
            pass  # rejected below
        else:
            fail("unsupported particle arguments for %s: %s" % (key, args))
    return finish_spec(java, spec, key)


def finish_spec(java, spec, key):
    if spec["type"] in UNWRITABLE_PARTICLES:
        fail("particle %s maps to %s, which MCProtocolLib cannot write; add a safe replacement" % (key, spec["type"]))
    if spec["type"] not in java.particles:
        fail("unknown particle type %s for %s" % (spec["type"], key))
    data = spec.get("data")
    kind = PARTICLE_DATA_KIND.get(spec["type"])
    if data is not None:
        if kind is None or kind not in data:
            fail("particle %s: data %s does not fit %s" % (key, data, spec["type"]))
        if "block" in data and isinstance(data["block"], str):
            data["block"] = java.block_state(data["block"])
        if "item" in data and isinstance(data["item"], str):
            data["item"] = java.item(data["item"])
    return spec


# --------------------------------------------------------------------------------------------- main

def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    parser.add_argument("--ref", default="/tmp/jtb/ref")
    parser.add_argument("--out", default=os.path.join(root, "src/main/resources/mappings/sound"))
    args = parser.parse_args()

    vb = os.path.join(args.ref, "ViaBedrock/src/main/resources/assets/viabedrock/data")
    vb_enums = os.path.join(args.ref, "ViaBedrock/src/main/java/net/raphimc/viabedrock/protocol/data/enums")
    samples = os.path.join(args.ref, "Mojang_bedrock-samples/resource_pack")
    cloudburst = os.path.join(args.ref, "PowerNukkitX_Protocol/bedrock-codec/src/main/java/org/cloudburstmc/protocol/bedrock/data")
    java = Java(args.ref)
    stats = {}

    # ---- sounds: bedrock name -> java sound + category
    bedrock_categories = read_json(os.path.join(vb, "bedrock/sounds.json"))
    vb_sounds = read_json(os.path.join(vb, "custom/sound_mappings.json"))
    definitions = read_json(os.path.join(samples, "sounds/sound_definitions.json"))["sound_definitions"]
    sounds = {}
    for name in sorted(set(vb_sounds) | set(definitions)):
        target = vb_sounds.get(name)
        if target is None:
            fail("no Java sound for vanilla Bedrock sound " + name)
        target = strip_ns(target)
        if target not in java.sounds:
            fail("unknown Java sound %s (for %s)" % (target, name))
        definition = definitions.get(name) or {}
        category = definition.get("category") or bedrock_categories.get(name) or "neutral"
        if category not in BEDROCK_CATEGORY_TO_JAVA:
            fail("unknown Bedrock sound category " + category)
        entry = [target, BEDROCK_CATEGORY_TO_JAVA[category]]
        # Hearing distances where Bedrock deviates from the "16 blocks per unit of volume" rule both editions
        # share by default: max_distance (fixed range), min_distance (full-volume radius, thunder and rain).
        max_distance = definition.get("max_distance")
        min_distance = definition.get("min_distance")
        if (max_distance or 0) > 0 or (min_distance or 0) > 0:
            entry += [float(max_distance or 0), float(min_distance or 0)]
        sounds[name] = entry
    stats["bedrock sound names"] = "%d/%d vanilla sound_definitions keys (+%d extra)" % (
        sum(1 for n in definitions if n in sounds), len(definitions), len(sounds) - len(definitions))

    # ---- level sound events
    cloudburst_events = re.findall(r'^\s+[A-Z0-9_]+\("([^"]+)"\)', read_text(os.path.join(cloudburst, "SoundEvent.java")), re.M)
    vb_events = read_json(os.path.join(vb, "bedrock/level_sound_event_mappings.json"))
    events = {}
    variants = 0
    for event in sorted(vb_events):
        mapping = vb_events[event]
        if mapping is None:
            continue
        out = {}
        for variant, configured in mapping.items():
            prefix, _, rest = variant.partition(":")
            key = "" if variant == "" else ("e:" + rest if prefix == "entity" else "b:" + rest)
            if prefix not in ("", "entity", "block"):
                fail("unknown LevelSoundEvent variant " + variant)
            sound = configured["sound"]
            if sound not in sounds:
                fail("LevelSoundEvent %s uses unknown sound %s" % (event, sound))
            volume = configured.get("volume", 1.0)
            pitch = configured.get("pitch", 1.0)
            vmin, vmax = (volume, volume) if not isinstance(volume, list) else volume
            pmin, pmax = (pitch, pitch) if not isinstance(pitch, list) else pitch
            out[key] = [sound, vmin, vmax, pmin, pmax]
            variants += 1
        events[event] = out
    for event, mapping in EXTRA_SOUND_EVENTS.items():
        for variant in mapping.values():
            if variant[0] not in sounds:
                fail("extra sound event %s uses unknown sound %s" % (event, variant[0]))
        events.setdefault(event, {}).update(mapping)
    unknown = sorted(set(events) - set(cloudburst_events))
    if unknown:
        fail("LevelSoundEvents unknown to the protocol library: %s" % unknown)
    unmapped = sorted(e for e in cloudburst_events if e not in events and e not in SOUND_EVENTS_IN_CODE)
    stats["LevelSoundEvents"] = "%d/%d protocol ids mapped (%d variants), %d handled in code, unmapped: %s" % (
        len(events), len(cloudburst_events), variants, len(SOUND_EVENTS_IN_CODE & set(cloudburst_events)), unmapped)

    block_sounds = read_json(os.path.join(vb, "bedrock/block_sounds.json"))
    sample_blocks = read_json(os.path.join(samples, "blocks.json"))
    for name, block in sample_blocks.items():
        if name == "format_version" or not isinstance(block, dict) or "sound" not in block:
            continue
        block_sounds.setdefault("minecraft:" + name if ":" not in name else name, block["sound"])
    stats["block sound types"] = "%d blocks" % len(block_sounds)

    instruments = [name for name, _ in sorted(enum_with_values(os.path.join(vb_enums, "bedrock/NoteBlockInstrument.java")), key=lambda e: e[1])]
    instrument_sounds = read_json(os.path.join(vb, "custom/note_block_instrument_mappings.json"))
    note_sounds = []
    for name in instruments:
        sound = instrument_sounds[name]
        if sound not in sounds:
            fail("note instrument %s uses unknown sound %s" % (name, sound))
        note_sounds.append(sound)

    # ---- particles (SpawnParticleEffect identifiers)
    vb_particles = read_json(os.path.join(vb, "custom/particle_mappings.json"))
    vanilla_effects = read_json(os.path.join(vb, "bedrock/particles.json"))
    effects = {}
    for key, value in vb_particles.items():
        if value is None and key not in EXTRA_PARTICLE_EFFECTS:
            continue
        effects[key] = convert_particle(java, value, key) if value is not None else None
    for key, value in EXTRA_PARTICLE_EFFECTS.items():
        if effects.get(key) is None:
            effects[key] = finish_spec(java, dict(value), key)
    effects = {k: v for k, v in sorted(effects.items()) if v is not None}
    stats["particle effects"] = "%d/%d vanilla identifiers mapped, unmapped: %s" % (
        sum(1 for e in vanilla_effects if e in effects), len(vanilla_effects), sorted(e for e in vanilla_effects if e not in effects))

    # ---- level event particles (ParticleType numeric ids)
    particle_types = {name: value for name, value in enum_with_values(os.path.join(vb_enums, "bedrock/ParticleType.java"))}
    vb_level_particles = read_json(os.path.join(vb, "custom/level_event_particle_mappings.json"))
    level_particles = {}
    for name, value in vb_level_particles.items():
        if value is None:
            continue
        level_particles[particle_types[name]] = convert_particle(java, value, "particle:" + name)
    for type_id, value in EXTRA_LEVEL_EVENT_PARTICLES.items():
        level_particles.setdefault(type_id, finish_spec(java, dict(value), "particle:%d" % type_id))
    stats["LevelEvent particle types"] = "%d mapped, missing ids 1-104: %s" % (
        len(level_particles), [i for i in range(1, 105) if i not in level_particles])

    # ---- level events
    level_event_ids = {name: value for name, value in enum_with_values(os.path.join(vb_enums, "bedrock/LevelEvent.java"))}
    java_vb_events = {name: value for name, value in enum_with_values(os.path.join(vb_enums, "java/LevelEvent.java"))}
    java_by_id = {v: k for k, v in java.level_events.items()}

    def java_event(name):
        # ViaBedrock java names -> 26.3 numbers -> MCProtocolLib constant (some names differ, e.g. 2001).
        if name in java_vb_events:
            number = java_vb_events[name]
            if number not in java_by_id:
                fail("Java level event %s (%d) unknown to MCProtocolLib" % (name, number))
            return number
        return java.level_event(name)

    vb_level_events = read_json(os.path.join(vb, "custom/level_event_mappings.json"))
    level_events = {}
    for name, value in vb_level_events.items():
        event_id = level_event_ids[name]
        if value is None or event_id in LEVEL_EVENTS_IN_CODE or event_id == 0x4000:
            continue
        if isinstance(value, str):
            level_events[event_id] = {"event": java_event(value)}
        elif "particle" in value:
            level_events[event_id] = {"particle": convert_particle(java, value, "event:" + name)}
        else:
            out = {}
            if "sound" in value:
                if value["sound"] not in sounds:
                    fail("level event %s uses unknown sound %s" % (name, value["sound"]))
                out["sound"] = value["sound"]
            if "event" in value:
                out["event"] = java_event(value["event"])
            if "data" in value:
                out["data"] = value["data"]
            level_events[event_id] = out
    for event_id, value in LEVEL_EVENT_OVERRIDES.items():
        out = dict(value)
        if "event" in out:
            out["event"] = java.level_event(out["event"])
        if "particle" in out:
            out["particle"] = finish_spec(java, dict(out["particle"]), "event:%d" % event_id)
        if "sound" in out and out["sound"] not in sounds:
            fail("level event %d uses unknown sound %s" % (event_id, out["sound"]))
        level_events[event_id] = out
    stats["LevelEvents"] = "%d table entries, %d ids handled in code (coverage: SoundMappingsTest)" % (
        len(level_events), len(LEVEL_EVENTS_IN_CODE))

    # ---- write
    os.makedirs(args.out, exist_ok=True)
    meta = {"generator": "tools/gen_sound_mappings.py", "bedrock": "1.26.50", "java": "26.3"}

    def write(name, content):
        path = os.path.join(args.out, name)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(content, f, separators=(",", ":"), sort_keys=False, ensure_ascii=False)
            f.write("\n")
        print("wrote %s (%d bytes)" % (path, os.path.getsize(path)))

    write("sounds.json", {"_meta": meta, "sounds": sounds})
    write("sound_events.json", {"_meta": meta, "events": events, "block_sounds": dict(sorted(block_sounds.items())),
                                "note_instruments": note_sounds})
    write("particles.json", {"_meta": meta, "effects": effects,
                             "level_event_particles": {str(k): v for k, v in sorted(level_particles.items())}})
    write("level_events.json", {"_meta": meta, "events": {str(k): v for k, v in sorted(level_events.items())}})
    for key, value in stats.items():
        print("%-28s %s" % (key + ":", value))


if __name__ == "__main__":
    main()
