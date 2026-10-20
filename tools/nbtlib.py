"""Minimal NBT support for the JavaToBedrock data generators (Python 3 standard library only).

Reads big-endian (Java, gzip or not), little-endian (Bedrock disk) and network (varint, little-endian)
NBT. Compounds are returned as dicts; with ``typed=True`` every compound value is a ``(tag_type, value)``
pair so Bedrock block states can be re-serialised with their exact tag types (needed for the hashes).
Also writes little-endian NBT and computes the Bedrock block state hash used by PowerNukkitX
(FNV-1a 32 of the little-endian NBT ``{name, states}``, ``minecraft:unknown`` = -2).
"""
import gzip
import io
import struct

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)


class Reader:
    def __init__(self, data, mode="be", typed=False):
        self.b = data
        self.p = 0
        self.mode = mode
        self.typed = typed

    def _unpack(self, fmt, size):
        value = struct.unpack_from(fmt, self.b, self.p)[0]
        self.p += size
        return value

    def _varuint(self):
        result = shift = 0
        while True:
            c = self.b[self.p]
            self.p += 1
            result |= (c & 0x7F) << shift
            if not c & 0x80:
                return result
            shift += 7

    def _zigzag(self):
        v = self._varuint()
        return (v >> 1) ^ -(v & 1)

    def _endian(self):
        return ">" if self.mode == "be" else "<"

    def string(self):
        n = self._varuint() if self.mode == "net" else self._unpack(self._endian() + "H", 2)
        s = self.b[self.p:self.p + n].decode("utf-8", "replace")
        self.p += n
        return s

    def payload(self, t):
        e = self._endian()
        if t == BYTE:
            return self._unpack("b", 1)
        if t == SHORT:
            return self._unpack(e + "h", 2)
        if t == INT:
            return self._zigzag() if self.mode == "net" else self._unpack(e + "i", 4)
        if t == LONG:
            return self._zigzag() if self.mode == "net" else self._unpack(e + "q", 8)
        if t == FLOAT:
            return self._unpack(e + "f", 4)
        if t == DOUBLE:
            return self._unpack(e + "d", 8)
        if t == BYTE_ARRAY:
            n = self.payload(INT)
            v = bytes(self.b[self.p:self.p + n])
            self.p += n
            return v
        if t == STRING:
            return self.string()
        if t == LIST:
            element = self._unpack("B", 1)
            n = self.payload(INT)
            return [self.payload(element) for _ in range(max(n, 0))]
        if t == COMPOUND:
            out = {}
            while True:
                child = self._unpack("B", 1)
                if child == END:
                    return out
                name = self.string()
                value = self.payload(child)
                out[name] = (child, value) if self.typed else value
        if t == INT_ARRAY:
            n = self.payload(INT)
            return [self.payload(INT) for _ in range(n)]
        if t == LONG_ARRAY:
            n = self.payload(INT)
            return [self.payload(LONG) for _ in range(n)]
        raise ValueError("bad tag %d at %d" % (t, self.p))

    def root(self):
        t = self._unpack("B", 1)
        self.string()
        return self.payload(t)


def read_bytes(path):
    with open(path, "rb") as f:
        data = f.read()
    return gzip.decompress(data) if data[:2] == b"\x1f\x8b" else data


def load(path, mode="be", typed=False):
    """Root payload of an NBT file (usually a dict)."""
    return Reader(read_bytes(path), mode, typed).root()


def untyped(value):
    """Strips the (type, value) pairs of a typed compound."""
    if isinstance(value, dict):
        return {k: untyped(v[1]) for k, v in value.items()}
    if isinstance(value, list):
        return [untyped(v) for v in value]
    return value


class WriterLE:
    """Little-endian NBT writer for the subset of tags block states use."""

    def __init__(self):
        self.out = io.BytesIO()

    def string(self, s):
        e = s.encode("utf-8")
        self.out.write(struct.pack("<H", len(e)))
        self.out.write(e)

    def tag(self, t, name, value):
        self.out.write(bytes([t]))
        self.string(name)
        self.payload(t, value)

    def payload(self, t, value):
        if t == BYTE:
            self.out.write(struct.pack("<b", value))
        elif t == SHORT:
            self.out.write(struct.pack("<h", value))
        elif t == INT:
            self.out.write(struct.pack("<i", value))
        elif t == LONG:
            self.out.write(struct.pack("<q", value))
        elif t == STRING:
            self.string(value)
        elif t == COMPOUND:
            for k, (ct, cv) in value.items():
                self.tag(ct, k, cv)
            self.out.write(b"\x00")
        else:
            raise ValueError(t)


def fnv1a_32(data):
    h = 0x811C9DC5
    for c in data:
        h ^= c
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h - (1 << 32) if h & 0x80000000 else h


def fnv1_64(data):
    """FNV-1 (multiply then xor) 64 bit: the order of the non-hashed Bedrock palette (unsigned)."""
    h = 0xCBF29CE484222325
    for c in data:
        h = (h * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
        h ^= c
    return h


def bedrock_state_hash(name, typed_states):
    """``typed_states``: dict name -> (tag type, value)."""
    if name == "minecraft:unknown":
        return -2
    w = WriterLE()
    w.tag(COMPOUND, "", {"name": (STRING, name), "states": (COMPOUND, dict(sorted(typed_states.items())))})
    return fnv1a_32(w.out.getvalue())
