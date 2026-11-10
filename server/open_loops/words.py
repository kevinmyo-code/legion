"""His own words, and nothing that could have come from anyone else (ADR 0085, CLAUDE.md section 7).

Open loops are extracted ONLY from what the member himself said or typed to Alfred. This is
stricter than memory consolidation's filter (`member_memory.consolidate.transcript`), on purpose
(no assistant line at all, and any tool turn dropped):

- **Only `user final=true` lines.** No assistant line at all, tool-free or not.
- **A whole turn is dropped** when anything in it could carry third-party text or already closed
  the loop: any `tool_call` / `tool_result` / `tool_cancelled` / `procedure_run` (a turn that ran
  a tool is either read-through, or it set the reminder itself), any `read_through` event (mail,
  web grounding, `fleet_web`, a camera `look`, a spoken brief or watch line (`proactive`), and the
  turn after one (`carry`)), and any line carrying the redaction marker.
  Consolidation applies the same read-through and redaction drop since open-loops ticket 07
  (2026-11-10), and keeps only the person's words from a source `can_mark_turns` refuses.
- **Only sources that can mark a turn, by allowlist.** A web record (`device_label` "web": the
  engine writes every tool line itself, `devicelogs/web_conversation.py`) or a phone record that
  carries a `run_id` (sent by phone builds from 2026-11-09 on, the same day the phone began logging
  `read_through` events). Anything else is left out whole: the head unit (its Live socket declares
  Google Search grounding and its audit has no `read_through` event,
  `headunit/.../diag/ConvoAudit.kt`; also refused by its `1.0+<sha>` version name), an older phone,
  or a record that names nothing. A blank or unknown source fails closed.

The turn is decided when it closes (`turn_complete`, `session_end`, or the end of the record),
because a `read_through` mark can be logged after his line in the same turn.
"""

from __future__ import annotations

from devicelogs import voice_audit

# A turn with any of these is dropped whole (see the module doc).
TOOL_EVENTS = frozenset({"tool_call", "tool_result", "tool_cancelled", "procedure_run"})
READ_THROUGH_EVENT = "read_through"
HEAD_UNIT_VERSION_PREFIX = "1.0+"


def is_head_unit(log) -> bool:
    return str(getattr(log, "app_version_name", "") or "").startswith(HEAD_UNIT_VERSION_PREFIX)


def can_mark_turns(log) -> bool:
    """True only for a source whose records mark read-through turns (see the module doc)."""
    if is_head_unit(log):
        return False
    from devicelogs.models import DEVICE_LABEL_WEB

    if getattr(log, "device_label", "") == DEVICE_LABEL_WEB:
        return True
    return bool(str(getattr(log, "run_id", "") or "").strip())


def own_lines(raw: str) -> list[str]:
    """His own final lines from clean turns, in order, whitespace collapsed."""
    out: list[str] = []
    turn: list[str] = []
    dirty = False

    def close() -> None:
        nonlocal turn, dirty
        if not dirty:
            out.extend(turn)
        turn, dirty = [], False

    for _date, _time, tag, msg in voice_audit._records(raw.splitlines()):
        if tag != "LIVE_AUDIT":
            continue
        event, fields = voice_audit.parse_audit(msg)
        if event in TOOL_EVENTS or event == READ_THROUGH_EVENT:
            dirty = True
            continue
        if event in ("turn_complete", "session_end", "session_start"):
            close()
            continue
        text = fields.get("text")
        if isinstance(text, str) and (voice_audit.REDACTED in text or "[redacted" in text):
            dirty = True
            continue
        response = fields.get("response")
        if isinstance(response, str) and "[redacted" in response:
            dirty = True
            continue
        if event == "user" and fields.get("final") == "true" and isinstance(text, str):
            cleaned = " ".join(text.split())
            if cleaned:
                turn.append(cleaned)
    close()
    return out


def own_text(log) -> str:
    """One record's own words as one text, or "" (unreadable, the head unit, or nothing clean)."""
    from devicelogs.render import batch_text

    if not can_mark_turns(log):
        return ""
    try:
        return "\n".join(own_lines(batch_text(log)))
    except ValueError:
        return ""
