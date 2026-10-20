#!/usr/bin/env python3
"""Generates the player subsystem's mapping files (stdlib only).

Outputs (relative to the project root):
  src/main/resources/mappings/effects/bedrock_effects.json
      Bedrock mob effect network id -> Java 26.3 mob effect key.
  src/main/resources/mappings/player/instant_break.json
      Java 26.3 block state id ranges [first, last] whose block breaks instantly in survival
      (destroy time 0): the Java client predicts those breaks on START_DESTROY_BLOCK and never
      sends FINISH, so the proxy must send the Bedrock PREDICT_DESTROY_BLOCK itself.

Sources (see src/main/resources/mappings/SOURCES.md):
  Geyser  core/.../level/EffectType.java   Bedrock effect ids (MIT)
  Geyser  core/.../level/block/Blocks.java Java block destroy times, registration order (MIT)
  ViaBedrock data/java/via_mappings.json   Java 26.3 block state order (GPL-3.0)
  PowerNukkitX entity/effect/EffectType.java cross-check of the Bedrock effect ids (LGPL-3.0)

usage: tools/gen_player_mappings.py [ref_dir=/tmp/jtb/ref] [pnx_dir=/home/user/PowerNukkitX]
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REF = sys.argv[1] if len(sys.argv) > 1 else '/tmp/jtb/ref'
PNX = sys.argv[2] if len(sys.argv) > 2 else '/home/user/PowerNukkitX'
GEYSER = os.path.join(REF, 'GeyserMC_Geyser/core/src/main/java/org/geysermc/geyser')

# Bedrock-only effects and their closest Java equivalent.
BEDROCK_ONLY = {'FATAL_POISON': 'POISON'}


def read(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def effects():
    source = read(os.path.join(GEYSER, 'level/EffectType.java'))
    mapping = {}
    for name, bedrock_id, java in re.findall(r'^\s*([A-Z_]+)\((\d+), (?:Effect\.([A-Z_]+)|null),', source, re.M):
        bedrock_id = int(bedrock_id)
        java = java or BEDROCK_ONLY.get(name)
        if bedrock_id == 0 or java is None:
            continue
        mapping[bedrock_id] = 'minecraft:' + java.lower()
    pnx = os.path.join(PNX, 'src/main/java/org/powernukkitx/entity/effect/EffectType.java')
    if os.path.exists(pnx):
        geyser_names = {int(i): n.lower() for n, i in re.findall(r'^\s*([A-Z_]+)\((\d+), ', source, re.M)}
        for name, bedrock_id in re.findall(r'new EffectType\("([a-z_]+)", (\d+)\)', read(pnx)):
            expected = geyser_names.get(int(bedrock_id))
            if expected not in (name, 'hero_of_the_village' if name == 'village_hero' else None):
                raise SystemExit(f'Effect id mismatch: PNX {name}={bedrock_id}, Geyser {expected}')
    return {str(k): mapping[k] for k in sorted(mapping)}


def instant_break_ranges():
    source = read(os.path.join(GEYSER, 'level/block/Blocks.java'))
    starts = [m.start() for m in re.finditer(r'public static final \w+ \w+ = register\(', source)]
    destroy_time = {}
    for i, start in enumerate(starts):
        statement = source[start:starts[i + 1] if i + 1 < len(starts) else len(source)]
        name = re.search(r'register\(new \w+\("([a-z0-9_]+)"', statement).group(1)
        time = re.search(r'\.destroyTime\((-?[0-9.]+)f\)', statement)
        destroy_time[name] = float(time.group(1)) if time else 0.0
    states = json.loads(read(os.path.join(REF, 'ViaBedrock/src/main/resources/assets/viabedrock/data/java/via_mappings.json')))['blockstates']
    order = []
    for state in states:
        block = state.split('[')[0]
        if not order or order[-1] != block:
            order.append(block)
    if order != list(destroy_time):
        raise SystemExit('Geyser block registration order differs from the Java 26.3 block state order')
    ranges = []
    for state_id, state in enumerate(states):
        block = state.split('[')[0]
        if destroy_time[block] != 0.0 or block in ('air', 'cave_air', 'void_air'):
            continue
        if ranges and ranges[-1][1] == state_id - 1:
            ranges[-1][1] = state_id
        else:
            ranges.append([state_id, state_id])
    return len(states), ranges


def write(relative, data):
    path = os.path.join(ROOT, relative)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(data, f, separators=(',', ':'))
        f.write('\n')
    print('wrote', relative)


def main():
    write('src/main/resources/mappings/effects/bedrock_effects.json', effects())
    state_count, ranges = instant_break_ranges()
    write('src/main/resources/mappings/player/instant_break.json', {'stateCount': state_count, 'ranges': ranges})


if __name__ == '__main__':
    main()
