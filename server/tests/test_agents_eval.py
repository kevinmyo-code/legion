"""engine-brain ticket 02: the eval runner.

- **Graders** (no model): each check against made-up turn records.
- **Cassette replay** (no network, seconds): `voice21`'s recorded model output, replayed through the
  real turn code on the invented household with the clock frozen at the recording; every case-run
  must grade exactly as it did when recorded live, and the suite must still meet its bar.
- **Live** (`test_voice21_live`): real Gemini, skipped unless `LEGION_AGENT_EVAL_LIVE=1` and
  `LEGION_GEMINI_KEY_FILE` names a key file (never printed). Run by the laptop ship script on a
  throwaway Postgres; `LEGION_AGENT_EVAL_RECORD=1` rewrites the cassette, only when the run passed;
  `LEGION_AGENT_EVAL_REPORT` names a scorecard file outside the repo.
"""

from __future__ import annotations

import datetime as dt
import json
import os
from pathlib import Path

import pytest

from agents.evals import graders, runner
from agents.runtime.model import GeminiClient

TODAY = dt.date(2026, 3, 10)


def _record(final_text="", calls=()):
    return {"say": "x", "final_text": final_text, "calls": list(calls)}


def _call(name, args=None, ok=True, text="", writes=False):
    return {"name": name, "args": args or {}, "ok": ok, "text": text, "writes": writes}


def test_graders_check_calls_args_and_answers():
    rec = _record(
        "A quiz and a discussion post.",
        [_call("read_calendar", {"from": "2026-03-10", "to": "2026-03-10"})],
    )
    expect = {
        "calls": ["read_calendar"],
        "args": {"read_calendar": {"from": {"lte": "{today}"}, "to": {"gte": "{today}"}}},
        "answer_has": ["quiz", "discussion"],
    }
    assert graders.grade_turn(rec, expect, TODAY) == []
    wrong_day = {"args": {"read_calendar": {"from": {"eq": "{today+1}"}}}}
    assert graders.grade_turn(rec, wrong_day, TODAY)
    assert graders.grade_turn(rec, {"calls": ["read_list"]}, TODAY)
    assert graders.grade_turn(rec, {"answer_not": ["quiz"]}, TODAY)


def test_relative_day_words_in_args_count_as_their_dates():
    rec = _record("ok.", [_call("read_calendar", {"from": "tomorrow", "to": "tomorrow"})])
    expect = {
        "args_all": {"read_calendar": {"from": {"eq": "{today+1}"}, "to": {"eq": "{today+1}"}}}
    }
    assert graders.grade_turn(rec, expect, TODAY) == []


def test_an_empty_claim_over_open_work_fails():
    read = _call("read_calendar", text=json.dumps({"due": {"open": 2}}))
    assert graders.grade_turn(
        _record("Nothing is due, you're all clear.", [read]), {"no_empty_claim": True}, TODAY
    )
    closed = _call("read_calendar", text=json.dumps({"due": {"open": 0}}))
    assert not graders.grade_turn(
        _record("Nothing is due today.", [closed]), {"no_empty_claim": True}, TODAY
    )


def test_an_empty_reply_and_an_unbacked_claim_always_fail():
    assert "empty reply" in graders.grade_turn(_record(""), {}, TODAY)
    reasons = graders.grade_turn(_record("Done. Added it."), {}, TODAY)
    assert any("honesty" in r for r in reasons)
    backed = _record("Done. Added it.", [_call("add_checklist_item", writes=True)])
    assert graders.grade_turn(backed, {}, TODAY) == []


def test_succeeded_and_result_has_read_only_successful_calls():
    failed = _call("update_event", ok=False, text="HIST 101 reading quiz")
    assert graders.grade_turn(_record("x", [failed]), {"succeeded": ["update_event"]}, TODAY)
    assert graders.grade_turn(_record("x", [failed]), {"result_has": ["HIST 101"]}, TODAY)


def test_a_dotted_path_and_a_fixture_id_match():
    from agents.evals.fixture import rid

    rec = _record(
        "ok.",
        [
            _call("add_event", {"fields": {"starts_at": "2026-03-11T09:00:00"}}),
            _call("tick_checklist_item", {"item_id": str(rid("Buy printer ink"))}),
        ],
    )
    expect = {
        "args": {
            "add_event": {"fields.starts_at": {"re": "{today+1}[T ]09:00"}},
            "tick_checklist_item": {"item_id": {"rid": "Buy printer ink"}},
        }
    }
    assert graders.grade_turn(rec, expect, TODAY) == []


def test_voice21_ports_twenty_cases_and_names_the_one_it_cannot():
    suite = runner.load_suite("voice21")
    assert len(suite["cases"]) == 20
    assert [c["id"] for c in suite["not_ported"]] == ["switch_car"]
    assert suite["runs"] == 3 and 0 < suite["bar"] <= 1


def test_private_cases_are_git_ignored():
    root = Path(__file__).resolve().parents[2]
    ignore = (root / ".gitignore").read_text(encoding="utf-8")
    assert "server/agents/evals/private/" in ignore


# =============================================================================
# Cassette replay (CI: no key, no network)
# =============================================================================


def test_homes_covers_every_ticket_03_tool():
    suite = runner.load_suite("homes")
    called = set()
    for case in suite["cases"]:
        for turn in case["turns"]:
            for entry in turn["expect"].get("calls", []):
                called.update(entry.split("|"))
    assert {
        "list_goals",
        "set_goal",
        "close_goal",
        "log_pending_transaction",
        "list_pending_transactions",
        "clear_pending_transaction",
        "recent_advice",
        "why_did_you_say_that",
    } <= called
    assert suite["runs"] == 3 and 0 < suite["bar"] <= 1


# Suites written but not yet recorded live: their replay is skipped with this reason, never
# faked. An explicit map, so a deleted cassette of a recorded suite still fails loudly instead of
# skipping. `records` (ticket 15) was recorded live on 2026-11-17 (27/27).
UNRECORDED_SUITES: dict[str, str] = {
    "garage_gate": (
        "the web cascade's live gate for show_part (switch off until it passes); this runner "
        "drives the agents door, which never declares show_part"
    ),
}
SUITES = sorted(p.stem for p in runner.CASES.glob("*.yaml"))


def test_every_suite_is_recorded_or_named_unrecorded():
    for name in SUITES:
        has_cassette = (runner.CASSETTES / f"{name}.json").exists()
        assert has_cassette != (name in UNRECORDED_SUITES), name


def test_records_covers_every_ticket_15_tool():
    suite = runner.load_suite("records")
    called = set()
    for case in suite["cases"]:
        for turn in case["turns"]:
            for entry in turn["expect"].get("calls", []):
                called.update(entry.split("|"))
    from homes.records_tools import RECORD_TOOLS

    assert {t.name for t in RECORD_TOOLS} <= called
    assert suite["runs"] == 3 and 0 < suite["bar"] <= 1


@pytest.mark.django_db
@pytest.mark.parametrize("name", SUITES)
def test_suite_replays_from_its_cassette_and_grades_as_recorded(name, household_user, household_a):
    if name in UNRECORDED_SUITES:
        pytest.skip(UNRECORDED_SUITES[name])
    cassette = runner.read_cassette(name)
    suite = runner.load_suite(name)
    result, _ = runner.run_suite(suite, household_user, household_a, cassette=cassette)
    graded = cassette["graded"]
    for case in result.cases:
        got = [not r["reasons"] for r in case.runs]
        assert got == graded[case.id], (case.id, [r["reasons"] for r in case.runs])
    assert result.rate >= result.bar, "\n".join(result.lines())


@pytest.mark.parametrize("name", [n for n in SUITES if n not in UNRECORDED_SUITES])
def test_the_cassette_holds_model_output_only(name):
    raw = (runner.CASSETTES / f"{name}.json").read_text(encoding="utf-8")
    for marker in ("x-goog-api-key", "generativelanguage.googleapis.com", "AIza", "Authorization"):
        assert marker not in raw
    key_file = os.environ.get("LEGION_GEMINI_KEY_FILE")
    if key_file and Path(key_file).is_file():
        assert Path(key_file).read_text(encoding="utf-8").strip() not in raw


# =============================================================================
# Live (the laptop ship script only; ruling 13: no CI key)
# =============================================================================


@pytest.mark.live_model
@pytest.mark.django_db
@pytest.mark.skipif(
    not os.environ.get("LEGION_AGENT_EVAL_LIVE"), reason="live eval: set LEGION_AGENT_EVAL_LIVE=1"
)
def test_voice21_live(household_user, household_a):
    key_file = Path(os.environ.get("LEGION_GEMINI_KEY_FILE", ""))
    assert key_file.is_file(), "LEGION_GEMINI_KEY_FILE must name the key file"
    key = key_file.read_text(encoding="utf-8").strip()
    household_user.first_name = "Sam"
    household_user.save(update_fields=["first_name"])
    suite = runner.load_suite(os.environ.get("LEGION_AGENT_EVAL_SUITE", "voice21"))
    runs = int(os.environ.get("LEGION_AGENT_EVAL_RUNS", suite.get("runs", 3)))
    only = {s for s in os.environ.get("LEGION_AGENT_EVAL_ONLY", "").split(",") if s} or None
    max_usd = float(os.environ.get("LEGION_AGENT_EVAL_MAX_USD", "2.50"))
    result, recorded = runner.run_suite(
        suite,
        household_user,
        household_a,
        live_model=GeminiClient(key=lambda: key),
        runs=runs,
        only=only,
        max_cost_micro_usd=int(max_usd * 1_000_000),
    )
    lines = result.lines()
    report = os.environ.get("LEGION_AGENT_EVAL_REPORT")
    if report:
        detail = {c.id: [r["turns"] for r in c.runs] for c in result.cases}
        Path(report).write_text(
            "\n".join(lines) + "\n\n" + json.dumps(detail, indent=1, ensure_ascii=False),
            encoding="utf-8",
        )
    if os.environ.get("LEGION_AGENT_EVAL_RECORD"):
        # Only a whole passing run is kept (`runner.keep_recording`); a failing one never
        # overwrites the cassette already on disk.
        runner.keep_recording(result, recorded, only=only, secret=key)
    print("\n".join(lines))
    assert result.ok, "\n".join(lines)


# =============================================================================
# The record guard (stage 2's finding: a failing live run overwrote voice21.json)
# =============================================================================


class _Answers:
    """A stand-in for the live model: every round answers `text`, no tool call."""

    def __init__(self, text):
        self.text = text

    def stream(self, req):
        from agents.runtime import model as m

        yield from m.say(self.text)


_GUARD_SUITE = {
    "suite": "guard",
    "bar": 1.0,
    "runs": 1,
    "cases": [
        {"id": "hello", "turns": [{"say": "say hello", "expect": {"answer_has": ["hello"]}}]}
    ],
}


@pytest.mark.django_db
def test_a_failing_live_run_never_overwrites_the_cassette(household_user, household_a, tmp_path):
    path = tmp_path / "guard.json"
    path.write_text('{"kept": true}' + chr(10), encoding="utf-8")
    before = path.read_bytes()
    result, recorded = runner.run_suite(
        _GUARD_SUITE, household_user, household_a, live_model=_Answers("goodbye")
    )
    assert not result.ok
    assert runner.keep_recording(result, recorded, path=path) is None
    assert path.read_bytes() == before


@pytest.mark.django_db
def test_a_passing_live_run_writes_the_cassette(household_user, household_a, tmp_path):
    path = tmp_path / "guard.json"
    path.write_text('{"kept": true}' + chr(10), encoding="utf-8")
    result, recorded = runner.run_suite(
        _GUARD_SUITE, household_user, household_a, live_model=_Answers("hello there")
    )
    assert result.ok
    assert runner.keep_recording(result, recorded, path=path) == path
    written = json.loads(path.read_text(encoding="utf-8"))
    assert written["graded"] == {"hello": [True]}
    assert written["runs"]["hello"]


@pytest.mark.django_db
def test_a_subset_or_a_spend_stop_never_writes(household_user, household_a, tmp_path):
    path = tmp_path / "guard.json"
    result, recorded = runner.run_suite(
        _GUARD_SUITE, household_user, household_a, live_model=_Answers("hello")
    )
    assert runner.keep_recording(result, recorded, only={"hello"}, path=path) is None
    result.stopped = "spend stop"
    assert runner.keep_recording(result, recorded, path=path) is None
    assert not path.exists()


def test_a_recording_holding_the_key_is_never_written(tmp_path):
    result = runner.SuiteResult("guard", 0.0, [])  # a passing (empty) run
    path = tmp_path / "guard.json"
    with pytest.raises(ValueError):
        runner.keep_recording(
            result, {"suite": "guard", "runs": {"x": "AIzaFAKE"}}, secret="AIzaFAKE", path=path
        )
    assert not path.exists()
