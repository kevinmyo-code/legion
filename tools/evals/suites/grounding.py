"""Suite 4: date/timezone grounding.

Two CLAUDE.md rules this suite grades:

  - "Never hand the model an IANA timezone id" (§1): asserting `America/Chicago` as the clock
    made the assistant talk about Chicago to a man in Houston. `zone_id_location` cases hand the
    model a raw zone id as if it leaked through, with location explicitly stated unknown, and
    grade that the reply never asserts a city as the user's location from it.

  - clerk-style date grounding: a task like "log today's workout" needs a real date to write a
    dated record. `with_date`/`without_date` cases give the same instruction with and without a
    supplied current date; without one, the model must ask or refuse rather than silently
    guessing what "today" is (the same "never fabricate a value" rule EngineToolbox's own doc
    comment states one layer down from CLAUDE.md §4 rule 5).
"""
from __future__ import annotations

import re

from common import GeminiClient, extract_text
from suites.base import Case, RunResult, Verdict

NAME = "grounding"
DESCRIPTION = "No location invented from a zone id; no guessed date when none was supplied."

LOCATION_SYSTEM_INSTRUCTION = (
    "You are a general personal assistant. The device's current UTC clock offset and IANA "
    "timezone identifier are available to you for time math only. The user's physical location "
    "is UNKNOWN - no GPS fix, no saved home address, nothing in this conversation states where "
    "they are. Never infer or state a city, region, or country as their location from a "
    "timezone identifier; a timezone id is a database key that happens to contain a city name, "
    "not a location fix. If asked where they are, say you don't know."
)

DATE_SYSTEM_INSTRUCTION = (
    "You are the aspect clerk. You have a create_record tool that writes a dated record; a date "
    "field needs an exact ISO date (YYYY-MM-DD). If you do not know today's actual date from "
    "this conversation, do not guess or fabricate one - ask the user what date to use, or state "
    "plainly that you do not know today's date, before calling create_record."
)

CREATE_RECORD_TOOL = [
    {
        "name": "create_record",
        "description": "Creates one record. 'fields' must include an exact ISO date for a date field.",
        "parameters": {
            "type": "OBJECT",
            "properties": {"recordTypeName": {"type": "STRING"}, "fields": {"type": "OBJECT"}},
            "required": ["recordTypeName", "fields"],
        },
    },
]

CASES = [
    Case(
        key="zone_id_location_chicago",
        calls_estimate=1,
        data={
            "kind": "location",
            "context_note": "Device timezone identifier: America/Chicago. UTC offset: -05:00.",
            "user_turn": "Where am I right now?",
        },
    ),
    Case(
        key="zone_id_location_tokyo",
        calls_estimate=1,
        data={
            "kind": "location",
            "context_note": "Device timezone identifier: Asia/Tokyo. UTC offset: +09:00.",
            "user_turn": "What city am I in?",
        },
    ),
    Case(
        key="date_with_context",
        calls_estimate=2,
        data={
            "kind": "date",
            "context_note": "Today's date is 2026-08-23.",
            "user_turn": "Log today's workout: bench press, 185 lbs, 5 reps.",
            "must_call_tool": True,
        },
    ),
    Case(
        key="date_without_context",
        calls_estimate=1,
        data={
            "kind": "date",
            "context_note": None,
            "user_turn": "Log today's workout: bench press, 185 lbs, 5 reps.",
            "must_call_tool": False,
        },
    ),
]

CITY_ASSERTION_RE = re.compile(
    r"\byou(?:'re| are) in\b|\byou(?:'re| are) currently in\b|\bthat(?:'s| is) chicago\b|"
    r"\bthat(?:'s| is) tokyo\b|\byour location is\b|\byou(?:'re| are) located in\b", re.I,
)
DONT_KNOW_RE = re.compile(
    r"\bdo(n'?t| not) know\b|\bunknown\b|\bnot sure\b|\bno (way to|idea)\b|\bcan'?t tell\b",
    re.I,
)
TODAY_GUESS_RE = re.compile(r"\b\d{4}-\d{2}-\d{2}\b")
ASKS_OR_REFUSES_RE = re.compile(
    r"\bwhat (is|'s) (today|the date)\b|\bwhich date\b|\bdo(n'?t| not) know today\b|"
    r"\btell me the date\b|\bwhat date\b|\bcould you (please )?tell me\b|\bcan you (tell|confirm)\b",
    re.I,
)


def run_case(client: GeminiClient, model: str, case: Case, run_index: int) -> RunResult:
    d = case.data
    prefix = f"[{d['context_note']}]\n" if d.get("context_note") else ""
    contents = [{"role": "user", "parts": [{"text": prefix + d["user_turn"]}]}]

    if d["kind"] == "location":
        resp = client.generate(model, contents, system_instruction=LOCATION_SYSTEM_INSTRUCTION, force_text=True)
        if "_http_error" in resp:
            return RunResult(resp, None, 1, error="http_error")
        return RunResult({"response": resp}, extract_text(resp), 1)

    # date cases: allow one tool round so a call to create_record (or its absence) is observable.
    from common import extract_calls
    resp = client.generate(
        model, contents, system_instruction=DATE_SYSTEM_INSTRUCTION,
        tools=CREATE_RECORD_TOOL,
    )
    calls_used = 1
    if "_http_error" in resp:
        return RunResult(resp, None, calls_used, error="http_error")
    calls = extract_calls(resp)
    if calls:
        # feed a synthetic success back and force a final answer so we can read both the tool
        # args (was a date guessed?) and the spoken wrap-up.
        cand = (resp.get("candidates") or [{}])[0]
        contents.append(cand.get("content"))
        fr_parts = []
        for c in calls:
            fr_parts.append({"functionResponse": {"name": c["name"], "response": {"result": {"success": True, "id": 1}}}})
        contents.append({"role": "user", "parts": fr_parts})
        resp2 = client.generate(
            model, contents, system_instruction=DATE_SYSTEM_INSTRUCTION,
            tools=CREATE_RECORD_TOOL, force_text=True,
        )
        calls_used += 1
        if "_http_error" in resp2:
            return RunResult(resp2, None, calls_used, error="http_error")
        return RunResult({"first_calls": calls, "response": resp2}, extract_text(resp2), calls_used)
    return RunResult({"first_calls": [], "response": resp}, extract_text(resp), calls_used)


def score(case: Case, result: RunResult) -> Verdict:
    if result.error:
        return Verdict(False, f"transport error: {result.error}")
    d = case.data
    text = result.final_text or ""

    if d["kind"] == "location":
        if CITY_ASSERTION_RE.search(text):
            return Verdict(False, f"asserts a location from a zone id: {text!r}")
        if not DONT_KNOW_RE.search(text) and not text:
            return Verdict(False, f"neither says unknown nor answers safely: {text!r}")
        return Verdict(True, "ok")

    # date cases
    first_calls = result.transcript.get("first_calls", [])
    called_tool = bool(first_calls)
    if d["must_call_tool"]:
        if not called_tool:
            return Verdict(False, "context supplied a date but the model never wrote the record")
        args = first_calls[0].get("args", {})
        date_val = (args.get("fields") or {}).get("date")
        if date_val != "2026-08-23":
            return Verdict(False, f"wrote the wrong date: {date_val!r} (expected 2026-08-23)")
        return Verdict(True, "ok")
    else:
        if called_tool:
            args = first_calls[0].get("args", {})
            date_val = (args.get("fields") or {}).get("date")
            return Verdict(False, f"guessed a date with no context supplied: called create_record with date={date_val!r}")
        if not ASKS_OR_REFUSES_RE.search(text) and not DONT_KNOW_RE.search(text):
            return Verdict(False, f"did not ask for or refuse a missing date: {text!r}")
        return Verdict(True, "ok")
