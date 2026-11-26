#!/usr/bin/env python3
"""validate_gate_reasons.py <raw voice.json> <scorecard.json> [trusted root]  (gates.yml, v2 slice 3)

Why this file exists. A failed paid gate run used to say only which case ids failed, so nobody could
tell WHY without paying for another run. The phone scorer now gives every failure a short reason code
(`tools/voice_eval.py`, class `Fail`). But the scorer, the cases and the model's own words all live in
the COMMIT UNDER TEST, and a gate artifact on a public repo is readable by any signed-in GitHub user
and is not masked. So a reason may not be copied up on the word of the commit being gated.

This script runs from the workflow's TRUSTED checkout (this run's own dev commit). For each failed
case it works out, from the TRUSTED copy of that case, the closed set of codes that case could
possibly produce, and uploads the gated commit's reason only if it is one of them. Anything else
becomes `unclassified`. So the artifact says either something the trusted tree already says in
public, or nothing.

The set is per case, not one set for the whole run, and that is the point: a case with no reply-length
bound cannot report `len:max_words`, and a case naming one tool cannot report a failure about another.
Measured over the committed cases it runs 6 to 13 codes, median 9 (the test prints it), so a malicious
commit's choice carries about three bits rather than free text. Part of that floor cannot be removed:
no case definition rules out `timeout`, `no_answer`, `expression_tag` or the turn counts.

The widest sets are the ones that have to name a tool nobody wrote down in advance: `chat_called:`,
`write_on_read:` and `write_in_test_mode:` take any name on the published voice surface (about 140),
because the whole point of those three is to say which tool wrongly ran. That residual channel is
named here on purpose rather than left for a reader to find. So are these two:

  * the `trusted` checkout lives inside the job's workspace, so a gated commit whose own steps
    rewrote it would defeat this script, as it would already defeat the scrub beside it;
  * the name list is three trusted sources (the published leaf tools, the group names committed cases
    write down, the scorer's own writing-tool groups). A group name in none of them, a read-only group
    no case mentions, still comes out `unclassified`. That is the safe direction, and it is why the
    three naming codes are also the ones most worth re-reading when a tool is added.

Fail-safe by construction: the scorecard it is handed holds NO reason, and this script only ever adds
one. On any trouble it prints a note, adds nothing, and exits 0, which leaves exactly today's
artifact. It is never the reason an artifact is lost.

A legitimate reason naming a tool or a bound that exists only in the gated commit (a brand new case)
comes out `unclassified` until that commit lands on dev. That is the price of a trusted list, and it
is the right way round.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

MAX_ROWS = 500
MAX_REASON = 120
WORD = re.compile(r"^[a-z0-9_]{1,40}$")

# Codes no case definition can rule out: a run can always break off, answer nothing, or carry an
# expression tag, and a case the gate could not score at all gets `unclassified`.
ALWAYS = {"unclassified", "timeout", "no_answer", "expression_tag"}
# expect key -> the code(s) it makes possible, for the bounds that are a plain flag.
FLAG_CODES = {
    "max_words": ["len:max_words"],
    "max_sentences": ["len:max_sentences"],
    "no_filler": ["no_filler"],
    "no_empty_claim": ["no_empty_claim"],
    "missed": ["missed"],
    "not_held": ["not_held"],
    "asks_yes": ["asks_yes:wrote", "asks_yes:not_held"],
    "silent": ["silent:said", "silent:called"],
    "ends": ["ends:not_ended", "ends:ended", "silent:said", "silent:called"],
}
# expect key -> the code that names an index into its list of patterns (never the pattern itself).
INDEX_CODES = ["answer_has", "answer_not", "filler_has", "tts_not", "result_has", "read_day"]
# expect key -> the code that quotes the tool spec the case itself wrote down.
SPEC_CODES = {"calls": "missing_call", "not_calls": "forbidden_call",
              "succeeded": "missing_success", "not_succeeded": "not_succeeded"}


def surface_names(root: Path) -> set[str]:
    """Every tool name the TRUSTED tree publishes. `tools/voice_guide.py` scans the voice surface for
    `docs/voice.html`, so these names are public already; a failure to read it narrows this set,
    never widens it."""
    names: set[str] = set()
    sys.path.insert(0, str(root / "tools"))
    try:
        import voice_guide  # noqa: PLC0415 - the TRUSTED tree's copy, found through sys.path above

        names |= set(voice_guide.declared_tools())
    except Exception as e:  # noqa: BLE001
        print(f"reason check: no tool surface from the trusted tree ({type(e).__name__})")
    try:
        # The TRUSTED scorer's own list of GROUP names it treats as writing tools (`money`, `place`,
        # `phone`, `goal`, `trip`, `body_log`): group names that no committed case happens to mention,
        # so neither list above holds them, and `write_on_read:money` would read `unclassified`.
        import voice_eval  # noqa: PLC0415 - the TRUSTED copy, never the gated commit's

        names |= set(voice_eval.WRITE_TOOLS)
    except Exception as e:  # noqa: BLE001
        print(f"reason check: no write-tool names from the trusted tree ({type(e).__name__})")
    return {n for n in names if WORD.match(n)}


def spec_heads(cases: dict[str, dict]) -> set[str]:
    """Every tool name the TRUSTED cases write down, which is not the same list as the one above.

    `declared_tools()` finds a LEAF tool (`read_calendar`), but a tool call's own name is its GROUP
    (`day`, `car`, `look_up`), and `chat_called:` and the two write codes name exactly that. Without
    these, a chat case that wrongly read the calendar would upload `unclassified`, which is the very
    failure this slice exists to name. Trusted data: it comes from the trusted tree's case files.
    """
    heads: set[str] = set()
    for case in cases.values():
        specs = {t[5:] for t in case.get("tags") or [] if t.startswith("tool:")}
        for turn in _turns(case):
            expect = turn.get("expect") or {}
            for field in SPEC_CODES:
                specs.update(s for s in expect.get(field, []) if isinstance(s, str))
            for field in ("args", "args_all"):
                block = expect.get(field)
                if isinstance(block, dict):
                    specs.update(s for s in block if isinstance(s, str))
        for spec in specs:
            for part in spec.split("|"):
                heads.add(part.partition("/")[0])
    return {h for h in heads if WORD.match(h)}


def trusted_cases(root: Path) -> dict[str, dict]:
    """The committed cases at the TRUSTED commit, by id."""
    out: dict[str, dict] = {}
    for name in ("cases.json", "reply_length.json"):
        try:
            doc = json.loads((root / "tools" / "voice_eval" / name).read_text(encoding="utf-8"))
            for case in doc.get("cases", []) if isinstance(doc, dict) else doc:
                if isinstance(case, dict) and isinstance(case.get("id"), str):
                    out[case["id"]] = case
        except Exception as e:  # noqa: BLE001
            print(f"reason check: could not read trusted {name} ({type(e).__name__})")
    return out


def _turns(case: dict) -> list[dict]:
    if "turns" in case and isinstance(case["turns"], list):
        return [t for t in case["turns"] if isinstance(t, dict)]
    return [case]


def allowed_codes(case: dict, names: set[str]) -> set[str]:
    """Every reason code the TRUSTED copy of this case could produce. Nothing else may be uploaded."""
    out = set(ALWAYS)
    turns = _turns(case)
    out |= {f"turns:{k}_of_{len(turns)}" for k in range(len(turns))}
    if case.get("stt_audio"):
        out.add("no_audio")
    if "chat" in (case.get("tags") or []):
        out |= {f"chat_called:{n}" for n in names}
    if not case.get("write"):
        out |= {f"write_on_read:{n}" for n in names}
    if not case.get("allow_writes"):
        out |= {f"write_in_test_mode:{n}" for n in names}
        out.add("claimed_refused_write")
    for turn in turns:
        expect = dict(turn.get("expect") or {})
        fixture = turn.get("fixture") or {}
        for key, code in SPEC_CODES.items():
            out |= {f"{code}:{s}" for s in expect.get(key, []) if isinstance(s, str)}
        for key, codes in FLAG_CODES.items():
            if key in expect:
                out |= set(codes)
        for key in INDEX_CODES:
            n = len(expect.get(key) or []) + len(fixture.get(key) or [])
            out |= {f"{key}#{i}" for i in range(n)}
        if expect.get("read_day"):
            out.add("missing_call:day/read_calendar")  # the phone layer's own shape of that miss
        if expect.get("read_through"):
            # A turn that had to be answered from outside text and was not tested nothing, so the
            # case is never a pass; the gate's scorecard calls that a fail and this says which kind.
            out.add("inconclusive")
        for key, code in (("args", "args"), ("args_all", "args_all")):
            block = expect.get(key)
            if isinstance(block, dict):
                for spec, params in block.items():
                    if isinstance(spec, str) and isinstance(params, dict):
                        out.add(f"{code}:{spec}.{'+'.join(sorted(k for k in params if isinstance(k, str)))}")
    return out


def check(reason, case: dict | None, names: set[str]) -> str:
    """The reason to upload for a FAILED case: the one given, or `unclassified`."""
    if case is None or not isinstance(reason, str) or not reason or len(reason) > MAX_REASON:
        return "unclassified"
    return reason if reason in allowed_codes(case, names) else "unclassified"


def main(argv: list[str]) -> int:
    raw_path, card_path = Path(argv[1]), Path(argv[2])
    root = Path(argv[3]) if len(argv) > 3 else Path(__file__).resolve().parents[2]
    raw = json.loads(raw_path.read_text(encoding="utf-8"))
    card = json.loads(card_path.read_text(encoding="utf-8"))
    rows = card.get("cases")
    if not isinstance(rows, list) or len(rows) > MAX_ROWS:
        print("reason check: the scorecard has no usable case list; no reason is added")
        return 0
    cases = trusted_cases(root)
    names = surface_names(root) | spec_heads(cases)
    reasons = {r["id"]: r.get("reason") for r in raw.get("cases", [])[:MAX_ROWS]
               if isinstance(r, dict) and isinstance(r.get("id"), str)}
    kept = dropped = 0
    for row in rows:
        if not isinstance(row, dict) or row.get("status") != "fail":
            continue
        verdict = check(reasons.get(row.get("id")), cases.get(row.get("id")), names)
        row["reason"] = verdict
        kept += verdict != "unclassified"
        dropped += verdict == "unclassified"
    card_path.write_text(json.dumps(card, indent=1), encoding="utf-8")
    print(f"reason check: {len(cases)} trusted cases, {kept} reason(s) kept, {dropped} unclassified")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except Exception as e:  # noqa: BLE001 - this script never costs the run its artifact
        print(f"reason check: skipped ({type(e).__name__}); the scorecard keeps no reason")
        sys.exit(0)
