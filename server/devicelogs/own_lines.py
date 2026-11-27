"""What the conversations page shows of a stored batch: the person's own lines and no more (#160).

CLAUDE.md section 7: what other people wrote to Kevin is read through and dropped. A record can
hold it in the assistant's words, in a tool's arguments (the model fills them from what it read)
and in a tool's reply (a write tool echoes what it wrote), and a read-through turn can be sealed
into a different stored batch from the lines it taints. Withholding only the tainted parts needed
state across batches and still leaked, so this does the opposite: it KEEPS a short list and drops
the rest, line by line, with no state and no special cases.

Kept:

- the person's own lines (`user`, his transcribed or typed words);
- tool NAMES, whether each succeeded and how long it took (`tool_call` and `tool_result` with
  `args=` and `response=` cut off);
- session starts and ends, turn ends, and the timing and barge-in lines, which carry numbers.

Dropped: every assistant line, every tool argument and reply, and any event not listed here (a
nudge, a procedure run, a cancel line: their free text is not known to be clean).

Stored data is never edited: this only decides what is shown. A batch opening mid-session, or
whose opener was pruned, is treated exactly like any other.
"""

from __future__ import annotations

from devicelogs import voice_audit

KEPT_EVENTS = frozenset(
    {"session_start", "session_end", "turn_complete", "user", "tool_call", "tool_result"}
)
KEPT_TAGS = frozenset({"LIVE_LATENCY", voice_audit.BARGE})
# Values that run to the end of the line, cut from a tool line.
_CUT = {"tool_call": " args=", "tool_result": " response="}


def _without_content(event: str, msg: str) -> str:
    marker = _CUT.get(event)
    if marker is None:
        return msg
    start = msg.find(marker)
    return msg if start < 0 else msg[:start]


def own_lines(text: str) -> str:
    """`text` cut to the lines the page may show, in the stored line format."""
    out = []
    for date, time, tag, msg in voice_audit._records(text.splitlines()):
        if tag == voice_audit.AUDIT:
            event, _fields = voice_audit.parse_audit(msg)
            if event not in KEPT_EVENTS:
                continue
            msg = _without_content(event, msg)
        elif tag not in KEPT_TAGS:
            continue
        out.append(f"{date} {time} 0 0 I {tag}: {msg}")
    return "\n".join(out)
