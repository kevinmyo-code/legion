"""The web assistant's system prompt, assembled by the engine (web-assistant ticket 03).

Ticket 03: "The web prompt is assembled by the engine, not the browser, and
baked into the locked token's setup, so a browser cannot alter it." This is
the assembly. Order, and why:

1. **Who is speaking**: the member's companion register, then its delivery
   (accent and idiom). The phone's order (`AriaBrain.assembleBase`).
2. **What this client is**: the web frame below - how the person reaches it,
   what it can see, and what only the phone app can do (ticket 04: "the web
   prompt names these ... and the assistant says so in words instead of
   pretending").
3. **The shared clauses**, from `shared_clauses.txt` through the generated
   `shared_clauses.py`, in source order. The crisis clause is last of these:
   it overrides everything before it.
4. **Facts, not instructions**: when the conversation started on the
   person's own clock (a UTC offset, never an IANA zone id - CLAUDE.md
   section 1), and who is speaking.

Nothing here names an assistant (CLAUDE.md section 1: the persona supplies the
name) or calls the person "the driver" (the concierge frame, CLAUDE.md
section 1). `tests/test_assistant_prompt.py` holds both.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta, timezone

from assistant import shared_clauses
from assistant.companions import ResolvedCompanion

# What only the phone can do, in words a person says. Ticket 04, "never on the
# web": mail (read-through only and never on the server, CLAUDE.md section 7),
# the car's live data, music, navigation, calls, alarms, the generated views.
PHONE_ONLY = (
    "music and Spotify",
    "mail",
    "the car's live readings and its diagnostic dongle",
    "navigation and directions",
    "the garage door",
    "phone calls and texts",
    "alarms and timers",
    "anything shown on the phone's own screen",
)

WEB_FRAME = (
    "You are speaking through the household's web app, by voice or in a typed chat; a typed "
    "message still gets a spoken answer, and your words appear on screen. Everything you know "
    "about the household comes from the engine's tools, and they show you only what this person "
    "may see: a row another member keeps private is invisible to you, so never guess at one. "
    f"Some things work only in LEGION's Android phone app, never from here: "
    f"{', '.join(PHONE_ONLY)}. If asked for one, say plainly that it can only be done in the "
    "phone app - never pretend to do it - and offer what you can do here. You have no web "
    "search here either, so you cannot look up news, weather, scores or prices; say so rather "
    "than guessing. Nothing said in this conversation is kept after it ends, and you have no "
    "memory of earlier ones. "
    "Every tool result says in words what happened; repeat it, and never report a write as done "
    "unless its result says it was committed. Rows marked UNVERIFIED and values marked "
    "ESTIMATES must be said as such, never as fact. A tick on a checklist is a tap, not a "
    'purchase: last_ticked tells you when a line was ticked, and you say "ticked", never '
    '"bought". The bought log (last_bought, list_purchases) is where purchases are recorded; its '
    "prices were typed by hand, so say so. "
)

MAX_OFFSET_MINUTES = 14 * 60
MIN_OFFSET_MINUTES = -12 * 60


def utc_offset_words(minutes: int) -> str:
    """`-300` -> `UTC-05:00`. The phone's `utcOffset` shape."""
    sign = "-" if minutes < 0 else "+"
    hours, mins = divmod(abs(minutes), 60)
    return f"UTC{sign}{hours:02d}:{mins:02d}"


def _clock_words(when: datetime) -> str:
    hour = when.hour % 12 or 12
    return f"{when:%A, %B} {when.day}, {when.year} at {hour}:{when.minute:02d} {when:%p}"


def time_fact(
    now: datetime, utc_offset_minutes: int | None, *, from_household: bool = False
) -> str:
    """The clock sentence. `from_household` is True when the offset is the
    household's (its owner set a timezone and the browser sent no offset of
    its own), so the sentence says whose clock it is rather than claiming the
    person's. Either way it is an OFFSET: a zone's name never reaches here."""
    if utc_offset_minutes is None:
        start = now.astimezone(UTC)
        return (
            f"This conversation started on {_clock_words(start)} UTC. The person's own UTC "
            f"offset is unknown, so do not convert to a local time and do not guess where they "
            f"are; if their local time matters, ask."
        )
    local = now.astimezone(timezone(timedelta(minutes=utc_offset_minutes)))
    whose = "the household's clock" if from_household else "the person's clock"
    return (
        f"This conversation started on {_clock_words(local)} on {whose}, which "
        f"reads {utc_offset_words(utc_offset_minutes)}. You have no clock of your own: the time "
        f"now is up to half an hour later than that, so give any time as approximate and never "
        f"invent one. You do not know where they are; do not guess a city."
    )


def member_fact(first_name: str) -> str:
    name = (first_name or "").strip()
    if not name:
        return "You do not know this person's name; do not invent one."
    return f"You are speaking with {name}, a member of this household."


def web_shared_clauses() -> str:
    return "".join(shared_clauses.CLAUSES[name] for name in shared_clauses.WEB_ORDER)


def build_system_prompt(
    *,
    companion: ResolvedCompanion,
    first_name: str,
    now: datetime,
    utc_offset_minutes: int | None,
    offset_from_household: bool = False,
) -> str:
    who = companion.clause
    if companion.delivery:
        who += " " + companion.delivery
    body = f"{who}\n\n{WEB_FRAME}{web_shared_clauses()}".rstrip()
    clock = time_fact(now, utc_offset_minutes, from_household=offset_from_household)
    return f"{body}\n\n{clock} {member_fact(first_name)}"
