"""procedures ticket 11: every load-time rule refuses its violation with a sentence naming it.

Rulings: ADR 0070, `.scratch/procedures/issues/01` (kinds, DAG, computed writes), `02` (decide
picks a named branch; never in a writing procedure), `03` (every write behind an ask unless the
request named it), `06` (no read-through tool in a step).
"""

from __future__ import annotations

import copy
import re
from pathlib import Path

import pytest

from procedures import steps
from procedures.definition import (
    READ_THROUGH_TOOLS,
    STEP_KINDS,
    DefinitionError,
    validate,
)

REPO = Path(__file__).resolve().parents[2]


@pytest.fixture(autouse=True)
def _functions(monkeypatch):
    monkeypatch.setitem(steps.FUNCTIONS, "any_past_due", lambda **_: True)
    monkeypatch.setitem(steps.FUNCTIONS, "compose", lambda **_: "text")


def read_only() -> dict:
    return {
        "name": "car_check_test",
        "description": "What is due on the car.",
        "inputs": {"vehicle": {"description": "Which car; all when absent."}},
        "steps": [
            {"id": "services", "kind": "tool", "tool": "next_service",
             "args": {"vehicle": "$inputs.vehicle"}, "next": "recalls"},
            {"id": "recalls", "kind": "tool", "tool": "vehicle_recalls", "next": "due"},
            {"id": "due", "kind": "check", "function": "any_past_due",
             "args": {"services": "$steps.services"},
             "fail_sentence": "Nothing on the car is past due.", "next": "answer"},
            {"id": "answer", "kind": "say",
             "template": "{$steps.services} {$steps.recalls}"},
        ],
    }


def writing() -> dict:
    return {
        "name": "log_event_test",
        "description": "Add an event after asking.",
        "steps": [
            {"id": "confirm", "kind": "ask", "question": "Add it?",
             "answers": {"yes": "add", "no": "cancelled"}},
            {"id": "add", "kind": "tool", "tool": "add_event", "next": "done"},
            {"id": "done", "kind": "say", "template": "Added."},
            {"id": "cancelled", "kind": "say", "template": "Nothing was added."},
        ],
    }


def refused(definition) -> str:
    with pytest.raises(DefinitionError) as caught:
        validate(definition)
    return " ".join(caught.value.problems)


def test_the_six_kinds_are_the_closed_set():
    assert STEP_KINDS == ("tool", "compute", "decide", "ask", "check", "say")


def test_a_read_only_definition_passes_and_does_not_write():
    checked = validate(read_only())
    assert checked.writes is False
    assert ("due", "next", "answer") in checked.edges


def test_writes_is_computed_from_the_tools_never_declared():
    assert validate(writing()).writes is True
    declared = read_only()
    declared["writes"] = False
    assert "writes" in refused(declared)


def test_an_unknown_kind_is_refused_naming_the_step():
    d = read_only()
    d["steps"][1]["kind"] = "loop"
    text = refused(d)
    assert "'recalls'" in text and "loop" in text


def test_a_cycle_is_refused():
    d = read_only()
    d["steps"][2]["next"] = "services"
    assert "cycle" in refused(d)


def test_a_step_that_goes_to_itself_is_refused():
    d = read_only()
    d["steps"][1]["next"] = "recalls"
    assert "goes to itself" in refused(d)


def test_an_unknown_tool_is_refused():
    d = read_only()
    d["steps"][1]["tool"] = "vehicle_recall"
    assert "'vehicle_recall', which is not an engine tool" in refused(d)


@pytest.mark.parametrize("tool", sorted(READ_THROUGH_TOOLS))
def test_a_read_through_tool_is_refused(tool):
    d = read_only()
    d["steps"][1]["tool"] = tool
    assert "third-party content" in refused(d)


def test_a_procedure_may_not_call_a_procedure():
    d = read_only()
    d["steps"][1]["tool"] = "run_procedure"
    assert "may not call a procedure" in refused(d)


def test_an_unknown_function_is_refused():
    d = read_only()
    d["steps"][2]["function"] = "nope"
    assert "'nope', which is not in procedures/steps.py" in refused(d)


def test_a_dangling_target_is_refused():
    d = read_only()
    d["steps"][0]["next"] = "missing"
    assert "there is no step by that name" in refused(d)


def test_a_step_that_can_never_run_is_refused():
    d = read_only()
    d["steps"].append({"id": "orphan", "kind": "say", "template": "x"})
    assert "'orphan' can never run" in refused(d)


def test_every_path_ends_at_a_say():
    d = read_only()
    del d["steps"][2]["next"]
    d["steps"].pop()
    d["steps"][2]["args"] = {}
    assert "leads nowhere" in refused(d)


def test_a_say_step_has_no_next():
    d = read_only()
    d["steps"][3]["next"] = "services"
    assert "'answer'" in refused(d)


def test_duplicate_step_ids_are_refused():
    d = read_only()
    d["steps"][1]["id"] = "services"
    assert "two steps are called 'services'" in refused(d)


def test_reading_a_step_that_has_not_run_is_refused():
    d = read_only()
    d["steps"][0]["args"] = {"x": "$steps.recalls"}
    assert "does not run before it" in refused(d)


def test_reading_an_undeclared_input_is_refused():
    d = read_only()
    d["steps"][1]["args"] = {"vin": "$inputs.vin"}
    assert "$inputs.vin" in refused(d)


def test_a_write_with_no_ask_before_it_is_refused():
    d = writing()
    d["steps"] = [
        {"id": "add", "kind": "tool", "tool": "add_event", "next": "done"},
        {"id": "done", "kind": "say", "template": "Added."},
    ]
    assert "without an ask step first" in refused(d)


def test_an_ask_after_the_write_does_not_count():
    d = copy.deepcopy(writing())
    d["steps"] = [
        {"id": "add", "kind": "tool", "tool": "add_event", "next": "confirm"},
        {"id": "confirm", "kind": "ask", "question": "Was that right?",
         "answers": {"yes": "done", "no": "cancelled"}},
        {"id": "done", "kind": "say", "template": "Added."},
        {"id": "cancelled", "kind": "say", "template": "Noted."},
    ]
    assert "'add' writes with 'add_event'" in refused(d)


def test_a_write_the_request_named_needs_no_ask():
    d = writing()
    d["steps"] = [
        {"id": "add", "kind": "tool", "tool": "add_event", "named_by_request": True,
         "next": "done"},
        {"id": "done", "kind": "say", "template": "Added."},
    ]
    assert validate(d).writes is True


def test_named_by_request_on_a_read_is_refused():
    d = read_only()
    d["steps"][0]["named_by_request"] = True
    assert "does not write" in refused(d)


def decide_only() -> dict:
    """The decide kind, proven with a test procedure (ruling 02: none of the first ones uses it)."""
    return {
        "name": "recall_worth_raising",
        "description": "Whether an open recall is worth raising.",
        "steps": [
            {"id": "recalls", "kind": "tool", "tool": "vehicle_recalls", "next": "judge"},
            {"id": "judge", "kind": "decide", "question": "Is any recall safety-critical?",
             "args": {"recalls": "$steps.recalls"},
             "branches": {"raise": "loud", "skip": "quiet"}, "on_fail": "skip"},
            {"id": "loud", "kind": "say", "template": "(a model chose to raise this) ..."},
            {"id": "quiet", "kind": "say", "template": "Nothing to raise."},
        ],
    }


def test_a_decide_step_with_named_branches_passes():
    checked = validate(decide_only())
    assert checked.writes is False
    assert {("judge", "raise", "loud"), ("judge", "skip", "quiet")} <= set(checked.edges)


def test_a_decide_step_needs_two_branches():
    d = decide_only()
    d["steps"][1]["branches"] = {"raise": "loud"}
    d["steps"][1]["on_fail"] = "raise"
    d["steps"].pop()
    assert "'judge'" in refused(d)


def test_decide_falls_back_to_one_of_its_own_branches():
    d = decide_only()
    d["steps"][1]["on_fail"] = "maybe"
    assert "which is not one of its branches" in refused(d)


def test_a_decide_step_in_a_writing_procedure_is_refused():
    d = writing()
    d["steps"].insert(0, {"id": "judge", "kind": "decide", "question": "Add it?",
                          "branches": {"go": "confirm", "stop": "cancelled"},
                          "on_fail": "stop"})
    assert "a model may not steer which write happens" in refused(d)


def test_an_ask_step_needs_its_answers():
    d = writing()
    d["steps"][0]["answers"] = {}
    assert "'confirm'" in refused(d)


def test_a_problem_names_the_procedure():
    d = read_only()
    d["steps"][1]["tool"] = "nope"
    assert refused(d).startswith("In 'car_check_test', step 'recalls'")


def test_the_read_through_set_mirrors_the_phones():
    """`LiveToolbox.EPISODIC_EXCLUDED_TOOLS` is the source; this copy must not drift."""
    kotlin = REPO / "app/src/main/java/com/kevin/legion/service/LiveToolbox.kt"
    if not kotlin.exists():
        pytest.skip("app/ is not in this checkout (the engine image is server/ alone)")
    text = kotlin.read_text(encoding="utf-8")
    block = re.search(r"EPISODIC_EXCLUDED_TOOLS\s*=\s*setOf\((.*?)\)", text, re.S)
    assert block, "EPISODIC_EXCLUDED_TOOLS moved; update this test and the mirror"
    assert set(re.findall(r'"([a-z_]+)"', block.group(1))) == READ_THROUGH_TOOLS
