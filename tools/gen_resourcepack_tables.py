#!/usr/bin/env python3
"""Generates the static tables of the resource pack converter (package resourcepack.convert) into
src/main/resources/mappings/resourcepack/. Python 3, standard library only.

Inputs (all read-only):
  --safe      texture_overrides_safe.json: Bedrock vanilla texture path -> Java vanilla texture paths
              whose vanilla images have the same size (produced by the r8 research scripts
              texture_mapping.py + validate_texture_mapping.py: inverted GeyserMC PackConverter
              table, identical paths, pixel-equal images, unique same-stem matches)
  --sounds    bedrock_to_java_sound_files.json: Bedrock vanilla sound file -> Java sound files
              (inverse of GeyserMC PackConverter mappings/sounds.json, r8 sound_mapping.py)
  --bedrock   Mojang bedrock-samples resource_pack directory (v1.26.50.4)
  --java      Java 26.3 client assets, assets/minecraft directory
  --blocks    Java 26.3 block state summary (mcmeta 26.3-summary/blocks/data.json)

Outputs:
  textures.json        {"blocks/stone": [["block/stone", 16, 16], ...]} with the Java frame size
                       (animated Java textures: one frame) used for the aspect-ratio check
  atlases.json         vanilla Bedrock short names: {"terrain": {"stone": ["blocks/stone", ...]},
                       "items": {...}}, only names whose textures are in textures.json
  flipbooks.json       vanilla Bedrock flipbooks {"blocks/sea_lantern": {"ticks": 5, "frames": [...],
                       "blend": false}} (absent keys = Bedrock defaults)
  sound_files.json     {"same": [paths identical on both sides], "mapped": {bedrock: [java...]}}
  carrier_blocks.json  Java blocks usable as custom-block carriers: property names and values in
                       Java order, and the vanilla blockstate file

usage: python3 tools/gen_resourcepack_tables.py --safe ... --sounds ... --bedrock ... --java ...
       --blocks ... [--out src/main/resources/mappings/resourcepack]
"""
import argparse
import json
import os
import re
import struct

CARRIERS = ['note_block', 'tripwire', 'brown_mushroom_block', 'red_mushroom_block', 'mushroom_stem',
            'chorus_plant', 'acacia_leaves', 'azalea_leaves', 'birch_leaves', 'cherry_leaves',
            'dark_oak_leaves', 'flowering_azalea_leaves', 'jungle_leaves', 'mangrove_leaves', 'oak_leaves',
            'pale_oak_leaves', 'spruce_leaves']


def lenient_json(path):
    """Bedrock JSON: BOM, // and /* */ comments, trailing commas."""
    with open(path, 'rb') as f:
        text = f.read().decode('utf-8', errors='replace')
    if text.startswith('﻿'):
        text = text[1:]
    out = []
    i, n, in_str = 0, len(text), False
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if c == '\\' and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
        elif c == '/' and text.startswith('//', i):
            j = text.find('\n', i)
            i = n if j < 0 else j
            continue
        elif c == '/' and text.startswith('/*', i):
            j = text.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        elif c in '}]':
            k = len(out) - 1
            while k >= 0 and out[k].isspace():
                k -= 1
            if k >= 0 and out[k] == ',':
                del out[k]
        out.append(c)
        i += 1
    return json.loads(''.join(out))


def png_size(path):
    with open(path, 'rb') as f:
        head = f.read(24)
    if head[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('not a png: ' + path)
    return struct.unpack('>II', head[16:24])


def java_frame_size(java_root, path):
    """Size of one frame of a Java texture (the whole image when it is not animated)."""
    png = os.path.join(java_root, 'textures', path + '.png')
    w, h = png_size(png)
    meta = png + '.mcmeta'
    if os.path.isfile(meta):
        anim = json.load(open(meta)).get('animation')
        if anim is not None:
            fw = anim.get('width')
            fh = anim.get('height')
            if fw is None and fh is None:
                fw = fh = min(w, h)
            fw = fw if fw is not None else w
            fh = fh if fh is not None else h
            return fw, fh
    return w, h


def strip_textures(path):
    return path[len('textures/'):] if path.startswith('textures/') else path


def atlas(path, mapped):
    data = lenient_json(path).get('texture_data', {})
    out = {}
    for name, entry in data.items():
        textures = entry.get('textures') if isinstance(entry, dict) else None
        if textures is None:
            continue
        items = textures if isinstance(textures, list) else [textures]
        paths = []
        for t in items:
            p = t.get('path') if isinstance(t, dict) else t
            paths.append(strip_textures(p) if isinstance(p, str) else '')
        if any(p in mapped for p in paths):
            out[name] = paths
    return dict(sorted(out.items()))


def flipbooks(path):
    out = {}
    for e in lenient_json(path):
        t = strip_textures(e['flipbook_texture'])
        v = {}
        if 'ticks_per_frame' in e:
            v['ticks'] = e['ticks_per_frame']
        if 'frames' in e:
            v['frames'] = e['frames']
        if 'blend_frames' in e:
            v['blend'] = e['blend_frames']
        out.setdefault(t, v)
    return dict(sorted(out.items()))


def carriers(blocks_json, java_root):
    data = json.load(open(blocks_json))
    out = {}
    for block in CARRIERS:
        props, _default = data[block]
        state_file = os.path.join(java_root, 'blockstates', block + '.json')
        out['minecraft:' + block] = {
            'properties': [[name, values] for name, values in props.items()],
            'blockstate': json.load(open(state_file)),
        }
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--safe', required=True)
    ap.add_argument('--sounds', required=True)
    ap.add_argument('--bedrock', required=True)
    ap.add_argument('--java', required=True)
    ap.add_argument('--blocks', required=True)
    ap.add_argument('--out', default=os.path.join('src', 'main', 'resources', 'mappings', 'resourcepack'))
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    safe = json.load(open(a.safe))
    textures = {}
    for bedrock, javas in sorted(safe.items()):
        textures[bedrock] = [[j, *java_frame_size(a.java, j)] for j in javas]

    tex_root = os.path.join(a.bedrock, 'textures')
    atlases = {'terrain': atlas(os.path.join(tex_root, 'terrain_texture.json'), textures),
               'items': atlas(os.path.join(tex_root, 'item_texture.json'), textures)}
    flips = flipbooks(os.path.join(tex_root, 'flipbook_textures.json'))

    sounds = json.load(open(a.sounds))
    same = sorted(b for b, js in sounds.items() if js == [b])
    mapped = {b: js for b, js in sorted(sounds.items()) if js != [b]}

    def dump(name, value):
        with open(os.path.join(a.out, name), 'w', encoding='utf-8') as f:
            json.dump(value, f, separators=(',', ':'), ensure_ascii=False, sort_keys=False)
            f.write('\n')

    dump('textures.json', textures)
    dump('atlases.json', atlases)
    dump('flipbooks.json', flips)
    dump('sound_files.json', {'same': same, 'mapped': mapped})
    dump('carrier_blocks.json', carriers(a.blocks, a.java))
    print('textures', len(textures), 'targets', sum(len(v) for v in textures.values()),
          '| terrain names', len(atlases['terrain']), 'item names', len(atlases['items']),
          '| flipbooks', len(flips), '| sound files', len(same) + len(mapped), '| carriers', len(CARRIERS))


if __name__ == '__main__':
    main()
