"""The checking sheet's contract, and the sentences code speaks from a checked row.

**The sheet** is the owner's private CSV (`garage/example_specs.csv` shows the columns, with
made-up fasteners). `read_sheet` returns the rows to store and every problem in words. A row is
stored only when ticked `verified` with everything a spoken figure needs; an unticked row is
skipped. A source naming a forum is refused: a forum figure is never spoken for steering or
suspension (plan decision 5).

**The spoken lines** (`figure_line`) are built from a stored row's fields only. A fastener with
no stored row gets `NOT_VERIFIED`, which holds no number. Digits, so the torque guard
(`agents.legion.speech_rules.backed_torque`) can match what code said.
"""

from __future__ import annotations

import csv
import datetime as dt
import re
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path

from garage.models import FT_LB, N_M, NM_PER_FTLB

COLUMNS = (
    "fastener_id",
    "verified_torque",
    "verified_torque_max",
    "verified_unit",
    "verified_socket",
    "verified_thread",
    "applies_to",
    "source",
    "source_page",
    "verified",
    "verified_on",
)
YES = frozenset({"yes", "y", "x", "true", "1"})
_UNITS = {
    "ft-lb": FT_LB,
    "ft lb": FT_LB,
    "ft-lbs": FT_LB,
    "ftlb": FT_LB,
    "lb-ft": FT_LB,
    "foot-pounds": FT_LB,
    "n-m": N_M,
    "nm": N_M,
    "n m": N_M,
    "n·m": N_M,
    "newton-metres": N_M,
    "newton-meters": N_M,
}
# A figure above these is almost surely a typo (the biggest front-end nut is under 200 ft-lb).
MAX_FIGURE = {FT_LB: Decimal(400), N_M: Decimal(540)}

NOT_VERIFIED = (
    "The torque for it is not verified yet, so I won't give a number. It's on your checking sheet."
)
CASTLE = "Castle nut: reach the figure, then go on to the next cotter pin slot. Never back off."


@dataclass(frozen=True)
class SheetRow:
    fastener_id: str
    torque_min: Decimal
    torque_max: Decimal | None
    unit: str
    socket: str
    thread: str
    applies_to: str
    source: str
    source_page: str
    verified_on: dt.date


def _decimal(text: str) -> Decimal | None:
    try:
        value = Decimal(text.strip())
    except (InvalidOperation, AttributeError):
        return None
    return value if value.is_finite() and value > 0 else None


def read_sheet(path: Path, known_ids) -> tuple[list[SheetRow], int, list[str]]:
    """(rows to store, rows skipped as not verified, problems). Any problem: store nothing."""
    with open(path, newline="", encoding="utf-8-sig") as fh:
        reader = csv.DictReader(fh)
        missing = [c for c in COLUMNS if c not in (reader.fieldnames or [])]
        if missing:
            return [], 0, [f"the sheet has no column {', '.join(missing)}"]
        lines = list(reader)
    rows, skipped, problems, seen = [], 0, [], set()
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
        if get["verified"].lower() not in YES:
            skipped += 1
            continue
        low = _decimal(get["verified_torque"])
        high = _decimal(get["verified_torque_max"]) if get["verified_torque_max"] else None
        unit = _UNITS.get(get["verified_unit"].lower())
        wrong = []
        if low is None:
            wrong.append("verified_torque is not a number above zero")
        if get["verified_torque_max"] and high is None:
            wrong.append("verified_torque_max is not a number above zero")
        if unit is None:
            wrong.append("verified_unit must be ft-lb or N-m")
        for column in ("applies_to", "source", "source_page"):
            if not get[column]:
                wrong.append(f"{column} is empty")
        if "forum" in get["source"].lower():
            wrong.append("a forum is not a source for a verified figure")
        try:
            verified_on = dt.date.fromisoformat(get["verified_on"])
        except ValueError:
            verified_on = None
            wrong.append("verified_on must be a date like 2026-11-22")
        if low is not None and high is not None and high < low:
            wrong.append("verified_torque_max is below verified_torque")
        if unit is not None and any(v is not None and v > MAX_FIGURE[unit] for v in (low, high)):
            wrong.append(f"a figure over {MAX_FIGURE[unit]} {unit} looks like a typo")
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
                get["applies_to"],
                get["source"],
                get["source_page"],
                verified_on,
            )
        )
    return rows, skipped, problems


# ---- what code says and shows ----------------------------------------------------------------


def _num(value) -> str:
    text = f"{Decimal(value):f}"
    return text.rstrip("0").rstrip(".") if "." in text else text


def _other(value, unit) -> str:
    v = Decimal(value)
    converted = v * Decimal(str(NM_PER_FTLB)) if unit == FT_LB else v / Decimal(str(NM_PER_FTLB))
    return str(int(converted.quantize(Decimal(1))))


def spoken_numbers(spec) -> set[str]:
    """Every number `figure_line` says for this row (the torque guard's backing)."""
    out = {_num(spec.torque_min)}
    if spec.torque_max is not None:
        out.add(_num(spec.torque_max))
    for size in (spec.socket, spec.thread):
        out |= {_num(n) for n in re.findall(r"\d+(?:\.\d+)?", size or "")}
    return out


def _range(spec) -> str:
    if spec.torque_max is None:
        return _num(spec.torque_min)
    return f"{_num(spec.torque_min)} to {_num(spec.torque_max)}"


UNIT_WORDS = {FT_LB: "foot-pounds", N_M: "newton-metres"}


def figure_line(spec) -> str:
    """The checked figure as code says it: the number, what it applies to, where it came from."""
    return (
        f"{_range(spec)} {UNIT_WORDS[spec.unit]}, for {spec.applies_to}. From {spec.source}, "
        f"{spec.source_page}, checked {spec.verified_on:%B} {spec.verified_on.day}."
    )


def size_line(spec) -> str:
    said = [
        f"socket {spec.socket}" if spec.socket else "",
        f"thread {spec.thread}" if spec.thread else "",
    ]
    said = [s for s in said if s]
    return ("Checked size: " + ", ".join(said) + ".") if said else ""


def figure_text(spec) -> str:
    """The card's figure in both units: '12 ft-lb (16 N·m)' or '12 to 14 ft-lb (16 to 19 N·m)'."""
    other = N_M if spec.unit == FT_LB else FT_LB
    low = _other(spec.torque_min, spec.unit)
    alt = low if spec.torque_max is None else f"{low} to {_other(spec.torque_max, spec.unit)}"
    label = {FT_LB: "ft-lb", N_M: "N·m"}
    return f"{_range(spec)} {label[spec.unit]} ({alt} {label[other]})"
