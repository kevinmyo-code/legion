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

import re

from devicelogs import voice_audit

KEPT_EVENTS = frozenset(
    {"session_start", "session_end", "turn_complete", "user", "tool_call", "tool_result"}
)
KEPT_TAGS = frozenset({"LIVE_LATENCY", voice_audit.BARGE})
# Values that run to the end of the line, cut from a tool line.
_CUT = {"tool_call": " args=", "tool_result": " response="}
# The writer's exact line format, ASCII only, from the first character of a real line. The parser's
# own pattern lets any Unicode space (a no-break space, U+2028) stand in for a separator.
_LINE = re.compile(
    r"^(?P<date>\d\d-\d\d) (?P<time>\d\d:\d\d:\d\d\.\d{3}) +\d+ +\d+ [VDIWEF] "
    r"(?P<tag>[A-Z_]+): ?(?P<msg>.*)$",
    re.ASCII,
)
# Anything `str.splitlines` would break a line on, other than the newline we split on ourselves.
_SEPARATORS = re.compile("[\r\x0b\x0c\x1c\x1d\x1e\x85\u2028\u2029]")


def _without_content(event: str, msg: str) -> str:
    marker = _CUT.get(event)
    if marker is None:
        return msg
    start = msg.find(marker)
    return msg if start < 0 else msg[:start]


def own_lines(text: str) -> str:
    """`text` cut to the lines the page may show, in the stored line format.

    Lines are split on a newline ONLY and a line counts only if it matches the writer's exact
    format from its first character. A separator inside a line (U+2028, U+0085, a form feed) stays
    inside that line and is turned into a space in what is kept, so a tool reply that carries one
    followed by a forged `user` line is cut with the rest of its reply and never becomes a line.
    """
    records = []
    for line in text.split("\n"):
        match = _LINE.match(line.removesuffix("\r"))
        if match:
            records.append((match["date"], match["time"], match["tag"], match["msg"]))
    records.sort(key=lambda r: (r[0], r[1]))  # stable: same-millisecond lines keep capture order
    out = []
    for date, time, tag, msg in records:
        if tag == voice_audit.AUDIT:
            event, _fields = voice_audit.parse_audit(msg)
            if event not in KEPT_EVENTS:
                continue
            msg = _without_content(event, msg)
        elif tag not in KEPT_TAGS:
            continue
        out.append(f"{date} {time} 0 0 I {tag}: {_SEPARATORS.sub(' ', msg)}")
    return "\n".join(out)
