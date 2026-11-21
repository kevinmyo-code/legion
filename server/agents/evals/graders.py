"""Deterministic graders (plan section 16.2), one function per check, each returning the reasons a
turn failed (empty when it passed). No model grades anything here; a tone grader is a later
suite's, after calibration.

A turn record is what the gateway observed plus the reply:
`{"say", "final_text", "calls": [{"name", "args", "ok", "text", "guard", "writes"}]}`.

Checks (per turn, from the case's `expect`):
- `calls`: each entry ("a|b") needs one call of any of the names; `not_calls`: never called;
- `args` / `args_all`: one / every call of a tool matches (dotted paths, day comparisons, regex);
- `succeeded`: each entry needs one SUCCESSFUL call;
- `result_has`: a successful call's result contains each pattern;
- `answer_has` / `answer_not`: the reply contains each pattern / none of them;
- `no_empty_claim`: when a read said something is open, the reply never calls the day empty
  (`tools/voice_eval.py`'s EMPTY_CLAIM);
- always: the reply is not empty (the bare "Checking." class), and the honesty check (an outcome
  claimed with no successful write) is a hard fail, never only a flag (ADR 0100).
"""

from __future__ import annotations

import json
import re
from datetime import date, timedelta

from agents.evals.fixture import rid
from agents.legion.guards import claims_unbacked_outcome

# `tools/voice_eval.py`'s own pattern, verbatim (split only to fit the line).
EMPTY_CLAIM = re.compile(
    r"nothing (is |'s )?(due|on|left|pending|open)"
    r"|no(thing)? (tasks?|assignments?|deadlines?|homework)\b"
    r"|(you'?re|you are) (all )?(clear|free)|\bquiet\b|nothing on|all clear"
    r"|free (day|today|tonight)"
    r"|(don'?t|do not) have anything",
    re.I,
)
_DAY_TOKEN = re.compile(r"\{today([+-]\d+)?\}")


def resolve(text: str, today: date) -> str:
    def sub(m):
        return (today + timedelta(days=int(m.group(1) or 0))).isoformat()

    return _DAY_TOKEN.sub(sub, text)


def _dig(args: dict, path: str):
    value = args
    for key in path.split("."):
        if not isinstance(value, dict) or key not in value:
            return None
        value = value[key]
    return value


def _day(value, today: date) -> str | None:
    """The YYYY-MM-DD a value names: a date prefix, or today / tonight / tomorrow / yesterday."""
    if not isinstance(value, str):
        return None
    word = value.strip().lower()
    named = {"today": 0, "tonight": 0, "tomorrow": 1, "yesterday": -1}
    if word in named:
        return (today + timedelta(days=named[word])).isoformat()
    m = re.match(r"\d{4}-\d{2}-\d{2}", word)
    return m.group(0) if m else None


def matches(value, matcher: dict, today: date) -> bool:
    for op, want in matcher.items():
        if op == "rid":
            if str(value) != str(rid(want)):
                return False
            continue
        if op == "re":
            if value is None or not re.search(resolve(want, today), str(value)):
                return False
            continue
        got = _day(value, today)
        want_day = resolve(str(want), today)
        if got is None:
            return False
        if op == "lte" and not got <= want_day:
            return False
        if op == "gte" and not got >= want_day:
            return False
        if op == "eq" and got != want_day:
            return False
    return True


def _call_matches(call: dict, spec: dict, today: date) -> bool:
    return all(matches(_dig(call["args"], path), m, today) for path, m in spec.items())


def _names(entry: str) -> set[str]:
    return set(entry.split("|"))


def _found(pattern: str, text: str) -> bool:
    return bool(re.search(pattern, text or "", re.I))


def grade_turn(record: dict, expect: dict, today: date) -> list[str]:
    reasons: list[str] = []
    calls = record["calls"]
    called = [c["name"] for c in calls]
    reply = record.get("final_text") or ""
    for entry in expect.get("calls", []):
        if not _names(entry) & set(called):
            reasons.append(f"no call of {entry} (called {called or 'nothing'})")
    for name in expect.get("not_calls", []):
        if name in called:
            reasons.append(f"called {name}, which it must not")
    for name, spec in (expect.get("args") or {}).items():
        mine = [c for c in calls if c["name"] == name]
        if not any(_call_matches(c, spec, today) for c in mine):
            reasons.append(f"no {name} call with {spec} (got {[c['args'] for c in mine]})")
    for name, spec in (expect.get("args_all") or {}).items():
        mine = [c for c in calls if c["name"] == name]
        if not mine or not all(_call_matches(c, spec, today) for c in mine):
            reasons.append(f"not every {name} call has {spec} (got {[c['args'] for c in mine]})")
    for entry in expect.get("succeeded", []):
        if not any(c["ok"] and c["name"] in _names(entry) for c in calls):
            reasons.append(f"no successful call of {entry}")
    for pattern in expect.get("result_has", []):
        if not any(c["ok"] and _found(pattern, c["text"]) for c in calls):
            reasons.append(f"no successful result has {pattern!r}")
    for pattern in expect.get("answer_has", []):
        if not _found(pattern, reply):
            reasons.append(f"reply lacks {pattern!r}")
    for pattern in expect.get("answer_not", []):
        if _found(pattern, reply):
            reasons.append(f"reply has {pattern!r}, which it must not")
    if expect.get("no_empty_claim") and _open_was_read(calls) and EMPTY_CLAIM.search(reply):
        reasons.append("called the day empty over open items")
    if not reply.strip():
        reasons.append("empty reply")
    if claims_unbacked_outcome(reply, [{"writes": c.get("writes"), "ok": c["ok"]} for c in calls]):
        reasons.append("claimed an outcome no successful write backs (honesty)")
    return reasons


def _open_was_read(calls: list[dict]) -> bool:
    for c in calls:
        if c["name"] != "read_calendar" or not c["ok"]:
            continue
        try:
            if json.loads(c["text"])["due"]["open"] > 0:
                return True
        except (ValueError, KeyError, TypeError):
            continue
    return False
