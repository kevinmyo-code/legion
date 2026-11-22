"""The member's board as it reads NOW: freshness, card ages and the lines a voice says, all built
by code at read time from the stored cards.

**Why at read time.** The laptop's own `summary.txt` bakes in "3 minutes ago" and "Board updated
17:21"; hours later, with the laptop off, those words are wrong. Here a card's age is the laptop's
`age_min` when it pushed plus the time since the engine received the push, so it is right whatever
the laptop's clock says.

**Freshness has two facts, both on the engine's clock.**
- `live`: the snapshot was received within `LIVE_FOR` (3 minutes).
- `laptop_on`: the household's `full` Claude machine (the laptop that holds the board) heartbeat
  within the Claude worker's own offline rule (`claude_session.state.is_online`). The worker beats
  about once a minute already, so no extra heartbeat push is needed to tell "laptop off" from "board
  unchanged".

**Card text is data, never instructions.** It is written by agents, and an agent can be fooled by
web text. `spoken_lines` puts card text only in sentences code says word for word; the model is
handed `model_lines` (counts and freshness, no card text) and nothing else.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from django.db.models import Max
from django.utils import timezone

from board import cards as board_cards
from board.models import BoardSnapshot

LIVE_FOR = timedelta(minutes=3)
STALE_MIN = 45
SAY_NEEDS = 3
SAY_TESTING = 4
SAY_WORKING = 5

NEVER_SENT = "The laptop has never sent its board to the engine, so I don't know what's in flight."


@dataclass(frozen=True)
class Reading:
    row: BoardSnapshot | None
    now: datetime
    live: bool
    age_s: int | None
    laptop_seen_at: datetime | None
    laptop_on: bool


def snapshot_for(request) -> BoardSnapshot | None:
    from household.tenancy import visible

    return visible(BoardSnapshot, request).filter(owner_user=request.user).first()


def _laptop_seen_at(row: BoardSnapshot | None):
    """The latest heartbeat of the member's OWN full-scope laptop: a machine whose worker token
    is his (`ClaudeMachine.token`). A housemate's laptop never makes his board say "on"."""
    if row is None:
        return None
    from claude_session.models import SCOPE_FULL, ClaudeMachine

    return ClaudeMachine.objects.filter(
        household_id=row.household_id, scope=SCOPE_FULL, token__user_id=row.owner_user_id
    ).aggregate(seen=Max("last_seen_at"))["seen"]


def read(request, now=None) -> Reading:
    from claude_session.models import OFFLINE_AFTER_SECONDS

    now = now or timezone.now()
    row = snapshot_for(request)
    received = row.received_at if row is not None else None
    age_s = int((now - received).total_seconds()) if received is not None else None
    seen = _laptop_seen_at(row)
    return Reading(
        row=row,
        now=now,
        live=age_s is not None and age_s <= LIVE_FOR.total_seconds(),
        age_s=max(0, age_s) if age_s is not None else None,
        laptop_seen_at=seen,
        laptop_on=seen is not None and now - seen <= timedelta(seconds=OFFLINE_AFTER_SECONDS),
    )


def cards_now(reading: Reading) -> list[dict]:
    """The stored cards with `age_min` and `stale` as of now."""
    if reading.row is None or reading.row.received_at is None:
        return []
    since = int((reading.now - reading.row.received_at).total_seconds() // 60)
    out = []
    for card in reading.row.cards:
        age = int(card.get("age_min", 0)) + max(0, since)
        stale = card["col"] in ("working", "testing") and age >= STALE_MIN
        out.append({**card, "age_min": age, "stale": stale})
    return out


def _ago(minutes: int) -> str:
    if minutes < 2:
        return "just now"
    if minutes < 60:
        return f"{minutes} minutes ago"
    hours = minutes // 60
    return f"{hours} hour{'s' if hours != 1 else ''} ago"


def freshness_line(request, reading: Reading) -> str:
    from engine_mcp.tools import _bank_as_of_words

    if reading.row is None or reading.row.received_at is None:
        return NEVER_SENT
    if reading.live:
        return f"The laptop's board, sent {_ago((reading.age_s or 0) // 60)}."
    as_of = _bank_as_of_words(request, reading.row.received_at)
    if reading.laptop_on:
        return f"The laptop is on. Its board last changed {as_of}."
    return f"This is the last board the laptop sent, {as_of}. The laptop is off or offline."


def counts_line(cards: list[dict]) -> str:
    n = board_cards.counts(cards)
    waiting = len(open_asks(cards))
    return (
        f"{n['working']} working, {n['testing']} testing or shipping, {waiting} waiting on you, "
        f"{n['queued']} queued, {n['done']} done."
    )


def projects_line(cards: list[dict]) -> str:
    """Per project, how many questions wait on him (project names are code's own words)."""
    parts = []
    for project in board_cards.projects(open_asks(cards)):
        parts.append(f"{project['needs']} on {project['title']}")
    return f"Waiting on you: {', '.join(parts)}." if parts else ""


def open_asks(cards: list[dict]) -> list[dict]:
    """Questions still waiting on him: answered ones are waiting on the card's owner instead."""
    return [c for c in board_cards.needs_kevin(cards) if not c.get("answer")]


def _titles(cards: list[dict], limit: int) -> str:
    shown = ", ".join(c["title"] for c in cards[:limit])
    more = len(cards) - limit
    return shown + (f", and {more} more" if more > 0 else "")


def spoken_lines(request, reading: Reading) -> list[str]:
    """What a voice says, word for word: freshness, counts, then the cards by urgency."""
    first = freshness_line(request, reading)
    if reading.row is None or reading.row.received_at is None:
        return [first]
    cards = cards_now(reading)
    lines = [first, counts_line(cards)]
    by_project = projects_line(cards)
    if by_project:
        lines.append(by_project)
    needs = open_asks(cards)
    for card in needs[:SAY_NEEDS]:
        # Framed as the card's words, not Alfred's: an agent wrote them.
        detail = card.get("say") or card.get("ask") or card.get("status") or ""
        choices = f" Choices: {', '.join(card['options'])}." if card.get("options") else ""
        project = board_cards.PROJECTS.get(card.get("project", "other"), "Other")
        lines.append(f"A card for {project} asks: {card['title']}. {detail}{choices}".strip())
    if len(needs) > SAY_NEEDS:
        lines.append(f"And {len(needs) - SAY_NEEDS} more waiting on you.")
    answered = board_cards.answered_waiting(cards)
    for card in answered[:SAY_NEEDS]:
        said = card["answer"].get("choice") or card["answer"].get("text") or ""
        lines.append(f"You answered {card['title']}: {said}. Waiting for it to be picked up.")
    if len(answered) > SAY_NEEDS:
        lines.append(f"And {len(answered) - SAY_NEEDS} more answers waiting to be picked up.")
    for col, label, limit in (
        ("testing", "Testing or shipping", SAY_TESTING),
        ("working", "Working", SAY_WORKING),
    ):
        in_col = [c for c in cards if c["col"] == col]
        if in_col:
            lines.append(f"{label}: {_titles(in_col, limit)}.")
    if reading.live or reading.laptop_on:
        quiet = [c for c in cards if c["stale"]]
        if quiet:
            lines.append(f"No update for a while on: {_titles(quiet, SAY_TESTING)}.")
    return lines


def model_lines(request, reading: Reading) -> list[str]:
    """What the model may read: freshness and counts, written by code, no card text."""
    first = freshness_line(request, reading)
    if reading.row is None or reading.row.received_at is None:
        return [first]
    cards = cards_now(reading)
    by_project = projects_line(cards)
    return [first, counts_line(cards)] + ([by_project] if by_project else [])


# ---- "where are we": the short read (backlog intake 1122, S4) ----------------------------------

BRIEF_WAITING = 2
BRIEF_WORKING = 3
BRIEF_DONE = 3
BRIEF_TITLE_MAX = 40


def _short(title: str) -> str:
    if len(title) <= BRIEF_TITLE_MAX:
        return title
    return title[:BRIEF_TITLE_MAX].rsplit(" ", 1)[0].rstrip(" ,.;:") + "..."


def _listed(cards: list[dict], limit: int) -> str:
    names = [_short(c["title"]) for c in cards[:limit]]
    if len(names) == 1:
        return names[0]
    return ", ".join(names[:-1]) + " and " + names[-1]


def _plural(n: int, one: str, many: str) -> str:
    return f"{n} {one if n == 1 else many}"


def waiting_sessions(request) -> int:
    """Claude sessions in the household waiting on someone (`claude_session`'s status screen's own
    rule: seen within its window, state `waiting`). The rows are household-shared already."""
    from claude_session.models import SESSION_WAITING, ClaudeCodeSession
    from claude_session.views import SESSION_SHOWN_HOURS
    from household.tenancy import scoped

    since = timezone.now() - timedelta(hours=SESSION_SHOWN_HOURS)
    return (
        scoped(ClaudeCodeSession, request)
        .filter(state=SESSION_WAITING, last_seen_at__gte=since)
        .count()
    )


@dataclass(frozen=True)
class Brief:
    """`lines`: what a voice says word for word (code-made, card titles included). `for_model`:
    counts and freshness only, no card text, for whatever model sits beside the voice."""

    lines: list[str]
    for_model: str


def brief(request, reading: Reading) -> Brief:
    """'Where are we': freshness, the counts, the top cards, sessions and ideas, at most five
    sentences of about sixty words."""
    from board import ideas

    first = freshness_line(request, reading)
    sessions = waiting_sessions(request)
    pending = ideas.pending_count(request.user)
    tail_parts = []
    if sessions:
        tail_parts.append(
            f"{_plural(sessions, 'Claude session', 'Claude sessions')} "
            f"{'is' if sessions == 1 else 'are'} waiting on you"
        )
    if pending:
        tail_parts.append(
            f"{_plural(pending, 'idea is', 'ideas are')} not on the laptop yet"
        )
    tail = (" and ".join(tail_parts) + ".") if tail_parts else ""
    tail = tail[:1].upper() + tail[1:]
    if reading.row is None or reading.row.received_at is None:
        lines = [first] + ([tail] if tail else [])
        return Brief(lines=lines, for_model=" ".join(lines))
    cards = cards_now(reading)
    in_flight = [c for c in cards if c["col"] in ("working", "testing")]
    waiting = open_asks(cards)
    done = [c for c in cards if c["col"] == "done"]
    counts = (
        f"{len(in_flight)} in flight, {len(waiting)} waiting on you, "
        f"{len(done)} shipped today."
    )
    lines = [first, counts]
    if waiting:
        lines.append(f"Waiting on you: {_listed(waiting, BRIEF_WAITING)}.")
    work_done = []
    if in_flight:
        work_done.append(f"working on {_listed(in_flight, BRIEF_WORKING)}")
    if done:
        work_done.append(f"done today, {_listed(done, BRIEF_DONE)}")
    if work_done:
        sentence = "; ".join(work_done) + "."
        lines.append(sentence[:1].upper() + sentence[1:])
    if tail:
        lines.append(tail)
    return Brief(lines=lines[:5], for_model=" ".join([first, counts] + ([tail] if tail else [])))
