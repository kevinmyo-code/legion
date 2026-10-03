"""Generate the PWA's placeholder icons from the web's own palette.

A script rather than two committed PNGs of unknown origin: anyone can read
what these are and regenerate them byte for byte, and when a real mark is
drawn this file is the thing that gets replaced rather than a binary someone
has to trust.

No dependencies. Pillow is not in this repo and a flat two-tone mark is not
worth adding one for. The PNG is assembled by hand from the three chunks a
minimal image needs (IHDR, IDAT, IEND), with zlib doing the compression the
format requires, and the shapes are signed-distance fields with 3x3
supersampling so the edges are not stair-stepped.

The colours are the OKLCH values of ADR 0053 (`docs/adr/0053-*.md`), light
mode, copied from `src/index.css` on purpose and converted to sRGB here. They
are NOT read from the phone's `Color.kt` any more: the web stopped wearing the
phone's mission-control palette on 2026-10-03.

    python scripts/make_icons.py            # write public/icon-192.png and icon-512.png
    python scripts/make_icons.py --hex      # print the sRGB hex of each palette entry

`--hex` is how the two `theme-color` values in `src/lib/theme-colors.ts` were
derived; if the palette in `index.css` moves, run it and update that file.
"""

from __future__ import annotations

import math
import struct
import sys
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
PUBLIC = HERE.parent / "public"

# ADR 0053, light mode: (lightness, chroma, hue). Names follow index.css.
PALETTE = {
    "ground-light": (0.98, 0.004, 285),
    "ground-dark": (0.17, 0.006, 285),
    "primary": (0.5, 0.15, 275),
    "primary-container": (0.915, 0.05, 275),
    "shared-container": (0.925, 0.055, 355),
}


def oklch_to_srgb(lightness: float, chroma: float, hue_deg: float) -> tuple[int, int, int]:
    """OKLCH to 8-bit sRGB (Bjorn Ottosson's Oklab matrices), clipped to gamut."""
    h = math.radians(hue_deg)
    a = chroma * math.cos(h)
    b = chroma * math.sin(h)

    l_ = lightness + 0.3963377774 * a + 0.2158037573 * b
    m_ = lightness - 0.1055613458 * a - 0.0638541728 * b
    s_ = lightness - 0.0894841775 * a - 1.2914855480 * b
    l3, m3, s3 = l_**3, m_**3, s_**3

    lin = (
        4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3,
        -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3,
        -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3,
    )

    def gamma(c: float) -> int:
        c = min(1.0, max(0.0, c))
        c = 12.92 * c if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055
        return round(c * 255)

    return gamma(lin[0]), gamma(lin[1]), gamma(lin[2])


def rgb(name: str) -> tuple[int, int, int]:
    return oklch_to_srgb(*PALETTE[name])


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


def round_rect_sdf(
    px: float, py: float, x0: float, y0: float, x1: float, y1: float, r: float
) -> float:
    """Signed distance to a rounded rectangle: negative inside."""
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    hx, hy = (x1 - x0) / 2 - r, (y1 - y0) / 2 - r
    qx, qy = abs(px - cx) - hx, abs(py - cy) - hy
    outside = math.hypot(max(qx, 0.0), max(qy, 0.0))
    return outside + min(max(qx, qy), 0.0) - r


def icon(size: int) -> bytes:
    """A primary-coloured square, an L in the tonal container, a pink dot.

    A flat, full-bleed square on purpose: a maskable icon is cropped by the
    OS into whatever shape it likes, so the ground has to run to the edge and
    the mark has to sit inside the inner 80% safe zone. The L is the first
    letter and nothing more; the pink dot is the "shared" colour from the
    palette. Placeholder, and it reads like one - a real mark is a design
    decision, not this script's.
    """
    ground = rgb("primary")
    letter = rgb("primary-container")
    dot = rgb("shared-container")

    u = size / 100.0
    # Stem and foot of the L, in a 100-unit design box, all inside 20..80.
    stem = (33 * u, 24 * u, 47 * u, 74 * u, 6 * u)
    foot = (33 * u, 61 * u, 69 * u, 74 * u, 6 * u)
    dot_cx, dot_cy, dot_r = 65 * u, 31 * u, 7.5 * u

    offsets = [(i + 0.5) / 3 for i in range(3)]
    rows = []
    for y in range(size):
        row = bytearray()
        for x in range(size):
            acc = [0.0, 0.0, 0.0]
            for oy in offsets:
                for ox in offsets:
                    px, py = x + ox, y + oy
                    if round_rect_sdf(px, py, *stem) < 0 or round_rect_sdf(px, py, *foot) < 0:
                        colour = letter
                    elif math.hypot(px - dot_cx, py - dot_cy) < dot_r:
                        colour = dot
                    else:
                        colour = ground
                    acc[0] += colour[0]
                    acc[1] += colour[1]
                    acc[2] += colour[2]
            row += bytes(round(c / 9) for c in acc)
        rows.append(bytes(row))
    return png(size, size, b"".join(rows))


def main() -> None:
    if "--hex" in sys.argv:
        for name in PALETTE:
            r, g, b = rgb(name)
            print(f"{name:18s} #{r:02x}{g:02x}{b:02x}")
        return
    PUBLIC.mkdir(parents=True, exist_ok=True)
    for size in (192, 512):
        target = PUBLIC / f"icon-{size}.png"
        target.write_bytes(icon(size))
        print(f"wrote {target} ({target.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
