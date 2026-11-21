"""Guards in code, not prompts (plan section 14, ADR 0100). Every tool call passes `check_call`
before it runs; a refusal's words go back to the model as the tool's result.

In order:
1. **Recruiter and member fences** (ADR 0068, 0069): the web door's own `refusal_for`, so what a
   recruiter may call here is exactly `RECRUITER_TOOLS` (default deny). The turn endpoint
   refuses recruiters before any of this (v1); this is the second lock.
2. **Allowlist**: a tool not in the spec is refused (counted as a misroute).
3. **Read-only specs**: `writes: none` refuses every write.
4. **The write guard (ADR 0090)**: a write in a turn whose state carries taint is refused, in
   `assistant.read_through`'s own words. Taint is set (and saved) before a third-party tool runs:
   `mark_before`, failing closed when the save fails.
5. **Confirm before write**: a destructive tool, or one the spec lists, returns a read-back the
   first time; `pending_confirm` holds the sha256 of its arguments, and only a yes in a LATER turn
   (`confirm_reply`) releases a call whose arguments hash the same. A tool with its own draft
   step (`CONFIRM_FLAG_TOOLS`, ticket 15) drafts freely; its `confirm: true` call is the one held
   for that later yes, so a model cannot draft and commit in one turn.

Tenancy is not a guard here because it is not optional: every engine tool runs through
`run_engine_tool` as the member (`agents/legion/gateway.py`), under their `visible()`.

`honesty_flag` is the post-hoc check (section 14): an outcome verb in the reply with no
successful write in the turn. It flags; it never blocks a sentence already spoken. Its limits are
the plan's: lexical negation, no check of the object, no view of past turns.
"""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass

from agents.legion import carry
from assistant import read_through
from assistant.surface import refusal_for
from engine_mcp.tools import TOOLS_BY_NAME
from household.models import is_recruiter

THIRD_PARTY_TOOLS = read_through.THIRD_PARTY_TOOLS
# Tools whose first call only drafts or counts and whose `confirm: true` call commits (the
# user-aspect records' bulk delete and tracker authoring, `homes/records_tools.py`).
CONFIRM_FLAG_TOOLS = frozenset({"delete_aspect_record", "create_aspect", "update_aspect"})
CONFIRM_TURNS = 2

NOT_ALLOWED = (
    "Nothing was read or written: {name} is not one of this agent's tools, so it did not run."
)
READ_ONLY = "Nothing was changed: this agent may only read, so {name} did not run."
NEEDS_CONFIRM = (
    "Not done yet: {name} changes or removes something, so it needs a yes first. Read back "
    "exactly what it will do and ask; it runs only if they say yes in their next message."
)


@dataclass(frozen=True)
class Verdict:
    allow: bool
    words: str = ""
    guard: str | None = None


ALLOW = Verdict(True)


def args_sha256(name: str, args: dict) -> str:
    canonical = json.dumps({"tool": name, "args": args}, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(canonical.encode()).hexdigest()


def check_call(state, spec, name: str, args: dict, tool, user) -> Verdict:
    if is_recruiter(user):
        refusal = refusal_for(name, user, args)
        if refusal is not None:
            return Verdict(False, refusal, "recruiter")
    elif name in TOOLS_BY_NAME:
        refusal = refusal_for(name, user, args)
        if refusal is not None:
            return Verdict(False, refusal, "not_allowed")
    if tool is None or name not in spec.tools:
        return Verdict(False, NOT_ALLOWED.format(name=name), "not_allowed")
    if tool.writes and not spec.writes_allowed:
        return Verdict(False, READ_ONLY.format(name=name), "not_allowed")
    if tool.writes and state.tainted:
        return Verdict(False, tainted_refusal(state, name, tool.destructive), "tainted")
    flagged = name in CONFIRM_FLAG_TOOLS and (args or {}).get("confirm") is True
    if tool.writes and (tool.destructive or name in spec.confirm or flagged):
        return hold_for_yes(state, name, args)
    return ALLOW


def tainted_refusal(state, name: str, destructive: bool = False) -> str:
    """The words a write refuses with in a tainted turn: the carried ones when the only reason is
    the turn before (`carry.only_carried`), else this turn's (`assistant.read_through`)."""
    carried = carry.only_carried(state.taint)
    if not carried:
        carry.note_refused(state)
    if name == "remember":
        return read_through.CARRY_REFUSAL if carried else read_through.REFUSAL
    template = read_through.CARRY_WRITE_REFUSAL if carried else read_through.WRITE_REFUSAL
    return template.format(verb="deleted" if destructive else "changed", name=name)


def hold_for_yes(state, name: str, args: dict) -> Verdict:
    """Confirm before write: the call runs only when a yes in this turn released exactly these
    arguments; otherwise it is held (`pending_confirm`) and refused in words for now."""
    digest = args_sha256(name, args)
    if state.confirmed and state.confirmed == digest:
        state.confirmed = None
        return ALLOW
    state.pending_confirm = {
        "tool": name,
        "args_sha256": digest,
        "until_seq": state.seq + CONFIRM_TURNS,
    }
    return Verdict(False, NEEDS_CONFIRM.format(name=name), "needs_confirm")


def needs_taint(name: str) -> bool:
    return name in THIRD_PARTY_TOOLS


# The plan's outcome verbs (section 14), as a claim: first person ("I've added", "I marked") or
# opening a sentence ("Done.", "Added buy stamps"). "The essay draft is already done" is a fact
# read from a tool, not a claim of an action, and is not flagged.
_VERBS = (
    r"added|set|sent|booked|started|cleared|logged|saved|ticked|removed|opened|played|"
    r"scheduled|marked|created|deleted|updated|changed|completed|put"
)
_CLAIM = re.compile(
    rf"\b(i'?ve|i have|i)\s+(just\s+|now\s+|gone ahead and\s+)?({_VERBS})\b"
    rf"|(^|[.!?]\s+)(done|all set|{_VERBS})\b",
    re.IGNORECASE,
)
# A negation shortly before the claim ("I couldn't add it", "nothing was added").
_NEGATED = re.compile(
    r"(\bnot\b|n't|\bnever\b|\bunable\b|nothing was|no changes?)\W+(\w+\W+){0,3}$",
    re.IGNORECASE,
)


def claims_unbacked_outcome(final_text: str, actions: list[dict]) -> bool:
    """True when the reply claims an action and no write in the turn succeeded."""
    if any(a.get("writes") and a.get("ok") for a in actions):
        return False
    text = final_text or ""
    for match in _CLAIM.finditer(text):
        claim = match.group(0)
        verb_at = match.start() + len(claim) - len(claim.lstrip(".!? "))
        before = text[max(0, verb_at - 40) : verb_at]
        if not _NEGATED.search(before) and not re.search(r"n't|\bnot\b", claim, re.I):
            return True
    return False


_YES = re.compile(
    r"^\W*(yes|yeah|yep|yup|sure|ok(ay)?|do it|go ahead|confirm(ed)?|please do|correct|"
    r"that'?s right)\b",
    re.IGNORECASE,
)


def is_yes(text: str) -> bool:
    return bool(_YES.search(text or ""))
