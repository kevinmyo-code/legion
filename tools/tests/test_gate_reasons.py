"""The gate's failure reasons (voice eval v2 slice 3): the scorer's codes, the trusted validator that
decides which may be uploaded, and the engine's node id. No key, no Gradle, no network, no model call.
Invented utterances only. Run from the repo root:

    python -m pytest tools/tests/test_gate_reasons.py -p no:cacheprovider
"""

from __future__ import annotations

import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = ROOT / ".github" / "scripts"


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


ve = _load("voice_eval_for_reasons", ROOT / "tools" / "voice_eval.py")
vgr = _load("validate_gate_reasons", SCRIPTS / "validate_gate_reasons.py")
gfw = _load("gate_fail_where", SCRIPTS / "gate_fail_where.py")

COMMITTED = json.loads((ROOT / "tools" / "voice_eval" / "cases.json").read_text(encoding="utf-8"))["cases"]
TRUSTED = vgr.trusted_cases(ROOT)
NAMES = vgr.surface_names(ROOT) | vgr.spec_heads(TRUSTED)
# A canary stands in for everything the artifact may never carry: a model's words, a tool argument,
# an assert's text, a key. If a canary ever reaches an uploaded file, the guard it tests is broken.
CANARY = "CANARY-the-spare-key-is-in-the-blue-tin"


def _lines(cid: str, user: str, calls, answer: str, end: bool = True) -> list[str]:
    def L(tag, msg):
        return f"11-15 10:00:00.000  4242  4242 I {tag}: {msg}"

    out = [L("LIVE_TEST", f"case_begin id={cid}"),
           L("LIVE_AUDIT", "session_start model=eval+gemini-3.5-flash-lite async_set=none"),
           L("LIVE_AUDIT", f"user final=true text={json.dumps(user)}")]
    for i, (name, args, response) in enumerate(calls):
        out.append(L("LIVE_AUDIT", f"tool_call id=e{i} name={name} async=false args={json.dumps(args, separators=(',', ':'))}"))
        out.append(L("LIVE_AUDIT", f"tool_result id=e{i} name={name} success=true ms=40 scheduling=none sent=true response={response}"))
    out.append(L("LIVE_AUDIT", f"assistant text={json.dumps(answer)}"))
    out.append(L("LIVE_AUDIT", "turn_complete"))
    out.append(L("LIVE_AUDIT", 'session_end reason="eval"'))
    if end:
        out.append(L("LIVE_TEST", f"case_end id={cid} ms=1500"))
    return out


def _score(case: dict, calls, answer: str, end: bool = True):
    _, runs = ve.parse_run(_lines(case["id"], case.get("say", "hello"), calls, answer, end), 2026)
    return ve.score_case(case, runs[0], phone=False)


# --------------------------------------------------------------- the scorer gives every failure a code


def test_every_failure_the_scorer_raises_carries_a_code():
    v = _score(COMMITTED[0], [("manage_item", {"action": "read_list"}, '{"success":true}')], "")
    assert v.failures, "the plant must fail, or this test proves nothing"
    for f in v.failures:
        assert isinstance(f, ve.Fail), f"a failure with no reason code: {f!r}"
        assert f.code


def test_no_failure_site_in_the_scorer_bypasses_the_code():
    """Static, over the whole file, so this does not rest on the one plant above: every append to a
    failure list goes through `Fail` or the `bad` helper that wraps it."""
    source = (ROOT / "tools" / "voice_eval.py").read_text(encoding="utf-8")
    sites = re.findall(r"fails\.append\(([^\n]*)", source)
    assert len(sites) >= 3, sites
    for site in sites:
        assert site.startswith("Fail(") or site.startswith("x.at_turn"), site
    assert "bad(" in source and re.search(r"def bad\(code: str, text: str\)", source)


def test_a_failures_words_still_read_as_a_plain_string():
    """Every existing reader of `failures` treats them as text; `Fail` must not change that."""
    v = _score(COMMITTED[0], [("manage_item", {"action": "read_list"}, '{"success":true}')], "")
    assert any("expected call" in f for f in v.failures)
    assert json.loads(json.dumps(v.failures)) == [str(f) for f in v.failures]


def test_the_row_carries_the_first_failures_code_and_a_passing_row_carries_none():
    assert ve.reason_of([ve.Fail("len:max_words", "x"), ve.Fail("no_answer", "y")]) == "len:max_words"
    assert ve.reason_of([]) is None
    assert ve.reason_of(["a bare string from nowhere"]) == "unclassified"


def test_the_turn_prefix_keeps_the_code():
    f = ve.Fail("no_answer", "no answer text").at_turn("turn 2: ")
    assert f == "turn 2: no answer text" and f.code == "no_answer"


# ------------------------------------------------- the validator accepts every code a real run can give


PLANTS = [
    ([("manage_item", {"action": "read_list"}, '{"success":true}')], CANARY, True),
    ([], "", True),
    ([("memory", {"action": "remember", "text": CANARY}, '{"success":true}')], "Saved that.", True),
    ([("day", {"action": "read_calendar"}, '{"success":true}')], "Nothing today.", True),
    ([], "A very long reply. " * 40, False),
]


def _codes_from_plants(case: dict) -> set[str]:
    """Every reason code the real scorer emits for this case under each plant."""
    return {f.code for calls, answer, end in PLANTS
            for f in _score(case, calls, answer, end).failures}


@pytest.mark.parametrize("case", COMMITTED, ids=[c["id"] for c in COMMITTED])
def test_a_real_failure_of_a_committed_case_is_accepted(case):
    """Drive the real scorer into failing each committed case, then ask the real validator. A code the
    scorer can emit and the validator rejects would read as `unclassified` on a paid run."""
    seen = _codes_from_plants(case)
    if case.get("known_limit"):
        # A recorded known limit never fails, by Kevin's ruling, so there is nothing to classify.
        assert not seen
        return
    assert seen, "no plant broke this case, so nothing was checked"
    for code in sorted(seen):
        assert vgr.check(code, case, NAMES) == code, f"the validator would drop a real code: {code}"


def test_the_plants_reach_more_than_one_kind_of_code(capsys):
    """What the sweep above actually exercises. A handful of plants cannot reach every failure site in
    the scorer, so the kinds they do reach are printed rather than claimed. Computed here, not shared
    through a global, so this test says the same thing run alone."""
    reached = {c for case in COMMITTED for c in _codes_from_plants(case)}
    kinds = sorted({c.split(":")[0].split("#")[0] for c in reached})
    assert len(kinds) >= 6, kinds
    with capsys.disabled():
        print(f"\nreason kinds the plants reach ({len(kinds)}): {', '.join(kinds)}")


def test_the_allowed_set_is_small_enough_to_carry_little_choice(capsys):
    """The set is per case, so a malicious commit picks from a short list rather than writing free
    text. The three codes that must name a tool nobody wrote down in advance are the exception, and
    are excluded here and stated in the script's own docstring. The measured sizes are printed so
    the claim in the report is a number somebody can re-read, not an adjective."""
    wide = {"chat_called", "write_on_read", "write_in_test_mode"}
    sizes = {}
    for case in COMMITTED:
        narrow = {c for c in vgr.allowed_codes(case, NAMES) if c.split(":")[0] not in wide}
        sizes[case["id"]] = len(narrow)
        assert len(narrow) <= 24, (case["id"], sorted(narrow))
    counts = sorted(sizes.values())
    with capsys.disabled():
        print(f"\nnarrow allowed-set size over {len(counts)} committed cases: "
              f"min {counts[0]}, median {counts[len(counts) // 2]}, max {counts[-1]}")


@pytest.mark.parametrize("forged", [
    CANARY,
    "the model said: " + CANARY,
    # Key-shaped, but spelled so no secret scanner has to think about it.
    "missing_call:NOT_A_REAL_KEY_000000000000000000000000",
    "chat_called:memory/remember_" + CANARY.replace("-", "_"),
    "answer_not#997",
    "len:max_words",
    "forbidden_call:day/read_calendar",
    "args:day/read_calendar.secret",
    "turns:9_of_9",
    "x" * 400,
    "",
    None,
    12,
    {"id": "nested"},
])
def test_a_forged_reason_is_rejected(forged):
    """Every one of these is either not a code at all, or a code THIS case could not have produced."""
    case = {"id": "chat_long_day", "tags": ["chat"], "say": "ha, long day",
            "expect": {"answer_not": ["(?i)saved"]}}
    assert vgr.check(forged, case, NAMES) == "unclassified"


def test_a_code_from_another_case_is_rejected_but_right_at_home_in_its_own():
    chat = {"id": "c", "tags": ["chat"], "say": "hi"}
    bound = {"id": "b", "say": "hi", "expect": {"max_words": 30}}
    assert vgr.check("len:max_words", chat, NAMES) == "unclassified"
    assert vgr.check("len:max_words", bound, NAMES) == "len:max_words"
    assert vgr.check("chat_called:memory", chat, NAMES) == "chat_called:memory"
    assert vgr.check("chat_called:memory", bound, NAMES) == "unclassified"


def test_an_unknown_case_id_can_say_nothing():
    assert vgr.check("len:max_words", None, NAMES) == "unclassified"


def test_an_inconclusive_case_says_so_instead_of_reading_as_a_mystery():
    """An inconclusive case has no failures by design, but the gate's scorecard still reports it as
    not passed, so without this it would upload as `fail` with `unclassified`. No case on today's
    gate list can go inconclusive; `web_answer_then_new_topic` is the one committed case that can,
    and it is the shape used here."""
    case = next(c for c in COMMITTED if c["id"] == "web_answer_then_new_topic")
    def L(tag, msg):
        return f"11-15 10:00:00.000  4242  4242 I {tag}: {msg}"

    lines = [L("LIVE_TEST", f"case_begin id={case['id']}"),
             L("LIVE_AUDIT", "session_start model=eval+gemini-3.5-flash-lite async_set=none")]
    for i, t in enumerate(ve.case_turns(case)):
        lines.append(L("LIVE_AUDIT", f"user final=true text={json.dumps(t['say'])}"))
        if i == 0:  # the search ran, but nothing marked the turn as answered from outside text
            lines.append(L("LIVE_AUDIT", 'tool_call id=e0 name=look_up async=false args={"action":"ask_web"}'))
            lines.append(L("LIVE_AUDIT", 'tool_result id=e0 name=look_up success=true ms=40 '
                                         'scheduling=none sent=true response={"success":true}'))
        lines.append(L("LIVE_AUDIT", f"assistant text={json.dumps('Here is what I found.')}"))
        lines.append(L("LIVE_AUDIT", "turn_complete"))
    lines.append(L("LIVE_AUDIT", 'session_end reason="eval"'))
    lines.append(L("LIVE_TEST", f"case_end id={case['id']} ms=1500"))
    _, runs = ve.parse_run(lines, 2026)
    v = ve.score_case(case, runs[0], phone=False)
    assert v.inconclusive and not v.passed and not v.failures
    _, summary = ve.scorecard([case], runs, {}, phone=False, verbose=False)
    assert summary["cases"][0]["reason"] == "inconclusive"
    assert vgr.check("inconclusive", case, NAMES) == "inconclusive"
    assert vgr.check("inconclusive", {"id": "x", "say": "hi"}, NAMES) == "unclassified"


def test_an_index_is_bounded_by_the_patterns_the_trusted_case_really_has():
    case = {"id": "i", "say": "hi", "expect": {"answer_not": ["a", "b"]}}
    assert vgr.check("answer_not#1", case, NAMES) == "answer_not#1"
    assert vgr.check("answer_not#2", case, NAMES) == "unclassified"


def test_every_grouped_tool_name_a_committed_case_names_can_be_reported():
    """`declared_tools()` lists LEAF tools (`read_calendar`); a tool call's own name is its GROUP
    (`day`, `car`, `look_up`). Without the group names, the commonest real failure this slice exists
    to name, a chat case that wrongly read the calendar, would upload `unclassified`."""
    heads = vgr.spec_heads(TRUSTED)
    assert {"day", "car", "look_up"} <= heads, sorted(heads)
    chat = {"id": "c", "tags": ["chat"], "say": "hi"}
    for head in sorted(heads):
        assert vgr.check(f"chat_called:{head}", chat, NAMES) == f"chat_called:{head}"


def test_a_chat_case_that_really_reads_the_calendar_says_so():
    case = next(c for c in COMMITTED if "chat" in c.get("tags", []) and "turns" not in c)
    v = _score(case, [("day", {"action": "read_calendar"}, '{"success":true}')], "Nothing today.")
    assert not v.passed and ve.reason_of(v.failures) == "chat_called:day"
    assert vgr.check("chat_called:day", case, NAMES) == "chat_called:day"


def test_a_tool_name_only_the_gated_commit_knows_is_rejected():
    case = {"id": "c", "tags": ["chat"], "say": "hi"}
    assert "nosuchtool" not in NAMES
    assert vgr.check("chat_called:nosuchtool", case, NAMES) == "unclassified"


def test_the_lists_come_from_the_tree_the_script_is_pointed_at(tmp_path):
    """Pointed at a tree with no voice surface and no cases, nothing is allowed; it never falls back
    to the gated commit's own copy. A fresh process, because that is how the gate runs it."""
    assert len(NAMES) > 50
    raw = tmp_path / "voice.json"
    raw.write_text(json.dumps({"cases": [{"id": "due_open", "reason": "missing_call:day/read_calendar"}]}),
                   encoding="utf-8")
    card = tmp_path / "card.json"
    card.write_text(json.dumps({"cases": [{"id": "due_open", "status": "fail"}]}), encoding="utf-8")
    empty = tmp_path / "empty-tree"
    empty.mkdir()
    subprocess.run([sys.executable, str(SCRIPTS / "validate_gate_reasons.py"), str(raw), str(card),
                    str(empty)], check=True, capture_output=True, text=True)
    assert json.loads(card.read_text(encoding="utf-8"))["cases"][0]["reason"] == "unclassified"


# ------------------------------------------------------------------- end to end: validator, then scrub


def _bash():
    return shutil.which("bash")


def _scrub(folder: Path, *allowed: str):
    return subprocess.run([_bash(), str(SCRIPTS / "scrub_gate_out.sh"), str(folder), *allowed],
                          capture_output=True, text=True)


@pytest.mark.skipif(not _bash(), reason="needs bash to run the real scrub script")
def test_a_real_reason_survives_the_scrub_and_a_forged_one_never_reaches_it(tmp_path):
    case = COMMITTED[0]
    v = _score(case, [("manage_item", {"action": "read_list"}, '{"success":true}')], CANARY)
    raw = {"cases": [{"id": case["id"], "reason": ve.reason_of(v.failures), "failures": list(v.failures),
                      "answer": CANARY},
                     {"id": "chat_long_day", "reason": "the model replied: " + CANARY}]}
    raw_path = tmp_path / "voice.json"
    raw_path.write_text(json.dumps(raw), encoding="utf-8")
    out = tmp_path / "gate-out"
    out.mkdir()
    card = out / "voice-scorecard.json"
    card.write_text(json.dumps({"requested": "default", "all_passed": False, "cases": [
        {"id": case["id"], "status": "fail"},
        {"id": "chat_long_day", "status": "fail"},
        {"id": "write_todo", "status": "pass"},
    ]}), encoding="utf-8")

    r = subprocess.run([sys.executable, str(SCRIPTS / "validate_gate_reasons.py"), str(raw_path),
                        str(card), str(ROOT)], capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
    rows = {c["id"]: c for c in json.loads(card.read_text(encoding="utf-8"))["cases"]}
    assert rows[case["id"]]["reason"] == "missing_call:day/read_calendar"
    assert rows["chat_long_day"]["reason"] == "unclassified"
    assert "reason" not in rows["write_todo"], "a passing case has nothing to explain"
    assert CANARY not in card.read_text(encoding="utf-8")

    s = _scrub(out, "voice-scorecard.json")
    assert s.returncode == 0, s.stdout + s.stderr
    assert card.exists() and "missing_call:day/read_calendar" in card.read_text(encoding="utf-8")


def test_the_validator_leaves_the_scorecard_alone_when_it_cannot_work(tmp_path):
    """It only ever ADDS a reason, so a bad input costs the run nothing: the artifact is today's."""
    card = tmp_path / "card.json"
    before = json.dumps({"cases": [{"id": "a", "status": "fail"}]})
    card.write_text(before, encoding="utf-8")
    r = subprocess.run([sys.executable, str(SCRIPTS / "validate_gate_reasons.py"),
                        str(tmp_path / "missing.json"), str(card), str(ROOT)],
                       capture_output=True, text=True)
    assert r.returncode == 0 and "skipped" in r.stdout
    assert card.read_text(encoding="utf-8") == before


def test_a_failed_case_the_scorer_said_nothing_about_still_gets_a_word(tmp_path):
    raw = tmp_path / "voice.json"
    raw.write_text(json.dumps({"cases": [{"id": "a"}]}), encoding="utf-8")
    card = tmp_path / "card.json"
    card.write_text(json.dumps({"cases": [{"id": "a", "status": "fail"}]}), encoding="utf-8")
    subprocess.run([sys.executable, str(SCRIPTS / "validate_gate_reasons.py"), str(raw), str(card),
                    str(ROOT)], check=True, capture_output=True, text=True)
    assert json.loads(card.read_text(encoding="utf-8"))["cases"][0]["reason"] == "unclassified"


# ------------------------------------------------------------------------------- the engine's node id

GATE_FILE = "tests/realrun_web_cascade.py"
SOURCE = "import pytest\n\n\ndef test_realrun_gate():\n    x = 1\n" + "    pass\n" * 300
PYTEST_OUT = f"""
=================================== FAILURES ===================================
_____________________________ test_realrun_gate ______________________________
>       assert heard, ("no amount heard in a spending answer", "{CANARY}")
E       AssertionError: ('no amount heard in a spending answer', '{CANARY}')

{GATE_FILE}:214: AssertionError
=========================== short test summary info ============================
FAILED {GATE_FILE}::test_realrun_gate - AssertionError: {CANARY}
"""


def test_the_engine_says_where_it_broke_and_nothing_else():
    lines = gfw.where(PYTEST_OUT, GATE_FILE, SOURCE)
    assert lines == [f"node: {GATE_FILE}::test_realrun_gate", "assert_line: 214"]
    assert CANARY not in "\n".join(lines)


def test_a_test_that_prints_its_own_fake_traceback_is_not_believed():
    forged = f"""
_____________________________ test_{CANARY.replace('-', '_')} ______________________________
FAILED other/file.py::test_not_in_this_file - x
other/file.py:3: AssertionError
"""
    assert gfw.where(forged, GATE_FILE, SOURCE) == ["node: unclassified"]


def test_a_line_number_past_the_end_of_the_file_is_dropped():
    out = f"_____ test_realrun_gate _____\n{GATE_FILE}:99999: AssertionError\n"
    assert gfw.where(out, GATE_FILE, SOURCE) == [f"node: {GATE_FILE}::test_realrun_gate"]


def test_a_line_number_from_another_file_is_not_this_files_line():
    out = f"_____ test_realrun_gate _____\nsite-packages/anything.py:12: AssertionError\n"
    assert gfw.where(out, GATE_FILE, SOURCE) == [f"node: {GATE_FILE}::test_realrun_gate"]


def test_a_function_name_with_no_def_in_the_gated_file_is_unclassified():
    assert gfw.where("_____ test_realrun_gate _____\n", GATE_FILE, "def something_else():\n    pass\n") \
        == ["node: unclassified"]


def test_the_shape_it_parses_is_the_shape_the_real_gate_test_has():
    """`gate_fail_where.py` only believes a name it can find a `def` for in the file the gate ran. If
    the real gate test were a method or an `async def` this would print `unclassified` on every real
    failure, which is the incident this slice exists for."""
    real = ROOT / "server" / "tests" / "realrun_web_cascade.py"
    if not real.exists():
        pytest.skip("no server tree in this checkout")
    source = real.read_text(encoding="utf-8")
    assert "test_realrun_gate" in gfw.where(
        f"_____ test_realrun_gate _____\n{GATE_FILE}:1: AssertionError\n", GATE_FILE, source)[0]


FAILING_TEST = f'''def test_realrun_gate():
    heard = []
    assert heard, ("no amount heard in a spending answer", "{CANARY}")
'''


@pytest.mark.skipif(not _bash(), reason="needs bash to run the real engine gate script")
def test_the_engine_gate_script_writes_the_node_id_into_its_summary(tmp_path):
    """The real `engine_live_gate.sh` over REAL pytest on a test that fails the way a non-heard-back
    assert does, so the output shape is pytest's and not my guess. Its summary must say where it
    broke, carry no canary, and pass the real scrub."""
    server = tmp_path / "server"
    (server / "tests").mkdir(parents=True)
    (server / "tests" / "realrun_web_cascade.py").write_text(FAILING_TEST, encoding="utf-8")
    out = tmp_path / "gate-raw"
    env = {**os.environ, "PYTEST": f"{sys.executable} -m pytest", "PYTHON3": sys.executable}
    r = subprocess.run([_bash(), str(SCRIPTS / "engine_live_gate.sh"), str(server), str(out), "", "0.12"],
                       capture_output=True, text=True, env=env)
    summary = (out / "summary.txt").read_text(encoding="utf-8")
    raw = (out / "raw" / "gate" / "pytest.txt").read_text(encoding="utf-8")
    assert r.returncode == 1
    assert CANARY in raw, "the plant must really fail, or this test proves nothing"
    assert f"node: {GATE_FILE}::test_realrun_gate" in summary
    assert "assert_line: 3" in summary, summary
    assert "RESULT: FAIL" in summary
    assert CANARY not in summary

    folder = tmp_path / "gate-out"
    folder.mkdir()
    (folder / "engine-summary.txt").write_text(summary, encoding="utf-8")
    s = _scrub(folder, "engine-summary.txt")
    assert s.returncode == 0, s.stdout + s.stderr
    assert (folder / "engine-summary.txt").exists()


# ------------------------------------------------------------------ the workflow really calls all this

GATES = (ROOT / ".github" / "workflows" / "gates.yml").read_text(encoding="utf-8")
ENGINE_GATE = (SCRIPTS / "engine_live_gate.sh").read_text(encoding="utf-8")


def test_gates_yml_validates_the_reason_from_the_trusted_checkout():
    """Every line that RUNS the validator must run the trusted checkout's copy, never the gated
    commit's own. A comment may name the repo path; a command may not."""
    runs = [l.strip() for l in GATES.splitlines()
            if "validate_gate_reasons.py" in l and not l.strip().startswith("#")]
    assert runs, "nothing in gates.yml runs the validator"
    for line in runs:
        assert "trusted/.github/scripts/validate_gate_reasons.py" in line, line


def test_the_engine_job_writes_a_summary_before_the_gate_can_fail():
    assert "RESULT: UNKNOWN (gate did not start)" in GATES
    assert GATES.index("RESULT: UNKNOWN") < GATES.index("Engine live gate")


def test_the_engine_gate_script_asks_where_on_every_failure_it_can():
    assert ENGINE_GATE.count("where ") >= 4
    assert "gate_fail_where.py" in ENGINE_GATE


def test_the_gate_still_spends_what_it_did():
    """Slice 3 may not raise the gate's spend or add a secret, an environment or a trigger."""
    assert ENGINE_GATE.count("REALRUN_BUDGET_USD=0.09") == 1
    assert ENGINE_GATE.count("gate_run ") == 2  # the first run and the one retry, as before
    assert GATES.count("MAX_RUNS_PER_DAY: '6'") == 1
    assert GATES.count("secrets.GEMINI_API_KEY") == 4
    assert "workflow_dispatch" in GATES and "pull_request" not in GATES and "schedule" not in GATES
