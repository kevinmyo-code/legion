"""The checking sheet's contract, the truck profile, and what code says from a checked row.

**The sheet** is the owner's private CSV (`garage/example_specs.csv` shows the columns, with
made-up fasteners). `read_sheet` returns the rows to store and every problem in words. A row is
stored only when ticked `verified` with everything a spoken figure needs:

- a figure with at most one decimal place (the table keeps one: nothing is rounded silently) and
  an explicit unit, `ft-lb`, `in-lb` or `N-m`, with no default (an inch-pound figure typed as
  foot-pounds is twelve times too tight);
- the vehicle it is for (years, engine, front axle; "any" where the source says it does not
  matter) and the hardware (`stock`, or `kit` with the kit's name);
- a source of an allowed kind (`garage.models.SOURCE_KINDS`: no forum, no video), a page, a date.

A ticked row is also refused when it does not cover the owner's truck, when it gives a stock
figure for a fastener the sheet marks as aftermarket on his truck (`on_truck` = kit), or when its
figure and source are the mock-up's own (`mockup_torque`, `mockup_torque_source`): a copied
figure is not a checked one. An unticked row is skipped.

**The profile** (`read_profile`): his year, engine, axle and lift, from the same private folder.

**The spoken lines** are built from a stored row and the profile only. Digits, so the torque guard
(`agents.legion.speech_rules.backed_torque`) can match what code said.
"""

from __future__ import annotations

import csv
import datetime as dt
import re
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path
from types import SimpleNamespace

from garage.models import FT_LB, IN_LB, KIT, N_M, NM_PER_FTLB, NM_PER_INLB, SOURCE_KINDS, STOCK

COLUMNS = (
    "fastener_id",
    "on_truck",
    "verified_torque",
    "verified_torque_max",
    "verified_unit",
    "verified_socket",
    "verified_thread",
    "year_from",
    "year_to",
    "engine",
    "axle",
    "hardware",
    "kit_name",
    "source_kind",
    "source",
    "source_page",
    "verified",
    "verified_on",
)
PROFILE_COLUMNS = ("year", "engine", "axle", "lift_in")
YES = frozenset({"yes", "y", "x", "true", "1"})
ANY = "any"
_UNITS = {
    "ft-lb": FT_LB,
    "ft-lbs": FT_LB,
    "lb-ft": FT_LB,
    "in-lb": IN_LB,
    "in-lbs": IN_LB,
    "lb-in": IN_LB,
    "n-m": N_M,
    "nm": N_M,
    "n·m": N_M,
}
# A figure above these is almost surely a typo (the biggest front-end nut is under 200 ft-lb).
MAX_FIGURE = {FT_LB: Decimal(400), IN_LB: Decimal(4800), N_M: Decimal(540)}
KINDS = {k for k, _ in SOURCE_KINDS}
STOCK_KINDS = KINDS - {"kit_instructions"}

NOT_VERIFIED = (
    "The torque for it is not verified yet, so I won't give a number. It's on your checking sheet."
)
NO_TRUCK = "No truck profile is loaded, so I won't give a number for it."
NOT_YOUR_TRUCK = (
    "The checked figure on file is for a different year, engine or axle than your truck, so I "
    "won't give it."
)
STOCK_ON_KIT = (
    "That joint has aftermarket hardware on your truck and the figure on file is for the stock "
    "part, so I won't give it. Use the kit's instructions."
)
CASTLE = "Castle nut: reach the figure, then go on to the next cotter pin slot. Never back off."


@dataclass(frozen=True)
class Truck:
    year: int
    engine: str
    axle: str
    lift_in: Decimal
    kit_fasteners: frozenset[str] = frozenset()


@dataclass(frozen=True)
class SheetRow:
    fastener_id: str
    torque_min: Decimal
    torque_max: Decimal | None
    unit: str
    socket: str
    thread: str
    year_from: int
    year_to: int
    engine: str
    axle: str
    hardware: str
    kit_name: str
    source_kind: str
    source: str
    source_page: str
    verified_on: dt.date


def same(a: str, b: str) -> bool:
    """Engine or axle words alike: case, spaces, dashes and a trailing "L" ignored."""

    def norm(x: str) -> str:
        x = re.sub(r"[\s\-]+", "", (x or "").lower())
        return re.sub(r"(?<=\d)l$", "", x)

    return norm(a) == norm(b)


def covers(row, truck: Truck) -> bool:
    """Whether a row's vehicle covers the truck: its years, and its engine and axle ("any" fits
    every one)."""
    return (
        row.year_from <= truck.year <= row.year_to
        and (same(row.engine, ANY) or same(row.engine, truck.engine))
        and (same(row.axle, ANY) or same(row.axle, truck.axle))
    )


def _decimal(text: str) -> Decimal | None:
    try:
        value = Decimal(text.strip())
    except (InvalidOperation, AttributeError):
        return None
    return value if value.is_finite() and value > 0 else None


def _places(value: Decimal) -> int:
    exponent = value.as_tuple().exponent
    return -exponent if isinstance(exponent, int) and exponent < 0 else 0


def _year(text: str) -> int | None:
    return int(text) if re.fullmatch(r"(19|20)\d\d", text or "") else None


def read_profile(path: Path) -> tuple[Truck | None, list[str]]:
    """The truck from its one-row CSV (`year,engine,axle,lift_in`), or None and the problems."""
    with open(path, newline="", encoding="utf-8-sig") as fh:
        rows = list(csv.DictReader(fh))
    if len(rows) != 1 or any(c not in rows[0] for c in PROFILE_COLUMNS):
        return None, [f"the profile must be one row with the columns {', '.join(PROFILE_COLUMNS)}"]
    got = {c: (rows[0].get(c) or "").strip() for c in PROFILE_COLUMNS}
    problems = [f"profile {c} is empty" for c, v in got.items() if not v]
    year = _year(got["year"])
    lift = _decimal(got["lift_in"]) if got["lift_in"] not in ("0", "") else Decimal(0)
    if got["year"] and year is None:
        problems.append("profile year must be a year like 1998")
    if got["lift_in"] and lift is None:
        problems.append("profile lift_in must be a number of inches (0 for none)")
    if same(got["engine"], ANY) or same(got["axle"], ANY):
        problems.append("the profile names the truck's own engine and axle, never 'any'")
    if problems:
        return None, problems
    return Truck(year, got["engine"], got["axle"], lift), []


def _mockup_copy(raw: dict, low: Decimal | None, source: str) -> bool:
    figure = re.search(r"\d+(?:\.\d+)?", raw.get("mockup_torque") or "")
    told = (raw.get("mockup_torque_source") or "").strip().lower()
    said = source.strip().lower()
    if figure is None or low is None or Decimal(figure.group(0)) != low:
        return False
    return "mock" in said or (bool(told) and (said in told or told in said))


def read_sheet(path: Path, known_ids, truck: Truck) -> tuple[list[SheetRow], int, list[str], set]:
    """(rows to store, rows skipped as not verified, problems, fasteners with kit hardware on
    the truck). Any problem: store nothing."""
    with open(path, newline="", encoding="utf-8-sig") as fh:
        reader = csv.DictReader(fh)
        missing = [c for c in COLUMNS if c not in (reader.fieldnames or [])]
        if missing:
            return [], 0, [f"the sheet has no column {', '.join(missing)}"], set()
        lines = list(reader)
    rows, skipped, problems, seen, kit_ids = [], 0, [], set(), set()
    for number, raw in enumerate(lines, start=2):
        get = {k: (raw.get(k) or "").strip() for k in COLUMNS}
        fid = get["fastener_id"]
        where = f"line {number} ({fid or 'no fastener id'})"
        if fid not in known_ids:
            problems.append(f"{where}: not a fastener on the model")
            continue
        if fid in seen:
            problems.append(f"{where}: this fastener is on the sheet twice")
            continue
        seen.add(fid)
        on_truck = get["on_truck"].lower()
        if on_truck not in (STOCK, KIT):
            problems.append(f"{where}: on_truck must be stock or kit")
            continue
        if on_truck == KIT:
            kit_ids.add(fid)
        if get["verified"].lower() not in YES:
            skipped += 1
            continue
        wrong = []
        low = _decimal(get["verified_torque"])
        high = _decimal(get["verified_torque_max"]) if get["verified_torque_max"] else None
        unit = _UNITS.get(get["verified_unit"].lower())
        if low is None:
            wrong.append("verified_torque is not a number above zero")
        if get["verified_torque_max"] and high is None:
            wrong.append("verified_torque_max is not a number above zero")
        if any(v is not None and _places(v) > 1 for v in (low, high)):
            wrong.append("a figure has more than one decimal place; the table keeps one")
        if unit is None:
            wrong.append("verified_unit must be ft-lb, in-lb or N-m, as the source prints it")
        years = (_year(get["year_from"]), _year(get["year_to"]))
        if None in years:
            wrong.append("year_from and year_to must be years like 1997")
        elif years[0] > years[1]:
            wrong.append("year_from is after year_to")
        for column in ("engine", "axle"):
            if not get[column]:
                wrong.append(
                    f"{column} is empty (write 'any' when the source says it does not matter)"
                )
        for column in ("source", "source_page"):
            if not get[column]:
                wrong.append(f"{column} is empty")
        hardware = get["hardware"].lower()
        kind = get["source_kind"].lower()
        if hardware not in (STOCK, KIT):
            wrong.append("hardware must be stock or kit")
        elif hardware == KIT and not get["kit_name"]:
            wrong.append("kit_name is empty for kit hardware")
        if kind not in KINDS:
            wrong.append(f"source_kind must be one of: {', '.join(sorted(KINDS))}")
        elif hardware == KIT and kind != "kit_instructions":
            wrong.append("a kit's figure comes from the kit maker's instructions")
        elif hardware == STOCK and kind not in STOCK_KINDS:
            wrong.append("a stock figure comes from the factory manual, a reprint or ALLDATA")
        if on_truck == KIT and hardware == STOCK:
            wrong.append("this joint has kit hardware on the truck; a stock figure is refused")
        try:
            verified_on = dt.date.fromisoformat(get["verified_on"])
        except ValueError:
            verified_on = None
            wrong.append("verified_on must be a date like 2026-11-22")
        if low is not None and high is not None and high < low:
            wrong.append("verified_torque_max is below verified_torque")
        if unit is not None and any(v is not None and v > MAX_FIGURE[unit] for v in (low, high)):
            wrong.append(f"a figure over {MAX_FIGURE[unit]} {unit} looks like a typo")
        if _mockup_copy(raw, low, get["source"]):
            wrong.append("the figure and source are the mock-up's own; check it in the source")
        vehicle = SimpleNamespace(
            year_from=years[0], year_to=years[1], engine=get["engine"], axle=get["axle"]
        )
        if not wrong and not covers(vehicle, truck):
            wrong.append("its years, engine or axle do not cover the truck profile")
        if wrong:
            problems.append(f"{where}: " + "; ".join(wrong))
            continue
        rows.append(
            SheetRow(
                fid,
                low,
                high if high != low else None,
                unit,
                get["verified_socket"],
                get["verified_thread"],
                years[0],
                years[1],
                get["engine"],
                get["axle"],
                hardware,
                get["kit_name"],
                kind,
                get["source"],
                get["source_page"],
                verified_on,
            )
        )
    return rows, skipped, problems, kit_ids


# ---- what code says and shows ----------------------------------------------------------------


def _num(value) -> str:
    text = f"{Decimal(value):f}"
    return text.rstrip("0").rstrip(".") if "." in text else text


def spoken_numbers(spec, truck: Truck | None = None) -> set[str]:
    """Every number the spoken lines say for this row (the torque guard's backing)."""
    out = {_num(spec.torque_min)}
    if spec.torque_max is not None:
        out.add(_num(spec.torque_max))
    for text in (spec.socket, spec.thread):
        out |= {_num(n) for n in re.findall(r"\d+(?:\.\d+)?", text or "")}
    if truck is not None:
        out |= {str(truck.year), *(_num(n) for n in re.findall(r"\d+(?:\.\d+)?", truck.engine))}
        out |= {_num(n) for n in re.findall(r"\d+(?:\.\d+)?", truck.axle)}
    return out


def _range(spec) -> str:
    if spec.torque_max is None:
        return _num(spec.torque_min)
    return f"{_num(spec.torque_min)} to {_num(spec.torque_max)}"


UNIT_WORDS = {FT_LB: "foot-pounds", IN_LB: "inch-pounds", N_M: "newton-metres"}


def hardware_words(spec) -> str:
    return f"the {spec.kit_name} part" if spec.hardware == KIT else "the stock part"


def figure_line(spec, truck: Truck) -> str:
    """The checked figure as code says it, with the truck it was checked for and where from."""
    return (
        f"{_range(spec)} {UNIT_WORDS[spec.unit]}. That's for your {truck.year}, {truck.engine}, "
        f"{truck.axle} axle, {hardware_words(spec)}. From {spec.source}, {spec.source_page}, "
        f"checked {spec.verified_on:%B} {spec.verified_on.day}."
    )


def refusal(spec, truck: Truck | None, fastener_id: str) -> str | None:
    """Why a stored row may not be spoken for this truck, as code says it; None when it may."""
    if truck is None:
        return NO_TRUCK
    if not covers(spec, truck):
        return NOT_YOUR_TRUCK
    if fastener_id in truck.kit_fasteners and spec.hardware == STOCK:
        return STOCK_ON_KIT
    return None


def size_line(spec) -> str:
    said = [
        f"socket {spec.socket}" if spec.socket else "",
        f"thread {spec.thread}" if spec.thread else "",
    ]
    said = [s for s in said if s]
    return ("Checked size: " + ", ".join(said) + ".") if said else ""


def _convert(value, unit) -> Decimal:
    v = Decimal(value)
    if unit == FT_LB:
        return v * Decimal(str(NM_PER_FTLB))
    if unit == IN_LB:
        return v * Decimal(str(NM_PER_INLB))
    return v / Decimal(str(NM_PER_FTLB))


def figure_text(spec) -> str:
    """The card's figure: the checked one, then the other unit labelled converted.
    '12 ft-lb (converted: about 16 N·m)'."""
    other = FT_LB if spec.unit == N_M else N_M
    label = {FT_LB: "ft-lb", IN_LB: "in-lb", N_M: "N·m"}

    def conv(v):
        return str(int(_convert(v, spec.unit).quantize(Decimal(1))))

    alt = conv(spec.torque_min)
    if spec.torque_max is not None:
        alt = f"{alt} to {conv(spec.torque_max)}"
    return f"{_range(spec)} {label[spec.unit]} (converted: about {alt} {label[other]})"
