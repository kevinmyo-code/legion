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

import pytest

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


# --------------------------------------------------------------------------- the rules
#
# Each rule takes the cases and RETURNS its complaints. The sweep over the committed files and the
# negative controls below both call the same function, so a rule cannot be deleted or weakened
# while its own guard stays green: the control goes red the moment the rule stops complaining.
# The keys whose values are regexes, checked by `rule_regexes_are_alive`.
REGEX_KEYS = ("answer_has", "answer_not", "result_has", "filler_has", "tts_not")


def check_blocks(case: dict) -> list[dict]:
    """Every `expect` and `fixture` block in a case, turns included."""
    out = []
    for t in case.get("turns", []):
        out += [t.get("expect") or {}, t.get("fixture") or {}]
    out += [case.get("expect") or {}, case.get("fixture") or {}]
    return out


def rule_ids_are_well_formed(cs: list[dict]) -> list[str]:
    bad = []
    seen = set()
    for c in cs:
        cid = c["id"]
        if cid in seen:
            bad.append(f"{cid}: id used twice")
        seen.add(cid)
        if not re.fullmatch(r"[a-z0-9_]+", cid):
            bad.append(f"{cid}: gates.yml only accepts [a-z0-9_]")
        if ("say" in c) == ("turns" in c):
            bad.append(f"{cid}: a case has `say` or `turns`, never both or neither")
    return bad


def rule_tags_are_in_the_vocabulary(cs: list[dict]) -> list[str]:
    bad = []
    for c in cs:
        for tag in c.get("tags", []):
            if tag.startswith("tool:"):
                if not tag[len("tool:"):]:
                    bad.append(f"{c['id']}: `tool:` with no tool after it")
            elif tag not in PLAIN_TAGS:
                bad.append(f"{c['id']}: unknown tag {tag!r}; known: {sorted(PLAIN_TAGS)} and tool:<spec>")
    return bad


def rule_a_chat_case_expects_no_call(cs: list[dict]) -> list[str]:
    """`chat` asserts no tool runs, so a `calls` or `succeeded` beside it is a contradiction."""
    return [f"{c['id']}: tagged chat but expects {sorted(ran_specs(c))}"
            for c in cs if "chat" in c.get("tags", []) and ran_specs(c)]


def rule_a_chat_case_is_never_a_known_limit(cs: list[dict]) -> list[str]:
    """`known_limit` returns a pass and demotes every failure to a note, the chat failure included.

    Scoring one case under both would make the `chat` check unfailable while still reading as a
    check, which is the exact defect the `chat` tag exists to replace.
    """
    return [f"{c['id']}: tagged chat and carries known_limit, so its chat check can never fail"
            for c in cs if "chat" in c.get("tags", []) and c.get("known_limit")]


def rule_a_chat_case_is_never_excused_on_a_layer(cs: list[dict]) -> list[str]:
    """`proves_nothing_on` takes a passing case out of the count on that layer.

    Round-2 review, 2026-11-26: `known_limit` was forbidden beside `chat` and this was not, though
    it is the same hole one step along. A chat case is a zero-tool check and zero tools means zero
    tools on every layer, so no layer can be the reason it proves nothing.
    """
    return [f"{c['id']}: tagged chat and carries proves_nothing_on, but a zero-tool check holds on every layer"
            for c in cs if "chat" in c.get("tags", []) and c.get("proves_nothing_on")]


def rule_a_tool_tag_names_an_expected_call(cs: list[dict]) -> list[str]:
    """A `tool:` tag that matches nothing is a label nobody checks: the usual way a tag goes stale."""
    bad = []
    for c in cs:
        ran = ran_specs(c)
        for tag in c.get("tags", []):
            if tag.startswith("tool:") and tag[len("tool:"):] not in ran:
                bad.append(f"{c['id']}: tagged {tag} but no turn expects it; expects {sorted(ran)}")
    return bad


def rule_save_none_rests_on_a_check(cs: list[dict]) -> list[str]:
    """`save:none` needs `memory` in `not_calls`, or `chat`, which forbids every tool."""
    bad = []
    for c in cs:
        if "save:none" not in c.get("tags", []) or "chat" in c.get("tags", []):
            continue
        forbidden = {s for e in expects(c) for spec in (e.get("not_calls") or []) for s in spec.split("|")}
        if not any(s.split("/")[0] == "memory" for s in forbidden):
            bad.append(f"{c['id']}: tagged save:none but no turn forbids `memory` and it is not a chat case")
    return bad


def rule_surfaces_and_moose_are_well_formed(cs: list[dict]) -> list[str]:
    bad = []
    for c in cs:
        surfaces = c.get("surfaces", ["phone"])
        if not surfaces:
            bad.append(f"{c['id']}: `surfaces` is empty; leave it out for the phone default")
        unknown = set(surfaces) - SURFACES
        if unknown:
            bad.append(f"{c['id']}: unknown surface(s) {sorted(unknown)}")
        if "moose" in c:
            if "moose" not in surfaces:
                bad.append(f"{c['id']}: has a `moose` override but does not list moose in `surfaces`")
            if not isinstance(c["moose"], dict):
                bad.append(f"{c['id']}: `moose` is an override object")
    return bad


def rule_regexes_are_alive(cs: list[dict]) -> list[str]:
    r"""Every committed pattern compiles and holds no control character.

    2026-11-26: `"\b"` in JSON is the BACKSPACE escape, not a word boundary, so
    `save_asked_plain`'s honesty check decoded to `\x08i'?ll remember that\x08` and could never
    match. It compiled, it ran, and it tested nothing. That is the same defect as a `not_calls`
    list that forbids a tool nobody has, which is what this whole change exists to remove, so it
    gets a check rather than a fixed typo.

    Its limit, so nobody over-trusts it (round-2 review, 2026-11-26): it catches a control character
    and a pattern that does not compile. It cannot catch a pattern that compiles, holds no control
    character and still never matches. The over-escaped twin of the defect above, `"(?i)\\\\bnope\\\\b"`
    (a literal backslash then `b`), passes this rule. "Can this pattern ever match" is not decidable
    in general, so reading a new pattern is still a reviewer's job.
    """
    bad = []
    for c in cs:
        for b in check_blocks(c):
            pats = [(k, p) for k in REGEX_KEYS for p in (b.get(k) or [])]
            for spec, params in list((b.get("args") or {}).items()) + list((b.get("args_all") or {}).items()):
                pats += [(f"args.{spec}.{key}", m["re"])
                         for key, m in params.items() if isinstance(m, dict) and "re" in m]
            for key, pat in pats:
                ctrl = sorted({hex(ord(ch)) for ch in pat if ord(ch) < 32})
                if ctrl:
                    bad.append(f"{c['id']} ({key}): pattern holds control character(s) {ctrl}; "
                               rf'a JSON "\b" is a backspace, write "\\b" for a word boundary')
                try:
                    re.compile(pat)
                except re.error as e:
                    bad.append(f"{c['id']} ({key}): pattern does not compile: {e}")
    return bad


def rule_ported_cases_have_a_phone_twin(cs: list[dict], ported: set[str]) -> list[str]:
    """voice21.yaml is a PORT of phone cases. An id there with no case here is a suite that drifted."""
    here = {c["id"] for c in cs}
    missing = sorted(ported - here)
    return [f"voice21.yaml has ids cases.json does not: {missing}"] if missing else []


def rule_an_engine_claim_is_really_ported(cs: list[dict], ported: set[str]) -> list[str]:
    """The other direction: `surfaces: [..., engine]` is a claim, and this is what makes it true."""
    return [f"{c['id']}: claims the engine surface but voice21.yaml has no such id and it carries no `engine_skip`"
            for c in cs
            if "engine" in c.get("surfaces", ["phone"]) and c["id"] not in ported and not c.get("engine_skip")]


CASE_RULES = (
    rule_ids_are_well_formed,
    rule_tags_are_in_the_vocabulary,
    rule_a_chat_case_expects_no_call,
    rule_a_chat_case_is_never_a_known_limit,
    rule_a_chat_case_is_never_excused_on_a_layer,
    rule_a_tool_tag_names_an_expected_call,
    rule_save_none_rests_on_a_check,
    rule_surfaces_and_moose_are_well_formed,
    rule_regexes_are_alive,
)


# --------------------------------------------------------------------------- the committed files


@pytest.mark.parametrize("rule", CASE_RULES, ids=lambda r: r.__name__)
def test_the_committed_cases_satisfy_every_rule(rule):
    assert rule(cases()) == []


def test_the_committed_regex_rule_actually_saw_patterns():
    """A sweep over an empty population is a pass that proves nothing."""
    pats = [p for c in cases() for b in check_blocks(c) for k in REGEX_KEYS for p in (b.get(k) or [])]
    assert len(pats) > 40, f"only {len(pats)} patterns found; the walk stopped seeing them"


def test_reply_length_cases_obey_the_shared_rules():
    """The gate list spans both files, so the second one is held to the same shape."""
    other = json.loads((REPO / "tools" / "voice_eval" / "reply_length.json").read_text(encoding="utf-8"))["cases"]
    assert rule_ids_are_well_formed(other) == []
    assert rule_regexes_are_alive(other) == []


def test_enough_tool_tags_exist_to_make_that_rule_meaningful():
    tagged = [t for c in cases() for t in c.get("tags", []) if t.startswith("tool:")]
    assert len(tagged) > 10, "almost no tool tags, so its rule proves nothing"


def test_the_two_suites_are_in_parity_in_both_directions():
    ported, cs = voice21_ids(), cases()
    assert len(ported) > 10, "voice21.yaml yielded almost no ids; the regex or the file changed shape"
    claimed = [c for c in cs if "engine" in c.get("surfaces", ["phone"])]
    assert claimed, "no case claims the engine surface, so this proves nothing"
    assert rule_ported_cases_have_a_phone_twin(cs, ported) == []
    assert rule_an_engine_claim_is_really_ported(cs, ported) == []


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
A_CALL = [("day", {"action": "read_calendar"})]


def test_a_chat_case_passes_when_no_tool_ran():
    assert ve.score_case(CHAT_CASE, chat_run([]), phone=False).passed


def test_a_chat_case_fails_on_any_tool_even_one_no_case_thought_to_forbid():
    v = ve.score_case(CHAT_CASE, chat_run(A_CALL), phone=False)
    assert not v.passed
    assert any("chat case called" in f for f in v.failures), v.failures


def test_an_untagged_case_is_not_judged_by_the_chat_rule():
    plain = {"id": "x", "say": "what's due today", "expect": {}}
    assert ve.score_case(plain, chat_run(A_CALL), phone=False).passed


def test_known_limit_really_does_swallow_the_chat_failure():
    """Why `rule_a_chat_case_is_never_a_known_limit` exists, proved against the real scorer."""
    both = dict(CHAT_CASE, known_limit="an invented limit")
    assert ve.score_case(both, chat_run(A_CALL), phone=False).passed
    assert rule_a_chat_case_is_never_a_known_limit([both]) != []


def test_proves_nothing_on_really_does_take_a_chat_case_out_of_the_count():
    """Why `rule_a_chat_case_is_never_excused_on_a_layer` exists, proved against the real scorer:
    the pair still FAILS, but a passing one would be reported apart and counted nowhere."""
    excused = dict(CHAT_CASE, proves_nothing_on="laptop: an invented reason")
    _, summary = ve.scorecard([excused], [chat_run([])], {}, phone=False, verbose=False)
    assert summary["not_counted"] == [excused["id"]] and summary["passed"] == 0
    assert rule_a_chat_case_is_never_excused_on_a_layer([excused]) != []


def test_the_excused_chat_rule_rejects_the_pair_and_allows_either_alone():
    assert one(rule_a_chat_case_is_never_excused_on_a_layer,
               {"id": "x", "tags": ["chat"], "say": "hi", "proves_nothing_on": "laptop: why"})
    assert one(rule_a_chat_case_is_never_excused_on_a_layer,
               {"id": "x", "tags": ["guard"], "say": "hi", "proves_nothing_on": "laptop: why"}) == []
    assert one(rule_a_chat_case_is_never_excused_on_a_layer, {"id": "x", "tags": ["chat"], "say": "hi"}) == []


# --------------------------------------------------------------------------- the rules' own teeth
#
# Each control feeds the REAL rule a case it must reject. Delete or weaken a rule and its control
# goes red, which an assertion written against a literal would not.


def one(rule, case, *args) -> list[str]:
    return rule([case], *args)


def test_the_id_rule_rejects_capitals_a_duplicate_and_a_case_with_no_words():
    assert one(rule_ids_are_well_formed, {"id": "Bad-Id", "say": "hi"})
    assert one(rule_ids_are_well_formed, {"id": "x", "say": "hi", "turns": []})
    assert rule_ids_are_well_formed([{"id": "x", "say": "hi"}, {"id": "x", "say": "hi"}])
    assert one(rule_ids_are_well_formed, {"id": "fine_id", "say": "hi"}) == []


def test_the_tag_rule_rejects_a_word_nobody_defined():
    assert one(rule_tags_are_in_the_vocabulary, {"id": "x", "tags": ["smalltalk"], "say": "hi"})
    assert one(rule_tags_are_in_the_vocabulary, {"id": "x", "tags": ["tool:"], "say": "hi"})
    assert one(rule_tags_are_in_the_vocabulary, {"id": "x", "tags": ["chat", "tool:day/read"], "say": "hi"}) == []


def test_the_chat_rule_rejects_a_chat_case_that_expects_a_tool():
    bad = {"id": "x", "tags": ["chat"], "say": "hi", "expect": {"calls": ["day/read_calendar"]}}
    assert one(rule_a_chat_case_expects_no_call, bad)
    assert one(rule_a_chat_case_expects_no_call, {"id": "x", "tags": ["chat"], "say": "hi", "expect": {}}) == []


def test_the_known_limit_rule_rejects_the_pair_and_allows_either_alone():
    assert one(rule_a_chat_case_is_never_a_known_limit,
               {"id": "x", "tags": ["chat"], "say": "hi", "known_limit": "a limit"})
    assert one(rule_a_chat_case_is_never_a_known_limit,
               {"id": "x", "tags": ["guard"], "say": "hi", "known_limit": "a limit"}) == []
    assert one(rule_a_chat_case_is_never_a_known_limit, {"id": "x", "tags": ["chat"], "say": "hi"}) == []


def test_the_tool_tag_rule_rejects_a_tag_pointing_at_nothing():
    bad = {"id": "x", "tags": ["tool:car/get_codes"], "say": "hi", "expect": {"calls": ["memory/remember"]}}
    assert one(rule_a_tool_tag_names_an_expected_call, bad)
    good = {"id": "x", "tags": ["tool:memory/remember"], "say": "hi", "expect": {"succeeded": ["memory/remember"]}}
    assert one(rule_a_tool_tag_names_an_expected_call, good) == []


def test_the_save_none_rule_rejects_a_tag_resting_on_nothing():
    bad = {"id": "x", "tags": ["save:none"], "say": "hi", "expect": {"not_calls": ["car"]}}
    assert one(rule_save_none_rests_on_a_check, bad)
    assert one(rule_save_none_rests_on_a_check,
               {"id": "x", "tags": ["save:none"], "say": "hi", "expect": {"not_calls": ["memory"]}}) == []
    assert one(rule_save_none_rests_on_a_check, {"id": "x", "tags": ["chat", "save:none"], "say": "hi"}) == []


def test_the_surface_rule_rejects_an_unknown_surface_and_a_stray_moose_block():
    assert one(rule_surfaces_and_moose_are_well_formed, {"id": "x", "say": "hi", "surfaces": ["wristwatch"]})
    assert one(rule_surfaces_and_moose_are_well_formed, {"id": "x", "say": "hi", "moose": {"say": "hi"}})
    assert one(rule_surfaces_and_moose_are_well_formed,
               {"id": "x", "say": "hi", "surfaces": ["phone", "moose"], "moose": {"say": "hi"}}) == []


def test_the_regex_rule_rejects_the_backspace_that_shipped_and_an_uncompilable_pattern():
    r"""The literal `"\b"` as JSON decodes it, which is what `save_asked_plain` carried."""
    dead = {"id": "x", "say": "hi", "fixture": {"answer_not": ["(?i)\bi'?ll remember that\b"]}}
    complaints = one(rule_regexes_are_alive, dead)
    assert complaints and "control character" in complaints[0]
    assert one(rule_regexes_are_alive, {"id": "x", "say": "hi", "expect": {"answer_has": ["(unclosed"]}})
    alive = {"id": "x", "say": "hi", "fixture": {"answer_not": [r"(?i)\bi'?ll remember that\b"]}}
    assert one(rule_regexes_are_alive, alive) == []
    assert re.search(alive["fixture"]["answer_not"][0], "I'll remember that")


def test_the_parity_rules_reject_a_claim_with_no_port_and_a_port_with_no_case():
    ported = {"ported_one"}
    unported = {"id": "not_ported_anywhere", "say": "hi", "surfaces": ["phone", "engine"]}
    assert one(rule_an_engine_claim_is_really_ported, unported, ported)
    assert one(rule_an_engine_claim_is_really_ported, dict(unported, engine_skip="no limb yet"), ported) == []
    assert one(rule_ported_cases_have_a_phone_twin, {"id": "something_else", "say": "hi"}, ported)
    assert one(rule_ported_cases_have_a_phone_twin, {"id": "ported_one", "say": "hi"}, ported) == []


def test_the_gate_header_check_rejects_a_count_that_drifted():
    header = "# The phone eval runs the 2 named\n# cases once, retries"
    stated = re.search(r"The phone eval runs the (\d+) named\s*#\s*cases once", header)
    assert stated and int(stated.group(1)) != len(gate_ids())


def test_ran_specs_reads_both_keys_and_splits_alternatives():
    """The helper the chat and tool rules both rest on."""
    c = {"id": "x", "turns": [{"expect": {"calls": ["a/b|c/d"]}}, {"expect": {"succeeded": ["e/f"]}}]}
    assert ran_specs(c) == {"a/b", "c/d", "e/f"}
    assert ran_specs({"id": "y", "say": "hi", "expect": {"not_calls": ["memory"]}}) == set()
