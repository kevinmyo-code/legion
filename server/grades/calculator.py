"""What do I need on the rest to finish a course at a target (Kevin, 2026-11-12).

Pure: no database, no clock, no request. `grades/service.py` builds the inputs from the member's
own rows and hands back the answer; tests build them by hand.

**The arithmetic is exact.** Every score, weight and target is a `Fraction` (Canvas's JSON numbers
go through `Decimal(str(x))` first, never float arithmetic), and rounding happens only when a
figure is turned into words or display numbers: a needed figure rounds UP, a best-reachable
figure rounds DOWN, so the display never says less is needed, or more is reachable, than is true.

**How a course total is formed, following Canvas's own grade calculator** (reasoned from Canvas's
documented behaviour, not read from its code):

- Unweighted (`apply_weights` false): earned points over possible points, across every assignment
  that counts.
- Weighted: each assignment group's earned over possible, times its weight, summed over the groups
  that hold any counted points. When those weights add to less than 100 the total is scaled up to
  100 (Canvas leaves out a group with nothing in it the same way); 100 or more is not scaled down.
- An excused assignment, one Canvas marks `omit_from_final_grade`, or a `not_graded` one does not
  count at all. An assignment with no score yet is REMAINING work.

**The projection** treats every remaining assignment the caller names as scoring one same
percentage, `p`, and solves the course total for the target: the total is a straight line in `p`,
so `p = (target - fixed part) / (slope)`. Ungraded work the caller did NOT name is left out, the
way Canvas's current score leaves it out, unless `others_at` gives it an assumed percentage; the
words say which.

**What makes it an ESTIMATE, said in words (CLAUDE.md section 4 rule 5):**

- a group with a drop rule (drop lowest / drop highest). Which scores are dropped depends on scores
  not yet given, so the projection ignores the rule;
- the current total worked out here not matching Canvas's own current score. Canvas then counts
  something this engine cannot see (a hidden or muted score, a drop, a grading period), and the
  projection inherits the gap.

Figures Canvas stated are Canvas's; everything this module works out is labelled as worked out.
"""

from __future__ import annotations

import math
import re
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from decimal import Decimal
from fractions import Fraction

HUNDRED = Fraction(100)

# The default letter scale when the course's own scheme is not on file (Kevin, 2026-11-12).
DEFAULT_SCALE: tuple[tuple[str, Fraction], ...] = (
    ("A", Fraction(90)),
    ("B", Fraction(80)),
    ("C", Fraction(70)),
    ("D", Fraction(60)),
)

STATUS_NEEDED = "needed"
STATUS_GUARANTEED = "guaranteed"
STATUS_IMPOSSIBLE = "impossible"
STATUS_NOTHING_LEFT = "nothing_left"

ANCHOR_MATCHES = "matches"
ANCHOR_DIFFERS = "differs"
ANCHOR_NOT_SHOWN = "canvas_shows_no_total"
ANCHOR_NOTHING_GRADED = "nothing_graded"


class Refusal(Exception):
    """Nothing was worked out. The message says why, in words."""

    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


def exact(value) -> Fraction | None:
    """A number as an exact Fraction, through `Decimal(str(x))` for a JSON float. None stays
    None. A bool or a non-number is refused rather than guessed at."""
    if value is None:
        return None
    if isinstance(value, bool):
        raise Refusal(f"Nothing was worked out. {value!r} is not a number.")
    if isinstance(value, Fraction):
        return value
    if isinstance(value, int):
        return Fraction(value)
    try:
        number = Decimal(str(value).strip())
    except ArithmeticError as exc:
        raise Refusal(f"Nothing was worked out. {value!r} is not a number.") from exc
    if not number.is_finite():
        raise Refusal(f"Nothing was worked out. {value!r} is not a number.")
    return Fraction(number)


@dataclass(frozen=True)
class Group:
    id: int
    name: str
    weight: Fraction | None = None
    drop_lowest: int = 0
    drop_highest: int = 0

    @property
    def drops(self) -> bool:
        return self.drop_lowest > 0 or self.drop_highest > 0

    def drop_words(self) -> str:
        parts = []
        if self.drop_lowest:
            parts.append(f"drops its lowest {self.drop_lowest}")
        if self.drop_highest:
            parts.append(f"drops its highest {self.drop_highest}")
        return f"{self.name} {' and '.join(parts)}"


@dataclass(frozen=True)
class Item:
    id: int
    name: str
    points_possible: Fraction
    score: Fraction | None = None
    group_id: int | None = None
    excused: bool = False
    # False for `not_graded` and `omit_from_final_grade`: it never counts.
    counts: bool = True

    @property
    def graded(self) -> bool:
        return self.score is not None


@dataclass(frozen=True)
class Course:
    label: str
    apply_weights: bool
    groups: tuple[Group, ...] = ()
    # Canvas's own current score, as stated (None when the course shows no total).
    canvas_current_score: Fraction | None = None
    # The course's own letters and lower bounds in percent, highest first; None when not on file.
    scale: tuple[tuple[str, Fraction], ...] | None = None
    scale_title: str | None = None

    def group(self, group_id) -> Group | None:
        for group in self.groups:
            if group.id == group_id:
                return group
        return None


@dataclass(frozen=True)
class Target:
    percent: Fraction
    letter: str | None
    # How the letter was turned into a percent, in words; None for a plain percent.
    scale_words: str | None


@dataclass
class Answer:
    status: str
    course: str
    target: Target
    remaining_words: str
    remaining_ids: list[int]
    remaining_points: Fraction
    needed_fraction: Fraction | None = None
    best_reachable: Fraction | None = None
    worst_reachable: Fraction | None = None
    current_worked_out: Fraction | None = None
    canvas_current_score: Fraction | None = None
    anchor: str = ANCHOR_NOTHING_GRADED
    estimate_reasons: list[str] = field(default_factory=list)
    assumptions: list[str] = field(default_factory=list)
    weights_left_out: list[str] = field(default_factory=list)

    @property
    def estimate(self) -> bool:
        return bool(self.estimate_reasons)

    def as_dict(self) -> dict:
        needed_points = (
            self.needed_fraction * self.remaining_points
            if self.needed_fraction is not None
            else None
        )
        return {
            "status": self.status,
            "course": self.course,
            "target_percent": shown(self.target.percent),
            "target_letter": self.target.letter,
            "scale": self.target.scale_words,
            "remaining": self.remaining_words,
            "remaining_assignment_ids": self.remaining_ids,
            "remaining_points": shown(self.remaining_points),
            "needed_percent": (
                up(self.needed_fraction * HUNDRED) if self.needed_fraction is not None else None
            ),
            "needed_points": up(needed_points) if needed_points is not None else None,
            "best_reachable_percent": down(self.best_reachable),
            "worst_reachable_percent": down(self.worst_reachable),
            "current_worked_out_percent": shown(self.current_worked_out),
            "canvas_current_score": shown(self.canvas_current_score),
            "anchor": self.anchor,
            "estimate": self.estimate,
            "estimate_reasons": list(self.estimate_reasons),
            "assumptions": list(self.assumptions),
            "words": self.words(),
        }

    def words(self) -> str:
        target = percent_words(self.target.percent, "half")
        if self.target.letter:
            target = f"{self.target.letter} ({target})"
        parts = []
        if self.status == STATUS_NEEDED:
            line = (
                f"To finish {self.course} at {target}, you need at least "
                f"{percent_words(self.needed_fraction * HUNDRED, 'up')} on {self.remaining_words}"
            )
            if self.remaining_points:
                line += (
                    f": {number_words(self.needed_fraction * self.remaining_points, 'up')} of "
                    f"{number_words(self.remaining_points, 'half')} points"
                )
            parts.append(line + ".")
        elif self.status == STATUS_GUARANTEED:
            parts.append(
                f"{target} in {self.course} is already guaranteed: even 0 on "
                f"{self.remaining_words} leaves the course at "
                f"{percent_words(self.worst_reachable, 'down')}."
            )
        elif self.status == STATUS_IMPOSSIBLE:
            parts.append(
                f"{target} in {self.course} is out of reach: 100% on {self.remaining_words} "
                f"reaches at most {percent_words(self.best_reachable, 'down')}."
            )
        else:
            met = self.best_reachable >= self.target.percent
            parts.append(
                f"No ungraded work is left to work it out on ({self.remaining_words}: none), so "
                f"{self.course} stands at {percent_words(self.best_reachable, 'half')}, which "
                f"{'meets' if met else 'is below'} {target}."
            )
        if self.target.scale_words:
            parts.append(f"{self.target.letter} was read on {self.target.scale_words}.")
        parts.extend(self.assumptions)
        if self.weights_left_out:
            parts.append(
                "Left out because nothing in them is graded or remaining here: "
                + ", ".join(self.weights_left_out)
                + "."
            )
        parts.append(self.anchor_words())
        if self.estimate_reasons:
            parts.append(
                "This is an ESTIMATE, not a figure Canvas stated: "
                + "; ".join(self.estimate_reasons)
                + "."
            )
        else:
            parts.append("Worked out here from the scores on Canvas; Canvas did not state it.")
        return " ".join(parts)

    def anchor_words(self) -> str:
        if self.anchor == ANCHOR_MATCHES:
            return (
                f"The current total worked out from the scores on file, "
                f"{percent_words(self.current_worked_out, 'half')}, matches Canvas's own current "
                f"score."
            )
        if self.anchor == ANCHOR_DIFFERS:
            return (
                f"Canvas's own current score is {percent_words(self.canvas_current_score, 'half')}"
                f", but worked out from the scores on file it is "
                f"{percent_words(self.current_worked_out, 'half')}."
            )
        if self.anchor == ANCHOR_NOT_SHOWN:
            return (
                "Canvas shows no total for this course, so the scores on file could not be "
                "checked against one."
            )
        return "Nothing in this course is graded yet, so there is no current total to check."


# =============================================================================
# Rounding, for display only
# =============================================================================


def _cents(value: Fraction, mode: str) -> int:
    """`value` in whole hundredths, rounded exactly on the Fraction (never through a float or a
    finite-precision Decimal): `up` is the ceiling, `down` the floor, `half` half away from 0."""
    scaled = value * 100
    if mode == "up":
        return math.ceil(scaled)
    if mode == "down":
        return math.floor(scaled)
    rounded = math.floor(abs(scaled) + Fraction(1, 2))
    return rounded if scaled >= 0 else -rounded


def _round(value: Fraction | None, mode: str) -> float | None:
    if value is None:
        return None
    return float(Decimal(_cents(value, mode)) / 100)


def up(value: Fraction | None) -> float | None:
    return _round(value, "up")


def down(value: Fraction | None) -> float | None:
    return _round(value, "down")


def shown(value: Fraction | None) -> float | None:
    return _round(value, "half")


_MODES = {"up": up, "down": down, "half": shown}


def number_words(value: Fraction, mode: str) -> str:
    text = f"{_MODES[mode](value):.2f}".rstrip("0").rstrip(".")
    return text or "0"


def percent_words(value: Fraction, mode: str) -> str:
    return f"{number_words(value, mode)}%"


# =============================================================================
# Targets
# =============================================================================

_PERCENT = re.compile(r"^\s*(\d+(?:\.\d+)?)\s*%?\s*$")
_LETTER = re.compile(r"^\s*([A-Za-z][A-Za-z+\-]{0,3})\s*$")


def scale_words(course: Course) -> str:
    if course.scale:
        title = f" ({course.scale_title})" if course.scale_title else ""
        return f"the course's own grading scheme{title}"
    letters = ", ".join(f"{name} {percent_words(cut, 'half')}" for name, cut in DEFAULT_SCALE)
    return (
        f"a default scale ({letters}), because this course's own grading scheme is not on file"
    )


def parse_target(value, course: Course) -> Target:
    """A percent ("85", "85%", 85.5) or a letter ("B", "b+") on the course's own scheme when it is
    on file, else on the default scale, said in words."""
    if isinstance(value, (int, float, Decimal, Fraction)) and not isinstance(value, bool):
        percent = exact(value)
    else:
        text = str(value or "")
        number = _PERCENT.match(text)
        if number:
            percent = exact(number.group(1))
        else:
            letter = _LETTER.match(text)
            if not letter:
                raise Refusal(
                    f"Nothing was worked out. {text!r} is neither a percent nor a letter grade."
                )
            wanted = letter.group(1).upper()
            scale = course.scale or DEFAULT_SCALE
            for name, cut in scale:
                if name.upper() == wanted:
                    words = scale_words(course)
                    return Target(cut, name, f"{words}, where {name} starts at "
                                             f"{percent_words(cut, 'half')}")
            raise Refusal(
                f"Nothing was worked out. {letter.group(1)} is not a grade on "
                f"{scale_words(course)}; its grades are {', '.join(n for n, _ in scale)}."
            )
    if percent is None or percent <= 0:
        raise Refusal("Nothing was worked out. A target has to be more than 0%.")
    return Target(percent, None, None)


# =============================================================================
# Choosing what is remaining
# =============================================================================


def counted(items: Iterable[Item]) -> list[Item]:
    return [item for item in items if item.counts and not item.excused]


def _matches(needle: str, *haystacks) -> bool:
    needle = needle.strip().lower()
    return any(needle and needle in str(h or "").lower() for h in haystacks)


def select_remaining(
    course: Course,
    items: Sequence[Item],
    *,
    assignment: str | int | None = None,
    group: str | int | None = None,
) -> tuple[list[int], str]:
    """The ids treated as remaining, and how to say them. Default: every counted assignment with
    no score yet. `assignment` names one (id or words in its name), `group` an assignment group
    (id or words in its name). An ambiguous or graded choice is refused in words."""
    live = counted(items)
    if assignment is not None and group is not None:
        raise Refusal("Nothing was worked out. Name an assignment or a group, not both.")
    if assignment is not None:
        ref = str(assignment).strip()
        found = [i for i in items if str(i.id) == ref] or [
            i for i in items if _matches(ref, i.name)
        ]
        if not found:
            raise Refusal(
                f"Nothing was worked out. No assignment in {course.label} matches {ref!r}."
            )
        if len(found) > 1:
            names = "; ".join(i.name for i in found[:6])
            raise Refusal(
                f"Nothing was worked out. {ref!r} matches {len(found)} assignments in "
                f"{course.label} ({names}). Name one."
            )
        item = found[0]
        if item not in live:
            why = "is excused" if item.excused else "does not count toward the grade"
            raise Refusal(f"Nothing was worked out. {item.name} {why}.")
        if item.graded:
            raise Refusal(
                f"Nothing was worked out. {item.name} already has a score on Canvas "
                f"({number_words(item.score, 'half')} of "
                f"{number_words(item.points_possible, 'half')})."
            )
        return [item.id], item.name
    if group is not None:
        ref = str(group).strip()
        found = [g for g in course.groups if str(g.id) == ref] or [
            g for g in course.groups if _matches(ref, g.name)
        ]
        if not found:
            raise Refusal(
                f"Nothing was worked out. No assignment group in {course.label} matches {ref!r}."
            )
        if len(found) > 1:
            names = "; ".join(g.name for g in found[:6])
            raise Refusal(
                f"Nothing was worked out. {ref!r} matches {len(found)} groups in {course.label} "
                f"({names}). Name one."
            )
        chosen = found[0]
        ids = [i.id for i in live if i.group_id == chosen.id and not i.graded]
        return ids, f"the ungraded work in {chosen.name}"
    ids = [i.id for i in live if not i.graded]
    return ids, "the remaining ungraded work"


# =============================================================================
# The projection
# =============================================================================


@dataclass
class _Bucket:
    key: object
    weight: Fraction
    earned: Fraction = Fraction(0)
    graded_possible: Fraction = Fraction(0)
    remaining: Fraction = Fraction(0)

    @property
    def total(self) -> Fraction:
        return self.graded_possible + self.remaining


def _buckets(course: Course, rows: list[tuple[Item, Fraction | None, bool]]) -> dict:
    """rows: (item, score to count or None, is_remaining)."""
    buckets: dict[object, _Bucket] = {}
    if not course.apply_weights:
        buckets[None] = _Bucket(None, HUNDRED)
    unplaced = []
    for item, score, is_remaining in rows:
        if course.apply_weights:
            group = course.group(item.group_id)
            if group is None:
                unplaced.append(item.name)
                continue
            bucket = buckets.setdefault(group.id, _Bucket(group.id, group.weight or Fraction(0)))
        else:
            bucket = buckets[None]
        if is_remaining:
            bucket.remaining += item.points_possible
        else:
            bucket.earned += score
            bucket.graded_possible += item.points_possible
    if unplaced:
        raise Refusal(
            f"Nothing was worked out. {course.label} weights its assignment groups, and "
            f"{len(unplaced)} assignment(s) have no group on file ({'; '.join(unplaced[:4])}). "
            f"The next Canvas read stores it."
        )
    return buckets


def _line(buckets: dict) -> tuple[Fraction, Fraction, list]:
    """The course total as `fixed + slope * p` (in percent), over the buckets holding points."""
    live = [b for b in buckets.values() if b.total > 0]
    if not live:
        return Fraction(0), Fraction(0), []
    weight = sum((b.weight for b in live), Fraction(0))
    if weight <= 0:
        raise Refusal(
            "Nothing was worked out. Every assignment group holding work here has a weight of "
            "0%, so the course total cannot be formed."
        )
    divisor = weight if weight < HUNDRED else HUNDRED
    fixed = sum((b.weight * b.earned / b.total for b in live), Fraction(0)) * HUNDRED / divisor
    slope = sum((b.weight * b.remaining / b.total for b in live), Fraction(0)) * HUNDRED / divisor
    return fixed, slope, live


def current_total(course: Course, items: Sequence[Item]) -> Fraction | None:
    """The current total worked out the way Canvas's current score is: graded work only."""
    rows = [(i, i.score, False) for i in counted(items) if i.graded]
    if not rows:
        return None
    buckets = _buckets(course, rows)
    fixed, _slope, live = _line(buckets)
    return fixed if live else None


def _same_to_the_cent(a: Fraction, b: Fraction) -> bool:
    return _cents(a, "half") == _cents(b, "half")


def what_i_need(
    course: Course,
    items: Sequence[Item],
    target,
    *,
    remaining: Sequence[int] | None = None,
    remaining_words: str | None = None,
    others_at=None,
) -> Answer:
    """The one percentage every remaining assignment needs for the course to reach `target`.

    `remaining` is the ids treated as remaining (default: every counted ungraded assignment);
    `select_remaining` turns a name into them. Other ungraded work is left out, or counted at
    `others_at` percent when given. Raises `Refusal` with the reason in words."""
    goal = parse_target(target, course)
    live = counted(items)
    if remaining is None:
        remaining_ids = [i.id for i in live if not i.graded]
        remaining_words = remaining_words or "the remaining ungraded work"
    else:
        remaining_ids = list(remaining)
        remaining_words = remaining_words or "the named work"
        by_id = {i.id: i for i in live}
        for rid in remaining_ids:
            if rid not in by_id:
                raise Refusal(
                    f"Nothing was worked out. Assignment {rid} is not counted work in "
                    f"{course.label}."
                )
            if by_id[rid].graded:
                raise Refusal(
                    f"Nothing was worked out. {by_id[rid].name} already has a score on Canvas."
                )
    chosen = set(remaining_ids)
    assumed = exact(others_at) if others_at is not None else None
    if assumed is not None and assumed < 0:
        raise Refusal("Nothing was worked out. An assumed percentage cannot be below 0.")

    rows: list[tuple[Item, Fraction | None, bool]] = []
    others = []
    for item in live:
        if item.id in chosen:
            if item.points_possible > 0:
                rows.append((item, None, True))
        elif item.graded:
            rows.append((item, item.score, False))
        elif item.points_possible > 0:
            others.append(item)
            if assumed is not None:
                rows.append((item, assumed / HUNDRED * item.points_possible, False))
    remaining_points = sum(
        (i.points_possible for i, _s, is_rem in rows if is_rem), Fraction(0)
    )

    buckets = _buckets(course, rows)
    fixed, slope, used = _line(buckets)
    if not used:
        raise Refusal(
            f"Nothing was worked out. Nothing in {course.label} is graded or left to score here, "
            f"so there is no total to work from."
        )

    answer = Answer(
        status=STATUS_NEEDED,
        course=course.label,
        target=goal,
        remaining_words=remaining_words,
        remaining_ids=[i.id for i, _s, is_rem in rows if is_rem],
        remaining_points=remaining_points,
        canvas_current_score=course.canvas_current_score,
    )
    if others:
        if assumed is None:
            answer.assumptions.append(
                f"{len(others)} other ungraded assignment(s) are left out, the way Canvas's "
                f"current score leaves them out; their scores will move this."
            )
        else:
            answer.assumptions.append(
                f"{len(others)} other ungraded assignment(s) are assumed to score "
                f"{percent_words(assumed, 'half')}, as asked."
            )
    if course.apply_weights:
        used_keys = {b.key for b in used}
        answer.weights_left_out = [
            f"{g.name} ({percent_words(g.weight or Fraction(0), 'half')})"
            for g in course.groups
            if g.id not in used_keys and (g.weight or 0) > 0
        ]

    # Estimate reasons. Drop rules in any group that holds counted work here.
    if course.apply_weights:
        groups_in_play = [course.group(b.key) for b in used]
    else:
        placed = {i.group_id for i, _s, _r in rows}
        groups_in_play = [g for g in course.groups if g.id in placed]
        if any(gid is None or course.group(gid) is None for gid in placed) and any(
            g.drops for g in course.groups
        ):
            answer.estimate_reasons.append(
                "some assignments have no group on file and the course has a drop rule, so "
                "whether a drop applies to them is not known"
            )
    for group in groups_in_play:
        if group is not None and group.drops:
            answer.estimate_reasons.append(
                f"{group.drop_words()}, and which scores are dropped depends on scores not yet "
                f"given, so the drop rule is ignored here"
            )

    # The anchor: the current total worked out here against Canvas's own.
    answer.current_worked_out = current_total(course, items)
    if answer.current_worked_out is None:
        answer.anchor = ANCHOR_NOTHING_GRADED
    elif course.canvas_current_score is None:
        answer.anchor = ANCHOR_NOT_SHOWN
    elif _same_to_the_cent(answer.current_worked_out, course.canvas_current_score):
        answer.anchor = ANCHOR_MATCHES
    else:
        answer.anchor = ANCHOR_DIFFERS
        answer.estimate_reasons.append(
            f"Canvas's own current score ({percent_words(course.canvas_current_score, 'half')}) "
            f"differs from the one worked out from the scores on file "
            f"({percent_words(answer.current_worked_out, 'half')}), so Canvas counts something "
            f"this engine cannot see"
        )

    answer.worst_reachable = fixed
    answer.best_reachable = fixed + slope
    if slope == 0:
        answer.status = STATUS_NOTHING_LEFT
        return answer
    needed = (goal.percent - fixed) / slope
    if needed <= 0:
        answer.status = STATUS_GUARANTEED
    elif needed > 1:
        answer.status = STATUS_IMPOSSIBLE
    else:
        answer.status = STATUS_NEEDED
        answer.needed_fraction = needed
    return answer
