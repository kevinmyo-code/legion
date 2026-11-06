"""The workout programme: templates and the progression rules, pure and deterministic.

Kevin, 2026-11-06: *"i wanna have it make workouts for me that i can tick off"*. His rulings on the
fat-loss map: gym access, **3 full-body strength days a week**, goal *"get lean, keep muscle"*.
This module owns every number a workout shows. **The model never writes one**: voice and the web
assistant may explain a target or swap an exercise for one of its listed alternatives, and the
target the swapped-in exercise gets comes from these same rules.

## The template

Two full-body days, A and B, alternating (A, B, A, then B, A, B the next week). Each day is five
slots: three compound lifts and two accessories, each with a set count and a rep range. A slot's
exercise can be swapped for one of its alternatives; the swap is kept on the programme.

## The rules, in the order they apply to one exercise

1. **Calibration.** An exercise with no finished set at a recorded weight has no target weight:
   *pick a weight you could lift for 2 more reps than the top of the range.* That covers the first
   week (every exercise is new) and any swap.
2. **Double progression.** The working weight is the weight used on most of the last session's
   done sets (ties go to the lighter). If every planned set was done, at the working weight or
   more, with reps at the top of the range, the next target is the working weight plus the
   exercise's increment. Otherwise the weight holds.
3. **Stall reset.** Two sessions in a row at the same working weight, each with a done set under
   the bottom of the range, drop the next target to 90%, rounded down to 5 lb.
4. **Deload.** Every sixth block of sessions (a block is one week's worth, `days_per_week`
   sessions) is a deload: one set fewer (never under one), reps at the bottom of the range, 90% of
   the weight the rules would otherwise hold, rounded down to 5 lb. **Counted in finished
   sessions, not calendar weeks**, so a missed week never brings a deload early, and the schedule
   is reproducible from the session rows alone. A deload session never moves a working weight: the
   rules read past it.
5. Bodyweight and timed exercises carry no weight: the target is the range, and the words say to
   add reps (or seconds) until every set reaches the top.

Weights are pounds. Nothing about a real person is in this file.
"""

from __future__ import annotations

import math
import re
from collections import Counter
from dataclasses import dataclass, field
from datetime import date, timedelta

KIND_WEIGHTED = "weighted"
KIND_BODYWEIGHT = "bodyweight"
KIND_TIMED = "timed"

RULE_CALIBRATE = "calibrate"
RULE_ADD = "add"
RULE_HOLD = "hold"
RULE_RESET = "reset"
RULE_DELOAD = "deload"
RULE_REPS = "reps"
RULE_EXTRA = "extra"
RULES: tuple[str, ...] = (
    RULE_CALIBRATE,
    RULE_ADD,
    RULE_HOLD,
    RULE_RESET,
    RULE_DELOAD,
    RULE_REPS,
    RULE_EXTRA,
)

DEFAULT_TEMPLATE = "full_body_ab"
DEFAULT_TRAINING_DAYS: tuple[str, ...] = ("mon", "wed", "fri")
WEEKDAYS: tuple[str, ...] = ("mon", "tue", "wed", "thu", "fri", "sat", "sun")
DELOAD_EVERY_BLOCKS = 6
RESET_FACTOR = 0.9
ROUND_TO_LBS = 5.0
CALIBRATION_RIR = 2


@dataclass(frozen=True)
class Exercise:
    key: str
    name: str
    kind: str = KIND_WEIGHTED
    increment_lbs: float = 5.0
    aliases: tuple[str, ...] = ()


@dataclass(frozen=True)
class Slot:
    key: str
    exercise: str
    sets: int
    reps_low: int
    reps_high: int
    alternatives: tuple[str, ...] = ()

    def choices(self) -> tuple[str, ...]:
        return (self.exercise, *self.alternatives)


_E = Exercise
CATALOG: dict[str, Exercise] = {
    e.key: e
    for e in (
        _E("back_squat", "Back squat", increment_lbs=10, aliases=("squat", "back squat")),
        _E("goblet_squat", "Goblet squat", aliases=("goblet squat",)),
        _E("leg_press", "Leg press", increment_lbs=10, aliases=("leg press",)),
        _E("bench_press", "Bench press", aliases=("bench", "bench press")),
        _E("dumbbell_bench_press", "Dumbbell bench press", aliases=("dumbbell bench", "db bench")),
        _E("push_up", "Push-up", kind=KIND_BODYWEIGHT, increment_lbs=0, aliases=("push up", "pushup")),
        _E("barbell_row", "Barbell row", aliases=("row", "barbell row", "bent over row")),
        _E("dumbbell_row", "Dumbbell row", aliases=("dumbbell row", "db row", "one arm row")),
        _E("seated_cable_row", "Seated cable row", aliases=("cable row", "seated row")),
        _E("lateral_raise", "Lateral raise", aliases=("lateral raise", "side raise", "laterals")),
        _E("cable_lateral_raise", "Cable lateral raise", aliases=("cable lateral raise",)),
        _E("plank", "Plank", kind=KIND_TIMED, increment_lbs=0, aliases=("plank",)),
        _E("side_plank", "Side plank", kind=KIND_TIMED, increment_lbs=0, aliases=("side plank",)),
        _E(
            "romanian_deadlift",
            "Romanian deadlift",
            increment_lbs=10,
            aliases=("rdl", "romanian deadlift", "deadlift"),
        ),
        _E("dumbbell_rdl", "Dumbbell Romanian deadlift", aliases=("dumbbell rdl", "db rdl")),
        _E("hip_thrust", "Hip thrust", increment_lbs=10, aliases=("hip thrust",)),
        _E("overhead_press", "Overhead press", aliases=("ohp", "overhead press", "shoulder press", "press")),
        _E(
            "dumbbell_shoulder_press",
            "Dumbbell shoulder press",
            aliases=("dumbbell shoulder press", "db shoulder press", "dumbbell press"),
        ),
        _E("lat_pulldown", "Lat pulldown", aliases=("pulldown", "lat pulldown", "lat pull down")),
        _E("chin_up", "Chin-up", kind=KIND_BODYWEIGHT, increment_lbs=0, aliases=("chin up", "chinup", "pull up", "pullup")),
        _E("dumbbell_lunge", "Dumbbell lunge", aliases=("lunge", "lunges", "dumbbell lunge")),
        _E("split_squat", "Split squat", aliases=("split squat", "bulgarian split squat")),
        _E(
            "hanging_knee_raise",
            "Hanging knee raise",
            kind=KIND_BODYWEIGHT,
            increment_lbs=0,
            aliases=("knee raise", "hanging knee raise", "leg raise"),
        ),
        _E("cable_crunch", "Cable crunch", aliases=("cable crunch", "crunch")),
    )
}

TEMPLATES: dict[str, dict[str, tuple[Slot, ...]]] = {
    DEFAULT_TEMPLATE: {
        "A": (
            Slot("squat", "back_squat", 3, 6, 8, ("goblet_squat", "leg_press")),
            Slot("push", "bench_press", 3, 6, 8, ("dumbbell_bench_press", "push_up")),
            Slot("row", "barbell_row", 3, 8, 10, ("dumbbell_row", "seated_cable_row")),
            Slot("shoulders", "lateral_raise", 2, 12, 15, ("cable_lateral_raise",)),
            Slot("core_a", "plank", 2, 30, 45, ("side_plank",)),
        ),
        "B": (
            Slot("hinge", "romanian_deadlift", 3, 8, 10, ("dumbbell_rdl", "hip_thrust")),
            Slot("press", "overhead_press", 3, 6, 8, ("dumbbell_shoulder_press",)),
            Slot("pull", "lat_pulldown", 3, 8, 10, ("chin_up", "seated_cable_row")),
            Slot("legs", "dumbbell_lunge", 2, 10, 12, ("split_squat",)),
            Slot("core_b", "hanging_knee_raise", 2, 10, 15, ("cable_crunch",)),
        ),
    }
}
DAY_KEYS: tuple[str, ...] = ("A", "B")


# =============================================================================
# Inputs: what happened before, as plain values
# =============================================================================


@dataclass(frozen=True)
class DoneSet:
    reps: int | None
    weight_lbs: float | None
    done: bool


@dataclass(frozen=True)
class Performance:
    """One finished session's sets of one exercise, newest first in a history list."""

    sets: tuple[DoneSet, ...]
    planned_sets: int
    reps_low: int
    reps_high: int
    deload: bool = False

    def done_sets(self) -> list[DoneSet]:
        return [s for s in self.sets if s.done]


@dataclass(frozen=True)
class Target:
    exercise_key: str
    name: str
    kind: str
    slot_key: str
    sets: int
    reps_low: int
    reps_high: int
    weight_lbs: float | None
    rule: str
    words: str
    alternatives: tuple[str, ...] = field(default=())

    @property
    def calibrating(self) -> bool:
        return self.rule == RULE_CALIBRATE

    @property
    def unit(self) -> str:
        return "seconds" if self.kind == KIND_TIMED else "reps"


# =============================================================================
# The rules
# =============================================================================


def round_down(lbs: float) -> float:
    return max(ROUND_TO_LBS, math.floor(lbs / ROUND_TO_LBS + 1e-9) * ROUND_TO_LBS)


def working_weight(perf: Performance) -> float | None:
    """The weight on most of the done sets; a tie goes to the lighter. None with no weight."""
    weights = [s.weight_lbs for s in perf.done_sets() if s.weight_lbs is not None]
    if not weights:
        return None
    counts = Counter(weights)
    top = max(counts.values())
    return min(w for w, c in counts.items() if c == top)


def progressed(perf: Performance, weight: float) -> bool:
    done = perf.done_sets()
    if len(done) < perf.planned_sets or not done:
        return False
    return all(
        s.reps is not None
        and s.reps >= perf.reps_high
        and s.weight_lbs is not None
        and s.weight_lbs >= weight
        for s in done
    )


def under_range(perf: Performance) -> bool:
    return any(s.reps is not None and s.reps < perf.reps_low for s in perf.done_sets())


def is_deload(session_number: int, days_per_week: int) -> bool:
    """Session `n` (1-based, counting finished sessions) is a deload in every sixth block."""
    block = (session_number - 1) // max(1, days_per_week)
    return block % DELOAD_EVERY_BLOCKS == DELOAD_EVERY_BLOCKS - 1


def _fmt(lbs: float) -> str:
    return f"{lbs:g}"


def _reps_said(perf: Performance) -> str:
    reps = [str(s.reps) for s in perf.done_sets() if s.reps is not None]
    return ", ".join(reps) if reps else "no sets"


def target_for(
    slot: Slot,
    exercise_key: str,
    history: list[Performance],
    *,
    deload: bool = False,
) -> Target:
    """The next target for one exercise in one slot, from its finished history (newest first)."""
    ex = CATALOG[exercise_key]
    sets, low, high = slot.sets, slot.reps_low, slot.reps_high
    alternatives = tuple(CATALOG[k].name for k in slot.choices() if k != exercise_key)

    def make(weight, rule, words, n_sets=sets, r_low=low, r_high=high) -> Target:
        return Target(
            exercise_key=exercise_key,
            name=ex.name,
            kind=ex.kind,
            slot_key=slot.key,
            sets=n_sets,
            reps_low=r_low,
            reps_high=r_high,
            weight_lbs=weight,
            rule=rule,
            words=words,
            alternatives=alternatives,
        )

    unit = "seconds" if ex.kind == KIND_TIMED else "reps"
    if ex.kind != KIND_WEIGHTED:
        if deload:
            return make(
                None,
                RULE_DELOAD,
                f"Deload session: one set fewer, {low} {unit}. Progress resumes next session.",
                n_sets=max(1, sets - 1),
                r_high=low,
            )
        return make(None, RULE_REPS, f"Add {unit} until every set reaches {high}.")

    real = [p for p in history if not p.deload and working_weight(p) is not None]
    if not real:
        return make(
            None,
            RULE_CALIBRATE,
            f"Calibration: pick a weight you could lift for {CALIBRATION_RIR} more reps than "
            f"{high}, and log what you used.",
        )
    last = real[0]
    weight = working_weight(last)
    if progressed(last, weight):
        nxt, rule = weight + ex.increment_lbs, RULE_ADD
        words = f"+{_fmt(ex.increment_lbs)} lb: every set reached {high} last time."
    elif (
        len(real) >= 2
        and working_weight(real[1]) == weight
        and under_range(last)
        and under_range(real[1])
    ):
        nxt, rule = round_down(weight * RESET_FACTOR), RULE_RESET
        words = (
            f"Down to {_fmt(nxt)} lb: two sessions in a row at {_fmt(weight)} lb had a set under "
            f"{low}. Build back up from here."
        )
    else:
        nxt, rule = weight, RULE_HOLD
        words = (
            f"Same weight: {_reps_said(last)} last time. Add weight when every set reaches {high}."
        )
    if deload:
        light = round_down(weight * RESET_FACTOR)
        return make(
            light,
            RULE_DELOAD,
            f"Deload session: {_fmt(light)} lb, one set fewer, {low} reps. Progress resumes next "
            f"session.",
            n_sets=max(1, sets - 1),
            r_high=low,
        )
    return make(nxt, rule, words)


# =============================================================================
# The programme around the rules
# =============================================================================


def slots_for(template: str, day_key: str, swaps: dict[str, str] | None = None) -> list[tuple[Slot, str]]:
    """The day's slots with the exercise each one holds after the programme's swaps."""
    swaps = swaps or {}
    out = []
    for slot in TEMPLATES[template][day_key]:
        chosen = swaps.get(slot.key)
        out.append((slot, chosen if chosen in slot.choices() else slot.exercise))
    return out


def next_day_key(last_day_key: str | None) -> str:
    return "B" if last_day_key == "A" else "A"


WEEKDAY_NAMES: tuple[str, ...] = (
    "Monday",
    "Tuesday",
    "Wednesday",
    "Thursday",
    "Friday",
    "Saturday",
    "Sunday",
)


@dataclass(frozen=True)
class Upcoming:
    """One coming session on the calendar: which day of the programme, and when."""

    day_key: str
    on: date
    session_number: int
    deload: bool
    is_today: bool

    @property
    def weekday(self) -> str:
        return WEEKDAYS[self.on.weekday()]

    @property
    def weekday_name(self) -> str:
        return WEEKDAY_NAMES[self.on.weekday()]


def upcoming_sessions(
    today: date,
    training_days: list[str] | tuple[str, ...],
    first_day_key: str,
    *,
    today_open: bool,
    first_number: int,
    count: int,
) -> list[Upcoming]:
    """The next `count` sessions on the calendar, from today's date.

    Today is the first candidate only when `today_open` (today is a training day with nothing
    finished yet, or a session is running). After that, each training weekday takes the next
    session, the day keys alternating from `first_day_key` (A, B, A...) the same way
    `next_day_key` alternates finished sessions, and the session number counting up from
    `first_number` so the deload flag is the one `is_deload` will give that session.
    """
    days = [d for d in training_days if d in WEEKDAYS] or list(DEFAULT_TRAINING_DAYS)
    per_week = len(days)
    out: list[Upcoming] = []
    key = first_day_key
    number = first_number
    day = today if today_open else today + timedelta(days=1)
    while len(out) < count:
        is_today = day == today
        if is_today and today_open or not is_today and WEEKDAYS[day.weekday()] in days:
            out.append(Upcoming(key, day, number, is_deload(number, per_week), is_today))
            key = next_day_key(key)
            number += 1
        day += timedelta(days=1)
    return out


def normalise(text: str) -> str:
    text = re.sub(r"[^a-z0-9 ]+", " ", text.lower())
    words = [w[:-1] if len(w) > 3 and w.endswith("s") and not w.endswith("ss") else w for w in text.split()]
    return " ".join(words)


def match_exercise(query: str, keys: list[str]) -> str | None:
    """The one exercise among `keys` that `query` names, or None when none or several do."""
    q = normalise(query)
    if not q:
        return None
    exact, partial = [], []
    for key in keys:
        ex = CATALOG[key]
        names = {normalise(ex.name), normalise(key.replace("_", " ")), *(normalise(a) for a in ex.aliases)}
        if q in names:
            exact.append(key)
        elif any(q in n or n in q for n in names):
            partial.append(key)
    if len(exact) == 1:
        return exact[0]
    if not exact and len(partial) == 1:
        return partial[0]
    return None


def swap_choice(slot: Slot, current: str, to: str | None) -> str | None:
    """What a swap lands on: the named alternative, or the next one after the current exercise."""
    choices = list(slot.choices())
    if to:
        picked = match_exercise(to, choices)
        return picked if picked and picked != current else None
    if len(choices) < 2:
        return None
    i = choices.index(current) if current in choices else 0
    return choices[(i + 1) % len(choices)]
