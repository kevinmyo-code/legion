"""`GET /api/board/queue`: what waits on him, counted, with the words a voice says (control room
C4), DARK with the rest of the board (`board.flags`).

Three counts, each from its own source, all on the engine's clock:
- **sessions**: his household's Claude Code sessions reported `waiting` in the last day (the
  Claude session status screen's own rule).
- **approvals**: Claude tasks he asked that stopped for his yes (`needs_confirmation`, the last
  week) with no yes sent yet, plus action records waiting on his tap (only while
  `LEGION_BOARD_ACTIONS` is on).
- **decisions**: board questions he may answer on the phone that have no answer yet.

`items` gives each waiting thing a stable key (a session's wait, a task, a record, a question by
its hash), so the phone raises each one once (`QueuePoller`). `spoken` and `line` are written by
code from the counts ONLY: no card, task or session text is ever in them, so a push or a spoken
line never carries words an agent or a web page wrote.
"""

from __future__ import annotations

from datetime import timedelta

from django.utils import timezone

from board import actions as board_actions
from board import asks, flags, reading

APPROVALS_WITHIN = timedelta(days=7)
# The phone's `ClaudeSessionController.RECENT`: how many tasks its screens read.
SHOWN_TASKS = 20
SESSIONS_WITHIN = timedelta(hours=24)
NOTHING = "Nothing needs you right now."


def _plural(n: int, word: str) -> str:
    return f"{n} {word}{'' if n == 1 else 's'}"


def line(counts: dict[str, int]) -> str:
    parts = [
        _plural(counts["sessions"], "session"),
        _plural(counts["approvals"], "approval"),
        _plural(counts["decisions"], "decision"),
    ]
    if not any(counts.values()):
        return NOTHING
    return "Needs you: " + ", ".join(parts) + "."


def _sessions(request, now) -> list[str]:
    from claude_session.models import SESSION_WAITING, ClaudeCodeSession
    from household.tenancy import scoped

    rows = scoped(ClaudeCodeSession, request).filter(
        state=SESSION_WAITING, last_seen_at__gte=now - SESSIONS_WITHIN
    )
    return [f"session:{r.session_id or r.pk}@{_epoch(r.waiting_since)}" for r in rows]


def _epoch(when) -> int:
    return int(when.timestamp()) if when is not None else 0


def _approvals(request, now) -> list[str]:
    from claude_session.models import NEEDS_CONFIRMATION, ClaudeTask
    from household.tenancy import scoped

    tasks = scoped(ClaudeTask, request)
    # The same window the control room's Approvals tab reads (the household's newest tasks), so
    # the home count never names a card he cannot see and clear there.
    newest = list(tasks.order_by("-created_at", "-id").values_list("id", flat=True)[:SHOWN_TASKS])
    asked = tasks.filter(
        id__in=newest,
        user=request.user,
        status=NEEDS_CONFIRMATION,
        created_at__gte=now - APPROVALS_WITHIN,
    )
    yes_sent = set(
        tasks.filter(confirms=True, parent__in=asked).values_list("parent_id", flat=True)
    )
    keys = [f"task:{t}" for t in asked.values_list("id", flat=True) if t not in yes_sent]
    if flags.actions_enabled():
        keys += [
            f"action:{a.id}"
            for a in board_actions.open_for(request.user, now)
            if a.status == "pending" and a.expires_at > now
        ]
    return keys


def _decisions(request, now) -> list[str]:
    r = reading.read(request, now)
    cards = asks.decorate(reading.cards_now(r), request.user)
    return [
        f"decision:{c['id']}:{c['ask_sha256'][:12]}"
        for c in cards
        if c["answerable"] and not c["answer_pending"]
    ]


def build(request, now=None) -> dict:
    now = now or timezone.now()
    groups = {
        "sessions": _sessions(request, now),
        "approvals": _approvals(request, now),
        "decisions": _decisions(request, now),
    }
    counts = {k: len(v) for k, v in groups.items()}
    said = line(counts)
    return {
        "counts": counts,
        "line": said,
        "spoken": [said],
        "items": [{"key": key, "kind": kind} for kind, keys in groups.items() for key in keys],
    }
