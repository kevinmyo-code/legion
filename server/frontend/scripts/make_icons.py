"""Generate the PWA's placeholder icons.

A script rather than two committed PNGs of unknown origin: anyone can read
what these are and regenerate them byte for byte, and when ticket 05 picks a
real mark this file is the thing that gets replaced rather than a binary
someone has to trust.

No dependencies - Pillow is not in this repo and a 192x192 solid square with a
border is not worth adding one for. The PNG is assembled by hand from the two
chunks a minimal image needs (IHDR, IDAT) plus IEND, with zlib doing the
compression the format requires.

Colours are read from the phone's mission-control palette rather than typed
in twice; see `app/src/main/java/com/kevin/legion/ui/theme/Color.kt`.

    python scripts/make_icons.py
"""

from __future__ import annotations

import re
import struct
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
PUBLIC = HERE.parent / "public"
# server/frontend/scripts -> server/frontend -> server -> repo root
COLOR_KT = (
    HERE.parent.parent.parent
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "kevin"
    / "legion"
    / "ui"
    / "theme"
    / "Color.kt"
)


def read_palette(name: str) -> tuple[int, int, int]:
    """Pull one `val <name> = Color(0xFFRRGGBB)` out of Color.kt.

    Parsed from the Kotlin rather than restated here on purpose: a hex value
    copied into a second file is a value that goes wrong the first time the
    palette is re-cut, and this one already has been (MILSPEC -> VACUUM/SENTRY).
    """
    source = COLOR_KT.read_text(encoding="utf-8")
    match = re.search(rf"^val {name} = Color\(0x([0-9A-Fa-f]{{8}})\)", source, re.M)
    if not match:
        raise SystemExit(f"{name} not found in {COLOR_KT}. Was the palette re-cut?")
    argb = int(match.group(1), 16)
    return (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF


def png(width: int, height: int, pixels: bytes) -> bytes:
    """Wrap raw RGB rows in the smallest valid PNG container."""

    def chunk(kind: bytes, payload: bytes) -> bytes:
        return (
            struct.pack(">I", len(payload))
            + kind
            + payload
            + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
        )

    # Colour type 2 is truecolour RGB, bit depth 8. Every scanline is prefixed
    # with a filter byte; 0 means "no filter", which keeps this readable at the
    # cost of a slightly larger file nobody will notice at 512 pixels.
    raw = b"".join(
        b"\x00" + pixels[y * width * 3 : (y + 1) * width * 3] for y in range(height)
    )
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def icon(size: int, ground: tuple[int, int, int], chrome: tuple[int, int, int]) -> bytes:
    """A ground-filled square inside a chrome border.

    Placeholder, and it looks like one on purpose - a maskable icon safe zone
    is the inner 80%, so the border sits outside it and is what gets cropped
    away on an Android adaptive icon. Nothing here should read as a finished
    mark.
    """
    edge = max(1, size // 16)
    rows = []
    for y in range(size):
        row = bytearray()
        for x in range(size):
            on_border = x < edge or y < edge or x >= size - edge or y >= size - edge
            row += bytes(chrome if on_border else ground)
        rows.append(bytes(row))
    return png(size, size, b"".join(rows))


def main() -> None:
    ground = read_palette("DeckGround")
    chrome = read_palette("DeckChrome")
    PUBLIC.mkdir(parents=True, exist_ok=True)
    for size in (192, 512):
        target = PUBLIC / f"icon-{size}.png"
        target.write_bytes(icon(size, ground, chrome))
        print(f"wrote {target} ({target.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
