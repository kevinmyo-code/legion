"""procedures tickets 12 and 13: the runner, the `run_procedure` tool, and the run traces.

Rulings: ADR 0070; `.scratch/procedures/issues/02` (decide), `03` (ask), `04` (latency budget),
`06` (traces), `07` (recruiters). Every run here goes through a real door (`/api/assistant/tool`
as a signed-in browser, or `/mcp` with a device token), so the fences under test are the ones a
client meets. No built-in ships yet (ticket 15): each test installs its own fixture procedure.
"""

from __future__ import annotations

import dataclasses
import json
from datetime import timedelta

import pytest
from django.utils import timezone

from devicelogs.models import DeviceLog
from engine_mcp import tools as engine_tools
from engine_mcp.tools import TOOLS_BY_NAME
from household.models import DeviceToken
from ingest.drive import Response
from procedures import decide, retention, runner, steps
from procedures.definition import validate
from procedures.models import (
    ChosenBy,
    Procedure,
    ProcedureRun,
    ProcedureStep,
    ProcedureVersion,
    RunStatus,
    Source,
)
from tests.test_assistant import browser, google  # noqa: F401 - fixture
from tests.test_engine_mcp import call, client_with, rpc
from tests.test_recruiter import demo_household, visitor  # noqa: F401 - fixtures

pytestmark = pytest.mark.django_db


# =============================================================================
# Fixtures
# =============================================================================


@pytest.fixture(autouse=True)
def _functions(monkeypatch):
    monkeypatch.setitem(steps.FUNCTIONS, "count_lines", lambda text="": len((text or "").split()))
    monkeypatch.setitem(steps.FUNCTIONS, "is_positive", lambda n=0: (n or 0) > 0)
    monkeypatch.setitem(steps.FUNCTIONS, "explode", lambda **_: 1 / 0)


def install(definition: dict, household=None) -> ProcedureVersion:
    checked = validate(definition)
    procedure = Procedure.objects.create(household=household, name=definition["name"])
    return ProcedureVersion.objects.create(
        procedure=procedure,
        household=household,
        version=1,
        definition=definition,
        source=Source.HAND if household else Source.BUILTIN,
        content_sha256="0" * 64,
        writes=checked.writes,
        is_active=True,
        approved_at=timezone.now(),
    )


def three_steps(name="checklist_check") -> dict:
    return {
        "name": name,
        "description": "What is on the checklists.",
        "steps": [
            {"id": "lists", "kind": "tool", "tool": "list_checklists", "next": "count"},
            {
                "id": "count",
                "kind": "compute",
                "function": "count_lines",
                "args": {"text": "$steps.lists.text"},
                "next": "answer",
            },
            {"id": "answer", "kind": "say", "template": "Lists: {$steps.lists}"},
        ],
    }


def with_check(name="checked") -> dict:
    return {
        "name": name,
        "description": "Stops early when a check fails.",
        "inputs": {"n": {"description": "A number."}},
        "steps": [
            {
                "id": "positive",
                "kind": "check",
                "function": "is_positive",
                "args": {"n": "$inputs.n"},
                "fail_sentence": "Nothing to report.",
                "next": "answer",
            },
            {"id": "answer", "kind": "say", "template": "It was {$inputs.n}."},
        ],
    }


def writing(name="add_reminder") -> dict:
    return {
        "name": name,
        "description": "Adds an event after asking.",
        "inputs": {"title": {"description": "The event's title.", "required": True}},
        "steps": [
            {
                "id": "confirm",
                "kind": "ask",
                "question": "Add it?",
                "answers": {"yes": "add", "no": "stop"},
            },
            {
                "id": "add",
                "kind": "tool",
                "tool": "add_event",
                "args": {"fields": {"title": "$inputs.title"}},
                "next": "done",
            },
            {"id": "done", "kind": "say", "template": "{$steps.add}"},
            {"id": "stop", "kind": "say", "template": "Nothing was added."},
        ],
    }


def deciding(name="pick_one") -> dict:
    return {
        "name": name,
        "description": "A model picks the branch.",
        "steps": [
            {"id": "lists", "kind": "tool", "tool": "list_checklists", "next": "which"},
            {
                "id": "which",
                "kind": "decide",
                "question": "Short or long?",
                "args": {"lists": "$steps.lists.text"},
                "branches": {"short": "short", "long": "long"},
                "on_fail": "short",
            },
            {"id": "short", "kind": "say", "template": "Short."},
            {"id": "long", "kind": "say", "template": "Long."},
        ],
    }


def run(client, name, **args):
    response = client.post(
        "/api/assistant/tool",
        {"name": "run_procedure", "args": {"name": name, **args}},
        format="json",
    )
    assert response.status_code in (200, 400), response.data
    return response


@pytest.fixture
def member_web(household_user):
    return browser(household_user)


def last_run() -> ProcedureRun:
    return ProcedureRun.objects.order_by("-started_at").first()


# =============================================================================
# Ticket 13: one run, its steps
# =============================================================================


def test_a_three_step_run_writes_one_run_and_three_step_rows(member_web, household_a):
    install(three_steps())
    response = run(member_web, "checklist_check")
    assert response.data["is_error"] is False, response.data
    text = response.data["text"]
    assert text.startswith("Lists: ")
    assert "ran lists, count, answer" in text and "Nothing was written." in text
    assert ProcedureRun.objects.count() == 1
    trace = last_run()
    assert trace.status == RunStatus.OK and trace.household == household_a
    assert trace.caller_kind == "web" and trace.resume_state is None
    rows = list(trace.steps.all())
    assert [(r.step_id, r.kind, r.outcome, r.branch) for r in rows] == [
        ("lists", "tool", "ok", "next"),
        ("count", "compute", "ok", "next"),
        ("answer", "say", "ok", ""),
    ]
    assert rows[0].tool == "list_checklists" and rows[0].args == {}
    assert all(r.household_id == household_a.id for r in rows)
    assert all(len(r.result) <= 403 for r in rows)


def test_a_failed_tool_mid_run_stores_failed_and_its_sentence(member_web, monkeypatch):
    install(three_steps())
    tool = TOOLS_BY_NAME["list_checklists"]

    def boom(request, args):
        raise RuntimeError("down")

    monkeypatch.setitem(
        engine_tools.TOOLS_BY_NAME, "list_checklists", dataclasses.replace(tool, handler=boom)
    )
    response = run(member_web, "checklist_check")
    assert response.data["is_error"] is True
    text = response.data["text"]
    assert "did not finish" in text and "did not run: count, answer" in text
    assert "Nothing was written." in text
    trace = last_run()
    assert trace.status == RunStatus.FAILED
    (row,) = trace.steps.all()
    assert row.outcome == "failed" and "nothing is known from this call" in row.result


def test_a_failing_compute_after_a_good_tool_is_partial_and_keeps_what_ran(member_web):
    definition = three_steps("explodes")
    definition["steps"][1]["function"] = "explode"
    install(definition)
    response = run(member_web, "explodes")
    assert response.data["is_error"] is True
    assert "ZeroDivisionError" in response.data["text"]
    assert "What the steps that ran said: lists:" in response.data["text"]
    assert last_run().status == RunStatus.PARTIAL


def test_an_optional_input_not_sent_is_left_out_of_the_tools_arguments(member_web):
    """Ticket 15: a `$inputs.x` that was not sent fills to nothing, and the argument is left out
    (the tool's own default applies) rather than sent as a null its schema would refuse."""
    definition = three_steps("by_day")
    definition["inputs"] = {"day": {"description": "A date."}}
    definition["steps"][0]["args"] = {"date": "$inputs.day"}
    install(definition)
    response = run(member_web, "by_day")
    assert response.data["is_error"] is False, response.data["text"]
    assert last_run().steps.get(step_id="lists").args == {}
    response = run(member_web, "by_day", inputs={"day": "2026-11-03"})
    assert response.data["is_error"] is False, response.data["text"]
    assert last_run().steps.get(step_id="lists").args == {"date": "2026-11-03"}


def test_a_partial_answer_leaves_out_rows_a_compute_already_put_into_words(member_web, monkeypatch):
    """Ticket 15: when a run stops, a tool whose rows an ok compute step summarised, and which
    no say repeats, is not dumped back; the compute's sentence is said instead."""
    monkeypatch.setitem(steps.FUNCTIONS, "words", lambda text="": "summarised in words")
    definition = {
        "name": "summarises",
        "description": "Reads, summarises, then fails.",
        "steps": [
            {"id": "lists", "kind": "tool", "tool": "list_checklists", "next": "sum"},
            {"id": "sum", "kind": "compute", "function": "words",
             "args": {"text": "$steps.lists.text"}, "next": "boom"},
            {"id": "boom", "kind": "compute", "function": "explode", "next": "answer"},
            {"id": "answer", "kind": "say", "template": "{$steps.sum}"},
        ],
    }
    install(definition)
    text = run(member_web, "summarises").data["text"]
    assert "What the steps that ran said: sum: summarised in words" in text
    assert "lists:" not in text


def test_a_failing_check_ends_with_its_sentence_and_is_an_answer(member_web):
    install(with_check())
    response = run(member_web, "checked", inputs={"n": 0})
    assert response.data["is_error"] is False
    assert response.data["text"].startswith("Nothing to report.")
    assert "not on this path: answer" in response.data["text"]
    trace = last_run()
    assert trace.status == RunStatus.OK
    assert [(r.step_id, r.branch) for r in trace.steps.all()] == [("positive", "fail")]
    assert run(member_web, "checked", inputs={"n": 2}).data["text"].startswith("It was 2.")


def test_unknown_procedure_input_and_answer_without_token_are_refused(member_web):
    install(with_check())
    assert "no procedure called 'nope'" in run(member_web, "nope").data["text"]
    assert "takes no input called x" in run(member_web, "checked", inputs={"x": 1}).data["text"]
    assert "resume_token" in run(member_web, "checked", answer="yes").data["text"]
    assert ProcedureRun.objects.count() == 0


def test_a_procedure_of_another_household_is_never_found(member_web, household_b):
    install(three_steps("theirs"), household=household_b)
    response = run(member_web, "theirs")
    assert "no procedure called 'theirs'" in response.data["text"]
    assert ProcedureRun.objects.count() == 0


def test_the_households_own_procedure_wins_over_a_builtin_of_the_same_name(member_web, household_a):
    install(with_check("same"))
    own = three_steps("same")
    install(own, household=household_a)
    assert run(member_web, "same").data["text"].startswith("Lists: ")


# =============================================================================
# Ask, resume, cancel (ruling 03)
# =============================================================================


def _token_of(text: str) -> str:
    return text.split("resume_token ", 1)[1].split(" ", 1)[0]


def test_ask_waits_then_yes_writes_and_says_it(member_web):
    install(writing())
    first = run(member_web, "add_reminder", inputs={"title": "Dentist"})
    assert first.data["is_error"] is False
    assert first.data["text"].startswith("Add it?")
    token = _token_of(first.data["text"])
    trace = last_run()
    assert trace.status == RunStatus.WAITING and trace.resume_state is not None
    assert token not in trace.result and trace.resume_token_sha256 != token
    second = run(member_web, "add_reminder", resume_token=token, answer=" Yes ")
    assert second.data["is_error"] is False, second.data
    assert "Written (the tool said committed): add (add_event)" in second.data["text"]
    trace.refresh_from_db()
    assert trace.status == RunStatus.OK and trace.resume_state is None
    assert [(r.step_id, r.outcome, r.chosen_by) for r in trace.steps.all()] == [
        ("confirm", "asked", "code"),
        ("confirm", "ok", "user"),
        ("add", "ok", "code"),
        ("done", "ok", "code"),
    ]
    # One answer per question.
    again = run(member_web, "add_reminder", resume_token=token, answer="yes")
    assert "matches no question" in again.data["text"]


def test_no_and_an_unlisted_answer_cancel_and_say_nothing_ran(member_web):
    install(writing())
    token = _token_of(run(member_web, "add_reminder", inputs={"title": "A"}).data["text"])
    no = run(member_web, "add_reminder", resume_token=token, answer="no")
    assert no.data["text"].startswith("Nothing was added.")
    assert "Nothing was written." in no.data["text"]
    token = _token_of(run(member_web, "add_reminder", inputs={"title": "B"}).data["text"])
    other = run(member_web, "add_reminder", resume_token=token, answer="maybe")
    assert "Cancelled: 'maybe' is not one of " in other.data["text"]
    assert last_run().status == RunStatus.CANCELLED
    assert not DeviceLog.objects.exists()


def test_an_answer_after_two_minutes_cancels(member_web):
    install(writing())
    token = _token_of(run(member_web, "add_reminder", inputs={"title": "A"}).data["text"])
    ProcedureRun.objects.update(resume_expires_at=timezone.now() - timedelta(seconds=1))
    late = run(member_web, "add_reminder", resume_token=token, answer="yes")
    assert "after the two minutes" in late.data["text"]
    assert "Nothing was written." in late.data["text"]
    assert last_run().status == RunStatus.CANCELLED


def test_another_request_before_the_answer_cancels_the_question(member_web):
    install(writing())
    install(with_check())
    token = _token_of(run(member_web, "add_reminder", inputs={"title": "A"}).data["text"])
    other = run(member_web, "checked", inputs={"n": 1})
    assert "A question from add_reminder was waiting; it was cancelled" in other.data["text"]
    late = run(member_web, "add_reminder", resume_token=token, answer="yes")
    assert "matches no question" in late.data["text"]


def test_a_waiting_run_past_its_time_is_swept(member_web):
    install(writing())
    run(member_web, "add_reminder", inputs={"title": "A"})
    ProcedureRun.objects.update(resume_expires_at=timezone.now() - timedelta(seconds=1))
    assert retention.cancel_expired() == 1
    trace = last_run()
    assert trace.status == RunStatus.CANCELLED and trace.resume_state is None


def test_a_read_scoped_token_runs_a_read_only_procedure_and_is_refused_the_write(
    settings, household_user
):
    settings.LEGION_MCP = True
    install(three_steps())
    install(writing())
    reader = client_with(household_user, DeviceToken.SCOPE_READ)
    is_error, text, structured = call(reader, "run_procedure", {"name": "checklist_check"})
    assert is_error is False and structured["status"] == "ok", text
    assert last_run().caller_kind == "mcp"
    _e, _t, asked = call(
        reader, "run_procedure", {"name": "add_reminder", "inputs": {"title": "A"}}
    )
    assert asked["status"] == "needs_answer" and sorted(asked["answers"]) == ["no", "yes"]
    is_error, text, structured = call(
        reader,
        "run_procedure",
        {"name": "add_reminder", "resume_token": asked["resume_token"], "answer": "yes"},
    )
    assert is_error is True and "read-only" in text.lower()
    assert "Nothing was written." in text and structured["written"] == []
    from django.db import connection

    with connection.cursor() as cursor:
        cursor.execute("select count(*) from public.events where title = 'A'")
        assert cursor.fetchone()[0] == 0


# =============================================================================
# Decide (ruling 02): mocked HTTP, labelled
# =============================================================================


def _gemini(branch_json: str, status=200):
    payload = {
        "candidates": [{"content": {"parts": [{"text": branch_json}]}}],
        "usageMetadata": {
            "promptTokenCount": 120,
            "candidatesTokenCount": 5,
            "totalTokenCount": 125,
        },
    }
    sent = []

    def transport(method, url, headers, body):
        sent.append(json.loads(body))
        return Response(status, {}, json.dumps(payload).encode())

    return transport, sent


def test_decide_takes_the_models_branch_and_labels_it(member_web, monkeypatch):
    monkeypatch.setenv("LEGION_GEMINI_KEY", "test-key")
    transport, sent = _gemini('{"branch": "long"}')
    monkeypatch.setattr(decide, "_transport", transport)
    install(deciding())
    response = run(member_web, "pick_one")
    assert response.data["text"].startswith("Long.")
    assert "a model chose 'long'" in response.data["text"]
    schema = sent[0]["generationConfig"]["responseSchema"]
    assert sorted(schema["properties"]["branch"]["enum"]) == ["long", "short"]
    row = ProcedureStep.objects.get(step_id="which")
    assert row.chosen_by == ChosenBy.MODEL and row.branch == "long"
    assert row.model == "gemini-3.5-flash-lite"
    assert (row.prompt_tokens, row.output_tokens) == (120, 5)
    assert row.cost_estimate_micro_usd is not None


@pytest.mark.parametrize(
    "reply,status", [('{"branch": "sideways"}', 200), ("It is long.", 200), ("{}", 500)]
)
def test_decide_outside_the_branches_falls_back_chosen_by_code(
    member_web, monkeypatch, reply, status
):
    monkeypatch.setenv("LEGION_GEMINI_KEY", "test-key")
    transport, _sent = _gemini(reply, status)
    monkeypatch.setattr(decide, "_transport", transport)
    install(deciding())
    response = run(member_web, "pick_one")
    assert response.data["text"].startswith("Short.")
    assert "No model choice at 'which'" in response.data["text"]
    assert "a model chose" not in response.data["text"]
    row = ProcedureStep.objects.get(step_id="which")
    assert row.chosen_by == ChosenBy.CODE and row.outcome == "failed" and row.branch == "short"


def test_decide_with_no_key_falls_back_and_says_why(member_web, monkeypatch):
    monkeypatch.delenv("LEGION_GEMINI_KEY", raising=False)
    install(deciding())
    response = run(member_web, "pick_one")
    assert response.data["text"].startswith("Short.")
    row = ProcedureStep.objects.get(step_id="which")
    assert "LEGION_GEMINI_KEY is not set" in row.result


# =============================================================================
# The latency budget (ruling 04)
# =============================================================================


def test_over_the_time_limit_returns_partial_with_the_unrun_steps_named(member_web, monkeypatch):
    install(three_steps())
    ticks = iter([0.0] + [10.0] * 50)
    monkeypatch.setattr(runner, "_clock", lambda: next(ticks))
    response = run(member_web, "checklist_check")
    assert response.data["is_error"] is True
    assert "time limit (3.0 s)" in response.data["text"]
    assert "did not run" in response.data["text"]


def test_the_time_limit_is_twice_the_budget():
    assert runner._deadline(three_steps()) == 3000
    assert runner._deadline(deciding()) == 8000


# =============================================================================
# The declaration
# =============================================================================


def test_tools_list_names_the_callers_procedures_only(settings, household_user, household_b):
    settings.LEGION_MCP = True
    install(three_steps())
    install(three_steps("theirs"), household=household_b)
    listed = rpc(client_with(household_user), "tools/list")
    tools = {t["name"]: t for t in json.loads(listed.content)["result"]["tools"]}
    schema = tools["run_procedure"]["inputSchema"]
    # The repo's built-ins (ticket 15) and the member's own household's, never household_b's.
    assert schema["properties"]["name"]["enum"] == [
        "car_check",
        "checklist_check",
        "money_check",
        "morning_briefing",
    ]
    assert "theirs" not in tools["run_procedure"]["description"]
    assert tools["run_procedure"]["annotations"]["readOnlyHint"] is False


def test_with_nothing_to_run_the_declaration_has_no_empty_enum(household_user, no_builtins):
    from assistant import live
    from assistant.surface import tools_for

    (tool,) = [t for t in tools_for(household_user) if t.name == "run_procedure"]
    declared = live.function_declarations([tool])[0]
    assert "enum" not in declared["parameters"]["properties"]["name"]
    assert "No procedure is available here yet" in declared["description"]


# =============================================================================
# The conversation record (ticket 13)
# =============================================================================


def test_a_run_writes_one_procedure_run_line_into_the_web_record(member_web, google):  # noqa: F811
    from tests.test_web_conversation import _body, _mint

    install(three_steps())
    conversation = _mint(member_web)
    response = member_web.post(
        "/api/assistant/tool",
        {
            "name": "run_procedure",
            "args": {"name": "checklist_check"},
            "conversation_id": conversation,
            "call_id": "p1",
        },
        format="json",
    )
    assert response.data["is_error"] is False
    trace = last_run()
    assert str(trace.conversation_id) == conversation
    body = _body(DeviceLog.objects.get(batch_id=conversation))
    lines = [ln for ln in body.splitlines() if "procedure_run " in ln]
    assert len(lines) == 1
    assert f"run={trace.id} name=checklist_check version=1 status=ok steps=3" in lines[0]
    assert "tool_call id=p1 name=run_procedure" in body


def test_the_voice_audit_renders_the_procedure_run_line():
    from devicelogs import voice_audit

    line = (
        "11-03 10:00:00.000  0  2 I LIVE_AUDIT: procedure_run run=abc name=car_check "
        "version=1 status=ok steps=3 ms=12 branches=-"
    )
    out = " | ".join(voice_audit.timeline([line]))
    assert "PROCEDURE car_check v1 ok, 3 steps, 12ms" in out


# =============================================================================
# Retention (ruling 06)
# =============================================================================


def _aged(days: int, **extra) -> ProcedureRun:
    trace = last_run()
    trace.pk = None
    trace.id = None
    for key, value in extra.items():
        setattr(trace, key, value)
    trace.started_at = timezone.now() - timedelta(days=days)
    trace.save(force_insert=True)
    return trace


def test_prune_removes_a_61_day_member_run_and_keeps_a_59_day_one(member_web):
    install(three_steps())
    run(member_web, "checklist_check")
    old, young = _aged(61), _aged(59)
    retention.prune()
    remaining = set(ProcedureRun.objects.values_list("id", flat=True))
    assert old.id not in remaining and young.id in remaining


def test_a_recruiter_run_is_capped_at_30_days(member_web):
    install(three_steps())
    run(member_web, "checklist_check")
    old, young = _aged(31, caller_kind="recruiter"), _aged(29, caller_kind="recruiter")
    retention.prune()
    remaining = set(ProcedureRun.objects.values_list("id", flat=True))
    assert old.id not in remaining and young.id in remaining


# =============================================================================
# Recruiter visits: the visit's runs go with its household (ruling 06)
# =============================================================================


def test_deleting_the_visit_household_deletes_its_runs_and_steps(
    visitor,  # noqa: F811
    demo_household,  # noqa: F811
):
    from django.db import connection

    from demo.visit_cleanup import delete_visit_household, deletion_order
    from tests.test_recruiter import _recruiter_browser, _visitor_token

    install(three_steps())
    token, _raw = _visitor_token(visitor)
    response = run(_recruiter_browser(visitor, token), "checklist_check")
    assert response.data["is_error"] is False, response.data
    trace = last_run()
    assert trace.household_id == demo_household.id and trace.caller_kind == "recruiter"
    with connection.cursor() as cursor:
        order, _self_refs, _schemas = deletion_order(cursor)
    assert order.index("procedure_steps") < order.index("procedure_runs")
    assert order.index("procedure_runs") < order.index("procedure_versions")
    counts = delete_visit_household(demo_household)
    assert counts["procedure_runs"] == 1 and counts["procedure_steps"] == 3
    assert not ProcedureRun.objects.exists() and not ProcedureStep.objects.exists()
    # The built-in it ran is untouched.
    assert ProcedureVersion.objects.filter(procedure__name="checklist_check").exists()


# =============================================================================
# The Postgres 15 floor (migration 0001's NULLS NOT DISTINCT)
# =============================================================================


def test_an_older_postgres_is_refused_in_words(monkeypatch):
    from django.db import connections

    from procedures.apps import postgres_is_15_or_newer

    assert postgres_is_15_or_newer(databases=["default"]) == []
    monkeypatch.setattr(type(connections["default"]), "pg_version", property(lambda self: 140011))
    (error,) = postgres_is_15_or_newer(databases=["default"])
    assert error.id == "procedures.E001"
    assert "Postgres 14 is too old" in error.msg and "Nothing was migrated" in error.msg


def test_an_engine_error_after_a_write_still_names_the_write(member_web, monkeypatch):
    install(writing())
    token = _token_of(run(member_web, "add_reminder", inputs={"title": "Dentist"}).data["text"])

    def broken(walk, template):
        raise KeyError("template")

    monkeypatch.setattr(runner, "_render", broken)
    response = run(member_web, "add_reminder", resume_token=token, answer="yes")
    assert response.data["is_error"] is True
    text = response.data["text"]
    assert "The engine hit an error after add (KeyError)" in text
    assert "Written (the tool said committed): add (add_event)" in text
    assert "Nothing was written" not in text
    assert last_run().status == RunStatus.PARTIAL
