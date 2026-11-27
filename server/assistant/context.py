"""The working context of an open web conversation (#475): transient, never a record.

The web cascade (ADR 0102) is one HTTP request per turn, so the model's memory of the conversation
has to live somewhere between requests. It used to be the conversation record itself
(`devicelogs.web_conversation`), which kept the assistant's replies, and a reply built on a web
search, the brief's news or a GitHub title is text other people wrote (CLAUDE.md section 7). The
record now keeps only his words, tool names with how they ended, times and markers. Everything
the next turn needs to carry the conversation on lives here instead (`TurnContext`):

- his words, each reply as it was said (written sentence by sentence, so a barge-in never finds
  the reply missing: the echo check compares against it), what history replays in a reply's
  place, the tools a turn ran (by name, with whether they went through), and where he spoke over
  a reply.

**Why this is read-through compliant.** Section 7 allows outside text to be read to answer a
question and then dropped; what it forbids is persisting it. These rows are that reading, kept
for the conversation that is still going on and for nothing else:

- only the live turn of the same conversation reads them (`history`, `last_reply`);
- a conversation's rows are deleted `TTL` after its last turn (enforced at every read, so a late
  sweep never hands over an old line), when the page ends the conversation (`end`), and by the
  hourly sweep (`expire`, in `prune_demo_visitors`), so nothing outlives an idle conversation by
  much more than an hour;
- they are never part of a durable record, the nightly backup (`ingest.backup.TRANSIENT_TABLES`
  dumps the table's shape and none of its rows) or any export;
- a conversation that goes on keeps only the turns `history` can replay (`KEEP_TURNS`): older
  rows are deleted as new ones arrive.
"""

from __future__ import annotations

import datetime
import logging

from django.db import DatabaseError, transaction
from django.db.models import Max
from django.utils import timezone

log = logging.getLogger(__name__)

TTL = datetime.timedelta(minutes=30)
# More than `cascade.HISTORY_TURNS`; a conversation never holds more than this many of his turns.
KEEP_TURNS = 12
CUT_OFF_NOTE = "(They spoke over this reply and stopped it here.)"


def _model():
    from assistant.models import TurnContext

    return TurnContext


def _row(request, conversation_id):
    from devicelogs.web_conversation import owned

    return owned(request, conversation_id) if conversation_id is not None else None


def _write(request, conversation_id, make) -> object | None:
    """Run `make(row)` on the caller's own conversation. Silent when it is not theirs or the
    database refuses: the turn has happened and must not fail on its context."""
    try:
        with transaction.atomic():
            row = _row(request, conversation_id)
            return make(row) if row is not None else None
    except DatabaseError:
        log.warning("turn context: could not write", exc_info=True)
        return None


def _trim(row) -> None:
    """Keep only the last KEEP_TURNS of his turns, and what came after the first of them."""
    TurnContext = _model()
    starts = list(
        TurnContext.objects.filter(conversation=row, kind=TurnContext.USER)
        .order_by("-at", "-id")
        .values_list("at", flat=True)[: KEEP_TURNS + 1]
    )
    if len(starts) > KEEP_TURNS:
        TurnContext.objects.filter(conversation=row, at__lte=starts[-1]).delete()


def add_user(request, conversation_id, at: datetime.datetime, words: str) -> None:
    TurnContext = _model()

    def make(row):
        TurnContext.objects.create(conversation=row, kind=TurnContext.USER, text=words, at=at)
        _trim(row)

    _write(request, conversation_id, make)


def add_tool(request, conversation_id, at: datetime.datetime, name: str, ok: bool) -> None:
    TurnContext = _model()
    _write(
        request,
        conversation_id,
        lambda row: TurnContext.objects.create(
            conversation=row, kind=TurnContext.TOOL, name=name, ok=ok, at=at
        ),
    )


def add_interrupted(request, conversation_id, at: datetime.datetime) -> None:
    TurnContext = _model()
    _write(
        request,
        conversation_id,
        lambda row: TurnContext.objects.create(
            conversation=row, kind=TurnContext.INTERRUPTED, at=at
        ),
    )


class Reply:
    """One turn's reply, written as it is said: the first sentence creates the row, each later
    one and the turn's end update it. A barge-in that arrives while the reply plays reads it."""

    def __init__(self, request, conversation_id):
        self.request, self.conversation_id = request, conversation_id
        self.pk = None

    def said(self, text: str, replay: str | None = None) -> None:
        text = (text or "").strip()
        if not text and self.pk is None:
            return
        TurnContext = _model()

        def make(row):
            if self.pk is None:
                # Stamped at its first sentence: after his words and the tools the turn ran.
                self.pk = TurnContext.objects.create(
                    conversation=row,
                    kind=TurnContext.MODEL,
                    text=text,
                    replay=replay,
                    at=timezone.now(),
                ).pk
            else:
                TurnContext.objects.filter(pk=self.pk, conversation=row).update(
                    text=text, replay=replay
                )

        _write(self.request, self.conversation_id, make)


def _live(row, now: datetime.datetime | None = None) -> list:
    """The conversation's rows in order, or none at all when its last turn is older than TTL
    (they are deleted then and there: a late sweep never hands over an old line)."""
    TurnContext = _model()
    rows = list(TurnContext.objects.filter(conversation=row).order_by("at", "id"))
    if rows and (now or timezone.now()) - rows[-1].at >= TTL:
        TurnContext.objects.filter(conversation=row).delete()
        return []
    return rows


def history(
    row, *, max_turns: int, opened_line: str = "", writes: frozenset[str] = frozenset()
) -> list[dict]:
    """The last `max_turns` exchanges as model contents: his words as `user`, each reply (or what
    replays in its place) as `model`, in time order. Tool calls and results are not replayed (the
    model asks again when it needs a figure), except a change that went through, noted as done,
    so a repeated ask is not made twice; a reply he spoke over is marked cut off."""
    turns: list[dict] = []

    def note(words: str) -> None:
        if turns and turns[-1]["role"] == "model":
            turns[-1]["parts"][0]["text"] += " " + words
        else:
            turns.append({"role": "model", "parts": [{"text": words}]})

    for item in _live(row):
        if item.kind == "tool":
            if item.ok and item.name in writes:
                note(f"(Done: {item.name}.)")
            continue
        if item.kind == "interrupted":
            if turns and turns[-1]["role"] == "model":
                said = turns[-1]["parts"][0]
                if not said["text"].endswith(CUT_OFF_NOTE):
                    said["text"] += " " + CUT_OFF_NOTE
            continue
        role = "user" if item.kind == "user" else "model"
        words = item.replay if role == "model" and item.replay is not None else item.text
        if not words.strip():
            continue
        if turns and turns[-1]["role"] == role:
            turns[-1]["parts"][0]["text"] += " " + words
        else:
            turns.append({"role": role, "parts": [{"text": words}]})
    kept: list[dict] = []
    seen = 0
    for content in reversed(turns):
        kept.append(content)
        if content["role"] == "user":
            seen += 1
            if seen >= max_turns:
                break
    kept.reverse()
    if kept and kept[0]["role"] != "user":
        # The conversation opened with the assistant's greeting (ADR 0102): the page's open
        # stands in as the turn it answered, so contents still start with a user turn.
        if opened_line:
            kept.insert(0, {"role": "user", "parts": [{"text": opened_line}]})
        else:
            while kept and kept[0]["role"] != "user":
                kept.pop(0)
    return kept


def last_reply(row) -> str:
    """The assistant's most recent reply, as it was said: the one that was (or is still) playing,
    which the echo check compares a barge-in against."""
    for item in reversed(_live(row)):
        if item.kind == "model" and item.text.strip():
            return item.text
    return ""


def end(row) -> int:
    """The page ended the conversation: its context goes now."""
    return _model().objects.filter(conversation=row).delete()[0]


def expire(now: datetime.datetime | None = None) -> int:
    """Delete every conversation's context whose last turn is TTL old. Returns rows deleted."""
    TurnContext = _model()
    cutoff = (now or timezone.now()) - TTL
    stale = (
        TurnContext.objects.values("conversation")
        .annotate(last=Max("at"))
        .filter(last__lt=cutoff)
        .values("conversation")
    )
    return TurnContext.objects.filter(conversation__in=stale).delete()[0]
