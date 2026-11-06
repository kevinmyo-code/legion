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

## Body coach 2 (2026-11-06)

Two templates (full body A/B; upper / lower in four days), each run on 2 to 4 days a week; every
exercise carries the equipment it needs and the joints it loads most, so a programme's filters
pick a slot's exercise (`resolve_day`); an optional cardio finisher in minutes. Changing any of it
is a proposal Kevin accepts (`programme_change.py`).

Weights are pounds. Nothing about a real person is in this file.
"""

from __future__ import annotations

import math
import re
from collections import Counter
from dataclasses import dataclass, field

KIND_WEIGHTED = "weighted"
KIND_BODYWEIGHT = "bodyweight"
KIND_TIMED = "timed"
# Body coach 2 (2026-11-06): the walking finisher; its range is in minutes.
KIND_CARDIO = "cardio"

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
UPPER_LOWER = "upper_lower"
DEFAULT_TRAINING_DAYS: tuple[str, ...] = ("mon", "wed", "fri")
WEEKDAYS: tuple[str, ...] = ("mon", "tue", "wed", "thu", "fri", "sat", "sun")
DELOAD_EVERY_BLOCKS = 6
RESET_FACTOR = 0.9
ROUND_TO_LBS = 5.0
CALIBRATION_RIR = 2

# Body coach 2 (`.scratch/body-coach-2/`, Kevin's rulings 2026-11-06): the equipment an exercise
# can be done with (any one of them; cables count as machine) and the joints it loads most, so
# "no barbell", "I'm at home today" and "my knee hurts" filter this catalog instead of asking the
# model for an exercise.
BARBELL, DUMBBELL, MACHINE, KETTLEBELL, BODYWEIGHT = (
    "barbell",
    "dumbbell",
    "machine",
    "kettlebell",
    "bodyweight",
)
EQUIPMENT: tuple[str, ...] = (BARBELL, DUMBBELL, MACHINE, KETTLEBELL, BODYWEIGHT)
# Kevin: a commercial gym, or home with calisthenics and a kettlebell sometimes.
LOCATION_GYM, LOCATION_HOME = "gym", "home"
LOCATIONS: tuple[str, ...] = (LOCATION_GYM, LOCATION_HOME)
HOME_EQUIPMENT: frozenset[str] = frozenset({KETTLEBELL, BODYWEIGHT})
SHOULDER, KNEE, LOWER_BACK = "shoulder", "knee", "lower_back"
JOINTS: tuple[str, ...] = (SHOULDER, KNEE, LOWER_BACK)
JOINT_WORDS = {SHOULDER: "shoulder", KNEE: "knee", LOWER_BACK: "lower back"}
EQUIPMENT_WORDS = {
    BARBELL: "a barbell",
    DUMBBELL: "dumbbells",
    MACHINE: "a machine or cable",
    KETTLEBELL: "a kettlebell",
    BODYWEIGHT: "bodyweight",
}


@dataclass(frozen=True)
class Exercise:
    key: str
    name: str
    kind: str = KIND_WEIGHTED
    increment_lbs: float = 5.0
    aliases: tuple[str, ...] = ()
    equipment: tuple[str, ...] = (DUMBBELL,)
    joints: tuple[str, ...] = ()


@dataclass(frozen=True)
class Slot:
    key: str
    exercise: str
    sets: int
    reps_low: int
    reps_high: int
    alternatives: tuple[str, ...] = ()
    # (exercise key, low, high) for a choice whose range differs from the slot's (a timed hold
    # among rep exercises).
    ranges: tuple[tuple[str, int, int], ...] = ()

    def choices(self) -> tuple[str, ...]:
        return (self.exercise, *self.alternatives)

    def range_for(self, exercise_key: str) -> tuple[int, int]:
        for key, low, high in self.ranges:
            if key == exercise_key:
                return low, high
        return self.reps_low, self.reps_high


_E = Exercise
# Bodyweight, and the home kettlebell: one bell, so these progress by reps, not by weight.
_BW = {"kind": KIND_BODYWEIGHT, "increment_lbs": 0, "equipment": (BODYWEIGHT,)}
_KB = {"kind": KIND_BODYWEIGHT, "increment_lbs": 0, "equipment": (KETTLEBELL,)}
_HOLD = {"kind": KIND_TIMED, "increment_lbs": 0, "equipment": (BODYWEIGHT,)}
_WALK = {"kind": KIND_CARDIO, "increment_lbs": 0}
CATALOG: dict[str, Exercise] = {
    e.key: e
    for e in (
        # Knee-dominant. Kevin's ruling: the default lower body is knee-friendly (split squats,
        # wall sits, hip hinges); a heavy deep squat only when he asks for it.
        _E("back_squat", "Back squat", increment_lbs=10, aliases=("squat", "back squat"),
           equipment=(BARBELL,), joints=(KNEE, LOWER_BACK)),
        _E("goblet_squat", "Goblet squat", aliases=("goblet squat",),
           equipment=(DUMBBELL, KETTLEBELL), joints=(KNEE,)),
        _E("leg_press", "Leg press", increment_lbs=10, aliases=("leg press",),
           equipment=(MACHINE,), joints=(KNEE,)),
        _E("split_squat", "Bulgarian split squat",
           aliases=("split squat", "bulgarian split squat", "bulgarian"),
           equipment=(DUMBBELL, KETTLEBELL, BODYWEIGHT)),
        _E("dumbbell_lunge", "Dumbbell lunge", aliases=("lunge", "lunges", "dumbbell lunge"),
           joints=(KNEE,)),
        _E("leg_extension", "Leg extension", aliases=("leg extension",), equipment=(MACHINE,),
           joints=(KNEE,)),
        _E("wall_sit", "Wall sit", aliases=("wall sit", "wall sits"), **_HOLD),
        # Hinge.
        _E("romanian_deadlift", "Romanian deadlift", increment_lbs=10,
           aliases=("rdl", "romanian deadlift", "deadlift"), equipment=(BARBELL,),
           joints=(LOWER_BACK,)),
        _E("dumbbell_rdl", "Dumbbell Romanian deadlift", aliases=("dumbbell rdl", "db rdl"),
           joints=(LOWER_BACK,)),
        _E("single_leg_rdl", "Single-leg Romanian deadlift",
           aliases=("single leg rdl", "single leg deadlift"),
           equipment=(DUMBBELL, KETTLEBELL, BODYWEIGHT)),
        _E("kettlebell_swing", "Kettlebell swing", aliases=("swing", "kettlebell swing", "kb swing"),
           joints=(LOWER_BACK,), **_KB),
        _E("hip_thrust", "Hip thrust", increment_lbs=10, aliases=("hip thrust",),
           equipment=(BARBELL,)),
        _E("glute_bridge", "Glute bridge", aliases=("glute bridge", "bridge"), **_BW),
        _E("leg_curl", "Leg curl", aliases=("leg curl", "hamstring curl"), equipment=(MACHINE,)),
        # Horizontal push.
        _E("bench_press", "Bench press", aliases=("bench", "bench press"), equipment=(BARBELL,),
           joints=(SHOULDER,)),
        _E("dumbbell_bench_press", "Dumbbell bench press", aliases=("dumbbell bench", "db bench")),
        _E("machine_chest_press", "Machine chest press", aliases=("chest press", "machine press"),
           equipment=(MACHINE,)),
        _E("incline_dumbbell_press", "Incline dumbbell press",
           aliases=("incline press", "incline dumbbell press", "incline db press")),
        _E("push_up", "Push-up", aliases=("push up", "pushup"), **_BW),
        _E("kettlebell_floor_press", "Kettlebell floor press",
           aliases=("floor press", "kettlebell floor press"), **_KB),
        # Vertical push.
        _E("overhead_press", "Overhead press",
           aliases=("ohp", "overhead press", "shoulder press", "press"), equipment=(BARBELL,),
           joints=(SHOULDER, LOWER_BACK)),
        _E("dumbbell_shoulder_press", "Dumbbell shoulder press",
           aliases=("dumbbell shoulder press", "db shoulder press", "dumbbell press"),
           joints=(SHOULDER,)),
        _E("machine_shoulder_press", "Machine shoulder press", aliases=("machine shoulder press",),
           equipment=(MACHINE,), joints=(SHOULDER,)),
        _E("pike_push_up", "Pike push-up", aliases=("pike push up", "pike pushup"),
           joints=(SHOULDER,), **_BW),
        _E("kettlebell_press", "Kettlebell press", aliases=("kettlebell press", "kb press"),
           joints=(SHOULDER,), **_KB),
        # Pull.
        _E("barbell_row", "Barbell row", aliases=("row", "barbell row", "bent over row"),
           equipment=(BARBELL,), joints=(LOWER_BACK,)),
        _E("dumbbell_row", "Dumbbell row", aliases=("dumbbell row", "db row", "one arm row")),
        _E("seated_cable_row", "Seated cable row", aliases=("cable row", "seated row"),
           equipment=(MACHINE,)),
        _E("inverted_row", "Inverted row", aliases=("inverted row", "body row"), **_BW),
        _E("kettlebell_row", "Kettlebell row", aliases=("kettlebell row", "kb row"), **_KB),
        _E("lat_pulldown", "Lat pulldown", aliases=("pulldown", "lat pulldown", "lat pull down"),
           equipment=(MACHINE,)),
        _E("chin_up", "Chin-up", aliases=("chin up", "chinup", "pull up", "pullup"), **_BW),
        # Shoulders and arms.
        _E("lateral_raise", "Lateral raise", aliases=("lateral raise", "side raise", "laterals"),
           joints=(SHOULDER,)),
        _E("cable_lateral_raise", "Cable lateral raise", aliases=("cable lateral raise",),
           equipment=(MACHINE,), joints=(SHOULDER,)),
        _E("face_pull", "Face pull", aliases=("face pull",), equipment=(MACHINE,)),
        _E("reverse_fly", "Reverse fly", aliases=("reverse fly", "rear delt fly")),
        _E("dumbbell_curl", "Dumbbell curl", aliases=("curl", "bicep curl", "dumbbell curl")),
        _E("cable_curl", "Cable curl", aliases=("cable curl",), equipment=(MACHINE,)),
        _E("triceps_pushdown", "Triceps pushdown",
           aliases=("pushdown", "tricep pushdown", "triceps pushdown"), equipment=(MACHINE,)),
        _E("overhead_triceps_extension", "Overhead triceps extension",
           aliases=("overhead extension", "tricep extension"), joints=(SHOULDER,)),
        _E("close_grip_push_up", "Close-grip push-up", aliases=("close grip push up", "diamond push up"),
           **_BW),
        _E("bench_dip", "Bench dip", aliases=("dip", "bench dip"), joints=(SHOULDER,), **_BW),
        # Core.
        _E("plank", "Plank", aliases=("plank",), **_HOLD),
        _E("side_plank", "Side plank", aliases=("side plank",), **_HOLD),
        _E("hanging_knee_raise", "Hanging knee raise",
           aliases=("knee raise", "hanging knee raise", "leg raise"), **_BW),
        _E("cable_crunch", "Cable crunch", aliases=("cable crunch", "crunch"),
           equipment=(MACHINE,)),
        _E("dead_bug", "Dead bug", aliases=("dead bug",), **_BW),
        # Cardio. Kevin: walking.
        _E("incline_walk", "Incline walk", aliases=("incline walk", "treadmill"),
           equipment=(MACHINE,), **_WALK),
        _E("brisk_walk", "Brisk walk", aliases=("walk", "brisk walk", "walking"),
           equipment=(BODYWEIGHT,), **_WALK),
        _E("bike", "Bike", aliases=("bike", "cycling", "stationary bike"), equipment=(MACHINE,),
           **_WALK),
        _E("elliptical", "Elliptical", aliases=("elliptical",), equipment=(MACHINE,), **_WALK),
    )
}

_S = Slot
_HOLD_RANGE = (30, 60)
TEMPLATES: dict[str, dict[str, tuple[Slot, ...]]] = {
    DEFAULT_TEMPLATE: {
        "A": (
            _S("squat", "split_squat", 3, 8, 10,
               ("wall_sit", "goblet_squat", "leg_press", "back_squat"),
               ranges=(("wall_sit", *_HOLD_RANGE), ("back_squat", 6, 8))),
            _S("push", "bench_press", 3, 6, 8,
               ("dumbbell_bench_press", "machine_chest_press", "push_up", "kettlebell_floor_press"),
               ranges=(("push_up", 8, 15), ("kettlebell_floor_press", 10, 15))),
            _S("row", "barbell_row", 3, 8, 10,
               ("dumbbell_row", "seated_cable_row", "kettlebell_row", "inverted_row"),
               ranges=(("kettlebell_row", 10, 15),)),
            _S("shoulders", "lateral_raise", 2, 12, 15,
               ("cable_lateral_raise", "face_pull", "reverse_fly")),
            _S("core_a", "plank", 2, 30, 45, ("side_plank", "dead_bug"),
               ranges=(("dead_bug", 8, 12),)),
        ),
        "B": (
            _S("hinge", "romanian_deadlift", 3, 8, 10,
               ("dumbbell_rdl", "single_leg_rdl", "hip_thrust", "kettlebell_swing", "glute_bridge"),
               ranges=(("kettlebell_swing", 12, 20), ("glute_bridge", 12, 15))),
            _S("press", "overhead_press", 3, 6, 8,
               ("dumbbell_shoulder_press", "machine_shoulder_press", "incline_dumbbell_press",
                "kettlebell_press", "pike_push_up"),
               ranges=(("kettlebell_press", 8, 12), ("pike_push_up", 6, 12))),
            _S("pull", "lat_pulldown", 3, 8, 10, ("chin_up", "seated_cable_row", "inverted_row"),
               ranges=(("chin_up", 5, 10),)),
            _S("legs", "wall_sit", 2, 30, 60,
               ("split_squat", "dumbbell_lunge", "leg_press", "glute_bridge"),
               ranges=(("split_squat", 8, 10), ("dumbbell_lunge", 10, 12), ("leg_press", 10, 12),
                       ("glute_bridge", 12, 15))),
            _S("core_b", "hanging_knee_raise", 2, 10, 15, ("cable_crunch", "dead_bug")),
        ),
    },
    UPPER_LOWER: {
        "A": (
            _S("u1_push", "bench_press", 3, 6, 8,
               ("dumbbell_bench_press", "machine_chest_press", "push_up", "kettlebell_floor_press"),
               ranges=(("push_up", 8, 15), ("kettlebell_floor_press", 10, 15))),
            _S("u1_row", "barbell_row", 3, 8, 10,
               ("dumbbell_row", "seated_cable_row", "kettlebell_row", "inverted_row"),
               ranges=(("kettlebell_row", 10, 15),)),
            _S("u1_press", "overhead_press", 2, 8, 10,
               ("dumbbell_shoulder_press", "machine_shoulder_press", "incline_dumbbell_press",
                "kettlebell_press", "pike_push_up")),
            _S("u1_pull", "lat_pulldown", 3, 8, 10, ("chin_up", "inverted_row"),
               ranges=(("chin_up", 5, 10),)),
            _S("u1_biceps", "dumbbell_curl", 2, 10, 12, ("cable_curl", "chin_up"),
               ranges=(("chin_up", 5, 10),)),
            _S("u1_triceps", "triceps_pushdown", 2, 10, 12,
               ("overhead_triceps_extension", "close_grip_push_up", "bench_dip")),
        ),
        "B": (
            _S("l1_squat", "split_squat", 3, 8, 10, ("goblet_squat", "leg_press", "back_squat"),
               ranges=(("back_squat", 6, 8),)),
            _S("l1_hinge", "romanian_deadlift", 3, 8, 10,
               ("dumbbell_rdl", "single_leg_rdl", "kettlebell_swing", "hip_thrust"),
               ranges=(("kettlebell_swing", 12, 20),)),
            _S("l1_quad", "wall_sit", 3, 30, 60, ("leg_press", "leg_extension", "goblet_squat"),
               ranges=(("leg_press", 10, 12), ("leg_extension", 12, 15), ("goblet_squat", 8, 10))),
            _S("l1_curl", "leg_curl", 2, 10, 12, ("single_leg_rdl", "glute_bridge"),
               ranges=(("glute_bridge", 12, 15),)),
            _S("l1_core", "plank", 2, 30, 45, ("side_plank", "dead_bug"),
               ranges=(("dead_bug", 8, 12),)),
        ),
        "C": (
            _S("u2_push", "incline_dumbbell_press", 3, 8, 10,
               ("dumbbell_bench_press", "machine_chest_press", "push_up", "kettlebell_floor_press"),
               ranges=(("push_up", 8, 15), ("kettlebell_floor_press", 10, 15))),
            _S("u2_pull", "chin_up", 3, 5, 10, ("lat_pulldown", "inverted_row"),
               ranges=(("lat_pulldown", 8, 10), ("inverted_row", 8, 12))),
            _S("u2_row", "dumbbell_row", 3, 8, 12, ("seated_cable_row", "kettlebell_row", "inverted_row")),
            _S("u2_shoulders", "lateral_raise", 3, 12, 15,
               ("cable_lateral_raise", "face_pull", "reverse_fly")),
            _S("u2_rear", "face_pull", 2, 12, 15, ("reverse_fly",)),
        ),
        "D": (
            _S("l2_hinge", "hip_thrust", 3, 8, 10,
               ("glute_bridge", "kettlebell_swing", "dumbbell_rdl", "romanian_deadlift"),
               ranges=(("glute_bridge", 12, 15), ("kettlebell_swing", 12, 20))),
            _S("l2_single", "single_leg_rdl", 3, 8, 10, ("dumbbell_rdl", "kettlebell_swing"),
               ranges=(("kettlebell_swing", 12, 20),)),
            _S("l2_lunge", "split_squat", 2, 8, 10, ("dumbbell_lunge", "goblet_squat"),
               ranges=(("dumbbell_lunge", 10, 12),)),
            _S("l2_quad", "wall_sit", 2, 30, 60, ("leg_extension", "leg_press"),
               ranges=(("leg_extension", 12, 15), ("leg_press", 10, 12))),
            _S("l2_core", "hanging_knee_raise", 2, 10, 15, ("cable_crunch", "dead_bug")),
        ),
    },
}
# The walking finisher, appended to every session when the programme has cardio on.
CARDIO_SLOT = Slot(
    "cardio", "incline_walk", 1, 10, 20, ("brisk_walk", "bike", "elliptical")
)
DAY_KEYS: tuple[str, ...] = tuple(TEMPLATES[DEFAULT_TEMPLATE])
ALL_DAY_KEYS: tuple[str, ...] = ("A", "B", "C", "D")
TEMPLATE_NAMES = {DEFAULT_TEMPLATE: "full body", UPPER_LOWER: "upper / lower"}
DAY_LABELS: dict[str, dict[str, str]] = {
    DEFAULT_TEMPLATE: {"A": "Day A", "B": "Day B"},
    UPPER_LOWER: {"A": "Upper 1", "B": "Lower 1", "C": "Upper 2", "D": "Lower 2"},
}
# Kevin's ruling: days are flexible, a sessions-a-week target with no fixed weekdays; the next
# session is the next one in the rotation. The weekdays stored on the programme are inert,
# kept so an older phone build still shows a week.
MIN_SESSIONS, MAX_SESSIONS = 2, 4
INERT_DAYS_FOR: dict[int, tuple[str, ...]] = {
    2: ("mon", "thu"),
    3: DEFAULT_TRAINING_DAYS,
    4: ("mon", "tue", "thu", "fri"),
}


def default_template_for(sessions_per_week: int) -> str:
    """Full body for 2 or 3 sessions a week, upper / lower for 4."""
    return UPPER_LOWER if sessions_per_week >= 4 else DEFAULT_TEMPLATE


def day_label(template: str, day_key: str) -> str:
    return DAY_LABELS.get(template, {}).get(day_key, f"Day {day_key}")


def find_slot(template: str, slot_key: str) -> Slot | None:
    if slot_key == CARDIO_SLOT.key:
        return CARDIO_SLOT
    for day in TEMPLATES.get(template, {}).values():
        for slot in day:
            if slot.key == slot_key:
                return slot
    return None


def unit_of(kind: str) -> str:
    return {KIND_TIMED: "seconds", KIND_CARDIO: "minutes"}.get(kind, "reps")


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
        return unit_of(self.kind)


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
    sets = slot.sets
    low, high = slot.range_for(exercise_key)
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

    unit = unit_of(ex.kind)
    if ex.kind != KIND_WEIGHTED:
        if deload:
            return make(
                None,
                RULE_DELOAD,
                f"Deload session: one set fewer, {low} {unit}. Progress resumes next session.",
                n_sets=max(1, sets - 1),
                r_high=low,
            )
        if ex.kind == KIND_CARDIO:
            return make(None, RULE_REPS, f"Add minutes until it reaches {high}.")
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


@dataclass(frozen=True)
class Filters:
    """What the programme filters the catalog by (body coach 2): equipment he does not have,
    joints to go easy on, and whether each session ends with the walking finisher. A home
    session adds every piece of equipment but a kettlebell and bodyweight (`at_home`)."""

    exclude_equipment: frozenset[str] = frozenset()
    avoid: frozenset[str] = frozenset()
    cardio: bool = False

    def allows(self, exercise_key: str) -> bool:
        return self.why_not(exercise_key) is None

    def why_not(self, exercise_key: str) -> str | None:
        ex = CATALOG[exercise_key]
        if all(e in self.exclude_equipment for e in ex.equipment):
            return "needs " + " or ".join(EQUIPMENT_WORDS[e] for e in ex.equipment)
        hit = [JOINT_WORDS[j] for j in ex.joints if j in self.avoid]
        if hit:
            return f"loads the {' and '.join(hit)}"
        return None


NO_FILTERS = Filters()


def at_home(filters: Filters) -> Filters:
    """Kevin: "I'm at home today" switches that session to home variants."""
    return Filters(
        filters.exclude_equipment | (frozenset(EQUIPMENT) - HOME_EQUIPMENT),
        filters.avoid,
        filters.cardio,
    )


@dataclass(frozen=True)
class Resolved:
    """One slot of one day after swaps and filters: the exercise it holds, or paused and why."""

    slot: Slot
    exercise: str | None
    paused_because: str | None = None


def resolve_day(
    template: str,
    day_key: str,
    swaps: dict[str, str] | None = None,
    filters: Filters = NO_FILTERS,
) -> list[Resolved]:
    """The day's slots in order. A slot holds its swap when the swap passes the filters, else the
    first of its choices that passes and is not already in the day; with none, it is paused (never
    filled with a guess; decision 6). The cardio finisher comes last when the programme has it."""
    swaps = swaps or {}
    slots = list(TEMPLATES[template][day_key])
    if filters.cardio:
        slots.append(CARDIO_SLOT)
    used: set[str] = set()
    out = []
    for slot in slots:
        wanted = swaps.get(slot.key)
        order = list(slot.choices())
        if wanted in order:
            order.remove(wanted)
            order.insert(0, wanted)
        pick = next((k for k in order if k not in used and filters.allows(k)), None)
        if pick is None:
            reasons = sorted({filters.why_not(k) or "already in the day" for k in slot.choices()})
            out.append(Resolved(slot, None, "; ".join(reasons)))
            continue
        used.add(pick)
        out.append(Resolved(slot, pick))
    return out


def slots_for(
    template: str,
    day_key: str,
    swaps: dict[str, str] | None = None,
    filters: Filters = NO_FILTERS,
) -> list[tuple[Slot, str]]:
    """The day's slots that hold an exercise, with the exercise each one holds."""
    return [(r.slot, r.exercise) for r in resolve_day(template, day_key, swaps, filters) if r.exercise]


def day_keys(template: str) -> tuple[str, ...]:
    return tuple(TEMPLATES[template])


def next_day_key(last_day_key: str | None, template: str = DEFAULT_TEMPLATE) -> str:
    """The template's days in turn (A, B for full body; A to D for upper / lower). A last day the
    template does not have starts it from its first day."""
    keys = day_keys(template)
    if last_day_key not in keys:
        return keys[0]
    return keys[(keys.index(last_day_key) + 1) % len(keys)]


def alternatives_for(slot: Slot, current: str, filters: Filters = NO_FILTERS) -> list[dict]:
    """Every choice of a slot except the current one, each with whether the programme's filters
    allow it and why not (decision 11: "alternatives to bench" is a read)."""
    out = []
    for key in slot.choices():
        if key == current:
            continue
        ex = CATALOG[key]
        out.append(
            {
                "exercise_key": key,
                "name": ex.name,
                "equipment": list(ex.equipment),
                "joints": list(ex.joints),
                "allowed": filters.allows(key),
                "why_not": filters.why_not(key),
            }
        )
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
