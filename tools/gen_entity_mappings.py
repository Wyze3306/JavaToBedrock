#!/usr/bin/env python3
"""Generates src/main/resources/mappings/entity/entities.json for the entity subsystem.

Sources (read-only reference checkouts, see src/main/resources/mappings/SOURCES.md):
  * ViaBedrock (GPL-3.0)  assets/viabedrock/data/custom/entity_mappings.json    Bedrock id -> Java id
                          assets/viabedrock/data/java/entity_data_fields.json    Java entity data field names per class
                          assets/viabedrock/data/custom/painting_mappings.json   Bedrock painting motive -> Java variant
                          assets/viabedrock/data/bedrock/effects.json            Bedrock effect ids (index + 1)
                          assets/viabedrock/data/custom/effect_mappings.json     Bedrock effect -> Java effect
                          assets/viabedrock/data/custom/damage_cause_mappings.json
  * Geyser (MIT)          core/.../entity/VanillaEntities.java: Java metadata types per index, hit boxes and the
                          Bedrock position offsets, used to name the field types and to cross-check the field counts.

The Java class hierarchy (which ViaBedrock takes from ViaVersion) is written out below; every Java entity type's field
count is verified against Geyser's translator count, so a wrong parent fails the generation.

usage: gen_entity_mappings.py [--viabedrock DIR] [--geyser DIR] [--mcpl DIR] [--out FILE]
"""
import argparse
import json
import os
import re
import sys

REF = '/tmp/jtb/ref'

# Parent of every ViaBedrock entity data class (vanilla 26.3 hierarchy, classes without fields left out).
CLASS_PARENT = {
    'ENTITY': None,
    'AREA_EFFECT_CLOUD': 'ENTITY', 'CUSHION': 'ENTITY', 'END_CRYSTAL': 'ENTITY', 'EXPERIENCE_ORB': 'ENTITY',
    'EYE_OF_ENDER': 'ENTITY', 'FALLING_BLOCK': 'ENTITY', 'FIREWORK_ROCKET': 'ENTITY', 'FISHING_BOBBER': 'ENTITY',
    'ITEM': 'ENTITY', 'OMINOUS_ITEM_SPAWNER': 'ENTITY', 'TNT': 'ENTITY', 'INTERACTION': 'ENTITY',
    'WITHER_SKULL': 'ENTITY', 'FIREBALL': 'ENTITY', 'ITEM_PROJECTILE': 'ENTITY',
    'DISPLAY': 'ENTITY', 'BLOCK_DISPLAY': 'DISPLAY', 'ITEM_DISPLAY': 'DISPLAY', 'TEXT_DISPLAY': 'DISPLAY',
    'ABSTRACT_ARROW': 'ENTITY', 'ARROW': 'ABSTRACT_ARROW', 'TRIDENT': 'ABSTRACT_ARROW',
    'HANGING_ENTITY': 'ENTITY', 'PAINTING': 'HANGING_ENTITY', 'ITEM_FRAME': 'HANGING_ENTITY',
    'VEHICLE': 'ENTITY', 'ABSTRACT_MINECART': 'VEHICLE', 'COMMAND_BLOCK_MINECART': 'ABSTRACT_MINECART',
    'FURNACE_MINECART': 'ABSTRACT_MINECART', 'ABSTRACT_BOAT': 'VEHICLE',
    'LIVING_ENTITY': 'ENTITY', 'ARMOR_STAND': 'LIVING_ENTITY', 'AVATAR': 'LIVING_ENTITY', 'PLAYER': 'AVATAR',
    'MANNEQUIN': 'AVATAR', 'MOB': 'LIVING_ENTITY',
    'ALLAY': 'MOB', 'BAT': 'MOB', 'BLAZE': 'MOB', 'BOGGED': 'MOB', 'COPPER_GOLEM': 'MOB', 'CREAKING': 'MOB',
    'CREEPER': 'MOB', 'ENDERMAN': 'MOB', 'ENDER_DRAGON': 'MOB', 'GHAST': 'MOB', 'IRON_GOLEM': 'MOB',
    'PHANTOM': 'MOB', 'SHULKER': 'MOB', 'SKELETON': 'MOB', 'SNOW_GOLEM': 'MOB', 'SPIDER': 'MOB', 'VEX': 'MOB',
    'WARDEN': 'MOB', 'WITHER': 'MOB', 'ZOGLIN': 'MOB', 'ZOMBIE': 'MOB', 'ZOMBIE_VILLAGER': 'ZOMBIE',
    'GUARDIAN': 'MOB', 'ABSTRACT_FISH': 'MOB', 'PUFFERFISH': 'ABSTRACT_FISH', 'SALMON': 'ABSTRACT_FISH',
    'TADPOLE': 'ABSTRACT_FISH', 'TROPICAL_FISH': 'ABSTRACT_FISH', 'ABSTRACT_PIGLIN': 'MOB',
    'PIGLIN': 'ABSTRACT_PIGLIN', 'ABSTRACT_RAIDER': 'MOB', 'SPELLCASTER_ILLAGER': 'ABSTRACT_RAIDER',
    'PILLAGER': 'ABSTRACT_RAIDER', 'WITCH': 'ABSTRACT_RAIDER',
    'ABSTRACT_AGEABLE': 'MOB',
    'ARMADILLO': 'ABSTRACT_AGEABLE', 'AXOLOTL': 'ABSTRACT_AGEABLE', 'BEE': 'ABSTRACT_AGEABLE',
    'CHICKEN': 'ABSTRACT_AGEABLE', 'COW': 'ABSTRACT_AGEABLE', 'FOX': 'ABSTRACT_AGEABLE', 'FROG': 'ABSTRACT_AGEABLE',
    'HAPPY_GHAST': 'ABSTRACT_AGEABLE', 'HOGLIN': 'ABSTRACT_AGEABLE', 'GOAT': 'ABSTRACT_AGEABLE',
    'MOOSHROOM': 'ABSTRACT_AGEABLE', 'OCELOT': 'ABSTRACT_AGEABLE', 'PANDA': 'ABSTRACT_AGEABLE',
    'PIG': 'ABSTRACT_AGEABLE', 'POLAR_BEAR': 'ABSTRACT_AGEABLE', 'RABBIT': 'ABSTRACT_AGEABLE',
    'SHEEP': 'ABSTRACT_AGEABLE', 'SNIFFER': 'ABSTRACT_AGEABLE', 'STRIDER': 'ABSTRACT_AGEABLE',
    'TURTLE': 'ABSTRACT_AGEABLE', 'DOLPHIN': 'ABSTRACT_AGEABLE', 'GLOW_SQUID': 'ABSTRACT_AGEABLE',
    'ABSTRACT_VILLAGER': 'ABSTRACT_AGEABLE', 'VILLAGER': 'ABSTRACT_VILLAGER',
    'ABSTRACT_CUBE_MOB': 'ABSTRACT_AGEABLE', 'SULFUR_CUBE': 'ABSTRACT_CUBE_MOB',
    'ABSTRACT_HORSE': 'ABSTRACT_AGEABLE', 'HORSE': 'ABSTRACT_HORSE', 'CAMEL': 'ABSTRACT_HORSE',
    'ABSTRACT_CHESTED_HORSE': 'ABSTRACT_HORSE', 'LLAMA': 'ABSTRACT_CHESTED_HORSE',
    'TAMABLE_ANIMAL': 'ABSTRACT_AGEABLE', 'ABSTRACT_NAUTILUS': 'TAMABLE_ANIMAL',
    'ZOMBIE_NAUTILUS': 'ABSTRACT_NAUTILUS', 'CAT': 'TAMABLE_ANIMAL', 'PARROT': 'TAMABLE_ANIMAL',
    'WOLF': 'TAMABLE_ANIMAL',
}

# Java entity types that have no field class of their own: the class whose fields they use.
TYPE_CLASS = {
    'SMALL_FIREBALL': 'FIREBALL', 'DRAGON_FIREBALL': 'ENTITY', 'WIND_CHARGE': 'ENTITY',
    'BREEZE_WIND_CHARGE': 'ENTITY', 'LLAMA_SPIT': 'ENTITY', 'SHULKER_BULLET': 'ENTITY', 'LEASH_KNOT': 'ENTITY',
    'LIGHTNING_BOLT': 'ENTITY', 'MARKER': 'ENTITY', 'EVOKER_FANGS': 'ENTITY',
    'EGG': 'ITEM_PROJECTILE', 'ENDER_PEARL': 'ITEM_PROJECTILE', 'EXPERIENCE_BOTTLE': 'ITEM_PROJECTILE',
    'SPLASH_POTION': 'ITEM_PROJECTILE', 'LINGERING_POTION': 'ITEM_PROJECTILE', 'SNOWBALL': 'ITEM_PROJECTILE',
    'SPECTRAL_ARROW': 'ABSTRACT_ARROW', 'GLOW_ITEM_FRAME': 'ITEM_FRAME',
    'MINECART': 'ABSTRACT_MINECART', 'CHEST_MINECART': 'ABSTRACT_MINECART', 'HOPPER_MINECART': 'ABSTRACT_MINECART',
    'SPAWNER_MINECART': 'ABSTRACT_MINECART', 'TNT_MINECART': 'ABSTRACT_MINECART',
    'BREEZE': 'MOB', 'ENDERMITE': 'MOB', 'GIANT': 'MOB', 'SILVERFISH': 'MOB', 'STRAY': 'MOB', 'PARCHED': 'MOB',
    'WITHER_SKELETON': 'MOB', 'CAVE_SPIDER': 'SPIDER', 'ZOMBIFIED_PIGLIN': 'ZOMBIE', 'DROWNED': 'ZOMBIE',
    'HUSK': 'ZOMBIE', 'ELDER_GUARDIAN': 'GUARDIAN', 'COD': 'ABSTRACT_FISH', 'PIGLIN_BRUTE': 'ABSTRACT_PIGLIN',
    'EVOKER': 'SPELLCASTER_ILLAGER', 'ILLUSIONER': 'SPELLCASTER_ILLAGER', 'RAVAGER': 'ABSTRACT_RAIDER',
    'VINDICATOR': 'ABSTRACT_RAIDER', 'SLIME': 'ABSTRACT_CUBE_MOB', 'MAGMA_CUBE': 'ABSTRACT_CUBE_MOB',
    'WANDERING_TRADER': 'ABSTRACT_VILLAGER', 'SQUID': 'ABSTRACT_AGEABLE', 'CAMEL_HUSK': 'CAMEL',
    'SKELETON_HORSE': 'ABSTRACT_HORSE', 'ZOMBIE_HORSE': 'ABSTRACT_HORSE', 'DONKEY': 'ABSTRACT_CHESTED_HORSE',
    'MULE': 'ABSTRACT_CHESTED_HORSE', 'TRADER_LLAMA': 'LLAMA', 'NAUTILUS': 'ABSTRACT_NAUTILUS',
}
BOAT_RE = re.compile(r'_(CHEST_)?(BOAT|RAFT)$')

# Java types Geyser does not define (it never spawns them): Geyser base definition + own metadata types (MCPL names).
GEYSER_MISSING = {
    'BLOCK_DISPLAY': ('displayBase', ['BLOCK_STATE']),
    'ITEM_DISPLAY': ('displayBase', ['ITEM_STACK', 'BYTE']),
}

# Types of the fields Geyser does not translate (vanilla 26.3 serializers), so every field has a type.
KNOWN_TYPES = {
    ('AREA_EFFECT_CLOUD', 'WAITING'): 'BOOLEAN', ('EXPERIENCE_ORB', 'VALUE'): 'INT',
    ('EYE_OF_ENDER', 'ITEM_STACK'): 'ITEM_STACK', ('FALLING_BLOCK', 'START_POS'): 'BLOCK_POS',
    ('FISHING_BOBBER', 'BITING'): 'BOOLEAN', ('TNT', 'BLOCK_STATE'): 'BLOCK_STATE',
    ('FIREBALL', 'ITEM_STACK'): 'ITEM_STACK',
    ('DISPLAY', 'TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS'): 'INT',
    ('DISPLAY', 'TRANSFORMATION_INTERPOLATION_DURATION'): 'INT', ('DISPLAY', 'POS_ROT_INTERPOLATION_DURATION'): 'INT',
    ('DISPLAY', 'SCALE'): 'VECTOR3', ('DISPLAY', 'LEFT_ROTATION'): 'QUATERNION',
    ('DISPLAY', 'RIGHT_ROTATION'): 'QUATERNION', ('DISPLAY', 'BILLBOARD_RENDER_CONSTRAINTS'): 'BYTE',
    ('DISPLAY', 'BRIGHTNESS_OVERRIDE'): 'INT', ('DISPLAY', 'VIEW_RANGE'): 'FLOAT', ('DISPLAY', 'SHADOW_RADIUS'): 'FLOAT',
    ('DISPLAY', 'SHADOW_STRENGTH'): 'FLOAT', ('DISPLAY', 'WIDTH'): 'FLOAT', ('DISPLAY', 'HEIGHT'): 'FLOAT',
    ('DISPLAY', 'GLOW_COLOR_OVERRIDE'): 'INT',
    ('TEXT_DISPLAY', 'LINE_WIDTH'): 'INT', ('TEXT_DISPLAY', 'BACKGROUND_COLOR'): 'INT',
    ('TEXT_DISPLAY', 'TEXT_OPACITY'): 'BYTE', ('TEXT_DISPLAY', 'STYLE_FLAGS'): 'BYTE',
    ('ABSTRACT_ARROW', 'PIERCE_LEVEL'): 'BYTE', ('TRIDENT', 'LOYALTY'): 'BYTE',
    ('LIVING_ENTITY', 'ARROW_COUNT'): 'INT', ('LIVING_ENTITY', 'STINGER_COUNT'): 'INT',
    ('AVATAR', 'PLAYER_MAIN_HAND'): 'HUMANOID_ARM', ('PLAYER', 'SCORE'): 'INT', ('MANNEQUIN', 'IMMOVABLE'): 'BOOLEAN',
    ('IRON_GOLEM', 'FLAGS'): 'BYTE', ('ZOMBIE', 'SPECIAL_TYPE'): 'INT',
    ('ZOMBIE_VILLAGER', 'VILLAGER_DATA_FINALIZED'): 'BOOLEAN', ('VILLAGER', 'VILLAGER_DATA_FINALIZED'): 'BOOLEAN',
    ('GUARDIAN', 'MOVING'): 'BOOLEAN', ('ABSTRACT_FISH', 'FROM_BUCKET'): 'BOOLEAN', ('SALMON', 'TYPE'): 'INT',
    ('TADPOLE', 'AGE_LOCKED'): 'BOOLEAN', ('ABSTRACT_RAIDER', 'IS_CELEBRATING'): 'BOOLEAN',
    ('WITCH', 'USING_ITEM'): 'BOOLEAN', ('ABSTRACT_AGEABLE', 'AGE_LOCKED'): 'BOOLEAN',
    ('AXOLOTL', 'FROM_BUCKET'): 'BOOLEAN', ('CHICKEN', 'SOUND_VARIANT'): 'CHICKEN_SOUND_VARIANT',
    ('COW', 'SOUND_VARIANT'): 'COW_SOUND_VARIANT', ('PIG', 'SOUND_VARIANT'): 'PIG_SOUND_VARIANT',
    ('CAT', 'SOUND_VARIANT'): 'CAT_SOUND_VARIANT', ('WOLF', 'SOUND_VARIANT'): 'WOLF_SOUND_VARIANT',
    ('CAT', 'RELAX_STATE_ONE'): 'BOOLEAN', ('FOX', 'TRUSTED_0'): 'OPTIONAL_LIVING_ENTITY_REFERENCE',
    ('FOX', 'TRUSTED_1'): 'OPTIONAL_LIVING_ENTITY_REFERENCE', ('HAPPY_GHAST', 'IS_LEASH_HOLDER'): 'BOOLEAN',
    ('PANDA', 'UNHAPPY_COUNTER'): 'INT', ('PANDA', 'SNEEZE_COUNTER'): 'INT', ('SNIFFER', 'DROP_SEED_AT_TICK'): 'INT',
    ('DOLPHIN', 'GOT_FISH'): 'BOOLEAN', ('DOLPHIN', 'MOISTNESS_LEVEL'): 'INT',
    ('GLOW_SQUID', 'DARK_TICKS_REMAINING'): 'INT', ('ABSTRACT_VILLAGER', 'UNHAPPY_COUNTER'): 'INT',
    ('SULFUR_CUBE', 'FROM_BUCKET'): 'BOOLEAN',
}

# Bedrock y = Java y + offset. Geyser's values (vanilla Bedrock) where PowerNukkitX agrees; PowerNukkitX's own
# getBaseOffset() otherwise (falling blocks are lifted like TNT, boats by 0.37).
OFFSET_OVERRIDES = {'falling_block': 0.49}
BOAT_OFFSET = 0.37

# Bedrock boat/chest boat VARIANT data -> Java wood (Geyser BoatEntity.BoatVariant order).
# Bedrock ActorDamageCause ids (ActorEvent HURT data): ViaBedrock's damage_cause_mappings.json keys by value.
DAMAGE_CAUSES = ['Override', 'Contact', 'EntityAttack', 'Projectile', 'Suffocation', 'Fall', 'Fire', 'FireTick', 'Lava',
                 'Drowning', 'BlockExplosion', 'EntityExplosion', 'Void', 'SelfDestruct', 'Magic', 'Wither', 'Starve',
                 'Anvil', 'Thorns', 'FallingBlock', 'Piston', 'FlyIntoWall', 'Magma', 'Fireworks', 'Lightning',
                 'Charging', 'Temperature', 'Freezing', 'Stalactite', 'Stalagmite', 'RamAttack', 'SonicBoom',
                 'Campfire', 'SoulCampfire', 'MaceSmash']

BOAT_VARIANTS = ['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove', 'bamboo', 'cherry', 'pale_oak',
                 'poplar']


def fail(message):
    print('error: ' + message, file=sys.stderr)
    sys.exit(1)


def load_json(path):
    with open(path, encoding='utf-8') as f:
        return json.load(f)


# ----------------------------------------------------------------------------------------------- Geyser parsing

def strip_comments(src):
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == '\\' else 1
            out.append(src[i:j + 1])
            i = j + 1
        elif src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
        elif src.startswith('/*', i):
            j = src.find('*/', i)
            i = n if j < 0 else j + 2
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def split_statements(body):
    depth, start, res, i = 0, 0, [], 0
    while i < len(body):
        c = body[i]
        if c == '"':
            j = i + 1
            while body[j] != '"':
                j += 2 if body[j] == '\\' else 1
            i = j + 1
            continue
        if c in '({[':
            depth += 1
        elif c in ')}]':
            depth -= 1
        elif c == ';' and depth == 0:
            res.append(' '.join(body[start:i].split()))
            start = i + 1
        i += 1
    return [s for s in res if s]


def call_args(text, open_index):
    """Top-level arguments of the call whose '(' is at open_index."""
    depth, args, start, i = 0, [], open_index + 1, open_index
    while True:
        c = text[i]
        if c in '({[':
            depth += 1
        elif c in ')}]':
            depth -= 1
            if depth == 0:
                args.append(text[start:i].strip())
                return args
        elif c == ',' and depth == 1:
            args.append(text[start:i].strip())
            start = i + 1
        i += 1


def parse_geyser(path):
    src = strip_comments(open(path, encoding='utf-8').read())
    m = re.search(r'static\s*\{', src)
    i, depth = m.end(), 1
    while depth:
        depth += {'{': 1, '}': -1}.get(src[i], 0)
        i += 1
    body = src[m.end():i - 1]
    body = re.sub(r'(?m)^\s*\{\s*$', '', body)
    body = re.sub(r'(?m)^\s*\}\s*$', ';', body)
    defs = {}
    types = {}
    for st in split_statements(body):
        target = re.match(r'(?:[\w<>.?, ]+\s)?(\w+)\s*=\s*(.*)$', st)
        if not target:
            continue
        name, expr = target.group(1), target.group(2)
        parent = None
        own_types = None
        if 'baseBuilder(' in expr:
            parent = None
        elif re.match(r'(?:VanillaEntityType|EntityTypeBase)\.(?:<[\w<>]+>)?(?:inherited|baseInherited)\(', expr):
            open_index = expr.index('(', expr.index('nherited'))
            parent = call_args(expr, open_index)[-1]
        elif expr.startswith('buildBoat(') or expr.startswith('buildChestBoat('):
            args = call_args(expr, expr.index('('))
            parent = args[0]
            own_types = []
            jtype = args[1].split('.')[-1]
            types[jtype] = name
        else:
            continue
        if own_types is None:
            own_types = []
            for t in re.finditer(r'\.addTranslator\(', expr):
                args = call_args(expr, t.end() - 1)
                own_types.append(None if args[0] == 'null' else args[0].split('.')[-1])
        d = {'parent': parent, 'types': own_types}
        for key in ('height', 'width', 'heightAndWidth', 'offset'):
            mm = re.search(r'\.' + key + r'\(([-\d.]+)[fF]?\)', expr)
            if mm:
                d[key] = float(mm.group(1))
        mm = re.search(r'\.bedrockIdentifier\("([^"]+)"\)', expr)
        if mm:
            d['bedrock'] = mm.group(1)
        mm = re.search(r'\.type\(EntityType\.(\w+)\)', expr)
        if mm:
            types[mm.group(1)] = name
        defs[name] = d
    return defs, types


def geyser_chain(defs, name):
    chain = []
    while name is not None:
        if name not in defs:
            fail('Geyser definition not found: ' + name)
        chain.append(defs[name])
        name = defs[name]['parent']
    return list(reversed(chain))


def geyser_info(defs, name):
    meta = []
    info = {'width': 0.0, 'height': 0.0, 'offset': 0.0}
    for d in geyser_chain(defs, name):
        meta.extend(d['types'])
        if 'heightAndWidth' in d:
            info['width'] = info['height'] = d['heightAndWidth']
        if 'height' in d:
            info['height'] = d['height']
        if 'width' in d:
            info['width'] = d['width']
        if 'offset' in d:
            info['offset'] = d['offset']
        if 'bedrock' in d:
            info['bedrock'] = d['bedrock']
    info['meta'] = meta
    return info


# ----------------------------------------------------------------------------------------------- MCPL entity types

def mcpl_entity_types(mcpl_dir):
    path = os.path.join(mcpl_dir, 'protocol/src/main/java/org/geysermc/mcprotocollib/protocol/data/game/entity/type/EntityType.java')
    src = strip_comments(open(path, encoding='utf-8').read())
    start = src.index('{', src.index('enum EntityType')) + 1
    body = src[start:src.index(';', start)]
    return [m.group(1) for m in re.finditer(r'\b([A-Z][A-Z0-9_]*)\b(?:\([^)]*\))?', body)]


def class_chain(cls):
    chain = []
    while cls is not None:
        if cls not in CLASS_PARENT:
            fail('unknown entity data class ' + cls)
        chain.append(cls)
        cls = CLASS_PARENT[cls]
    return list(reversed(chain))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--viabedrock', default=os.path.join(REF, 'ViaBedrock'))
    parser.add_argument('--geyser', default=os.path.join(REF, 'GeyserMC_Geyser'))
    parser.add_argument('--mcpl', default=os.path.join(REF, 'GeyserMC_MCProtocolLib'))
    parser.add_argument('--out', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), '..',
                                                       'src/main/resources/mappings/entity/entities.json'))
    args = parser.parse_args()

    vbd = os.path.join(args.viabedrock, 'src/main/resources/assets/viabedrock/data')
    fields = load_json(os.path.join(vbd, 'java/entity_data_fields.json'))
    bedrock_to_java = load_json(os.path.join(vbd, 'custom/entity_mappings.json'))
    paintings = load_json(os.path.join(vbd, 'custom/painting_mappings.json'))
    bedrock_effects = load_json(os.path.join(vbd, 'bedrock/effects.json'))
    effect_mappings = load_json(os.path.join(vbd, 'custom/effect_mappings.json'))
    damage_causes = load_json(os.path.join(vbd, 'custom/damage_cause_mappings.json'))
    geyser_defs, geyser_types = parse_geyser(os.path.join(
        args.geyser, 'core/src/main/java/org/geysermc/geyser/entity/VanillaEntities.java'))
    java_types = mcpl_entity_types(args.mcpl)
    if sorted(damage_causes) != sorted(DAMAGE_CAUSES):
        fail('damage cause names changed: ' + str(sorted(set(damage_causes) ^ set(DAMAGE_CAUSES))))

    for cls in fields:
        if cls not in CLASS_PARENT:
            fail('class without parent: ' + cls)

    types_out = {}
    class_types = {}
    errors = []
    for jtype in java_types:
        if jtype in fields:
            cls = jtype
        elif jtype in TYPE_CLASS:
            cls = TYPE_CLASS[jtype]
        elif BOAT_RE.search(jtype):
            cls = 'ABSTRACT_BOAT'
        else:
            errors.append('no data class for Java type ' + jtype)
            continue
        names = []
        for c in class_chain(cls):
            names.extend(fields.get(c, []))
        if len(set(names)) != len(names):
            errors.append('duplicate field names for %s: %s' % (jtype, names))
        gname = geyser_types.get(jtype)
        if gname is None and jtype in GEYSER_MISSING:
            base, own = GEYSER_MISSING[jtype]
            geyser_defs['$' + jtype] = {'parent': base, 'types': own}
            gname = '$' + jtype
        if gname is None:
            errors.append('Java type %s missing in Geyser' % jtype)
            continue
        info = geyser_info(geyser_defs, gname)
        if len(info['meta']) != len(names):
            errors.append('%s: %d fields (ViaBedrock) vs %d translators (Geyser)' % (jtype, len(names), len(info['meta'])))
            continue
        entry = {'class': cls, 'width': info['width'], 'height': info['height']}
        offset = info['offset']
        if cls == 'ABSTRACT_BOAT':
            offset = BOAT_OFFSET
        offset = OFFSET_OVERRIDES.get(jtype.lower(), offset)
        if offset:
            entry['offset'] = offset
        types_out[jtype.lower()] = entry
        # Metadata types of the class chain's fields, merged over every Java type using them.
        index = 0
        for c in class_chain(cls):
            known = class_types.setdefault(c, [None] * len(fields.get(c, [])))
            for i in range(len(known)):
                t = info['meta'][index]
                index += 1
                if t is None:
                    continue
                if known[i] is not None and known[i] != t:
                    errors.append('%s.%s typed %s and %s' % (c, fields[c][i], known[i], t))
                known[i] = t
    if errors:
        fail('\n  '.join(['entity hierarchy check failed:'] + errors))

    classes_out = {}
    for cls, parent in CLASS_PARENT.items():
        names = fields.get(cls, [])
        known = class_types.get(cls, [None] * len(names))
        typed = []
        for name, t in zip(names, known):
            t = t or KNOWN_TYPES.get((cls, name))
            if t is None:
                fail('no metadata type for %s.%s' % (cls, name))
            typed.append([name, t])
        classes_out[cls] = {'parent': parent, 'fields': typed}

    bedrock_out = {}
    for bedrock_id, java_id in sorted(bedrock_to_java.items()):
        if java_id is not None:
            java_name = java_id.split(':', 1)[1]
            if java_name not in types_out:
                fail('mapping %s -> unknown Java type %s' % (bedrock_id, java_id))
            java_id = java_name
        bedrock_out[bedrock_id] = java_id

    effects_out = {}
    for index, bedrock_id in enumerate(bedrock_effects):
        java_id = effect_mappings.get(bedrock_id)
        effects_out[str(index + 1)] = None if java_id is None else java_id.split(':', 1)[1]

    out = {
        '_comment': 'Generated by tools/gen_entity_mappings.py - do not edit. Sources: ViaBedrock (GPL-3.0), Geyser (MIT).',
        'bedrock_to_java': bedrock_out,
        'classes': classes_out,
        'java_types': types_out,
        'paintings': {k: v.split(':', 1)[1] for k, v in sorted(paintings.items())},
        'effects': effects_out,
        'damage_causes': [damage_causes[name].split(':', 1)[1] for name in DAMAGE_CAUSES],
        'boat_variants': BOAT_VARIANTS,
    }
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(out, f, separators=(',', ':'), sort_keys=False)
        f.write('\n')
    print('wrote %s: %d Bedrock ids, %d Java types, %d classes, %d paintings, %d effects' % (
        os.path.normpath(args.out), len(bedrock_out), len(types_out), len(classes_out), len(out['paintings']),
        len(effects_out)))


if __name__ == '__main__':
    main()
