"""The late-answer path (design 2026-11-20, `agents/legion/late.py`), dark behind
`LEGION_LATE_ANSWERS`.

- Off (the default): every door behaves as before. A "tell me later" utterance goes to the model
  like any other, no row is read or written, the runner does nothing.
- On: "look into X and tell me later" is answered at once by code and queues a task; the runner
  answers it read-only in its own conversation; his next turn says the answer first, once, on
  whichever door asks first; "anything pending?" and "cancel that" are answered by code.

Made-up questions and answers only.
"""

from __future__ import annotations

import json
from datetime import timedelta

import pytest
from django.db import IntegrityError, transaction
from django.utils import timezone

from agents.legion import late
from agents.legion.flags import LATE_ANSWERS_ENV, TURN_ENV
from agents.models import AgentConversation, AgentTurn, LateTask, OwedLine
from agents.runtime import model as m

pytestmark = pytest.mark.django_db

ASK = "look into the opening hours of the made-up museum and tell me later"


def _events(response) -> list[tuple[str, dict]]:
    body = b"".join(response.streaming_content).decode()
    out = []
    for block in body.strip().split("\n\n"):
        lines = dict(line.split(": ", 1) for line in block.splitlines())
        out.append((lines["event"], json.loads(lines["data"])))
    return out


def _sentences(events) -> list[str]:
    return [d["text"] for k, d in events if k == "sentence"]


@pytest.fixture
def door_on(monkeypatch):
    monkeypatch.setenv(TURN_ENV, "on")
    monkeypatch.setenv("LEGION_GEMINI_KEY", "test-key-not-real")


@pytest.fixture
def late_on(monkeypatch, db):
    monkeypatch.setenv(LATE_ANSWERS_ENV, "on")
    late.saw_tick()  # a runner is ticking (review item C)


@pytest.fixture
def fake_model(monkeypatch):
    holder = {"made": 0}

    def factory(key):
        holder["made"] += 1
        holder["model"] = m.FakeModel(holder.pop("rounds", []))
        return holder["model"]

    monkeypatch.setattr("agents.views.GeminiClient", factory)
    return holder


def _turn(client, words, conversation_id=None):
    body = {"transcript": words}
    if conversation_id:
        body["conversation_id"] = conversation_id
    return _events(client.post("/api/agents/turn", body, format="json"))


def _owe(user, text="About the made-up museum: it opens at nine.", **extra):
    from agents.evals.runner import member_request
    from household.tenancy import household_of

    request = member_request(user)
    fields = {"expires_at": timezone.now() + timedelta(hours=1), **extra}
    return OwedLine.objects.create(
        household=household_of(request),
        owner_user=user,
        source="late_task",
        source_id=str(fields.pop("source_id", "made-up-1")),
        text=text,
        **fields,
    )


# -- the phrases ---------------------------------------------------------------------------------


@pytest.mark.parametrize(
    "words,question",
    [
        (ASK, "the opening hours of the made-up museum"),
        (
            "Can you find out when the made-up shop closes, and let me know later.",
            "when the made-up shop closes",
        ),
        ("research invented widgets in the background", "invented widgets"),
        ("get back to me on the invented ferry times", "the invented ferry times"),
        ("look into it and tell me when it's ready", "it"),
    ],
)
def test_a_tell_me_later_phrase_names_its_question(words, question):
    assert late.ask_of(words) == question


@pytest.mark.parametrize(
    "words",
    ["what's the weather", "look into the museum", "tell me later", "remind me later to call"],
)
def test_other_words_are_not_a_late_ask(words):
    assert late.ask_of(words) is None


# -- switched off: exactly as before ------------------------------------------------------------


def test_switched_off_a_tell_me_later_ask_goes_to_the_model_and_no_row_is_written(
    auth_client, door_on, fake_model, monkeypatch
):
    monkeypatch.delenv(LATE_ANSWERS_ENV, raising=False)
    fake_model["rounds"] = [m.say("Sure, the museum opens at nine.")]
    events = _turn(auth_client, ASK)
    assert _sentences(events) == ["Sure, the museum opens at nine."]
    assert fake_model["model"].requests  # the model answered
    assert not LateTask.objects.exists() and not OwedLine.objects.exists()


def test_switched_off_an_owed_line_is_never_said(
    auth_client, household_user, door_on, fake_model, monkeypatch
):
    monkeypatch.delenv(LATE_ANSWERS_ENV, raising=False)
    line = _owe(household_user)
    fake_model["rounds"] = [m.say("Hello.")]
    events = _turn(auth_client, "hello")
    assert _sentences(events) == ["Hello."]
    line.refresh_from_db()
    assert line.delivered_at is None


def test_switched_off_the_runner_does_nothing(household_user, monkeypatch):
    monkeypatch.delenv(LATE_ANSWERS_ENV, raising=False)
    _task(household_user)
    assert late.run_due(lambda: pytest.fail("no model may be made")) == 0
    assert LateTask.objects.get().status == "queued"


# -- asking: the conversation stays free --------------------------------------------------------


def test_an_ask_is_answered_by_code_at_once_with_no_model_round(
    auth_client, household_user, door_on, late_on, fake_model
):
    fake_model["rounds"] = []
    events = _turn(auth_client, ASK)
    assert _sentences(events) == [late.ACK]
    assert events[-1][0] == "done" and events[-1][1]["status"] == "done"
    assert fake_model["model"].requests == []
    task = LateTask.objects.get()
    assert (task.status, task.question, task.owner_user_id) == (
        "queued",
        "the opening hours of the made-up museum",
        household_user.pk,
    )
    turn = AgentTurn.objects.get()
    assert turn.final_text == late.ACK and turn.route_reason == "late_answer"
    # The next turn's model sees what was said.
    assert turn.history[-1] == {"role": "model", "parts": [{"text": late.ACK}]}


def test_two_open_tasks_is_the_most(auth_client, door_on, late_on, fake_model):
    fake_model["rounds"] = []
    for _ in range(2):
        assert _sentences(_turn(auth_client, ASK)) == [late.ACK]
    assert _sentences(_turn(auth_client, ASK)) == [late.TOO_MANY_OPEN]
    assert LateTask.objects.count() == 2


def test_the_daily_count_is_a_cap(auth_client, household_user, door_on, late_on, fake_model):
    for _ in range(late.MAX_PER_DAY):
        _task(household_user, status="done")
    fake_model["rounds"] = []
    assert _sentences(_turn(auth_client, ASK)) == [late.TOO_MANY_TODAY]
    assert LateTask.objects.count() == late.MAX_PER_DAY


def test_the_daily_allowance_is_its_own_feature(
    auth_client, household_user, door_on, late_on, fake_model
):
    from llm import client as llm_client

    llm_client.record(
        household=household_user.household,
        feature=late.FEATURE,
        model="x",
        user=household_user,
        cost_estimate_micro_usd=late.DAILY_MICRO_USD,
    )
    fake_model["rounds"] = []
    assert _sentences(_turn(auth_client, ASK)) == [late.SPENT]
    assert not LateTask.objects.exists()


# -- the runner ---------------------------------------------------------------------------------


def _task(user, question="the opening hours of the made-up museum", **fields):
    from agents.evals.runner import member_request
    from household.tenancy import household_of

    return LateTask.objects.create(
        household=household_of(member_request(user)),
        owner_user=user,
        origin_client="web",
        question=question,
        **fields,
    )


def test_the_runner_answers_in_its_own_conversation_and_owes_the_line(
    household_user, late_on, monkeypatch
):
    from llm.models import LlmUsage

    live = AgentConversation.objects.create(
        household=household_user.household, owner_user=household_user, client="web"
    )
    task = _task(household_user)
    model = m.FakeModel([m.say("It opens at nine. It closes at five.")])
    assert late.run_due(lambda: model) == 1
    task.refresh_from_db()
    assert task.status == "done" and task.attempts == 1
    assert task.answer == "It opens at nine. It closes at five."
    line = OwedLine.objects.get()
    assert line.text == (
        "About the opening hours of the made-up museum: It opens at nine. It closes at five."
    )
    assert line.source_id == str(task.pk) and line.delivered_at is None
    # Its own conversation, never his live one.
    run = AgentConversation.objects.get(pk=task.run_conversation)
    assert run.client == "background" and run.pk != live.pk
    assert not live.turns.exists()
    # Under the read-only background spec, metered as its own feature.
    assert AgentTurn.objects.get().route_agents == ["background"]
    assert set(LlmUsage.objects.values_list("feature", flat=True)) == {late.FEATURE}


def test_a_background_task_cannot_write(household_user, late_on):
    from checklists.models import ChecklistItem

    _task(household_user)
    model = m.FakeModel(
        [
            m.call("add_checklist_item", {"checklist": "Todo", "text": "made-up"}),
            m.say("I could not add it. Want me to add it?"),
        ]
    )
    late.run_due(lambda: model)
    assert not ChecklistItem.objects.exists()
    turn = AgentTurn.objects.get()
    assert turn.writes_ran is False
    call = turn.tool_calls.get()
    assert call.outcome == "refused" and call.guard == "not_allowed"
    assert OwedLine.objects.get().text.endswith("Want me to add it?")


def test_a_research_task_runs_its_tool_as_him_and_is_marked_tainted(
    household_user, late_on, monkeypatch
):
    """The allowed tool runs through the rebuilt request, as the member; a web search taints the
    task, and the line it owes is marked as read-through when said (`read_third_party`)."""
    from types import SimpleNamespace

    seen = {}

    def fake_search(query):
        seen["query"] = query
        return SimpleNamespace(
            answer="The made-up museum opens at nine.",
            sources=[],
            search_queries=[query],
            model="fake",
            usage={},
        )

    monkeypatch.setattr("engine_mcp.web_search.search", fake_search)
    _task(household_user)
    model = m.FakeModel(
        [m.call("web_search", {"query": "made-up museum hours"}), m.say("It opens at nine.")]
    )
    late.run_due(lambda: model)
    assert seen["query"] == "made-up museum hours"
    from llm.models import LlmUsage

    # Run as him: his own search, metered to him once, as background work.
    assert LlmUsage.objects.filter(feature=late.SEARCH_FEATURE, user=household_user).count() == 1
    task = LateTask.objects.get()
    assert task.status == "done" and task.tainted
    call = AgentTurn.objects.get().tool_calls.get()
    assert (call.tool, call.outcome) == ("web_search", "ok")
    assert late.read_third_party(OwedLine.objects.get())


def test_a_failed_run_owes_a_plain_failure_line(household_user, late_on):
    _task(household_user)
    late.run_due(lambda: m.FakeModel([]))
    assert LateTask.objects.get().status == "failed"
    assert OwedLine.objects.get().text == (
        "I couldn't finish looking into the opening hours of the made-up museum."
    )


def test_a_task_whose_lease_ran_out_is_claimed_again_then_given_up(household_user, late_on):
    past = timezone.now() - timedelta(minutes=5)
    task = _task(household_user, status="running", attempts=1, lease_until=past)
    model = m.FakeModel([m.say("It opens at nine.")])
    assert late.run_due(lambda: model) == 1
    task.refresh_from_db()
    assert (task.status, task.attempts) == ("done", 2)

    stuck = _task(
        household_user, question="invented ferry", status="running", attempts=2, lease_until=past
    )
    late.run_due(lambda: pytest.fail("no model after the last attempt"))
    stuck.refresh_from_db()
    assert stuck.status == "failed"
    assert OwedLine.objects.get(source_id=str(stuck.pk)).text == (
        "I couldn't finish looking into invented ferry."
    )


def test_a_running_task_with_a_live_lease_is_left_alone(household_user, late_on):
    _task(
        household_user,
        status="running",
        attempts=1,
        lease_until=timezone.now() + timedelta(minutes=1),
    )
    assert late.run_due(lambda: pytest.fail("not due")) == 0


def test_a_cancel_that_lands_while_it_runs_owes_nothing(household_user, late_on):
    task = _task(household_user)

    class CancelMidway:
        requests = []

        def stream(self, req):
            LateTask.objects.filter(pk=task.pk).update(status="cancelled")
            yield from m.say("It opens at nine.")

    late.run_due(lambda: CancelMidway())
    task.refresh_from_db()
    assert task.status == "cancelled" and not OwedLine.objects.exists()


def test_the_command_is_a_no_op_while_switched_off(household_user, monkeypatch):
    from io import StringIO

    from django.core.management import call_command

    monkeypatch.delenv(LATE_ANSWERS_ENV, raising=False)
    _task(household_user)
    out = StringIO()
    call_command("late_tasks", stdout=out)
    assert "switched off" in out.getvalue()
    assert LateTask.objects.get().status == "queued"


def test_the_warm_tick_may_run_it():
    from legion.tick import TASKS

    assert "late_tasks" in TASKS


# -- delivery at the next turn ------------------------------------------------------------------


def test_the_owed_line_is_said_first_once_and_kept_out_of_the_model(
    auth_client, household_user, door_on, late_on, fake_model
):
    line = _owe(household_user)
    fake_model["rounds"] = [m.say("Hello again.")]
    events = _turn(auth_client, "hello")
    sentences = [(d["text"], d.get("role")) for k, d in events if k == "sentence"]
    assert sentences == [
        ("About the made-up museum: it opens at nine.", "late"),
        ("Hello again.", "answer"),
    ]
    late_event = next(d for k, d in events if k == "sentence" and d.get("role") == "late")
    assert late_event["owed_id"] == str(line.pk)
    # Not in the model's context for this turn.
    sent = json.dumps(fake_model["model"].requests[0].contents)
    assert "made-up museum" not in sent
    line.refresh_from_db()
    assert line.delivered_at is not None and line.delivered_client == "phone"
    # Never twice.
    fake_model["rounds"] = [m.say("Still here.")]
    assert _sentences(_turn(auth_client, "and now")) == ["Still here."]


def test_one_line_a_turn_and_the_rest_are_counted(
    auth_client, household_user, door_on, late_on, fake_model
):
    _owe(household_user, text="First made-up answer.", source_id="a")
    _owe(
        household_user,
        text="Second made-up answer.",
        source_id="b",
        ready_at=timezone.now() + timedelta(seconds=1),
    )
    fake_model["rounds"] = [m.say("Hi.")]
    said = _sentences(_turn(auth_client, "hi"))
    assert said[0] == "First made-up answer. 1 more is ready; ask what's pending."
    assert OwedLine.objects.filter(delivered_at__isnull=True).count() == 1


def test_an_expired_line_is_never_said(auth_client, household_user, door_on, late_on, fake_model):
    _owe(household_user, expires_at=timezone.now() - timedelta(minutes=1))
    fake_model["rounds"] = [m.say("Hi.")]
    assert _sentences(_turn(auth_client, "hi")) == ["Hi."]


def test_a_line_is_not_said_while_a_yes_is_awaited(
    auth_client, household_user, door_on, late_on, fake_model
):
    conversation = AgentConversation.objects.create(
        household=household_user.household,
        owner_user=household_user,
        client="phone",
        pending_confirm={"tool": "x", "args_sha256": "0" * 64, "until_seq": 5},
    )
    _owe(household_user)
    fake_model["rounds"] = [m.say("Okay, I won't.")]
    said = _sentences(_turn(auth_client, "no", conversation_id=str(conversation.pk)))
    assert said == ["Okay, I won't."]
    assert OwedLine.objects.get().delivered_at is None


def test_another_member_never_hears_his_line(
    household_user, household_a, door_on, late_on, fake_model
):
    from tests.conftest import _client_for, _member

    mia_client = _client_for(_member(household_a, "mia@example.com"))
    _owe(household_user)
    fake_model["rounds"] = [m.say("Hi.")]
    assert _sentences(_turn(mia_client, "hi")) == ["Hi."]
    assert OwedLine.objects.get().delivered_at is None


def test_two_doors_claiming_at_once_say_it_once(household_user, late_on):
    from agents.evals.runner import member_request

    _owe(household_user)
    request = member_request(household_user)
    first, _ = late.claim(request, "web")
    second, _ = late.claim(request, "phone")
    assert first is not None and second is None


def test_the_daily_spoken_cap_holds(household_user, late_on):
    from agents.evals.runner import member_request

    for n in range(late.MAX_SPOKEN_PER_DAY):
        _owe(household_user, source_id=f"said-{n}", delivered_at=timezone.now())
    _owe(household_user, source_id="one-more")
    assert late.claim(member_request(household_user), "web") == (None, "")


# -- pending and cancel, by code ----------------------------------------------------------------


def test_anything_pending_names_what_runs_then_says_what_is_ready(
    auth_client, household_user, door_on, late_on, fake_model
):
    fake_model["rounds"] = []
    _task(household_user)
    assert _sentences(_turn(auth_client, "anything pending?")) == [
        "Still working on the opening hours of the made-up museum."
    ]
    LateTask.objects.update(status="done")
    _owe(household_user)
    assert _sentences(_turn(auth_client, "what's ready")) == [
        "About the made-up museum: it opens at nine."
    ]
    # Said once; asked again, it is read back as the last one.
    assert _sentences(_turn(auth_client, "anything pending")) == [
        "Nothing new. The last one was: About the made-up museum: it opens at nine."
    ]
    assert fake_model["model"].requests == []


def test_cancel_that_cancels_the_newest_open_task(
    auth_client, household_user, door_on, late_on, fake_model
):
    fake_model["rounds"] = []
    older = _task(household_user, question="older")
    newer = _task(household_user, question="newer")
    assert _sentences(_turn(auth_client, "cancel the background task")) == [late.CANCELLED]
    older.refresh_from_db()
    newer.refresh_from_db()
    assert (older.status, newer.status) == ("queued", "cancelled")
    assert late.run_due(lambda: m.FakeModel([m.say("Older answer.")])) == 1
    assert OwedLine.objects.get().source_id == str(older.pk)


def test_cancel_that_with_nothing_open_while_switched_off_is_the_models(
    auth_client, door_on, fake_model, monkeypatch
):
    monkeypatch.delenv(LATE_ANSWERS_ENV, raising=False)
    fake_model["rounds"] = [m.say("Nothing to cancel.")]
    assert _sentences(_turn(auth_client, "cancel that")) == ["Nothing to cancel."]


@pytest.mark.parametrize(
    "words", ["cancel that", "anything pending?", "what's pending", "stop it", "forget it"]
)
def test_with_nothing_of_ours_to_act_on_the_words_are_the_models(
    auth_client, door_on, late_on, fake_model, words
):
    """A timer, music, a waiting change, a pending purchase: switched on, these still reach the
    model when the late-answer path has no task open and no line from the last day."""
    fake_model["rounds"] = [m.say("The model answered.")]
    assert _sentences(_turn(auth_client, words)) == ["The model answered."]
    assert fake_model["model"].requests


# -- never a recruiter, never a head unit -------------------------------------------------------


def test_a_recruiter_is_never_served(late_on):
    from rest_framework.test import APIClient

    from agents.evals.runner import member_request
    from demo.models import DemoCode
    from household.models import User, is_recruiter

    _row, code = DemoCode.rotate()
    browser = APIClient(enforce_csrf_checks=True)
    browser.get("/api/auth/csrf")
    assert (
        browser.post("/api/demo/enter", {"code": code, "ref": "late"}, format="json").status_code
        == 201
    )
    visitor = next(u for u in User.objects.all() if is_recruiter(u))
    request = member_request(visitor)
    assert late.intercept(request, ASK, "web") is None
    assert late.claim(request, "web") == (None, "")
    assert not LateTask.objects.exists()


def test_a_head_unit_starts_nothing_and_hears_nothing(household_user, late_on):
    from agents.evals.runner import member_request
    from headunit_signin.models import HeadUnitCredential

    _owe(household_user)
    unit, _raw = HeadUnitCredential.mint(
        household=household_user.household, owner_user=household_user, label="Invented unit"
    )
    request = member_request(household_user)
    assert request.user == household_user  # authenticates first; the unit's token then rides
    request.auth = unit
    assert late.intercept(request, ASK, "headunit").text == late.NOT_FROM_UNIT
    assert late.claim(request, "headunit") == (None, "")
    assert not LateTask.objects.exists()
    # Nothing of his read back or cancelled from the unit either.
    task = _task(household_user)
    assert late.intercept(request, "anything pending?", "headunit") is None
    assert late.intercept(request, "cancel that", "headunit") is None
    task.refresh_from_db()
    assert task.status == "queued"
    assert OwedLine.objects.get().delivered_at is None


def test_late_rows_are_pruned_after_30_days(household_user, late_on):
    from agents.legion.retention import prune

    old = timezone.now() - timedelta(days=31)
    _task(household_user, question="old")
    _owe(household_user, source_id="old")
    LateTask.objects.update(created_at=old)
    OwedLine.objects.update(created_at=old)
    _task(household_user, question="new")
    _owe(household_user, source_id="new")
    out = prune()
    assert (out["late_tasks"], out["owed_lines"]) == (1, 1)
    assert list(LateTask.objects.values_list("question", flat=True)) == ["new"]
    assert list(OwedLine.objects.values_list("source_id", flat=True)) == ["new"]


# -- the tables ---------------------------------------------------------------------------------


def test_both_tables_are_private_to_their_member(household_user, household_a):
    from agents.evals.runner import member_request
    from household.tenancy import visible
    from tests.conftest import _member

    mia = _member(household_a, "mia@example.com")
    _task(household_user)
    _owe(household_user)
    for model in (LateTask, OwedLine):
        assert visible(model, member_request(household_user)).count() == 1
        assert not visible(model, member_request(mia)).exists()


def test_the_database_refuses_an_owner_from_another_household(household_user, user_b):
    with pytest.raises(IntegrityError), transaction.atomic():
        LateTask.objects.create(
            household=household_user.household,
            owner_user=user_b,
            origin_client="web",
            question="made-up",
        )
    with pytest.raises(IntegrityError), transaction.atomic():
        OwedLine.objects.create(
            household=household_user.household,
            owner_user=user_b,
            source="late_task",
            source_id="x",
            text="made-up",
            expires_at=timezone.now(),
        )


def test_an_owed_line_is_unique_per_source(household_user):
    _owe(household_user, source_id="same")
    with pytest.raises(IntegrityError), transaction.atomic():
        _owe(household_user, source_id="same")


# -- review 2026-11-20, item F: a topic of a bare pronoun is asked back -------------------------


@pytest.mark.parametrize(
    "words", ["look into it and tell me later", "research that in the background"]
)
def test_a_pronoun_topic_is_asked_back_and_queues_nothing(
    auth_client, door_on, late_on, fake_model, words
):
    fake_model["rounds"] = []
    assert _sentences(_turn(auth_client, words)) == [late.ASK_WHAT]
    assert not LateTask.objects.exists()
    assert fake_model["model"].requests == []


# -- review 2026-11-20, item i: switched off, the turn's prune issues no late-answer query -------


def test_switched_off_prune_leaves_the_late_tables_alone(household_user, monkeypatch):
    from django.db import connection
    from django.test.utils import CaptureQueriesContext

    from agents.legion.retention import prune

    monkeypatch.delenv(LATE_ANSWERS_ENV, raising=False)
    _task(household_user, question="old")
    LateTask.objects.update(created_at=timezone.now() - timedelta(days=31))
    with CaptureQueriesContext(connection) as queries:
        out = prune()
    assert "late_tasks" not in out
    assert not any(
        "agents_late_task" in q["sql"] or "agents_owed_line" in q["sql"]
        for q in queries.captured_queries
    )
    assert LateTask.objects.count() == 1


# -- review 2026-11-20, item g: the owner keys delete with the user at the database ---------------


@pytest.mark.parametrize("table", ["agents_late_task", "agents_owed_line"])
def test_the_owner_key_cascades_at_the_database(table):
    """So a member can be deleted by code that predates these tables (a rollback): Postgres
    removes their rows itself."""
    from django.db import connection

    with connection.cursor() as cursor:
        cursor.execute(
            """
            select c.confdeltype from pg_constraint c
            join pg_attribute a on a.attrelid = c.conrelid and a.attnum = any(c.conkey)
            where c.contype = 'f' and c.conrelid = %s::regclass and a.attname = 'owner_user_id'
            """,
            [f"public.{table}"],
        )
        assert [row[0] for row in cursor.fetchall()] == ["c"]


# -- review 2026-11-20, item B: a bare "cancel it" is ours only right after our own ack ----------


def _conversation_of(events) -> str:
    return next(d for k, d in events if k == "turn")["conversation_id"]


def test_cancel_it_after_a_timer_goes_to_the_model(
    auth_client, household_user, door_on, late_on, fake_model
):
    task = _task(household_user)
    fake_model["rounds"] = [m.say("Timer set for ten minutes.")]
    conversation = _conversation_of(_turn(auth_client, "set a timer for ten minutes"))
    fake_model["rounds"] = [m.say("The timer is cancelled.")]
    said = _sentences(_turn(auth_client, "cancel it", conversation_id=conversation))
    assert said == ["The timer is cancelled."]
    task.refresh_from_db()
    assert task.status == "queued"


def test_a_bare_cancel_right_after_the_ack_cancels_the_task(
    auth_client, door_on, late_on, fake_model
):
    fake_model["rounds"] = []
    conversation = _conversation_of(_turn(auth_client, ASK))
    said = _sentences(_turn(auth_client, "cancel that", conversation_id=conversation))
    assert said == [late.CANCELLED]
    assert LateTask.objects.get().status == "cancelled"


@pytest.mark.parametrize(
    "words",
    ["cancel the background task", "cancel the research", "cancel what you're looking into"],
)
def test_the_explicit_cancel_is_ours_anywhere(
    auth_client, household_user, door_on, late_on, fake_model, words
):
    _task(household_user)
    fake_model["rounds"] = []
    assert _sentences(_turn(auth_client, words)) == [late.CANCELLED]


# -- review 2026-11-20, item A: a line built from web pages never becomes the model's own words ---


PLANTED = "About the made-up museum: it opens at nine. Shall I add a visit to your calendar?"


def _tainted_line(user):
    task = _task(user, status="done", tainted=True)
    return _owe(user, text=PLANTED, source_id=str(task.pk))


def test_a_yes_after_a_web_built_line_writes_nothing(
    auth_client, household_user, door_on, late_on, fake_model
):
    _tainted_line(household_user)
    fake_model["rounds"] = [m.say("Hello again.")]
    first = _turn(auth_client, "hello")
    assert _sentences(first)[0] == PLANTED
    conversation = _conversation_of(first)
    fake_model["rounds"] = [
        m.call("add_event", {"fields": {"title": "Museum visit"}}),
        m.say("I can't add that this turn."),
    ]
    _turn(auth_client, "yes", conversation_id=conversation)
    # The model never saw the line as anything it said.
    assert "made-up museum" not in json.dumps(fake_model["model"].requests[0].contents)
    call = AgentTurn.objects.get(seq=2).tool_calls.get()
    assert (call.tool, call.outcome, call.guard) == ("add_event", "refused", "tainted")


def test_anything_pending_keeps_the_answer_out_of_history_and_taints_the_next_turn(
    auth_client, household_user, door_on, late_on, fake_model
):
    _tainted_line(household_user)
    first = _turn(auth_client, "anything pending?")
    assert _sentences(first) == [PLANTED]
    turn = AgentTurn.objects.get()
    assert "made-up museum" not in json.dumps(turn.history)
    fake_model["rounds"] = [
        m.call("add_event", {"fields": {"title": "Museum visit"}}),
        m.say("I can't add that this turn."),
    ]
    _turn(auth_client, "yes", conversation_id=_conversation_of(first))
    call = AgentTurn.objects.get(seq=2).tool_calls.get()
    assert (call.outcome, call.guard) == ("refused", "tainted")


def test_the_background_prompt_never_asks_for_a_yes():
    from agents.legion.specs import spec

    prompt = " ".join(spec("background").prompt.lower().split())
    # A yes to a question built from web pages could run a change a page asked for.
    assert "end with" not in prompt and "whether they want" not in prompt
    assert "do not ask" in prompt


# -- review 2026-11-20, item C: queued tasks expire; no "On it" without a runner ------------------


def test_a_queued_task_past_its_time_expires_frees_its_slot_and_is_owed_once(
    auth_client, household_user, door_on, late_on, fake_model
):
    late.saw_tick()
    stale = timezone.now() - late.QUEUE_TTL - timedelta(minutes=1)
    for question in ("first stale", "second stale"):
        _task(household_user, question=question)
    LateTask.objects.update(created_at=stale)
    fake_model["rounds"] = []
    # Both slots were taken; the stale ones expire, so a new ask is taken.
    assert _sentences(_turn(auth_client, ASK)) == [late.ACK]
    assert set(LateTask.objects.filter(status="failed").values_list("question", flat=True)) == {
        "first stale",
        "second stale",
    }
    lines = set(OwedLine.objects.values_list("text", flat=True))
    assert lines == {
        "I couldn't get to first stale in time.",
        "I couldn't get to second stale in time.",
    }
    late.run_due(lambda: m.FakeModel([m.say("Nine.")]))
    assert OwedLine.objects.filter(text__contains="in time").count() == 2  # once each


def test_without_a_recent_tick_there_is_no_on_it(auth_client, door_on, late_on, fake_model):
    late.saw_tick(timezone.now() - late.TICK_FRESH - timedelta(seconds=1))
    fake_model["rounds"] = []
    assert _sentences(_turn(auth_client, ASK)) == [late.NO_RUNNER]
    assert not LateTask.objects.exists()


def test_the_command_records_the_tick(household_user, late_on, monkeypatch):
    from io import StringIO

    from django.core.management import call_command

    monkeypatch.setenv("LEGION_GEMINI_KEY", "test-key-not-real")
    monkeypatch.setattr(
        "agents.management.commands.late_tasks.GeminiClient", lambda key: m.FakeModel([])
    )
    call_command("late_tasks", stdout=StringIO())
    assert late.runner_is_fresh()


# -- review 2026-11-20, item D: searches and calls are capped and searches count as spend ---------


def _stub_search(monkeypatch):
    from types import SimpleNamespace

    from llm import client as llm_client

    ran = []

    def fake_search(query):
        ran.append(query)
        # Priced as `llm.client.usage_of` prices one grounded search with no tokens.
        usage = {"cost_estimate_micro_usd": llm_client.SEARCH_MICRO_USD}
        return SimpleNamespace(
            answer="Made-up answer.", sources=[], search_queries=[query], model="fake", usage=usage
        )

    monkeypatch.setattr("engine_mcp.web_search.search", fake_search)
    return ran


def test_a_task_runs_at_most_two_searches_and_each_counts_as_spend(
    household_user, late_on, monkeypatch
):
    ran = _stub_search(monkeypatch)
    task = _task(household_user)
    model = m.FakeModel(
        [
            m.call("web_search", {"query": "one"}),
            m.call("web_search", {"query": "two"}),
            m.call("web_search", {"query": "three"}),
            m.say("Done looking."),
        ]
    )
    late.run_due(lambda: model)
    assert ran == ["one", "two"]
    task.refresh_from_db()
    assert task.searches == 2
    third = AgentTurn.objects.get().tool_calls.order_by("created_at", "id").last()
    assert (third.outcome, third.guard) == ("refused", "budget")



def test_one_search_is_metered_once(household_user, late_on, monkeypatch):
    """Recheck item 2: the search tool's own row (priced with the repo's search price) is the only
    row for a search, and the late caps count it."""
    from llm import client as llm_client
    from llm.models import LlmUsage

    _stub_search(monkeypatch)
    _task(household_user)
    model = m.FakeModel(
        [
            m.call("web_search", {"query": "one"}),
            m.call("web_search", {"query": "two"}),
            m.say("Done looking."),
        ]
    )
    late.run_due(lambda: model)
    rows = LlmUsage.objects.filter(user=household_user)
    searches = rows.exclude(feature=late.FEATURE)
    assert searches.count() == 2
    assert set(searches.values_list("feature", flat=True)) == {late.SEARCH_FEATURE}
    model_cost = sum(
        rows.filter(feature=late.FEATURE).values_list("cost_estimate_micro_usd", flat=True)
    )
    assert late.spent_today_micro_usd(household_user) == model_cost + (
        2 * llm_client.SEARCH_MICRO_USD
    )


def test_a_round_runs_at_most_two_calls(household_user, late_on, monkeypatch):
    ran = _stub_search(monkeypatch)
    _task(household_user)
    three_at_once = (
        m.call("check_conditions", {}, call_id="a")
        + m.call("legion_docs", {"query": "x"}, call_id="b")
        + m.call("web_search", {"query": "late"}, call_id="c")
    )
    late.run_due(lambda: m.FakeModel([three_at_once, m.say("Done.")]))
    calls = list(AgentTurn.objects.get().tool_calls.values_list("tool", "guard"))
    assert ("web_search", "budget") in calls and ran == []


def test_the_daily_allowance_counts_searches(
    auth_client, household_user, door_on, late_on, fake_model
):
    from llm import client as llm_client

    llm_client.record(
        household=household_user.household,
        feature=late.SEARCH_FEATURE,
        model="x",
        user=household_user,
        cost_estimate_micro_usd=late.DAILY_MICRO_USD,
    )
    fake_model["rounds"] = []
    assert _sentences(_turn(auth_client, ASK)) == [late.SPENT]


# -- review 2026-11-20, item E: a hard stop, taint kept across runs, one claim, one slot ---------


def test_a_run_past_its_time_runs_no_further_tool(household_user, late_on, monkeypatch):
    ran = _stub_search(monkeypatch)
    monkeypatch.setattr(late, "RUN_SECONDS", 0)
    _task(household_user)
    late.run_due(lambda: m.FakeModel([m.call("web_search", {"query": "x"}), m.say("Late.")]))
    assert ran == []
    call = AgentTurn.objects.get().tool_calls.get()
    assert (call.outcome, call.guard) == ("refused", "budget")


def test_a_second_run_keeps_the_first_runs_taint_and_its_metering(
    household_user, late_on, monkeypatch
):
    from llm.models import LlmUsage

    _stub_search(monkeypatch)
    task = _task(household_user)

    class BreaksMidway:
        def __init__(self):
            self.rounds = [m.call("web_search", {"query": "x"})]

        def stream(self, req):
            if self.rounds:
                yield from self.rounds.pop(0)
                return
            raise RuntimeError("an instance went away mid-run")

    late.run_due(lambda: BreaksMidway())
    task.refresh_from_db()
    assert task.status == "running" and task.tainted
    first_rounds = LlmUsage.objects.filter(feature=late.FEATURE).count()
    assert first_rounds == 1
    later = timezone.now() + late.LEASE + timedelta(seconds=1)
    late.run_due(lambda: m.FakeModel([m.say("It opens at nine.")]), now=later)
    task.refresh_from_db()
    assert (task.status, task.attempts, task.tainted) == ("done", 2, True)
    assert LlmUsage.objects.filter(feature=late.FEATURE).count() == first_rounds + 1


@pytest.mark.django_db(transaction=True)
def test_two_doors_claiming_in_parallel_say_it_once(household_user, monkeypatch):
    import threading

    from django.db import connection

    from agents.evals.runner import member_request

    monkeypatch.setenv(LATE_ANSWERS_ENV, "on")
    _owe(household_user)
    got, start = [], threading.Barrier(2)

    def door(client):
        try:
            start.wait()
            line, _more = late.claim(member_request(household_user), client)
            got.append(line is not None)
        finally:
            connection.close()

    threads = [threading.Thread(target=door, args=(c,)) for c in ("web", "phone")]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert sorted(got) == [False, True]


@pytest.mark.django_db(transaction=True)
def test_two_asks_at_once_take_one_slot(household_user, monkeypatch):
    import threading

    from django.db import connection

    from agents.evals.runner import member_request

    monkeypatch.setenv(LATE_ANSWERS_ENV, "on")
    late.saw_tick()
    _task(household_user, question="already open")
    said, start = [], threading.Barrier(2)

    def ask(n):
        try:
            start.wait()
            reply = late.intercept(
                member_request(household_user), f"research topic {n} in the background", "web"
            )
            said.append(reply.text)
        finally:
            connection.close()

    threads = [threading.Thread(target=ask, args=(n,)) for n in (1, 2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert sorted(said) == sorted([late.ACK, late.TOO_MANY_OPEN])
    assert LateTask.objects.count() == late.MAX_OPEN
