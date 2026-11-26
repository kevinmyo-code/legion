"""The committed voice-eval case files' own shape: free, no key, no Gradle, no network.

`tools/tests/test_voice_eval.py` covers the scorer. This file covers the DATA the scorer reads, and
the prose that quotes it, so a wrong count or a mislabelled case fails on every PR instead of at the
next paid run. Run from the repo root:

    python -m pytest tools/tests/test_voice_eval_cases.py -p no:cacheprovider
"""

from __future__ import annotations

import importlib.util
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CASES = REPO / "tools" / "voice_eval" / "cases.json"
GATE = REPO / "tools" / "voice_eval" / "gate_cases.txt"
GATES_YML = REPO / ".github" / "workflows" / "gates.yml"
VOICE21 = REPO / "server" / "agents" / "evals" / "cases" / "voice21.yaml"

_spec = importlib.util.spec_from_file_location("voice_eval_cases_under_test", REPO / "tools" / "voice_eval.py")
ve = importlib.util.module_from_spec(_spec)
sys.modules["voice_eval_cases_under_test"] = ve
_spec.loader.exec_module(ve)

# The closed tag vocabulary (voice eval v2 design, 1.1). `tool:` carries a call spec after it.
PLAIN_TAGS = {"chat", "save:none", "save:asked", "recall", "guard", "speech", "length"}
SURFACES = {"phone", "moose", "engine"}


def gate_ids() -> list[str]:
    lines = GATE.read_text(encoding="utf-8").splitlines()
    return [x.strip() for x in lines if x.strip() and not x.strip().startswith("#")]


def cases() -> list[dict]:
    return json.loads(CASES.read_text(encoding="utf-8"))["cases"]


def expects(case: dict) -> list[dict]:
    """Every turn's `expect`. A single-turn case's top-level `expect` is its one turn."""
    if "turns" in case:
        return [t.get("expect", {}) or {} for t in case["turns"]]
    return [case.get("expect", {}) or {}]


def ran_specs(case: dict) -> set[str]:
    """Call specs the case asserts DID run: `calls` and `succeeded`, each alternative of `a|b`."""
    out = set()
    for e in expects(case):
        for spec in list(e.get("calls") or []) + list(e.get("succeeded") or []):
            out |= set(spec.split("|"))
    return out


def voice21_ids() -> set[str]:
    """The ported suite's ids, read with a regex: the checks job installs pytest and nothing else."""
    return set(re.findall(r"^\s*-\s+id:\s*([a-z0-9_]+)\s*$", VOICE21.read_text(encoding="utf-8"), re.M))


def test_the_gates_header_states_the_number_of_cases_the_gate_list_holds():
    """2026-11-25: the header said 36 while the list held 38, and so did the public note.

    A count in prose rots silently. This is the check that replaces remembering.
    """
    header = GATES_YML.read_text(encoding="utf-8")
    stated = re.search(r"The phone eval runs the (\d+) named\s*#\s*cases once", header)
    assert stated, "gates.yml no longer states how many cases the phone eval runs"
    assert int(stated.group(1)) == len(gate_ids()), (
        f"gates.yml says {stated.group(1)} cases; gate_cases.txt holds {len(gate_ids())}"
    )


def test_every_gate_id_is_a_committed_case():
    """The Kotlin offline test asserts this too; here it costs no Gradle run."""
    ids = set()
    for f in ("cases.json", "reply_length.json"):
        data = json.loads((REPO / "tools" / "voice_eval" / f).read_text(encoding="utf-8"))
        ids |= {c["id"] for c in data["cases"]}
    missing = [i for i in gate_ids() if i not in ids]
    assert not missing, f"gate_cases.txt names ids no case file has: {missing}"
    assert len(gate_ids()) == len(set(gate_ids())), "an id is listed twice in gate_cases.txt"


# --------------------------------------------------------------------------- the tag schema


def test_every_case_has_a_well_formed_id_and_something_to_say():
    ids = [c["id"] for c in cases()]
    assert len(ids) == len(set(ids)), "a case id is used twice"
    for c in cases():
        assert re.fullmatch(r"[a-z0-9_]+", c["id"]), f"{c['id']}: gates.yml only accepts [a-z0-9_]"
        assert ("say" in c) ^ ("turns" in c), f"{c['id']}: a case has `say` or `turns`, never both or neither"


def test_every_tag_is_in_the_closed_vocabulary():
    for c in cases():
        for tag in c.get("tags", []):
            if tag.startswith("tool:"):
                assert tag[len("tool:"):], f"{c['id']}: `tool:` with no tool after it"
            else:
                assert tag in PLAIN_TAGS, f"{c['id']}: unknown tag {tag!r}; known: {sorted(PLAIN_TAGS)} and tool:<spec>"


def test_a_chat_case_may_not_also_expect_a_call():
    """`chat` asserts no tool runs, so a `calls` or `succeeded` beside it is a contradiction."""
    for c in cases():
        if "chat" in c.get("tags", []):
            assert not ran_specs(c), f"{c['id']}: tagged chat but expects {sorted(ran_specs(c))}"


def test_a_tool_tag_names_a_call_the_case_actually_expects():
    """A `tool:` tag that matches nothing is a label nobody checks: the usual way a tag goes stale."""
    checked = 0
    for c in cases():
        ran = ran_specs(c)
        for tag in c.get("tags", []):
            if not tag.startswith("tool:"):
                continue
            spec = tag[len("tool:"):]
            assert spec in ran, f"{c['id']}: tagged {tag} but no turn expects {spec}; expects {sorted(ran)}"
            checked += 1
    assert checked > 10, "no tool tags were checked, so this test proves nothing"


def test_save_none_is_backed_by_a_check_that_can_fail():
    """`save:none` must rest on something: `memory` in `not_calls`, or `chat` (which forbids every tool)."""
    for c in cases():
        if "save:none" not in c.get("tags", []):
            continue
        if "chat" in c.get("tags", []):
            continue
        forbidden = {s for e in expects(c) for spec in (e.get("not_calls") or []) for s in spec.split("|")}
        assert any(s.split("/")[0] == "memory" for s in forbidden), (
            f"{c['id']}: tagged save:none but no turn forbids `memory` and it is not a chat case"
        )


def test_surfaces_and_the_moose_override_are_well_formed():
    for c in cases():
        surfaces = c.get("surfaces", ["phone"])
        assert surfaces, f"{c['id']}: `surfaces` is empty; leave it out for the phone default"
        unknown = set(surfaces) - SURFACES
        assert not unknown, f"{c['id']}: unknown surface(s) {sorted(unknown)}"
        if "moose" in c:
            assert "moose" in surfaces, f"{c['id']}: has a `moose` override but does not list moose in `surfaces`"
            assert isinstance(c["moose"], dict), f"{c['id']}: `moose` is an override object"


# --------------------------------------------------------------------------- phone / engine parity


def test_every_ported_engine_case_still_has_its_phone_twin():
    """voice21.yaml is a PORT of phone cases. An id there with no case here is a suite that drifted."""
    ported = voice21_ids()
    assert len(ported) > 10, "voice21.yaml yielded almost no ids; the regex or the file changed shape"
    here = {c["id"] for c in cases()}
    assert not (ported - here), f"voice21.yaml has ids cases.json does not: {sorted(ported - here)}"


def test_every_case_claiming_the_engine_surface_is_actually_ported():
    """The other direction: `surfaces: [..., engine]` is a claim, and this is what makes it true."""
    ported = voice21_ids()
    claimed = [c for c in cases() if "engine" in c.get("surfaces", ["phone"])]
    assert claimed, "no case claims the engine surface, so this test proves nothing"
    for c in claimed:
        assert c["id"] in ported or c.get("engine_skip"), (
            f"{c['id']}: claims the engine surface but voice21.yaml has no such id and it carries no `engine_skip`"
        )


# --------------------------------------------------------------------------- what the `chat` tag DOES


def chat_run(calls: list[tuple[str, dict]]) -> "ve.CaseRun":
    """One finished case whose single turn made `calls`, in the scorer's own shapes."""
    turn = ve.Turn(
        user="ha, long day",
        calls=[ve.ToolCall(id=f"e{i}", name=n, args=a, success=True, ms=10, response='{"success":true}')
               for i, (n, a) in enumerate(calls)],
        answer_parts=["Long ones are the worst."],
    )
    return ve.CaseRun(id="chat_long_day", day=ve.datetime.date(2026, 11, 25), turns=[turn], ended=True)


CHAT_CASE = {"id": "chat_long_day", "tags": ["chat", "save:none"], "say": "ha, long day", "expect": {}}


def test_a_chat_case_passes_when_no_tool_ran():
    assert ve.score_case(CHAT_CASE, chat_run([]), phone=False).passed


def test_a_chat_case_fails_on_any_tool_even_one_no_case_thought_to_forbid():
    v = ve.score_case(CHAT_CASE, chat_run([("day", {"action": "read_calendar"})]), phone=False)
    assert not v.passed
    assert any("chat case called" in f for f in v.failures), v.failures


def test_an_untagged_case_is_not_judged_by_the_chat_rule():
    plain = {"id": "x", "say": "what's due today", "expect": {}}
    assert ve.score_case(plain, chat_run([("day", {"action": "read_calendar"})]), phone=False).passed
