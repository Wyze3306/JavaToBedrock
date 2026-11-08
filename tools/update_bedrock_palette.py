#!/usr/bin/env python3
"""Moves the Bedrock half of block/blocks.bin.gz to a newer PowerNukkitX block palette (Python 3, stdlib only).

usage: python3 -I tools/update_bedrock_palette.py <block_palette.nbt> [--table src/main/resources/mappings/block/blocks.bin.gz]

gen_world_mappings.py builds the whole table from its reference checkouts. When a Bedrock update only adds
properties to existing blocks, this script carries the table over without them: every state of the new palette
(PowerNukkitX gamedata/kaooot/block_palette.nbt, its order and network hashes) keeps the Java state of the old
state it extends, and the properties in CONNECTIONS, which Java has as block properties, set the Java state
exactly. Java -> Bedrock follows the same states. The Java half of the table is copied as it is.

Bedrock 1.26.60 added the connections of chorus plants, fire and redstone wire (they were derived from the
neighbours before, see block/DerivedStates.java).

The script fails when a block appears or disappears, when a palette hash cannot be recomputed, when a new
state matches no old one, or when a new property is neither known nor ignorable.
"""
import argparse
import gzip
import os
import struct
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.dont_write_bytecode = True
sys.path.insert(0, HERE)
import nbtlib  # noqa: E402

# Bedrock block -> Bedrock property -> (Java property, Bedrock value -> Java value)
BOOL = {0: "false", 1: "true"}
WIRE = {"none": "none", "side": "side", "up": "up"}
CONNECTIONS = {
    "minecraft:chorus_plant": {"minecraft:connection_%s" % d: (d, BOOL)
                               for d in ("down", "up", "north", "south", "west", "east")},
    "minecraft:fire": {"minecraft:connection_%s" % d: (d, BOOL) for d in ("up", "north", "south", "west", "east")},
    "minecraft:redstone_wire": {"redstone_%s" % d: (d, WIRE) for d in ("north", "south", "west", "east")},
}


def fail(message):
    print("ERROR: " + message, file=sys.stderr)
    sys.exit(1)


class Reader:
    def __init__(self, data):
        self.d, self.p = data, 0

    def take(self, n):
        v = self.d[self.p:self.p + n]
        self.p += n
        return v

    def u8(self):
        return self.take(1)[0]

    def i8(self):
        return struct.unpack(">b", self.take(1))[0]

    def u16(self):
        return struct.unpack(">H", self.take(2))[0]

    def i32(self):
        return struct.unpack(">i", self.take(4))[0]

    def utf(self):
        return self.take(self.u16()).decode("utf-8")


class Writer:
    def __init__(self):
        self.parts = []

    def raw(self, b):
        self.parts.append(b)

    def u8(self, v):
        self.raw(struct.pack(">B", v))

    def i8(self, v):
        self.raw(struct.pack(">b", v))

    def u16(self, v):
        self.raw(struct.pack(">H", v))

    def i32(self, v):
        self.raw(struct.pack(">i", v))

    def utf(self, s):
        b = s.encode("utf-8")
        self.u16(len(b))
        self.raw(b)


def read_java(r):
    """Java blocks: [(name, [(property, [values])])] and the first state id of each block."""
    blocks = []
    for _ in range(r.u16()):
        name = r.utf()
        r.u16()
        r.u8()
        props = []
        for _ in range(r.u8()):
            pname = r.utf()
            props.append((pname, [r.utf() for _ in range(r.u8())]))
        blocks.append((name, props))
    count = struct.unpack(">I", r.take(4))[0]
    r.take(count * 4)  # light, occlusion, support, flags
    r.take(count * 2)  # waterlogged twins
    return blocks, count


def java_layout(blocks):
    base, layout = 0, {}
    for name, props in blocks:
        size = 1
        for _, values in props:
            size *= len(values)
        layout[name] = (base, props, size)
        base += size
    return layout, base


def java_decode(layout_entry, state):
    base, props, _ = layout_entry
    offset, values = state - base, {}
    for pname, pvalues in reversed(props):  # first property varies slowest
        values[pname] = pvalues[offset % len(pvalues)]
        offset //= len(pvalues)
    return values


def java_encode(layout_entry, values):
    base, props, _ = layout_entry
    offset = 0
    for pname, pvalues in props:
        offset = offset * len(pvalues) + pvalues.index(values[pname])
    return base + offset


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("palette")
    parser.add_argument("--table", default=os.path.join(HERE, "..", "src/main/resources/mappings/block/blocks.bin.gz"))
    args = parser.parse_args()

    data = gzip.open(args.table).read()
    r = Reader(data)
    if r.take(4) != b"JTBB" or r.u8() != 2:
        fail("unsupported table format")
    java_start = r.p
    java_blocks, java_count = read_java(r)
    java_bytes = data[java_start:r.p]
    layout, total = java_layout(java_blocks)
    if total != java_count:
        fail("Java state count mismatch: %d != %d" % (total, java_count))
    java_block_of = {}
    for name, (base, _, size) in layout.items():
        for s in range(base, base + size):
            java_block_of[s] = name

    # Old Bedrock half
    names = []  # (name, [(prop, tag, [values])])
    for _ in range(r.u16()):
        name = r.utf()
        props = []
        for _ in range(r.u8()):
            pname, tag = r.utf(), r.u8()
            reader = {1: r.i8, 3: r.i32, 8: r.utf}[tag]
            props.append((pname, tag, [reader() for _ in range(r.u8())]))
        names.append((name, props))
    size = r.i32()
    old_entries = []
    for _ in range(size):
        ni = r.u16()
        old_entries.append((ni, [r.i8() for _ in names[ni][1]]))
    r.take(size * 4)  # old hashes
    old_to_java = [r.u16() for _ in range(size)]
    old_from_java = [r.u16() for _ in range(java_count)]
    tail = data[r.p:]

    def old_key(entry):
        ni, idx = entry
        name, props = names[ni]
        return name, tuple(sorted((p[0], p[2][i]) for p, i in zip(props, idx)))

    old_index = {old_key(e): i for i, e in enumerate(old_entries)}

    # New palette
    root = nbtlib.load(args.palette, typed=True)
    palette = []
    for typed in root["blocks"][1]:
        name, states = typed["name"][1], typed["states"][1]
        h = typed["network_id"][1]
        if name != "minecraft:unknown" and nbtlib.bedrock_state_hash(name, states) != h:
            fail("hash mismatch for %s" % name)
        palette.append((name, {k: v for k, v in states.items()}, h))
    old_names = {n for n, _ in names}
    new_names = {n for n, _, _ in palette}
    if old_names != new_names:
        fail("blocks changed: +%s -%s (regenerate with gen_world_mappings.py)"
             % (sorted(new_names - old_names), sorted(old_names - new_names)))

    # Property definitions: old order, new properties appended in palette order
    seen = defaultdict(lambda: defaultdict(set))
    tags = {}
    order = defaultdict(list)
    for name, states, _ in palette:
        for k, (tag, value) in states.items():
            seen[name][k].add(value)
            tags[(name, k)] = tag
            if k not in order[name]:
                order[name].append(k)
    new_defs = []
    added = defaultdict(list)
    for name, props in names:
        known = [p[0] for p in props]
        extra = [k for k in order[name] if k not in known]
        for k in extra:
            if k not in CONNECTIONS.get(name, {}):
                fail("new property %s of %s has no mapping (add it to CONNECTIONS)" % (k, name))
            added[name].append(k)
        defs = [(p[0], p[1], p[2]) for p in props]
        for k in extra:
            defs.append((k, tags[(name, k)], sorted(seen[name][k])))
        new_defs.append((name, defs))
    name_index = {n: i for i, (n, _) in enumerate(new_defs)}

    new_entries, hashes, to_java = [], [], []
    new_index = {}
    for name, states, h in palette:
        defs = new_defs[name_index[name]][1]
        idx = [d[2].index(states[d[0]][1]) for d in defs]
        base_key = (name, tuple(sorted((k, v[1]) for k, v in states.items() if k not in added[name])))
        oi = old_index.get(base_key)
        if oi is None:
            fail("no old state for %s %s" % base_key)
        java = old_to_java[oi]
        if added[name] and java != 0xFFFF:
            jname = java_block_of[java]
            values = java_decode(layout[jname], java)
            for k in added[name]:
                jprop, convert = CONNECTIONS[name][k]
                values[jprop] = convert[states[k][1]]
            java = java_encode(layout[jname], values)
        new_index[(name, tuple(sorted((k, v[1]) for k, v in states.items())))] = len(new_entries)
        new_entries.append((name_index[name], idx))
        hashes.append(h)
        to_java.append(java)

    from_java = []
    for state, oi in enumerate(old_from_java):
        if oi == 0xFFFF:
            from_java.append(oi)
            continue
        name, items = old_key(old_entries[oi])
        items = dict(items)
        if added[name]:
            values = java_decode(layout[java_block_of[state]], state)
            for k in added[name]:
                jprop, convert = CONNECTIONS[name][k]
                back = {v: b for b, v in convert.items()}
                items[k] = back[values[jprop]]
        key = (name, tuple(sorted(items.items())))
        if key not in new_index:
            fail("Java state %d maps to %s, missing from the new palette" % (state, key))
        from_java.append(new_index[key])

    w = Writer()
    w.raw(b"JTBB")
    w.u8(2)
    w.raw(java_bytes)
    w.u16(len(new_defs))
    for name, defs in new_defs:
        w.utf(name)
        w.u8(len(defs))
        for pname, tag, values in defs:
            w.utf(pname)
            w.u8(tag)
            w.u8(len(values))
            for v in values:
                {1: w.i8, 3: w.i32, 8: w.utf}[tag](v)
    w.i32(len(new_entries))
    for ni, idx in new_entries:
        w.u16(ni)
        for i in idx:
            w.i8(i)
    for h in hashes:
        w.i32(h)
    for j in to_java:
        w.u16(j)
    for b in from_java:
        w.u16(b)
    w.raw(tail)
    with gzip.open(args.table, "wb", compresslevel=9) as out:
        out.write(b"".join(w.parts))
    print("palette: %d -> %d states; properties added: %s" % (size, len(new_entries),
          {n: ks for n, ks in added.items() if ks}))


if __name__ == "__main__":
    main()
