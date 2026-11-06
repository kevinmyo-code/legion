"""What to eat from what's left today (body coach 2, 2026-11-06). Pure: no ORM; the view gathers
rows and calls `suggest`.

Kevin: *"or ask it what to eat based on what i have left calorie and macro wise etc?"*, *"that
aligns with my weight goals"* (the fat-loss goal: get lean, keep muscle). The rules
(`.scratch/body-coach-2/issues/01-decisions.md`, 12 to 16, open to Kevin):

- **Left** = today's target minus today's logged meals. No target: nothing is said about what is
  left and options rank by protein for their calories, said in words.
- **Options** are given, never made up: his saved meals (his own estimates), groceries bought in
  the last 14 days **as bought** (the receipt reader's guess for the whole item, never divided
  into a portion nobody stated), and pairs of two such groceries whose sum fits.
- **Fit first.** Only options within what is left. A meal share by the hour (morning a third of
  what is left, midday and afternoon a half, evening and late all of it) puts options inside the
  share first. Within that: protein per kcal while protein is short, else the closest fit.
- **Never a push under 800 kcal or toward skipping food.** Late (21:00 on) with under 300 kcal
  left: said plainly, small options. Already over: one neutral line with the figures, then the
  options that add the fewest kcal, "if you're hungry". Under 800 logged in the evening: said, and
  a proper meal suggested.
- **Targets are kcal and protein only** (Kevin, 2026-11-06: *"im not too particular or strict
  about macros"*); carbs and fat eaten are shown when a meal states them.
- **An off day is one neutral line** (Kevin): over by so much today, and what the last 7 logged
  days average against the target. Never guilt, never a word about the lapse itself.

Every figure is an ESTIMATE (CLAUDE.md section 4 rule 5) and says so. Nothing about a real person
is in this file or its tests.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import time

from body_coach.energy import FLOOR_KCAL

MAX_OPTIONS = 5
MAX_OVER_OPTIONS = 3
MAX_COMBOS = 3
LATE_FROM = time(21, 0)
LATE_LITTLE_KCAL = 300
EVENING_FROM = time(17, 0)
COMBO_MIN_PROTEIN_PER_100 = 5.0

PARTS = (
    (time(11, 0), "morning", 1 / 3),
    (time(14, 0), "midday", 1 / 2),
    (time(17, 0), "afternoon", 1 / 2),
    (LATE_FROM, "evening", 1.0),
    (time(23, 59, 59, 999999), "late", 1.0),
)

SOURCE_SAVED = "saved_meal"
SOURCE_GROCERY = "grocery"
SOURCE_COMBO = "combo"
BASIS_SAVED = "your saved estimate"
BASIS_AS_BOUGHT = "as bought"

STATE_OK = "ok"
STATE_NO_TARGET = "no_target"
STATE_OVER = "over"
STATE_LATE_LITTLE = "late_little_left"
STATE_UNDER_FLOOR = "under_floor"


@dataclass(frozen=True)
class Targets:
    kcal: int | None
    kcal_basis: str | None  # "meal target" or "suggested by the energy estimate"
    protein_g: float | None


@dataclass(frozen=True)
class Eaten:
    """Today's logged meals. Carbs and fat are None when no meal today states them (Kevin's
    ruling: kcal and protein are the targets; carbs and fat are shown when known)."""

    kcal: int = 0
    protein_g: float = 0.0
    carbs_g: float | None = None
    fat_g: float | None = None
    meals: int = 0


@dataclass(frozen=True)
class Week:
    """The last 7 household-local days, today included, that have a meal with kcal logged."""

    days: int = 0
    mean_kcal: float | None = None


@dataclass(frozen=True)
class Candidate:
    id: str
    source: str
    name: str
    kcal: int
    protein_g: float
    carbs_g: float | None = None
    fat_g: float | None = None
    basis: str = BASIS_SAVED
    detail: str = ""
    parts: tuple[str, ...] = field(default=())

    @property
    def density(self) -> float:
        """Grams of protein per 100 kcal."""
        return self.protein_g * 100 / self.kcal if self.kcal > 0 else 0.0


def part_of_day(clock: time) -> tuple[str, float]:
    for until, name, share in PARTS:
        if clock < until:
            return name, share
    return "late", 1.0


def combos(groceries: list[Candidate], kcal_left: int | None) -> list[Candidate]:
    """Pairs of two groceries, at least one protein-dense, whose summed as-bought estimate fits
    what is left; the densest few. Nothing is divided."""
    if kcal_left is None or kcal_left <= 0:
        return []
    out = []
    for i, a in enumerate(groceries):
        for b in groceries[i + 1 :]:
            if max(a.density, b.density) < COMBO_MIN_PROTEIN_PER_100:
                continue
            kcal = a.kcal + b.kcal
            if kcal > kcal_left:
                continue
            first, second = sorted((a, b), key=lambda c: c.id)
            out.append(
                Candidate(
                    id=f"{SOURCE_COMBO}:{first.id.split(':', 1)[1]}+{second.id.split(':', 1)[1]}",
                    source=SOURCE_COMBO,
                    name=f"{first.name} + {second.name}",
                    kcal=kcal,
                    protein_g=a.protein_g + b.protein_g,
                    carbs_g=_add(a.carbs_g, b.carbs_g),
                    fat_g=_add(a.fat_g, b.fat_g),
                    basis=BASIS_AS_BOUGHT,
                    detail="two groceries bought recently, each as bought",
                    parts=(first.id, second.id),
                )
            )
    out.sort(key=lambda c: (-c.density, -c.protein_g, c.id))
    return out[:MAX_COMBOS]


def _add(a, b):
    return None if a is None or b is None else a + b


def _r1(x):
    return None if x is None else round(x, 1)


def _left(target, eaten):
    return None if target is None else target - eaten


def option_dict(c: Candidate, kcal_left: int | None, share_kcal: float | None, rank: int) -> dict:
    why = [f"{c.density:.1f} g protein per 100 kcal"]
    if kcal_left is not None:
        rest = kcal_left - c.kcal
        why.append(f"leaves about {rest} kcal" if rest >= 0 else f"{-rest} kcal over")
    if c.basis == BASIS_AS_BOUGHT:
        why.append("figures for the whole item as bought")
    return {
        "rank": rank,
        "id": c.id,
        "source": c.source,
        "name": c.name,
        "kcal": c.kcal,
        "protein_g": _r1(c.protein_g),
        "carbs_g": _r1(c.carbs_g),
        "fat_g": _r1(c.fat_g),
        "basis": c.basis,
        "detail": c.detail,
        "estimate": True,
        "fits": kcal_left is None or c.kcal <= kcal_left,
        "fits_this_meal": share_kcal is None or c.kcal <= share_kcal,
        "protein_per_100_kcal": round(c.density, 1),
        "why": "; ".join(why),
    }


def suggest(
    *,
    clock: time,
    targets: Targets,
    eaten: Eaten,
    saved: list[Candidate],
    groceries: list[Candidate],
    week: Week | None = None,
) -> dict:
    part, share = part_of_day(clock)
    kcal_left = _left(targets.kcal, eaten.kcal)
    protein_left = (
        None
        if targets.protein_g is None
        else round(max(0.0, targets.protein_g - eaten.protein_g), 1)
    )
    protein_short = protein_left is None or protein_left > 0
    pool = [c for c in [*saved, *groceries] if c.kcal > 0]

    if targets.kcal is None:
        state = STATE_NO_TARGET
    elif kcal_left <= 0:
        state = STATE_OVER
    elif clock >= EVENING_FROM and eaten.kcal < FLOOR_KCAL:
        state = STATE_UNDER_FLOOR
    elif clock >= LATE_FROM and kcal_left < LATE_LITTLE_KCAL:
        state = STATE_LATE_LITTLE
    else:
        state = STATE_OK

    share_kcal = None
    too_big: list[str] = []
    if state == STATE_NO_TARGET:
        ranked = sorted(pool, key=lambda c: (-c.density, -c.protein_g, c.id))
    elif state == STATE_OVER:
        ranked = sorted(pool, key=lambda c: (c.kcal, -c.protein_g, c.id))[:MAX_OVER_OPTIONS]
    else:
        share_kcal = kcal_left * share
        pool = pool + combos(groceries, kcal_left)
        fitting = [c for c in pool if c.kcal <= kcal_left]
        too_big = sorted({c.name for c in groceries if c.kcal > kcal_left})

        def key(c: Candidate):
            inside = c.kcal <= share_kcal
            if state == STATE_UNDER_FLOOR:
                # A proper meal first: the biggest that fits, then the most protein.
                return (-c.kcal, -c.protein_g, c.id)
            if protein_short:
                return (not inside, -c.density, -c.protein_g, c.id)
            return (not inside, abs(share_kcal - c.kcal), c.id)

        ranked = sorted(fitting, key=key)
    ranked = ranked[:MAX_OPTIONS]
    options = [option_dict(c, kcal_left, share_kcal, i + 1) for i, c in enumerate(ranked)]

    body = {
        "estimate": True,
        "clock": clock.strftime("%H:%M"),
        "part_of_day": part,
        "state": state,
        "target": {
            "kcal": targets.kcal,
            "kcal_basis": targets.kcal_basis,
            "protein_g": _r1(targets.protein_g),
        },
        "eaten": {
            "kcal": eaten.kcal,
            "protein_g": _r1(eaten.protein_g),
            "carbs_g": _r1(eaten.carbs_g),
            "fat_g": _r1(eaten.fat_g),
            "meals": eaten.meals,
        },
        "kcal_left": kcal_left,
        "protein_left_g": protein_left,
        "week": {
            "days": week.days if week else 0,
            "mean_kcal": round(week.mean_kcal) if week and week.mean_kcal is not None else None,
        },
        "this_meal_kcal": round(share_kcal) if share_kcal is not None else None,
        "options": options,
        "too_big_as_bought": too_big,
    }
    body["words"] = words(body)
    return body


# =============================================================================
# Words, for a voice to read out
# =============================================================================


def _g(x) -> str:
    return f"{x:g}"


def _option_words(o: dict) -> str:
    where = {
        SOURCE_SAVED: "saved meal",
        SOURCE_GROCERY: "bought recently, as bought",
        SOURCE_COMBO: "two groceries, as bought",
    }[o["source"]]
    return f"{o['rank']}. {o['name']} ({where}): {o['kcal']} kcal, {_g(o['protein_g'])} g protein."


def words(b: dict) -> str:
    t, e = b["target"], b["eaten"]
    state = b["state"]
    lead = f"It's {b['clock']}, {b['part_of_day']}."
    parts = [lead]
    if state == STATE_NO_TARGET:
        parts.append(
            f"There is no calorie target, so nothing can be said about what's left; logged today "
            f"is {e['kcal']} kcal. The options are ranked by protein for their calories."
        )
    elif state == STATE_OVER:
        protein = (
            f" Protein is at {_g(e['protein_g'])} of {_g(t['protein_g'])} g."
            if t["protein_g"] is not None
            else ""
        )
        parts.append(f"Over by {-b['kcal_left']} kcal today: {e['kcal']} of {t['kcal']}.{protein}")
        w = b["week"]
        if w["mean_kcal"] is not None and w["days"] >= 2:
            how = "on target" if w["mean_kcal"] <= t["kcal"] else "over the target"
            parts.append(
                f"The last {w['days']} days logged average {w['mean_kcal']} kcal a day, {how}."
            )
        if b["options"]:
            parts.append("If you're hungry, these add the fewest calories:")
    else:
        protein = ""
        if b["protein_left_g"] is not None:
            protein = (
                f" and {_g(b['protein_left_g'])} g of protein"
                if b["protein_left_g"] > 0
                else "; protein is met for today"
            )
        parts.append(
            f"About {b['kcal_left']} kcal{protein} left of the {t['kcal']} kcal {t['kcal_basis']}."
        )
        macros = []
        if e["carbs_g"] is not None:
            macros.append(f"{_g(e['carbs_g'])} g carbs")
        if e["fat_g"] is not None:
            macros.append(f"{_g(e['fat_g'])} g fat")
        if macros:
            parts.append(f"Eaten so far: {' and '.join(macros)}.")
        if state == STATE_UNDER_FLOOR:
            parts.append(
                f"Only {e['kcal']} kcal is logged today, under {FLOOR_KCAL}. If that's right, "
                f"have a proper meal; these fit what's left:"
            )
        elif state == STATE_LATE_LITTLE:
            parts.append("It's late and little is left, so the options are small:")
        elif b["options"]:
            parts.append("Options that fit, best first:")
    parts.extend(_option_words(o) for o in b["options"])
    if not b["options"]:
        parts.append("Nothing saved or bought in the last 14 days fits what's left.")
    if b["too_big_as_bought"]:
        names = b["too_big_as_bought"][:4]
        parts.append(f"Also bought recently but too big as bought: {', '.join(names)}.")
    parts.append(
        "Every figure is an estimate. Grocery figures are for the whole item as bought; nothing "
        "tracks what has been eaten from them."
    )
    return " ".join(parts)
