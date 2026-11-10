"""A programme change, proposed from structured choices and never from the model's own exercises
or numbers (body coach 2, 2026-11-06). Pure: no ORM; `workouts.py` stores and applies it.

Kevin: *"will i be able to tell it like hey i wanna change the workouts. recommend me alternatives
etc and change it accordingly"*. He says what he wants ("4 days", "Tue/Thu/Sat", "my shoulder
hurts", "no barbell", "add cardio", "different split", "swap overhead press"); the model passes
the matching **choices** (`Choices`); this module builds the programme after them from the
templates and catalog in `programme.py`, says what changes in words a voice can read out, and the
person accepts or dismisses it. The rules in `.scratch/body-coach-2/issues/01-decisions.md`:

- sessions a week, never weekdays (Kevin, 2026-11-06: days are flexible; the next session is the
  next one in the rotation); a new count with no split named takes the default split for it (full
  body for 2 or 3, upper / lower for 4);
- "no barbell" adds to the equipment he does not have, "I have a barbell again" takes it away;
  a sore joint adds to the joints to go easy on, "it's better" takes it away;
- a split change carries his swaps to any new slot that offers the same exercise;
- a swap names an exercise in the programme and, optionally, one of its slot's choices that the
  filters allow; with none named it takes the first allowed one;
- a proposal that changes nothing is refused, with nothing stored.
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace

from body_coach import programme as rules

SPLITS = {"full_body": rules.DEFAULT_TEMPLATE, "upper_lower": rules.UPPER_LOWER}
SPLIT_OTHER = "other"
REFERRAL = "If the {joint} keeps hurting, see a physio."


class ChangeRefused(Exception):
    def __init__(self, message: str):
        super().__init__(message)
        self.message = message


@dataclass(frozen=True)
class State:
    """Everything a programme's workouts are built from, as plain values."""

    template: str
    training_days: tuple[str, ...]
    swaps: dict = field(default_factory=dict)
    exclude_equipment: frozenset[str] = frozenset()
    avoid: frozenset[str] = frozenset()
    cardio: bool = False

    @property
    def filters(self) -> rules.Filters:
        return rules.Filters(self.exclude_equipment, self.avoid, self.cardio)

    def as_dict(self) -> dict:
        return {
            "template": self.template,
            "training_days": list(self.training_days),
            "swaps": dict(sorted(self.swaps.items())),
            "exclude_equipment": sorted(self.exclude_equipment),
            "avoid": sorted(self.avoid),
            "cardio": self.cardio,
        }

    @classmethod
    def from_dict(cls, d: dict) -> State:
        return cls(
            template=d["template"],
            training_days=tuple(d["training_days"]),
            swaps=dict(d.get("swaps") or {}),
            exclude_equipment=frozenset(d.get("exclude_equipment") or ()),
            avoid=frozenset(d.get("avoid") or ()),
            cardio=bool(d.get("cardio")),
        )


@dataclass(frozen=True)
class Choices:
    """What the person asked for, as the engine tool's schema allows it. Every field optional."""

    sessions_per_week: int | None = None
    split: str | None = None
    without_equipment: tuple[str, ...] = ()
    with_equipment: tuple[str, ...] = ()
    avoid: tuple[str, ...] = ()
    clear_avoid: tuple[str, ...] = ()
    cardio: bool | None = None
    swap_exercise: str | None = None
    swap_to: str | None = None

    def as_dict(self) -> dict:
        out = {}
        for key, value in self.__dict__.items():
            if value in (None, ()):
                continue
            out[key] = list(value) if isinstance(value, tuple) else value
        return out


# =============================================================================
# Reading the choices
# =============================================================================


def _members(value, allowed: tuple[str, ...], name: str) -> tuple[str, ...]:
    if value in (None, ""):
        return ()
    if isinstance(value, str):
        value = [value]
    if not isinstance(value, (list, tuple)):
        raise ChangeRefused(f"Nothing was proposed. {name} must be a list.")
    out = []
    for v in value:
        key = str(v).strip().lower().replace(" ", "_").replace("-", "_")
        if key == "back":
            key = rules.LOWER_BACK
        if key.endswith("s") and key[:-1] in allowed:
            key = key[:-1]
        if key not in allowed:
            raise ChangeRefused(
                f"Nothing was proposed. {v!r} is not one of {', '.join(allowed)} ({name})."
            )
        if key not in out:
            out.append(key)
    return tuple(out)


def read_choices(data: dict) -> Choices:
    """The request body as `Choices`, or a refusal naming what was wrong. Unknown keys refuse."""
    if not isinstance(data, dict):
        raise ChangeRefused("Nothing was proposed. The body must be a JSON object.")
    known = set(Choices.__dataclass_fields__)
    unknown = sorted(set(data) - known)
    if unknown:
        raise ChangeRefused(
            f"Nothing was proposed. Unknown choice(s): {', '.join(unknown)}. Allowed: "
            f"{', '.join(sorted(known))}."
        )
    count = data.get("sessions_per_week")
    if count is not None and (
        isinstance(count, bool)
        or not isinstance(count, int)
        or not rules.MIN_SESSIONS <= count <= rules.MAX_SESSIONS
    ):
        raise ChangeRefused(
            f"Nothing was proposed. The programme has templates for {rules.MIN_SESSIONS} to "
            f"{rules.MAX_SESSIONS} sessions a week; got {count!r}."
        )
    split = data.get("split")
    if split is not None and split not in (*SPLITS, SPLIT_OTHER):
        raise ChangeRefused(
            f"Nothing was proposed. split must be full_body, upper_lower or other; got {split!r}."
        )
    cardio = data.get("cardio")
    if cardio is not None and not isinstance(cardio, bool):
        raise ChangeRefused("Nothing was proposed. cardio must be true or false.")
    for name in ("swap_exercise", "swap_to"):
        if data.get(name) is not None and not isinstance(data[name], str):
            raise ChangeRefused(f"Nothing was proposed. {name} must be a string.")
    return Choices(
        sessions_per_week=count,
        split=split,
        without_equipment=_members(
            data.get("without_equipment"), rules.EQUIPMENT, "without_equipment"
        ),
        with_equipment=_members(data.get("with_equipment"), rules.EQUIPMENT, "with_equipment"),
        avoid=_members(data.get("avoid"), rules.JOINTS, "avoid"),
        clear_avoid=_members(data.get("clear_avoid"), rules.JOINTS, "clear_avoid"),
        cardio=cardio,
        swap_exercise=(data.get("swap_exercise") or "").strip() or None,
        swap_to=(data.get("swap_to") or "").strip() or None,
    )


# =============================================================================
# Building the programme after
# =============================================================================


def held(state: State) -> dict[str, list[rules.Slot]]:
    """Every exercise the programme holds, with the slots that hold it, across its days."""
    out: dict[str, list[rules.Slot]] = {}
    for day in rules.day_keys(state.template):
        for r in rules.resolve_day(state.template, day, state.swaps, state.filters):
            if r.exercise:
                out.setdefault(r.exercise, []).append(r.slot)
    return out


def _carry_swaps(old: State, template: str) -> dict:
    """A split change keeps his picks: a new slot that offers an exercise he swapped to holds it."""
    if template == old.template:
        return dict(old.swaps)
    picked = [ex for ex in old.swaps.values() if ex in rules.CATALOG]
    out = {}
    for day in rules.day_keys(template):
        for slot in rules.TEMPLATES[template][day]:
            for ex in picked:
                if ex in slot.alternatives and slot.key not in out:
                    out[slot.key] = ex
    if old.cardio and rules.CARDIO_SLOT.key in old.swaps:
        out[rules.CARDIO_SLOT.key] = old.swaps[rules.CARDIO_SLOT.key]
    return out


def _apply_swap(state: State, exercise: str, to: str | None) -> State:
    holding = held(state)
    key = rules.match_exercise(exercise, list(holding))
    if key is None:
        names = ", ".join(rules.CATALOG[k].name for k in holding)
        raise ChangeRefused(
            f"Nothing was proposed. {exercise!r} is not one exercise in the programme ({names})."
        )
    swaps = dict(state.swaps)
    filters = state.filters
    for slot in holding[key]:
        options = [k for k in slot.choices() if k != key]
        if to:
            pick = rules.match_exercise(to, options)
            if pick is None:
                allowed = ", ".join(rules.CATALOG[k].name for k in options if filters.allows(k))
                raise ChangeRefused(
                    f"Nothing was proposed. {rules.CATALOG[key].name} can be swapped for: "
                    f"{allowed or 'nothing the filters allow'}."
                )
            why = filters.why_not(pick)
            if why:
                raise ChangeRefused(
                    f"Nothing was proposed. {rules.CATALOG[pick].name} {why}, which the programme "
                    f"is set to avoid."
                )
        else:
            pick = next((k for k in options if filters.allows(k)), None)
            if pick is None:
                raise ChangeRefused(
                    f"Nothing was proposed. Every alternative to {rules.CATALOG[key].name} is "
                    f"ruled out by the programme's equipment or joints."
                )
        if pick == slot.exercise:
            swaps.pop(slot.key, None)
        else:
            swaps[slot.key] = pick
    return replace(state, swaps=swaps)


def build_after(before: State, choices: Choices) -> State:
    # The weekdays are inert (sessions are flexible); only their count is the target.
    days = before.training_days
    count = choices.sessions_per_week
    if count is not None and count != len(days):
        days = rules.INERT_DAYS_FOR[count]

    if choices.split == SPLIT_OTHER:
        template = (
            rules.UPPER_LOWER
            if before.template == rules.DEFAULT_TEMPLATE
            else rules.DEFAULT_TEMPLATE
        )
    elif choices.split is not None:
        template = SPLITS[choices.split]
    elif len(days) != len(before.training_days):
        template = rules.default_template_for(len(days))
    else:
        template = before.template

    exclude = (before.exclude_equipment | set(choices.without_equipment)) - set(
        choices.with_equipment
    )
    avoid = (before.avoid | set(choices.avoid)) - set(choices.clear_avoid)
    if exclude >= set(rules.EQUIPMENT):
        raise ChangeRefused("Nothing was proposed. With no equipment at all there is no workout.")
    after = State(
        template=template,
        training_days=tuple(days),
        swaps=_carry_swaps(before, template),
        exclude_equipment=frozenset(exclude),
        avoid=frozenset(avoid),
        cardio=before.cardio if choices.cardio is None else choices.cardio,
    )
    if choices.swap_exercise:
        after = _apply_swap(after, choices.swap_exercise, choices.swap_to)
    elif choices.swap_to:
        raise ChangeRefused("Nothing was proposed. swap_to needs swap_exercise.")
    return after


# =============================================================================
# What changes, in words
# =============================================================================


def days_preview(state: State) -> list[dict]:
    out = []
    for day in rules.day_keys(state.template):
        resolved = rules.resolve_day(state.template, day, state.swaps, state.filters)
        out.append(
            {
                "day_key": day,
                "label": rules.day_label(state.template, day),
                "exercises": [rules.CATALOG[r.exercise].name for r in resolved if r.exercise],
                "paused": [
                    {
                        "slot_key": r.slot.key,
                        "usually": rules.CATALOG[r.slot.exercise].name,
                        "because": r.paused_because,
                    }
                    for r in resolved
                    if not r.exercise
                ],
            }
        )
    return out


def _names(keys) -> list[str]:
    return [rules.CATALOG[k].name for k in keys]


def join_words(items: list[str]) -> str:
    if len(items) <= 1:
        return "".join(items)
    return ", ".join(items[:-1]) + " and " + items[-1]


def diff(before: State, after: State, trained: set[str] | None = None) -> dict:
    """What the change does. `trained`: exercises with finished history, whose progress carries
    to wherever they still are; anything else new starts with a calibration set."""
    trained = trained or set()
    b, a = held(before), held(after)
    order = list(dict.fromkeys([*b, *a]))
    kept = [k for k in order if k in b and k in a]
    dropped = [k for k in order if k in b and k not in a]
    added = [k for k in order if k in a and k not in b]
    reasons = {k: after.filters.why_not(k) for k in dropped}
    paused = [p for d in days_preview(after) for p in d["paused"]]
    return {
        "split_changed": before.template != after.template,
        "sessions_changed": len(before.training_days) != len(after.training_days),
        "kept": _names(kept),
        "dropped": [
            {"name": rules.CATALOG[k].name, "because": reasons[k] or "not in the new days"}
            for k in dropped
        ],
        "added": [{"name": rules.CATALOG[k].name, "calibrates": k not in trained} for k in added],
        "paused": paused,
        "cardio": {"before": before.cardio, "after": after.cardio},
    }


def words(before: State, after: State, d: dict, choices: Choices) -> str:
    """The proposal as a voice reads it: the shape, what goes, what comes, what keeps its
    progress, and that nothing changes until he accepts."""
    parts = [f"Proposed: {summary(after)}."]
    if d["dropped"]:
        by_reason: dict[str, list[str]] = {}
        for x in d["dropped"]:
            by_reason.setdefault(x["because"], []).append(x["name"])
        parts.append(
            "Out: " + "; ".join(f"{join_words(n)}, {why}" for why, n in by_reason.items()) + "."
        )
    if d["added"]:
        new = [x["name"] for x in d["added"]]
        calibrate = any(x["calibrates"] for x in d["added"])
        parts.append(
            f"In: {join_words(new)}"
            + ("; new ones start with a calibration set." if calibrate else ".")
        )
    if d["kept"] and (d["dropped"] or d["added"]):
        parts.append(f"{len(d['kept'])} exercises stay and keep their progress.")
    for p in d["paused"]:
        parts.append(f"{p['usually']} slot paused: every choice for it {p['because']}.")
    if d["cardio"]["before"] != d["cardio"]["after"]:
        parts.append(
            "Each session ends with a 10 to 20 minute walk."
            if after.cardio
            else "The walking finisher comes off."
        )
    for joint in choices.avoid:
        parts.append(REFERRAL.format(joint=rules.JOINT_WORDS[joint]))
    parts.append("Nothing changes until you accept.")
    return " ".join(parts)


def summary(state: State) -> str:
    """The programme in one sentence, for the read and the accept."""
    bits = [f"{len(state.training_days)} sessions a week, {rules.TEMPLATE_NAMES[state.template]}"]
    if state.exclude_equipment:
        bits.append(
            "no " + join_words([rules.EQUIPMENT_WORDS[e] for e in sorted(state.exclude_equipment)])
        )
    if state.avoid:
        bits.append(
            "going easy on the " + join_words([rules.JOINT_WORDS[j] for j in sorted(state.avoid)])
        )
    if state.cardio:
        bits.append("a walking finisher")
    return ", ".join(bits)
