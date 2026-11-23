"""Check (a): no real clock times in committed docs and code comments.

Review finding it automates: log-style times ("14:42", "2026-11-21T07:13") written into public docs
and comments. Dates are fine (ADR dates, "2026-11-22"); a time of day is what leaks a personal timeline.

Where it looks:
  - docs: every line of .md files, and of .html under docs/ (generated pages are allowlisted);
  - code: the comment part of a line (after `#` or `//` outside a string, or a `*` / `/*` comment line).
    Python docstrings are not comments to this check (a fragment of a diff cannot tell).
What it accepts:
  - a path in clock_times_allow.txt (tests with fake data, time-of-day config, generated pages);
  - a line carrying the marker `clock-ok` (a time that is config or an example, not a log time).
Gate mode (--base), added lines only:
  FAIL  a log-like time anywhere (with seconds, after a date, or with a zone: "2026-11-21 14:42",
        "11:47:13", "20:18 CST"), and a bare time ("14:42") in a doc;
  WARN  a bare time in a code comment, and a range anywhere ("04:00 to 11:00"): these are as often a
        time-of-day rule the text explains as a log time, and only a reader can tell which.
Quoted spans and UTC offsets are skipped as format examples.
--all: counts the whole tree, never fails.
"""

from __future__ import annotations

import re

from common import FAIL, WARN, Finding, ext, is_binary, load_allowlist, matches, split_comment
from gitdiff import Change

NAME = "clock-times"
# Two-digit hour, not part of a longer number, not a UTC offset ("+00:00", "UTC-06:00").
TIME = re.compile(r"(?<![\d:.+\-])(?:[01]\d|2[0-3]):[0-5]\d(?!\d)")
# A time that reads as a log entry: with seconds, after a date, or with a zone name.
LOG_LIKE = re.compile(
    r"\d{4}-\d\d-\d\d[ T]+(?:[01]\d|2[0-3]):[0-5]\d"
    r"|(?<![\d:])(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d"
    r"|(?:[01]\d|2[0-3]):[0-5]\d\s*(?:CST|CDT|CT|EST|EDT|PST|PDT|UTC|GMT|SGT|local)\b"
)
# A range is a time-of-day rule ("04:00 to 11:00", "22:00-07:00", "between 04:00 and 11:00"), not a log.
_T = r"(?:[01]\d|2[0-3]):[0-5]\d"
RANGE = re.compile(rf"{_T}\s*(?:to|-|until|and)\s*{_T}")
# Quoted spans are examples of a format ("21:41", `11:59 PM`), not times something happened.
QUOTED = re.compile(r'"[^"]*"|`[^`]*`')
MARKER = "clock-ok"


def comment_part(path: str, line: str) -> str:
    """The part of `line` that is a comment, for code files; the whole line for docs; '' otherwise."""
    if is_doc(path):
        return line
    return split_comment(path, line)[1]


def is_doc(path: str) -> bool:
    e = ext(path)
    return e == ".md" or (e in (".html", ".htm") and path.startswith("docs/"))


def times_in(path: str, line: str) -> list[tuple[str, str]]:
    """(level, time) for each clock time in the doc text or comment of `line`. FAIL: log-like anywhere, and a
    bare time in a doc. WARN: a bare time in a code comment, and a range anywhere (a time-of-day rule)."""
    if MARKER in line:
        return []
    text = QUOTED.sub("", comment_part(path, line))
    logs = [m.span() for m in LOG_LIKE.finditer(text)]
    ranges = [m.span() for m in RANGE.finditer(text)]
    out = []
    for m in TIME.finditer(text):
        if any(a <= m.start() < b for a, b in logs):
            level = FAIL
        elif any(a <= m.start() < b for a, b in ranges):
            level = WARN
        else:
            level = FAIL if is_doc(path) else WARN
        out.append((level, m.group(0)))
    return out


def check(change: Change, allow: list[str] | None = None) -> list[Finding]:
    allow = load_allowlist("clock_times_allow.txt") if allow is None else allow
    out = []
    for path, letter in sorted(change.status.items()):
        if letter == "D" or is_binary(path) or matches(path, allow):
            continue
        for no, text in change.added.get(path, []):
            for level, t in times_in(path, text):
                out.append(Finding(level, NAME, path, no, f"clock time {t} in a doc or comment (mark clock-ok if it is config)"))
    return out
