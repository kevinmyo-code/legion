"""Today, tonight and tomorrow on the agent turn (engine-brain switch plan 1119, inventory row 13):
the phone's `RelativeDay` (`app/.../service/tools/day/RelativeDay.kt`), ported as pure functions.

- The words a date argument may hold (`today`, `tonight`, `tomorrow`, `yesterday`) resolve on the
  TURN's local date: the device's clock and zone when it sent `utc_offset_minutes`, else the
  household's (`turn.local_day`), never the engine's UTC date.
- His own plain day word beats the model's arithmetic (the 2026-11-17 evening eval: asked "and
  tomorrow?", flash-lite passed today+2): when his turn names exactly one of today, tonight or
  tomorrow and nothing else that names a date, and the turn's only date-taking call asks for a
  single day exactly one off it, the call is put back to his day, with a note for the model.

The phone corrects `read_calendar` and `manage_item`; the engine has a twin of the first only, so
`manage_item` (a phone tool, relayed) stays corrected on the phone.
"""

from __future__ import annotations

import re
from datetime import date, time, timedelta

DATE_TOOLS = frozenset({"read_calendar"})
DATE_KEYS = ("date", "from", "to")
WORDS = {"today": 0, "tonight": 0, "tomorrow": 1, "yesterday": -1}
_DAY_WORDS = {"today": 0, "tonight": 0, "tomorrow": 1}
_SMALL_HOURS_END = 5
_BLOCKERS = frozenset(
    """not from after before next last week weekend fortnight month yesterday
    monday tuesday wednesday thursday friday saturday sunday january february march april may june
    july august september october november december thanksgiving christmas easter halloween holiday
    birthday eve move moved reschedule push postpone zero one two three four five six seven eight
    nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty
    thirty couple few first second third fourth fifth sixth seventh eighth ninth tenth eleventh
    twelfth thirteenth fourteenth fifteenth sixteenth seventeenth eighteenth nineteenth twentieth
    thirtieth""".split()
)
NOTE = "Used {label}, the day he said, not the date asked for."


def parse(raw, today: date) -> date | None:
    """`raw` as a date: a day word on `today`, or YYYY-MM-DD; None otherwise."""
    if not isinstance(raw, str):
        return None
    word = raw.strip().lower()
    if word in WORDS:
        return today + timedelta(days=WORDS[word])
    try:
        return date.fromisoformat(word)
    except ValueError:
        return None


def label(day: date) -> str:
    """"Monday 2026-11-16": how a resolved day is said back."""
    return f"{day:%A} {day.isoformat()}"


def said_day(words: str | None, today: date, at: time | None = None) -> date | None:
    """The one day his `words` name, or None: exactly one of today, tonight or tomorrow, and no
    other date expression in the turn. "tonight" in the small hours may mean the night in
    progress, so it is left to the model then."""
    found = [w for w in re.split(r"[^a-z0-9]+", (words or "").lower()) if w]
    blocked = (
        not found
        or any(w in _BLOCKERS or any(ch.isdigit() for ch in w) for w in found)
        or (at is not None and at.hour < _SMALL_HOURS_END and "tonight" in found)
    )
    if blocked:
        return None
    days = [_DAY_WORDS[w] for w in found if w in _DAY_WORDS]
    return today + timedelta(days=days[0]) if len(days) == 1 else None


def is_date_call(name: str, args: dict) -> bool:
    return name in DATE_TOOLS and any(k in (args or {}) for k in DATE_KEYS)


def resolved(args: dict, today: date) -> dict:
    """`args` with every day word in a date key made its YYYY-MM-DD on the turn's `today`."""
    out = dict(args or {})
    for key in DATE_KEYS:
        value = out.get(key)
        if isinstance(value, str) and value.strip().lower() in WORDS:
            out[key] = parse(value, today).isoformat()
    return out


def _single_day(args: dict) -> tuple[tuple[str, ...], date] | None:
    def iso(key):
        try:
            return date.fromisoformat(str(args.get(key, "")).strip())
        except ValueError:
            return None

    ranged = "from" in args or "to" in args
    if ranged and "date" in args:
        return None
    if ranged:
        start = iso("from")
        return (("from", "to"), start) if start is not None and start == iso("to") else None
    day = iso("date")
    return (("date",), day) if day is not None else None


def corrected(
    name: str, args: dict, words: str | None, day: date, at: time, only_date_call: bool
) -> tuple[dict, str] | None:
    """`args` with its one day, one off the day he said, put back to that day, and the note; None
    when nothing changes. `day` and `at`: the turn's local date and time when it started."""
    if name not in DATE_TOOLS or not only_date_call:
        return None
    said = said_day(words, day, at)
    if said is None:
        return None
    single = _single_day(args)
    if single is None:
        return None
    keys, asked = single
    if abs((asked - said).days) != 1:
        return None
    fixed = dict(args)
    for key in keys:
        fixed[key] = said.isoformat()
    return fixed, NOTE.format(label=label(said))
