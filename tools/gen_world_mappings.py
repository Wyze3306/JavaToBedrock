#!/usr/bin/env python3
"""Generates the block and biome tables of the world subsystem (Python 3, standard library only).

usage: python3 -I tools/gen_world_mappings.py [--ref /tmp/jtb/ref] [--out src/main/resources/mappings]

Inputs (see src/main/resources/mappings/SOURCES.md for versions and licences):
  PowerNukkitX gamedata  kaooot/block_palette.nbt, kaooot/biomes.json  (Bedrock 1.26.50 palette and hashes)
  ViaBedrock data        custom/blockstate_mappings.json (Bedrock -> Java), java/via_mappings.json (Java ids),
                         custom/pre_waterlogged_blockstates.json, custom/potted_blockstates.json,
                         custom/banner_pattern_mappings.json, java/heightmap_blockstates.nbt
  GeyserMC mappings      blocks.nbt (Java -> Bedrock), block_shapes.nbt (outline shapes), biomes.json
  mcmeta 26.3-summary    blocks/data.json (Java properties, value order, defaults)
  tools/data/java_light_26_1.json   per-block light emission / filter of the default state (PrismarineJS
                         minecraft-data 26.1, MIT), refined per state below

Outputs:
  block/blocks.bin.gz    see BLOCKS_FORMAT below (read by block/VanillaBlocks.java)
  world/biomes.json      Bedrock biome ids and names -> Java biome keys
  world/block_entities.json  banner pattern codes

The script fails when a palette hash cannot be recomputed, when the Java state order does not match
via_mappings, or when a palette state has no Java state.
"""
import argparse
import gzip
import itertools
import json
import os
import struct
import sys
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import nbtlib  # noqa: E402

BLOCKS_FORMAT = """
blocks.bin.gz, gzip of big-endian data:
  "JTBB" u8 version(1)
  u16 javaBlockCount; per block: utf name (no namespace), u16 defaultStateOffset, u8 blockEntityType (0xFF none),
      u8 propertyCount, per property: utf name, u8 valueCount, utf values...
      (state ids are consecutive per block: cartesian product of the properties sorted by name, first slowest)
  u32 javaStateCount; u8[n] light (emission << 4 | opacity); u8[n] occluded faces (bit = Direction ordinal);
      u8[n] flags (1 motion blocking, 2 motion blocking without leaves, 4 has fluid, 8 air); u16[n] waterlogged twin (0xFFFF none)
  u16 bedrockNameCount; per name: utf name, u8 propertyCount, per property: utf name, u8 tag type (1 byte, 3 int,
      8 string), u8 valueCount, values (byte: i8, int: i32, string: utf)
  u32 paletteSize; per entry (palette = runtime id order when ids are not hashed): u16 nameIndex, u8 value index per property
      i32[paletteSize] network hash; u16[paletteSize] Java state
  u16[javaStateCount] palette index of the best Bedrock state (0xFFFF none)
  u16 pottedCount; per entry u16 plant Java state, u16 potted Java state
"""

AIR_BLOCKS = {"air", "cave_air", "void_air"}
FULL_CUBE = [[0.5, 0.5, 0.5, 1.0, 1.0, 1.0]]

# Blocks whose shape occludes light per face (vanilla BlockBehaviour#useShapeForLightOcclusion).
SHAPE_OCCLUSION_SUFFIXES = ("_slab", "_stairs")
SHAPE_OCCLUSION_BLOCKS = {
    "snow", "farmland", "dirt_path", "enchanting_table", "lectern", "stonecutter", "daylight_detector",
    "end_portal_frame", "sculk_sensor", "calibrated_sculk_sensor", "sculk_shrieker", "piston_head", "composter",
}
# Full-cube shaped blocks that still let light through (no occlusion): never forced to opacity 15.
SEE_THROUGH_HINTS = ("glass", "leaves", "ice", "slime", "honey", "barrier", "spawner", "beacon", "grate", "scaffolding")

COPPER_BULB_LIGHT = {"copper_bulb": 15, "exposed_copper_bulb": 12, "weathered_copper_bulb": 8, "oxidized_copper_bulb": 4}
TRIAL_SPAWNER_LIGHT = {"inactive": 0, "waiting_for_players": 4, "active": 8, "waiting_for_reward_ejection": 8,
                       "ejecting_reward": 8, "cooldown": 0}
VAULT_LIGHT = {"inactive": 6, "active": 12, "unlocking": 12, "ejecting": 12}
LIT_LIGHT = {"furnace": 13, "blast_furnace": 13, "smoker": 13, "redstone_ore": 9, "deepslate_redstone_ore": 9,
             "redstone_torch": 7, "redstone_wall_torch": 7, "redstone_lamp": 15, "campfire": 15, "soul_campfire": 10}

# Bedrock states where Geyser reads the Bedrock state right and ViaBedrock does not (checked by hand).
PREFER_GEYSER = {"minecraft:sulfur_spike[dripstone_thickness=merge,hanging=1]", "minecraft:jigsaw[facing_direction=0,rotation=0]",
                 "minecraft:tnt[explode_bit=1]", "minecraft:mushroom_stem[huge_mushroom_bits=0]"}

DYES = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan",
        "purple", "blue", "brown", "green", "red", "black"]

# Legacy Bedrock biomes without a Java counterpart -> closest Java 26.3 biome (no upstream source).
LEGACY_BIOMES = {
    "legacy_frozen_ocean": "frozen_ocean", "ice_mountains": "snowy_plains", "mushroom_island_shore": "mushroom_fields",
    "desert_hills": "desert", "forest_hills": "forest", "taiga_hills": "taiga", "extreme_hills_edge": "windswept_hills",
    "jungle_hills": "jungle", "birch_forest_hills": "birch_forest", "cold_taiga_hills": "snowy_taiga",
    "mega_taiga_hills": "old_growth_pine_taiga", "mesa_plateau": "badlands", "deep_warm_ocean": "warm_ocean",
    "bamboo_jungle_hills": "bamboo_jungle", "desert_mutated": "desert", "taiga_mutated": "taiga",
    "swampland_mutated": "swamp", "jungle_mutated": "jungle", "jungle_edge_mutated": "sparse_jungle",
    "birch_forest_hills_mutated": "old_growth_birch_forest", "roofed_forest_mutated": "dark_forest",
    "cold_taiga_mutated": "snowy_taiga", "redwood_taiga_hills_mutated": "old_growth_spruce_taiga",
    "extreme_hills_plus_trees_mutated": "windswept_forest", "savanna_plateau_mutated": "windswept_savanna",
    "mesa_plateau_stone_mutated": "wooded_badlands", "mesa_plateau_mutated": "badlands",
}
BIOME_PREFERENCE = {"the_end": "minecraft:the_end", "river": "minecraft:river"}


def fail(message):
    print("ERROR: " + message, file=sys.stderr)
    sys.exit(1)


def parse_state(text):
    """'minecraft:a[b=c,d=e]' or 'a[...]' -> ('minecraft:a', {b: c, d: e})."""
    if "[" in text:
        name, rest = text.split("[", 1)
        props = dict(kv.split("=", 1) for kv in rest[:-1].split(",")) if rest[:-1] else {}
    else:
        name, props = text, {}
    if ":" not in name:
        name = "minecraft:" + name
    return name, props


def state_key(name, props):
    return name + ("[" + ",".join("%s=%s" % (k, props[k]) for k in sorted(props)) + "]" if props else "")


def bedrock_key(name, typed_states):
    return state_key(name, {k: v for k, (_, v) in typed_states.items()})


# ---------------------------------------------------------------------------------------------- Java

def load_java(ref):
    via = json.load(open(os.path.join(ref, "ViaBedrock/src/main/resources/assets/viabedrock/data/java/via_mappings.json")))
    summary = json.load(open(os.path.join(ref, "mcmeta/26.3-summary/blocks/data.json")))
    blocks = []
    states = []
    for name in via["blocks"]:
        props, defaults = summary[name]
        prop_names = sorted(props)
        first = len(states)
        default = None
        for combo in itertools.product(*[props[p] for p in prop_names]):
            values = dict(zip(prop_names, combo))
            if values == defaults:
                default = len(states) - first
            states.append((len(blocks), values))
        if default is None:
            fail("no default state for " + name)
        blocks.append({"name": name, "props": [(p, props[p]) for p in prop_names], "first": first,
                       "count": len(states) - first, "default": default})
    expected = [state_key(*parse_state(s)) for s in via["blockstates"]]
    actual = [state_key("minecraft:" + blocks[b]["name"], v) for b, v in states]
    if expected != actual:
        bad = next(i for i, (a, e) in enumerate(zip(actual, expected)) if a != e)
        fail("Java state order differs from via_mappings at %d: %s vs %s" % (bad, actual[bad], expected[bad]))
    index = {k: i for i, k in enumerate(actual)}
    return via, blocks, states, actual, index


# ---------------------------------------------------------------------------------------------- Bedrock

def load_palette(ref):
    root = nbtlib.load(os.path.join(ref, "PowerNukkitX_gamedata/src/main/resources/gamedata/kaooot/block_palette.nbt"),
                       typed=True)
    entries = []
    for typed_entry in root["blocks"][1]:
        name = typed_entry["name"][1]
        states = typed_entry["states"][1]
        network_id = typed_entry["network_id"][1]
        h = nbtlib.bedrock_state_hash(name, states)
        if name != "minecraft:unknown" and h != network_id:
            fail("hash mismatch for %s: %d != %d" % (bedrock_key(name, states), h, network_id))
        entries.append({"name": name, "states": states, "hash": h})
    hashes = [e["hash"] for e in entries]
    if len(set(hashes)) != len(hashes):
        fail("hash collision in the vanilla palette")
    order = [nbtlib.fnv1_64(e["name"].encode()) for e in entries]
    if order != sorted(order):
        fail("palette is not sorted by FNV-1 64 of the name")
    return entries


# ---------------------------------------------------------------------------------------------- light

def boxes(shape):
    out = []
    for mx, my, mz, sx, sy, sz in shape:
        out.append((mx - sx / 2, my - sy / 2, mz - sz / 2, mx + sx / 2, my + sy / 2, mz + sz / 2))
    return out


def face_mask(shape):
    """Bit per Direction ordinal (down, up, north, south, west, east) for faces the shape covers fully."""
    eps = 1e-6
    faces = [  # (axis index of the plane, plane value, the two other axes)
        (1, 0.0, (0, 2)), (1, 1.0, (0, 2)), (2, 0.0, (0, 1)), (2, 1.0, (0, 1)), (0, 0.0, (2, 1)), (0, 1.0, (2, 1))]
    mask = 0
    bs = boxes(shape)
    for bit, (axis, plane, (a, b)) in enumerate(faces):
        grid = [[False] * 16 for _ in range(16)]
        for box in bs:
            lo, hi = box[axis], box[axis + 3]
            if not (abs(lo - plane) < eps if plane == 0.0 else abs(hi - plane) < eps):
                continue
            for i in range(16):
                for j in range(16):
                    u, v = (i + 0.5) / 16, (j + 0.5) / 16
                    if box[a] - eps <= u <= box[a + 3] + eps and box[b] - eps <= v <= box[b + 3] + eps:
                        grid[i][j] = True
        if all(all(row) for row in grid):
            mask |= 1 << bit
    return mask


def emission(block, values, constant):
    if block in LIT_LIGHT:
        return LIT_LIGHT[block] if values.get("lit") == "true" else 0
    base = block[6:] if block.startswith("waxed_") else block
    if base in COPPER_BULB_LIGHT:
        return COPPER_BULB_LIGHT[base] if values.get("lit") == "true" else 0
    if block.endswith("candle_cake"):
        return 3 if values.get("lit") == "true" else 0
    if block == "candle" or block.endswith("_candle"):
        return 3 * int(values["candles"]) if values.get("lit") == "true" else 0
    if block == "sea_pickle":
        return 3 + 3 * int(values["pickles"]) if values.get("waterlogged") == "true" else 0
    if block == "respawn_anchor":
        return [0, 3, 7, 11, 15][int(values["charges"])]
    if block == "light":
        return int(values["level"])
    if block in ("cave_vines", "cave_vines_plant"):
        return 14 if values.get("berries") == "true" else 0
    if block == "glow_lichen":
        return 7 if any(values.get(f) == "true" for f in ("down", "up", "north", "south", "west", "east")) else 0
    if block == "trial_spawner":
        return TRIAL_SPAWNER_LIGHT[values["trial_spawner_state"]]
    if block == "vault":
        return VAULT_LIGHT[values["vault_state"]]
    return constant


def light_tables(blocks, states, shapes, light_data, pre_wet, fluid_names):
    light = []
    occlusion = []
    changed = Counter()
    for sid, (b, values) in enumerate(states):
        block = blocks[b]["name"]
        shape = shapes[sid]
        default_shape = shapes[blocks[b]["first"] + blocks[b]["default"]]
        constant, opacity = light_data.get(block, (None, None))
        if block in AIR_BLOCKS:
            opacity, constant = 0, 0
        elif opacity is None:
            # Block newer than the light source: derive like vanilla getLightBlock().
            if block.endswith("_leaves"):
                opacity = 1
            elif default_shape == FULL_CUBE and not any(h in block for h in SEE_THROUGH_HINTS):
                opacity = 15
            else:
                opacity = 0
            constant = 0
            changed["derived-new-block"] += 1
        if opacity == 15 and shape != FULL_CUBE:
            opacity = 0
            changed["opaque-but-partial"] += 1
        elif (block.endswith("_slab") and values.get("type") == "double") or (block == "snow" and values["layers"] == "8"):
            opacity = 15
            changed["full-variant"] += 1
        wet = values.get("waterlogged") == "true" or sid in pre_wet or block in fluid_names
        if wet and opacity < 1:
            opacity = 1
        light.append((emission(block, values, constant) << 4) | opacity)
        uses_shape = block in SHAPE_OCCLUSION_BLOCKS or block.endswith(SHAPE_OCCLUSION_SUFFIXES)
        occlusion.append(face_mask(shape) if uses_shape and opacity < 15 else 0)
    return light, occlusion, changed


# ---------------------------------------------------------------------------------------------- block entities

def block_entity_type(name, be_types):
    rules = [
        (lambda n: n in ("furnace", "blast_furnace", "smoker", "ender_chest", "jukebox", "dispenser", "dropper",
                         "creaking_heart", "brewing_stand", "enchanting_table", "end_portal", "beacon", "daylight_detector",
                         "hopper", "comparator", "structure_block", "end_gateway", "conduit", "barrel", "lectern", "bell",
                         "jigsaw", "sculk_sensor", "calibrated_sculk_sensor", "sculk_catalyst", "sculk_shrieker",
                         "chiseled_bookshelf", "decorated_pot", "crafter", "trial_spawner", "vault", "test_block",
                         "test_instance_block", "potent_sulfur", "trapped_chest"), lambda n: n),
        (lambda n: n == "chest" or n.endswith("copper_chest"), lambda n: "chest"),
        (lambda n: n.endswith("_hanging_sign"), lambda n: "hanging_sign"),
        (lambda n: n.endswith("_sign"), lambda n: "sign"),
        (lambda n: n == "spawner", lambda n: "mob_spawner"),
        (lambda n: n == "moving_piston", lambda n: "piston"),
        (lambda n: n.endswith(("_skull", "_head")) and not n.endswith("piston_head"), lambda n: "skull"),
        (lambda n: n.endswith("_banner"), lambda n: "banner"),
        (lambda n: n in ("command_block", "chain_command_block", "repeating_command_block"), lambda n: "command_block"),
        (lambda n: n.endswith("shulker_box"), lambda n: "shulker_box"),
        (lambda n: n in ("campfire", "soul_campfire"), lambda n: "campfire"),
        (lambda n: n in ("beehive", "bee_nest"), lambda n: "beehive"),
        (lambda n: n.endswith("_shelf") and n != "shelf_mushroom", lambda n: "shelf"),
        (lambda n: n in ("suspicious_sand", "suspicious_gravel"), lambda n: "brushable_block"),
        (lambda n: n.endswith("copper_golem_statue"), lambda n: "copper_golem_statue"),
    ]
    for test, target in rules:
        if test(name):
            t = target(name)
            if t not in be_types:
                fail("unknown block entity type %s for %s" % (t, name))
            return be_types.index(t)
    return 0xFF


# ---------------------------------------------------------------------------------------------- writer

class Out:
    def __init__(self):
        self.parts = []

    def u8(self, v):
        self.parts.append(struct.pack(">B", v))

    def i8(self, v):
        self.parts.append(struct.pack(">b", v))

    def u16(self, v):
        self.parts.append(struct.pack(">H", v))

    def i32(self, v):
        self.parts.append(struct.pack(">i", v))

    def u32(self, v):
        self.parts.append(struct.pack(">I", v))

    def utf(self, s):
        e = s.encode("utf-8")
        self.u16(len(e))
        self.parts.append(e)

    def array(self, fmt, values):
        self.parts.append(struct.pack(">%d%s" % (len(values), fmt), *values))

    def bytes(self):
        return b"".join(self.parts)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ref", default=os.environ.get("JTB_REF", "/tmp/jtb/ref"))
    parser.add_argument("--out", default=os.path.join(HERE, "..", "src", "main", "resources", "mappings"))
    args = parser.parse_args()
    ref = args.ref
    vb = os.path.join(ref, "ViaBedrock/src/main/resources/assets/viabedrock/data")

    via, jblocks, jstates, jkeys, jindex = load_java(ref)
    print("java: %d blocks, %d states" % (len(jblocks), len(jstates)))
    palette = load_palette(ref)
    print("bedrock palette: %d states, %d names, hashes verified" % (len(palette), len({e["name"] for e in palette})))

    # ---- Bedrock -> Java
    vbm = {}
    for k, v in json.load(open(os.path.join(vb, "custom/blockstate_mappings.json"))).items():
        vbm[state_key(*parse_state(k))] = jindex[state_key(*parse_state(v))]
    geyser = nbtlib.load(os.path.join(ref, "GeyserMC_mappings/blocks.nbt"))["bedrock_mappings"]
    if len(geyser) != len(jstates):
        fail("Geyser blocks.nbt size %d != %d" % (len(geyser), len(jstates)))
    geyser_key = []
    for jid, e in enumerate(geyser):
        name = "minecraft:" + e.get("bedrock_identifier", jblocks[jstates[jid][0]]["name"])
        geyser_key.append(state_key(name, {k: str(v) for k, v in e.get("state", {}).items()}))
    geyser_inverse = defaultdict(list)
    for jid, k in enumerate(geyser_key):
        geyser_inverse[k].append(jid)

    air = jindex["minecraft:air"]
    b2j = []
    overridden = []
    missing = 0
    for e in palette:
        k = bedrock_key(e["name"], e["states"])
        j = vbm.get(k)
        if e["name"] == "minecraft:unknown":
            j = air
        elif k in PREFER_GEYSER and k in geyser_inverse:
            g = sorted(geyser_inverse[k], key=lambda x: ("waterlogged=true" in jkeys[x], x))[0]
            if g != j:
                overridden.append("%s: %s -> %s" % (k, jkeys[j] if j is not None else None, jkeys[g]))
                j = g
        if j is None:
            missing += 1
            j = air
        b2j.append(j)
    if missing:
        fail("%d palette states without a Java state" % missing)
    print("bedrock -> java: %d/%d mapped, %d Geyser overrides:" % (len(palette) - missing, len(palette), len(overridden)))
    for line in overridden:
        print("   " + line)

    # ---- per Java state data
    pre_wet = {jindex[state_key(*parse_state(s))] for s in json.load(open(os.path.join(vb, "custom/pre_waterlogged_blockstates.json")))}
    twin = []
    for jid, (b, values) in enumerate(jstates):
        if jid in pre_wet or values.get("waterlogged") == "true":
            twin.append(jid)
        elif values.get("waterlogged") == "false":
            wet = dict(values, waterlogged="true")
            twin.append(jindex[state_key("minecraft:" + jblocks[b]["name"], wet)])
        else:
            twin.append(0xFFFF)
    motion = set(nbtlib.load(os.path.join(vb, "java/heightmap_blockstates.nbt"))["motion_blocking"])
    fluid_names = {"water", "lava", "bubble_column"}
    flags = []
    for jid, (b, values) in enumerate(jstates):
        name = jblocks[b]["name"]
        f = 0
        if jid in motion:
            f |= 1
            if not name.endswith("_leaves"):
                f |= 2
        if jid in pre_wet or values.get("waterlogged") == "true" or name in fluid_names:
            f |= 4
        if name in AIR_BLOCKS:
            f |= 8
        flags.append(f)

    shapes_nbt = nbtlib.load(os.path.join(ref, "GeyserMC_mappings/block_shapes.nbt"))
    shapes = [shapes_nbt["shapes"][i] for i in shapes_nbt["indices"]]
    light_data = json.load(open(os.path.join(HERE, "data", "java_light_26_1.json")))
    light, occlusion, changed = light_tables(jblocks, jstates, shapes, light_data, pre_wet, fluid_names)
    emitters = sum(1 for v in light if v >> 4)
    print("light: %d emitting states, %d opaque, %d with face occlusion, rules applied: %s" % (
        emitters, sum(1 for v in light if v & 15 == 15), sum(1 for v in occlusion if v), dict(changed)))

    be_types = via["blockentities"]
    block_be = [block_entity_type(b["name"], be_types) for b in jblocks]
    per_type = Counter(be_types[t] for t in block_be if t != 0xFF)
    print("block entities: %d Java blocks, types without a block: %s" % (
        sum(per_type.values()), [t for t in be_types if t not in per_type]))

    # ---- Java -> Bedrock
    palette_index = {bedrock_key(e["name"], e["states"]): i for i, e in enumerate(palette)}
    vb_inverse = defaultdict(list)
    for i, j in enumerate(b2j):
        vb_inverse[j].append(i)
    j2b = []
    round_trip = 0
    for jid in range(len(jstates)):
        dry = jid
        if jstates[jid][1].get("waterlogged") == "true":
            dry = jindex[state_key("minecraft:" + jblocks[jstates[jid][0]]["name"], dict(jstates[jid][1], waterlogged="false"))]
        candidates = vb_inverse.get(jid) or vb_inverse.get(dry) or []
        g = palette_index.get(geyser_key[jid])
        if g is not None and (b2j[g] in (jid, dry) or not candidates):
            choice = g
        elif candidates:
            choice = candidates[0]
        else:
            choice = 0xFFFF
        if choice != 0xFFFF and b2j[choice] in (jid, dry):
            round_trip += 1
        j2b.append(choice)
    print("java -> bedrock: %d/%d states have a Bedrock state, %d round-trip exactly" % (
        sum(1 for c in j2b if c != 0xFFFF), len(j2b), round_trip))

    # ---- potted plants
    potted = []
    for plant, pot in json.load(open(os.path.join(vb, "custom/potted_blockstates.json"))).items():
        pk = state_key(*parse_state(plant))
        pot_name = parse_state(pot)[0][len("minecraft:"):]
        block = next(b for b in jblocks if b["name"] == pot_name)
        potted.append((jindex[pk], block["first"] + block["default"]))

    # ---- binary
    out = Out()
    out.parts.append(b"JTBB")
    out.u8(1)
    out.u16(len(jblocks))
    for b, be in zip(jblocks, block_be):
        out.utf(b["name"])
        out.u16(b["default"])
        out.u8(be)
        out.u8(len(b["props"]))
        for name, values in b["props"]:
            out.utf(name)
            out.u8(len(values))
            for v in values:
                out.utf(v)
    out.u32(len(jstates))
    out.array("B", light)
    out.array("B", occlusion)
    out.array("B", flags)
    out.array("H", twin)

    names = []
    name_index = {}
    name_props = {}
    for e in palette:
        n = e["name"]
        if n not in name_index:
            name_index[n] = len(names)
            names.append(n)
            name_props[n] = {}
        for p, (t, v) in e["states"].items():
            values = name_props[n].setdefault(p, (t, []))[1]
            if name_props[n][p][0] != t:
                fail("mixed tag types for %s.%s" % (n, p))
            if v not in values:
                values.append(v)
    out.u16(len(names))
    for n in names:
        out.utf(n)
        props = sorted(name_props[n].items())
        out.u8(len(props))
        for p, (t, values) in props:
            out.utf(p)
            out.u8(t)
            out.u8(len(values))
            for v in values:
                if t == nbtlib.BYTE:
                    out.i8(v)
                elif t == nbtlib.INT:
                    out.i32(v)
                elif t == nbtlib.STRING:
                    out.utf(v)
                else:
                    fail("unsupported state tag type %d" % t)
    out.u32(len(palette))
    for e in palette:
        n = e["name"]
        out.u16(name_index[n])
        props = sorted(name_props[n].items())
        if len(props) != len(e["states"]):
            fail("state of %s lacks properties" % n)
        for p, (t, values) in props:
            out.u8(values.index(e["states"][p][1]))
    out.array("i", [e["hash"] for e in palette])
    out.array("H", b2j)
    out.array("H", j2b)
    out.u16(len(potted))
    for plant, pot in potted:
        out.u16(plant)
        out.u16(pot)

    raw = out.bytes()
    os.makedirs(os.path.join(args.out, "block"), exist_ok=True)
    os.makedirs(os.path.join(args.out, "world"), exist_ok=True)
    blob = gzip.compress(raw, 9, mtime=0)
    with open(os.path.join(args.out, "block", "blocks.bin.gz"), "wb") as f:
        f.write(blob)
    print("wrote block/blocks.bin.gz: %d bytes (%d raw)" % (len(blob), len(raw)))

    # ---- biomes
    pnx_biomes = json.load(open(os.path.join(ref, "PowerNukkitX_gamedata/src/main/resources/gamedata/kaooot/biomes.json")))
    geyser_biomes = json.load(open(os.path.join(ref, "GeyserMC_mappings/biomes.json")))
    inverse = defaultdict(list)
    for java_key, v in geyser_biomes.items():
        inverse[v["bedrock_id"]].append(java_key)
    ids = {}
    java = {}
    for name, bid in sorted(pnx_biomes.items(), key=lambda kv: kv[1]):
        short = name.split(":", 1)[1]
        ids[str(bid)] = short
        if bid in inverse:
            java[short] = BIOME_PREFERENCE.get(short, sorted(inverse[bid])[0])
        elif short in LEGACY_BIOMES:
            java[short] = "minecraft:" + LEGACY_BIOMES[short]
        else:
            fail("no Java biome for Bedrock biome " + name)
    with open(os.path.join(args.out, "world", "biomes.json"), "w") as f:
        json.dump({"_comment": "generated by tools/gen_world_mappings.py", "bedrock_ids": ids, "java": java}, f, indent=1)
        f.write("\n")
    print("wrote world/biomes.json: %d Bedrock biomes" % len(ids))

    patterns = json.load(open(os.path.join(vb, "custom/banner_pattern_mappings.json")))
    with open(os.path.join(args.out, "world", "block_entities.json"), "w") as f:
        json.dump({"_comment": "generated by tools/gen_world_mappings.py", "banner_patterns": patterns,
                   "dyes": DYES}, f, indent=1)
        f.write("\n")
    print("wrote world/block_entities.json")


if __name__ == "__main__":
    main()
