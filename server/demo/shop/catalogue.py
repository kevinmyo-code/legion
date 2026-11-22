"""The made-up shop the store-rep demo sells from. Every name, SKU, price and count below is
invented for this file; none is a real brand or product.

Prices are integer cents (CLAUDE.md section 4 rule 3). Stock is the shop's count when the visit
starts; a visit's own placed orders come off it (`available`), and nothing else ever changes it.
There is one promotion and it is real here: the pack-and-cover bundle. No other discount exists,
which is what lets the rep's sentence check (`guards`) treat any other as invented.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

SHOP_NAME = "Larkspur & Pine"
FREE_SHIPPING_OVER_CENTS = 7_500
SHIPPING_CENTS = 800
DELIVERY_WORDS = "3 to 5 business days"
RETURN_DAYS = 30
MAX_QTY = 5


@dataclass(frozen=True)
class Product:
    sku: str
    name: str
    category: str
    price_cents: int
    stock: int
    blurb: str
    # SKUs that genuinely go with this one: the only add-ons the rep may suggest.
    pairs_with: tuple[str, ...] = ()
    words: tuple[str, ...] = ()


# How a rep names each product in a sentence, lowercase: every word of its name (bar a few generic
# ones), plus a few spoken forms. Generated from the names, so a new product is covered the day it
# is added. A word two products share names both ("The Cirrus is $98.00": the tent and its
# footprint). The price check reads a sentence's amounts against what it names (`guards`).
_GENERIC_WORDS = frozenset({"pack", "bag", "pairs", "pair", "the", "and", "of", "with"})
_SPOKEN_FORMS: dict[str, tuple[str, ...]] = {
    "LP-PK40": ("40 litre", "40-litre"),
    "LP-PK28": ("28 litre", "28-litre"),
    "LP-RC40": ("rain cover",),
    "LP-HB2": ("water bladder",),
    "LP-SB20": ("sleeping bag",),
    "LP-SP": ("sleeping pad",),
    "LP-HL": ("head lamp",),
}


@dataclass(frozen=True)
class Bundle:
    skus: tuple[str, ...]
    saving_cents: int
    label: str


PRODUCTS: tuple[Product, ...] = (
    Product(
        "LP-PK40", "Ridgeline 40L Daypack", "packs", 12_900, 14,
        "40 litres, padded hip belt, fits a 15-inch laptop sleeve.",
        ("LP-RC40", "LP-HB2"), ("backpack", "pack", "bag", "hiking", "travel"),
    ),
    Product(
        "LP-PK28", "Ridgeline 28L Daypack", "packs", 9_800, 6,
        "28 litres, lighter, no hip belt; the cheaper Ridgeline.",
        ("LP-RC40",), ("backpack", "pack", "bag", "daypack", "small"),
    ),
    Product(
        "LP-RC40", "Pack Rain Cover", "accessories", 2_400, 40,
        "Fits packs from 28 to 40 litres.",
        (), ("rain", "cover", "waterproof"),
    ),
    Product(
        "LP-HB2", "2L Hydration Bladder", "accessories", 3_200, 0,
        "Fits the Ridgeline 40L's sleeve.",
        (), ("water", "hydration", "bladder", "drink"),
    ),
    Product(
        "LP-TN2", "Cirrus 2-Person Tent", "camp", 28_900, 3,
        "Two doors, 2.1 kg, three-season.",
        ("LP-FP2",), ("tent", "camping", "shelter"),
    ),
    Product(
        "LP-FP2", "Cirrus 2 Footprint", "camp", 3_900, 12,
        "Ground sheet cut for the Cirrus 2.",
        (), ("footprint", "groundsheet", "tent"),
    ),
    Product(
        "LP-SB20", "Larch 20F Sleeping Bag", "camp", 17_900, 8,
        "Rated to 20 F, synthetic fill.",
        ("LP-SP",), ("sleeping", "bag", "camping", "sleep"),
    ),
    Product(
        "LP-SP", "Ember Sleeping Pad", "camp", 8_900, 10,
        "Self-inflating, 5 cm thick.",
        (), ("pad", "mat", "sleeping", "sleep"),
    ),
    Product(
        "LP-HL", "Lumen 400 Headlamp", "accessories", 3_600, 25,
        "400 lumens, USB-C rechargeable.",
        (), ("light", "lamp", "headlamp", "torch", "flashlight"),
    ),
    Product(
        "LP-SK", "Merino Hiking Socks, 2 pairs", "apparel", 2_200, 50,
        "Wool blend, cushioned heel.",
        (), ("socks", "sock", "merino", "wool"),
    ),
)

BY_SKU: dict[str, Product] = {p.sku: p for p in PRODUCTS}


def _name_words(name: str) -> tuple[str, ...]:
    words = [w.strip(",.").lower() for w in name.split()]
    return tuple(w for w in words if len(w) >= 3 and w not in _GENERIC_WORDS)


ALIASES: dict[str, tuple[str, ...]] = {
    p.sku: (*_name_words(p.name), *_SPOKEN_FORMS.get(p.sku, ())) for p in PRODUCTS
}

BUNDLES: tuple[Bundle, ...] = (
    Bundle(("LP-PK40", "LP-RC40"), 1_000, "Ridgeline 40L with the rain cover"),
)


def money(cents: int) -> str:
    """$129.00: always two places, so the sentence check can compare it exactly."""
    return f"${cents // 100:,}.{cents % 100:02d}"


def available(sku: str, placed: dict[str, int] | None = None) -> int:
    product = BY_SKU.get(sku)
    if product is None:
        return 0
    return max(product.stock - (placed or {}).get(sku, 0), 0)


def stock_words(count: int) -> str:
    return f"{count} in stock" if count else "out of stock, no restock date known"


def find(query: str = "", sku: str = "", limit: int = 4) -> list[Product]:
    """Products whose SKU is `sku`, or whose name, category or words match `query`; best first."""
    if sku:
        product = BY_SKU.get(sku.strip().upper())
        return [product] if product else []
    terms = [t for t in re.split(r"[^a-z0-9]+", (query or "").lower()) if len(t) > 1]
    if not terms:
        return list(PRODUCTS[:limit])
    scored = []
    for index, product in enumerate(PRODUCTS):
        hay = {*re.split(r"[^a-z0-9]+", product.name.lower()), product.category, *product.words}
        score = sum(1 for t in terms if t in hay or t.rstrip("s") in hay)
        if score:
            scored.append((-score, index, product))
    return [p for _, _, p in sorted(scored)[:limit]]


def bundles_for(skus: set[str]) -> list[Bundle]:
    """The bundles any of `skus` belongs to (what the rep may mention about them)."""
    return [b for b in BUNDLES if skus & set(b.skus)]


def name_of(line: dict) -> str:
    """An order line's product name: the catalogue's, else the one stored with it, else the SKU."""
    product = BY_SKU.get(line.get("sku", ""))
    return product.name if product else str(line.get("name") or line.get("sku") or "item")


def bundle_savings() -> set[int]:
    return {b.saving_cents for b in BUNDLES}


def bundle_prices() -> set[int]:
    return {sum(BY_SKU[s].price_cents for s in b.skus) - b.saving_cents for b in BUNDLES}


def backed_cents() -> set[int]:
    """Every amount the shop itself states: prices, bundle prices and savings, the shipping
    rules. A rep's sentence may name these and the totals code computed, nothing else."""
    out = {p.price_cents for p in PRODUCTS}
    out |= bundle_savings() | bundle_prices()
    out.update({FREE_SHIPPING_OVER_CENTS, SHIPPING_CENTS})
    return out


@dataclass(frozen=True)
class Quote:
    lines: tuple[dict, ...]
    bundle_saving_cents: int
    shipping_cents: int
    total_cents: int
    bundle_labels: tuple[str, ...]


def quote(items: dict[str, int]) -> Quote:
    """The order's lines and total in cents: code's arithmetic, never the model's. `items` are
    known SKUs with quantities already checked against stock."""
    lines = []
    subtotal = 0
    for sku, qty in items.items():
        unit = BY_SKU[sku].price_cents
        # The name is kept with the line, so an old order still reads if a SKU is ever renamed.
        lines.append(
            {
                "sku": sku,
                "name": BY_SKU[sku].name,
                "qty": qty,
                "unit_cents": unit,
                "line_cents": unit * qty,
            }
        )
        subtotal += unit * qty
    saving = 0
    labels = []
    for bundle in BUNDLES:
        times = min(items.get(s, 0) for s in bundle.skus)
        if times:
            saving += bundle.saving_cents * times
            labels.append(bundle.label)
    goods = subtotal - saving
    shipping = 0 if goods > FREE_SHIPPING_OVER_CENTS else SHIPPING_CENTS
    return Quote(tuple(lines), saving, shipping, goods + shipping, tuple(labels))
