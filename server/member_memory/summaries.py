"""A short summary of each conversation, written the moment it ends (issue #451).

Kevin, 2026-11-25: past chats were not summarized. The hourly `consolidate_memories` job writes at
most five facts per record, never what was talked about, so a later chat could not pick up the
thread. Now, when a chat ends, by hand or by timeout, the client says so
(`POST /api/member-memories/turns/end`) and the engine writes a few sentences on it at once: the
topics, what was decided, anything promised. A chat whose end never arrives (the app was killed,
the phone lost signal for good) is summarized by `sweep` once its turns have been quiet for
`IDLE_MINUTES`: on the member's next chat end, and on every hourly `consolidate_memories` run.

## What the model is shown (CLAUDE.md section 7, the same rules as `consolidate.py`)

Only the conversation's saved turns (`ConversationTurn`, issue #450), so nothing a turn row may not
hold reaches the prompt: no tool argument or result, no turn that read third-party text, no
redacted line, no vault-looking number, nothing of a crisis conversation (its turns are deleted).
From those turns, **the person's words, and the reply only from a turn that called no tool**: a
tool turn's reply may be quoting the tool, exactly as consolidation reads a record. A turn carrying
the redaction marker is skipped whole, again, in case one ever got in.

The summary itself is checked before it is kept: one that looks like it holds an identity or bank
number, or a crisis phrase, is not stored.

## Failures

No key, an unreachable model or an unreadable answer stores nothing and says why in words; the
sweep tries again on a later run while the chat is inside `SWEEP_LOOKBACK_HOURS`. Nothing here
logs or stores transcript text beyond the summary row.

`consolidate._transport` is the one call to Google, so the test suite's stub for consolidation
covers this module too (`tests/conftest.py`).
"""

from __future__ import annotations

import json
import urllib.error
from dataclasses import dataclass
from datetime import timedelta

from django.db import transaction
from django.db.models import Count, Max, Min
from django.db.models.functions import Now
from django.utils import timezone

from agents.voice.crisis import detect as is_crisis
from ingest import statements
from llm.client import retrying
from member_memory import consolidate
from member_memory.models import (
    SUMMARY_ENDED_IDLE,
    SUMMARY_TEXT_MAX_CHARS,
    ConversationSummary,
    ConversationTurn,
)
from member_memory.sensitive import looks_sensitive

IDLE_MINUTES = 10  # a chat with no turn for this long is over, whether or not its end arrived
SWEEP_LOOKBACK_HOURS = 48  # older chats are left alone, so a failing one is not retried forever
SWEEP_LIMIT = 50  # per household, per hourly run
SWEEP_ON_END = 1  # the caller's own idle chat summarized after the one that just ended
MAX_TRANSCRIPT_CHARS = consolidate.MAX_TRANSCRIPT_CHARS

NOT_WRITTEN_NO_TURNS = "no_turns"
NOT_WRITTEN_NO_WORDS = "no_words"
NOT_WRITTEN_NO_KEY = "no_key"
NOT_WRITTEN_FAILED = "failed"
NOT_WRITTEN_SENSITIVE = "sensitive"
NOT_WRITTEN_FORGOTTEN = "forgotten"

REASONS: dict[str, str] = {
    NOT_WRITTEN_NO_TURNS: "No summary: no turn of this conversation was saved.",
    NOT_WRITTEN_NO_WORDS: "No summary: the conversation had none of the person's words to read.",
    NOT_WRITTEN_NO_KEY: (
        f"No summary: {statements.GEMINI_KEY_ENV} is not set, so no model can be asked."
    ),
    NOT_WRITTEN_FAILED: "No summary yet: the model gave no usable answer. It is tried again later.",
    NOT_WRITTEN_SENSITIVE: "No summary: it looked like it held something that is never stored.",
    NOT_WRITTEN_FORGOTTEN: "No summary: the conversation was forgotten while it was being read.",
}

PROMPT = """You write a short note on one finished conversation between a person and their
household assistant, so a later conversation can pick up where this one left off.

In two to four plain sentences, in the third person, say what was talked about, what was decided,
and anything the person or the assistant promised to do. Use only what is in the conversation
below; never guess, never add advice. Leave out small talk, greetings and opinions about the
assistant. Never write an identity or bank number (passport, NRIC, SSN, A-number, account or card
number). If nothing of substance was said, say so in one short sentence.

Conversation:
{transcript}
"""


@dataclass(frozen=True)
class Outcome:
    written: bool
    reason: str | None = None
    row: ConversationSummary | None = None
    unchanged: bool = False

    @property
    def sentence(self) -> str | None:
        return REASONS.get(self.reason) if self.reason else None


def _redacted(text: str) -> bool:
    return consolidate._redacted(text)


def transcript(turns: list[ConversationTurn]) -> str:
    """The person's words and the tool-free replies, in turn order (module doc)."""
    lines: list[str] = []
    for row in turns:
        if _redacted(row.user_text) or _redacted(row.assistant_text):
            continue
        if row.user_text:
            lines.append(f"User: {row.user_text}")
        if row.assistant_text and not row.tools:
            lines.append(f"Assistant: {row.assistant_text}")
    return "\n".join(lines)[-MAX_TRANSCRIPT_CHARS:]


def request_body(text: str) -> bytes:
    return json.dumps(
        {
            "contents": [{"role": "user", "parts": [{"text": PROMPT.format(transcript=text)}]}],
            "generationConfig": {
                "responseMimeType": "application/json",
                "responseSchema": {
                    "type": "OBJECT",
                    "properties": {"summary": {"type": "STRING"}},
                    "required": ["summary"],
                },
                "temperature": 0,
            },
        }
    ).encode()


def parse(payload: dict) -> str:
    """The summary text from Gemini's answer. Raises `consolidate.ConsolidationFailed`."""
    candidates = payload.get("candidates") if isinstance(payload, dict) else None
    candidate = candidates[0] if isinstance(candidates, list) and candidates else {}
    content = candidate.get("content") if isinstance(candidate, dict) else None
    parts = content.get("parts") if isinstance(content, dict) else None
    text = "".join(
        p["text"]
        for p in (parts if isinstance(parts, list) else [])
        if isinstance(p, dict) and isinstance(p.get("text"), str) and p.get("thought") is not True
    )
    try:
        answer = json.loads(text)
    except ValueError:
        raise consolidate.ConsolidationFailed("The model's answer was not JSON.") from None
    summary = answer.get("summary") if isinstance(answer, dict) else None
    if not isinstance(summary, str) or not summary.strip():
        raise consolidate.ConsolidationFailed("The model's answer had no summary.")
    summary = " ".join(summary.split())
    if len(summary) > SUMMARY_TEXT_MAX_CHARS:
        summary = summary[: SUMMARY_TEXT_MAX_CHARS - 3].rstrip() + "..."
    return summary


def _sender(transport):
    if transport is not None:
        return transport
    # Looked up at call time, so the test suite's stub of `consolidate._transport` holds here.
    return retrying(lambda *a: consolidate._transport(*a), attempts=2)


def summarize(
    *,
    household_id,
    member_id,
    session: str,
    ended_by: str,
    key: str | None = None,
    transport=None,
) -> Outcome:
    """Writes (or rewrites) the summary of one of a member's conversations from its saved turns."""
    turns = list(
        ConversationTurn.objects.filter(
            household_id=household_id, member_id=member_id, session=session
        ).order_by("turn")
    )
    if not turns:
        return Outcome(written=False, reason=NOT_WRITTEN_NO_TURNS)
    existing = ConversationSummary.objects.filter(member_id=member_id, session=session).first()
    if existing is not None and existing.turns_read >= len(turns):
        return Outcome(written=True, row=existing, unchanged=True)
    text = transcript(turns)
    if not any(line.startswith("User: ") for line in text.splitlines()):
        return Outcome(written=False, reason=NOT_WRITTEN_NO_WORDS)
    key = key if key is not None else statements.gemini_key()
    if not key:
        return Outcome(written=False, reason=NOT_WRITTEN_NO_KEY)
    send = _sender(transport)
    try:
        response = send(
            "POST",
            statements.GEMINI_URL,
            {"Content-Type": "application/json", "x-goog-api-key": key},
            request_body(text),
        )
        if response.status != 200:
            return Outcome(written=False, reason=NOT_WRITTEN_FAILED)
        summary = parse(response.json())
    except (
        TimeoutError,
        urllib.error.URLError,
        ConnectionError,
        OSError,
        ValueError,
        UnicodeDecodeError,
        consolidate.ConsolidationFailed,
    ):
        return Outcome(written=False, reason=NOT_WRITTEN_FAILED)
    if looks_sensitive(summary) or is_crisis(summary):
        return Outcome(written=False, reason=NOT_WRITTEN_SENSITIVE)

    with transaction.atomic():
        # A crisis phrase or a forget may have deleted the turns while the model was asked:
        # then nothing of the conversation is kept, its summary included.
        still = ConversationTurn.objects.filter(member_id=member_id, session=session).count()
        if still < len(turns):
            ConversationSummary.objects.filter(member_id=member_id, session=session).delete()
            return Outcome(written=False, reason=NOT_WRITTEN_FORGOTTEN)
        row, _created = ConversationSummary.objects.update_or_create(
            member_id=member_id,
            session=session,
            defaults={
                "household_id": household_id,
                "owner_user_id": member_id,
                "source": turns[-1].source,
                "text": summary,
                "ended_by": ended_by,
                "turns_read": len(turns),
                "started_at": turns[0].spoken_at,
                "ended_at": max(row.spoken_at for row in turns),
                "updated_at": Now(),
            },
        )
    row.refresh_from_db()
    return Outcome(written=True, row=row)


def idle_sessions(
    *, household=None, member=None, exclude_session=None, now=None, limit=SWEEP_LIMIT
):
    """Conversations quiet for `IDLE_MINUTES`, inside the lookback, with no summary or one that
    read fewer turns than are saved now: `(household_id, member_id, session)`, oldest first."""
    now = now or timezone.now()
    rows = ConversationTurn.objects.all()
    if household is not None:
        rows = rows.filter(household=household)
    if member is not None:
        rows = rows.filter(member=member)
    if exclude_session:
        rows = rows.exclude(session=exclude_session)
    grouped = (
        rows.values("household_id", "member_id", "session")
        .annotate(last=Max("spoken_at"), first=Min("spoken_at"), saved=Count("id"))
        .filter(
            last__lte=now - timedelta(minutes=IDLE_MINUTES),
            last__gte=now - timedelta(hours=SWEEP_LOOKBACK_HOURS),
        )
        .order_by("last")
    )
    found = []
    for group in grouped.iterator():
        read = (
            ConversationSummary.objects.filter(
                member_id=group["member_id"], session=group["session"]
            )
            .values_list("turns_read", flat=True)
            .first()
        )
        if read is not None and read >= group["saved"]:
            continue
        found.append((group["household_id"], group["member_id"], group["session"]))
        if len(found) >= limit:
            break
    return found


@dataclass
class SweepReport:
    read: int = 0
    written: int = 0
    failed: int = 0
    no_key: bool = False

    def sentence(self) -> str:
        if self.no_key:
            return "No conversation was summarized: no model key is set."
        return (
            f"Summarized {self.written} of {self.read} quiet conversations; "
            f"{self.failed} failed (tried again later)."
        )


def sweep(
    *,
    household=None,
    member=None,
    exclude_session=None,
    limit=SWEEP_LIMIT,
    key=None,
    transport=None,
) -> SweepReport:
    """Summarizes the conversations whose end never arrived (module doc)."""
    report = SweepReport()
    key = key if key is not None else statements.gemini_key()
    if not key:
        report.no_key = True
        return report
    for household_id, member_id, session in idle_sessions(
        household=household, member=member, exclude_session=exclude_session, limit=limit
    ):
        outcome = summarize(
            household_id=household_id,
            member_id=member_id,
            session=session,
            ended_by=SUMMARY_ENDED_IDLE,
            key=key,
            transport=transport,
        )
        report.read += 1
        if outcome.written and not outcome.unchanged:
            report.written += 1
        elif outcome.reason == NOT_WRITTEN_FAILED:
            report.failed += 1
    return report


def as_dict(row: ConversationSummary) -> dict:
    return {
        "id": str(row.id),
        "session": row.session,
        "source": row.source,
        "text": row.text,
        "ended_by": row.ended_by,
        "turns_read": row.turns_read,
        "started_at": row.started_at.isoformat(),
        "ended_at": row.ended_at.isoformat(),
    }
