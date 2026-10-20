#!/usr/bin/env python3
"""Generates src/main/resources/mappings/items/item_mappings.json.gz (Python 3, stdlib only).

Inputs (pinned in src/main/resources/mappings/SOURCES.md):
  --viabedrock  ViaBedrock checkout (GPL-3.0): java/via_mappings.json (Java item order),
                custom/item_mappings.json (Bedrock -> Java items), custom/enchantment_mappings.json,
                custom/effect_mappings.json, bedrock/effects.json, java/effects.json,
                custom/banner_pattern_mappings.json and the generated Enchant_Type enum.
  --mcmeta      mcmeta checkout: 26.3-summary/item_components/data.json (default Java components).
  --geyser      Geyser checkout (MIT): org/geysermc/geyser/inventory/item/Potion.java (Java potion order).
  --gamedata    PowerNukkitX gamedata checkout: gamedata/kaooot/item_tags.json (vanilla Bedrock item tags,
                merged with ViaBedrock's bedrock/item_tags.json).

Output layout (all lists are in Java registry order):
  java_items        [[name, max_stack_size, max_damage, flags], ...]  index = Java item id
                    flags: bit 0 cooking fuel, bit 1 brewing fuel, bits 4-7 equipment slot
                    (0 none, 1 head, 2 chest, 3 legs, 4 feet, 5 body, 6 saddle)
  potions           [java potion key, ...]                             index = Java potion id
  bedrock_items     {bedrock identifier: {aux or "": [java item id, bedrock name key or null, extra or null]}}
                    block items use the single key "" (their mapping never depends on the state)
                    extra: {"potion": key} | {"block_state": {prop: value}} | {"instrument": key}
                           | {"effects": [[java effect key, amplifier, duration]], "color": rgb}
  enchantments      {bedrock enchantment id: java enchantment key}
  effects           {bedrock effect id: java effect id}
  banner_patterns   {bedrock pattern code: java banner pattern key}
  item_tags         {bedrock item tag: [bedrock identifier, ...]}  (recipe ingredients by tag)
"""
import argparse
import gzip
import json
import os
import re
import sys

EQUIP_SLOTS = {"head": 1, "chest": 2, "legs": 3, "feet": 4, "body": 5, "saddle": 6}


def load_json(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def java_items(via, mcmeta):
    names = via["items"]
    out = []
    for name in names:
        comps = mcmeta.get(name)
        if comps is None:
            raise SystemExit("mcmeta has no components for java item " + name)
        stack = comps.get("minecraft:max_stack_size", 64)
        damage = comps.get("minecraft:max_damage", 0)
        flags = 0
        if "minecraft:cooking_fuel" in comps:
            flags |= 1
        if "minecraft:brewing_fuel" in comps:
            flags |= 2
        equippable = comps.get("minecraft:equippable")
        if equippable is not None:
            flags |= EQUIP_SLOTS.get(equippable.get("slot"), 0) << 4
        out.append([name, stack, damage, flags])
    return out


def java_potions(geyser):
    path = os.path.join(geyser, "core/src/main/java/org/geysermc/geyser/inventory/item/Potion.java")
    with open(path, encoding="utf-8") as f:
        source = f.read()
    body = source[source.index("public enum Potion"):]
    body = body[:body.index(";")]
    constants = re.findall(r"^\s*([A-Z_]+)\(\d+", body, re.MULTILINE)
    # Geyser's enum names differ from the Java keys for these two (the ordinals are the Java ids).
    rename = {"WIND_CHARGING": "wind_charged", "INFESTATION": "infested"}
    return ["minecraft:" + rename.get(c, c.lower()) for c in constants]


TAG_POTION = re.compile(r'^\{Potion:"([^"]+)"\}$')
TAG_INSTRUMENT = re.compile(r'^\{instrument:"([^"]+)"\}$')
TAG_BLOCK_STATE = re.compile(r'^\{BlockStateTag:\{(.*)\}\}$')
TAG_EFFECTS = re.compile(r'^\{custom_potion_effects:\[(.*)\],CustomPotionColor:(-?\d+)\}$')
TAG_EFFECT = re.compile(r'\{id:"([^"]+)",amplifier:(\d+),duration:(\d+)\}')


def convert_tag(tag):
    """Legacy (pre-1.20.5) SNBT from ViaBedrock -> the small 'extra' object of the output."""
    m = TAG_POTION.match(tag)
    if m:
        return {"potion": m.group(1)}
    m = TAG_INSTRUMENT.match(tag)
    if m:
        return {"instrument": m.group(1)}
    m = TAG_BLOCK_STATE.match(tag)
    if m:
        props = {}
        for part in m.group(1).split(","):
            key, value = part.split(":", 1)
            props[key] = value.strip('"')
        return {"block_state": props}
    m = TAG_EFFECTS.match(tag)
    if m:
        effects = [[e[0], int(e[1]), int(e[2])] for e in TAG_EFFECT.findall(m.group(1))]
        return {"effects": effects, "color": int(m.group(2))}
    raise SystemExit("unsupported java_tag " + tag)


def bedrock_items(mappings, java_index):
    out = {}
    for identifier in sorted(mappings):
        definition = mappings[identifier]
        entries = {}
        if "block" in definition:
            # Only minecraft:brown_mushroom_block has several states, all mapped to the same Java item.
            first = next(iter(definition["block"].values()))
            entries[""] = first
        else:
            for key, value in definition["meta"].items():
                entries[key] = value
        converted = {}
        for key, value in entries.items():
            java_id = value["java_id"]
            if java_id.startswith("minecraft:"):
                java_id = java_id[len("minecraft:"):]
            if java_id not in java_index:
                raise SystemExit("unknown java item " + java_id + " for " + identifier)
            extra = convert_tag(value["java_tag"]) if "java_tag" in value else None
            converted[key] = [java_index[java_id], value.get("java_name"), extra]
        out[identifier] = converted
    return out


def enchantments(viabedrock):
    enum_path = os.path.join(viabedrock, "src/main/java/net/raphimc/viabedrock/protocol/data/enums/bedrock/generated/Enchant_Type.java")
    with open(enum_path, encoding="utf-8") as f:
        ids = dict(re.findall(r"^\s*(\w+)\((\d+)\)", f.read(), re.MULTILINE))
    mapping = load_json(os.path.join(viabedrock, "src/main/resources/assets/viabedrock/data/custom/enchantment_mappings.json"))
    return {ids[name]: key for name, key in mapping.items() if name in ids}


def effects(data):
    bedrock = load_json(os.path.join(data, "bedrock/effects.json"))
    java = load_json(os.path.join(data, "java/effects.json"))
    mapping = load_json(os.path.join(data, "custom/effect_mappings.json"))
    out = {}
    for index, key in enumerate(bedrock):
        java_key = mapping.get(key, key)
        if java_key in java:
            out[str(index + 1)] = java.index(java_key)  # Bedrock effect ids start at 1
    return out


def item_tags(data, gamedata):
    tags = {}
    sources = [os.path.join(data, "bedrock/item_tags.json"),
               os.path.join(gamedata, "src/main/resources/gamedata/kaooot/item_tags.json")]
    for path in sources:
        if os.path.exists(path):
            for tag, items in load_json(path).items():
                tags.setdefault(tag, set()).update(items)
    return {tag: sorted(items) for tag, items in sorted(tags.items())}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--viabedrock", default="/tmp/jtb/ref/ViaBedrock")
    parser.add_argument("--mcmeta", default="/tmp/jtb/ref/mcmeta")
    parser.add_argument("--geyser", default="/tmp/jtb/ref/GeyserMC_Geyser")
    parser.add_argument("--gamedata", default="/tmp/jtb/ref/PowerNukkitX_gamedata")
    parser.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "src/main/resources/mappings/items/item_mappings.json.gz"))
    args = parser.parse_args()

    data = os.path.join(args.viabedrock, "src/main/resources/assets/viabedrock/data")
    via = load_json(os.path.join(data, "java/via_mappings.json"))
    mcmeta = load_json(os.path.join(args.mcmeta, "26.3-summary/item_components/data.json"))
    items = java_items(via, mcmeta)
    java_index = {entry[0]: i for i, entry in enumerate(items)}

    result = {
        "java_items": items,
        "potions": java_potions(args.geyser),
        "bedrock_items": bedrock_items(load_json(os.path.join(data, "custom/item_mappings.json")), java_index),
        "enchantments": enchantments(args.viabedrock),
        "effects": effects(data),
        "banner_patterns": load_json(os.path.join(data, "custom/banner_pattern_mappings.json")),
        "item_tags": item_tags(data, args.gamedata),
    }
    if len(result["potions"]) != 46:
        raise SystemExit("expected 46 java potions, got %d" % len(result["potions"]))

    raw = json.dumps(result, separators=(",", ":"), sort_keys=True).encode("utf-8")
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "wb") as f:
        # mtime=0 keeps the output byte-identical between runs.
        f.write(gzip.compress(raw, compresslevel=9, mtime=0))
    print("java items %d, bedrock items %d, %d bytes (%d gzipped) -> %s" % (
        len(items), len(result["bedrock_items"]), len(raw), os.path.getsize(args.out), os.path.normpath(args.out)),
        file=sys.stderr)


if __name__ == "__main__":
    main()
