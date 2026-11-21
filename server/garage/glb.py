"""The schematic model as a binary glTF (GLB), built from `garage.xj_front` with no 3D tool.

One node per part (its solids as one mesh, its own material, so the screen can tint or fade one
part alone) and one node per fastener (a small hex marker at its spot). Every node carries its id
in `extras.legion` = {id, kind}: three's GLTFLoader strips dots from node NAMES, but hands
`extras` over untouched as `userData`. Colours are baked into materials (no textures: the page's
CSP has no `blob:`). Deterministic: the same manifest gives the same bytes, so a test can hold
the committed file to it.
"""

from __future__ import annotations

import json
import math
import struct

from garage import xj_front as m

FLOAT = 5126
UINT16 = 5123
UINT32 = 5125
ARRAY_BUFFER = 34962
ELEMENT_BUFFER = 34963
MARKER = (0.91, 0.31, 0.12, 1.0)
MARKER_RADIUS_IN = 0.75
MARKER_HEIGHT_IN = 0.7


def _sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _norm(v):
    n = math.sqrt(sum(x * x for x in v)) or 1.0
    return (v[0] / n, v[1] / n, v[2] / n)


def _cylinder(a, b, r, segments):
    """Side, top and bottom of a cylinder from a to b: (positions, normals, indices)."""
    axis = _norm(_sub(b, a))
    helper = (1.0, 0.0, 0.0) if abs(axis[0]) < 0.9 else (0.0, 1.0, 0.0)
    u = _norm(_cross(axis, helper))
    v = _cross(axis, u)
    pos, nor, idx = [], [], []
    ring = []
    for i in range(segments):
        t = 2 * math.pi * i / segments
        d = tuple(math.cos(t) * u[k] + math.sin(t) * v[k] for k in range(3))
        ring.append(d)
    # side
    for d in ring:
        for end in (a, b):
            pos.append(tuple(end[k] + d[k] * r for k in range(3)))
            nor.append(d)
    for i in range(segments):
        j = (i + 1) % segments
        a0, b0, a1, b1 = 2 * i, 2 * i + 1, 2 * j, 2 * j + 1
        idx += [a0, a1, b0, b0, a1, b1]
    # caps
    for end, n, flip in ((a, tuple(-x for x in axis), True), (b, axis, False)):
        centre = len(pos)
        pos.append(end)
        nor.append(n)
        for d in ring:
            pos.append(tuple(end[k] + d[k] * r for k in range(3)))
            nor.append(n)
        for i in range(segments):
            p, q = centre + 1 + i, centre + 1 + (i + 1) % segments
            idx += [centre, q, p] if flip else [centre, p, q]
    return pos, nor, idx


def _box(c, s):
    pos, nor, idx = [], [], []
    h = (s[0] / 2, s[1] / 2, s[2] / 2)
    for axis in range(3):
        for sign in (-1, 1):
            n = [0.0, 0.0, 0.0]
            n[axis] = float(sign)
            o1, o2 = [(axis + 1) % 3, (axis + 2) % 3]
            corners = []
            for a1, a2 in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
                p = [0.0, 0.0, 0.0]
                p[axis] = sign * h[axis]
                p[o1] = a1 * h[o1]
                p[o2] = a2 * h[o2]
                corners.append(tuple(c[k] + p[k] for k in range(3)))
            base = len(pos)
            pos += corners
            nor += [tuple(n)] * 4
            idx += (
                [base, base + 1, base + 2, base, base + 2, base + 3]
                if sign > 0
                else [base, base + 2, base + 1, base, base + 3, base + 2]
            )
    return pos, nor, idx


def _ball(c, r, rings=8, segments=12):
    pos, nor, idx = [], [], []
    for i in range(rings + 1):
        phi = math.pi * i / rings
        for j in range(segments + 1):
            th = 2 * math.pi * j / segments
            n = (math.sin(phi) * math.cos(th), math.cos(phi), math.sin(phi) * math.sin(th))
            pos.append(tuple(c[k] + n[k] * r for k in range(3)))
            nor.append(n)
    for i in range(rings):
        for j in range(segments):
            p = i * (segments + 1) + j
            q = p + segments + 1
            idx += [p, p + 1, q, q, p + 1, q + 1]
    return pos, nor, idx


def _solid(shape: m.Shape):
    a = tuple(x * m.INCH for x in shape.a)
    if shape.kind == "rod":
        b = tuple(x * m.INCH for x in shape.b)
        return _cylinder(a, b, shape.r * m.INCH, shape.segments)
    if shape.kind == "box":
        return _box(a, tuple(x * m.INCH for x in shape.b))
    return _ball(a, shape.r * m.INCH)


def _marker():
    """One hex marker centred on its node's origin: each fastener node places it (`translation`),
    so the screen can scale a marker about its own centre."""
    half = MARKER_HEIGHT_IN * m.INCH / 2
    return _cylinder((0.0, -half, 0.0), (0.0, half, 0.0), MARKER_RADIUS_IN * m.INCH, 6)


def _merge(solids):
    pos, nor, idx = [], [], []
    for p, n, i in solids:
        base = len(pos)
        pos += p
        nor += n
        idx += [base + x for x in i]
    return pos, nor, idx


class _Writer:
    def __init__(self):
        self.bin = bytearray()
        self.views = []
        self.accessors = []

    def _view(self, data: bytes, target: int) -> int:
        while len(self.bin) % 4:
            self.bin.append(0)
        self.views.append(
            {"buffer": 0, "byteOffset": len(self.bin), "byteLength": len(data), "target": target}
        )
        self.bin += data
        return len(self.views) - 1

    def vec3(self, values) -> int:
        flat = [round(float(x), 6) for v in values for x in v]
        view = self._view(struct.pack(f"<{len(flat)}f", *flat), ARRAY_BUFFER)
        xs = [struct.unpack("<f", struct.pack("<f", x))[0] for x in flat]
        lo = [min(xs[k::3]) for k in range(3)]
        hi = [max(xs[k::3]) for k in range(3)]
        self.accessors.append(
            {
                "bufferView": view,
                "componentType": FLOAT,
                "count": len(values),
                "type": "VEC3",
                "min": lo,
                "max": hi,
            }
        )
        return len(self.accessors) - 1

    def indices(self, values) -> int:
        big = max(values) > 65535
        fmt = "I" if big else "H"
        view = self._view(struct.pack(f"<{len(values)}{fmt}", *values), ELEMENT_BUFFER)
        self.accessors.append(
            {
                "bufferView": view,
                "componentType": UINT32 if big else UINT16,
                "count": len(values),
                "type": "SCALAR",
            }
        )
        return len(self.accessors) - 1

    def mesh(self, solids, material: int, name: str) -> dict:
        pos, nor, idx = _merge(solids)
        return {
            "name": name,
            "primitives": [
                {
                    "attributes": {"POSITION": self.vec3(pos), "NORMAL": self.vec3(nor)},
                    "indices": self.indices(idx),
                    "material": material,
                }
            ],
        }


def _material(name, color):
    mat = {
        "name": name,
        "pbrMetallicRoughness": {
            "baseColorFactor": list(color),
            "metallicFactor": 0.2,
            "roughnessFactor": 0.6,
        },
    }
    if color[3] < 1:
        mat["alphaMode"] = "BLEND"
        mat["doubleSided"] = True
    return mat


def _node_name(identifier: str) -> str:
    # Readable in a glTF viewer; the id itself rides in `extras`.
    return identifier.replace(".", "_").replace("@", "__")


def build() -> bytes:
    w = _Writer()
    materials, meshes, nodes = [], [], []
    for part in m.PARTS:
        materials.append(_material(part.id, part.color))
        meshes.append(w.mesh([_solid(s) for s in part.shapes], len(materials) - 1, part.id))
        nodes.append(
            {
                "name": _node_name(part.id),
                "mesh": len(meshes) - 1,
                "extras": {"legion": {"id": part.id, "kind": "part"}},
            }
        )
    materials.append(_material("fastener", MARKER))
    marker_material = len(materials) - 1
    meshes.append(w.mesh([_marker()], marker_material, "fastener"))
    marker_mesh = len(meshes) - 1
    for f in m.FASTENERS:
        nodes.append(
            {
                "name": _node_name(f.id),
                "mesh": marker_mesh,
                "translation": [round(v, 6) for v in m.position_m(f)],
                "extras": {"legion": {"id": f.id, "kind": "fastener", "part": f.part}},
            }
        )
    root = {
        "name": m.MODEL,
        "children": list(range(len(nodes))),
        "extras": {"legion": {"id": m.MODEL, "kind": "model"}},
    }
    nodes.append(root)
    gltf = {
        "asset": {"version": "2.0", "generator": "legion garage.glb"},
        "scene": 0,
        "scenes": [{"name": m.MODEL, "nodes": [len(nodes) - 1]}],
        "nodes": nodes,
        "meshes": meshes,
        "materials": materials,
        "accessors": w.accessors,
        "bufferViews": w.views,
        "buffers": [{"byteLength": len(w.bin)}],
    }
    while len(w.bin) % 4:
        w.bin.append(0)
    gltf["buffers"][0]["byteLength"] = len(w.bin)
    js = json.dumps(gltf, separators=(",", ":"), sort_keys=True).encode("utf-8")
    js += b" " * ((4 - len(js) % 4) % 4)
    total = 12 + 8 + len(js) + 8 + len(w.bin)
    return (
        struct.pack("<4sII", b"glTF", 2, total)
        + struct.pack("<I4s", len(js), b"JSON")
        + js
        + struct.pack("<I4s", len(w.bin), b"BIN\x00")
        + bytes(w.bin)
    )


def read_json(data: bytes) -> dict:
    """The JSON chunk of a GLB (for tests and checks)."""
    magic, version, _ = struct.unpack_from("<4sII", data, 0)
    if magic != b"glTF" or version != 2:
        raise ValueError("not a glTF 2.0 binary")
    length, kind = struct.unpack_from("<I4s", data, 12)
    if kind != b"JSON":
        raise ValueError("first chunk is not JSON")
    return json.loads(data[20 : 20 + length])
