"""The late-answer path on the web member door (`POST /api/assistant/turn`, cascade), and its
absence for a recruiter's visit. Dark behind `LEGION_LATE_ANSWERS` (`agents/legion/late.py`).

Google is never called (the cascade suite's fakes). Every word here is invented.
"""

from __future__ import annotations

from datetime import timedelta

import pytest
from django.utils import timezone

from agents.legion import late
from agents.legion.flags import LATE_ANSWERS_ENV
from agents.models import LateTask, OwedLine
from agents.runtime.model import FakeModel, call, say
from assistant import cascade, views
from ingest.statements import GEMINI_KEY_ENV
from tests.test_assistant import browser
from tests.test_assistant_cascade import KEY, FakeSpeech, _events, _kinds, _of, _post, _record
from tests.test_recruiter import _recruiter_browser, _visitor_token

pytestmark = pytest.mark.django_db

ASK = "look into the invented ferry times and tell me later"


@pytest.fixture(autouse=True)
def _clean(monkeypatch):
    # As the cascade suite: no filler audio fetched, the cascade engine for every role.
    monkeypatch.setattr(cascade, "FILLER_AUDIO", cascade.FillerAudio(spawn=lambda work: None))
    monkeypatch.setenv(GEMINI_KEY_ENV, KEY)
    monkeypatch.delenv("LEGION_WEB_VOICE", raising=False)
    monkeypatch.delenv("LEGION_WEB_VOICE_RECRUITER", raising=False)


@pytest.fixture
def engine(monkeypatch):
    class Engine:
        model = FakeModel([say("Nothing is on today.")])
        speech = FakeSpeech()

    monkeypatch.setattr(views, "_cascade_clients", lambda: (Engine.model, Engine.speech))
    return Engine


@pytest.fixture
def member(household_user):
    return browser(household_user)


@pytest.fixture
def visitor(db):
    from household.models import Household, HouseholdMember, User

    demo = Household.objects.create(name="Demo visit late", is_demo_visit=True)
    user = User.objects.create_user(email="visitor-late@demo.invalid", first_name="Pat")
    user.set_unusable_password()
    user.save()
    HouseholdMember.objects.create(user=user, household=demo, role=HouseholdMember.RECRUITER)
    token, _raw = _visitor_token(user)
    return _recruiter_browser(user, token)


@pytest.fixture
def late_on(monkeypatch, db):
    monkeypatch.setenv(LATE_ANSWERS_ENV, "on")
    late.saw_tick()  # a runner is ticking (review item C)


@pytest.fixture
def late_off(monkeypatch):
    monkeypatch.delenv(LATE_ANSWERS_ENV, raising=False)


def _owe(user, text="About the invented ferry: it leaves at noon.", source_id="made-up"):
    return OwedLine.objects.create(
        household=user.household,
        owner_user=user,
        source="late_task",
        source_id=source_id,
        text=text,
        expires_at=timezone.now() + timedelta(hours=1),
    )


def test_switched_off_the_member_door_is_unchanged(member, engine, household_user, late_off):
    _owe(household_user)
    engine.model = FakeModel([say("The ferry leaves at noon.")])
    events = _events(_post(member, text=ASK))
    assert _kinds(events) == ["turn", "sentence", "done"]
    assert _of(events, "sentence") == [{"text": "The ferry leaves at noon."}]
    assert engine.model.requests  # the model answered, as before
    assert not LateTask.objects.exists()
    assert OwedLine.objects.get().delivered_at is None


def test_a_member_ask_is_answered_by_code_and_recorded(member, engine, household_user, late_on):
    engine.model = FakeModel([])
    events = _events(_post(member, text=ASK))
    assert _of(events, "sentence") == [{"text": late.ACK}]
    assert _of(events, "done")[0]["status"] == "done"
    assert engine.model.requests == []
    task = LateTask.objects.get()
    assert (task.origin_client, task.question) == ("web", "the invented ferry times")
    record = _record(_of(events, "turn")[0]["conversation_id"])
    assert f'assistant text="{late.ACK}"' in record


def test_a_member_hears_the_owed_line_after_his_answer_and_once(
    member, engine, household_user, late_on
):
    """On the web the line comes after his answer: it is claimed only once the model has ruled
    the words were for the assistant (review item E)."""
    _owe(household_user)
    engine.model = FakeModel([say("Hello again.")])
    events = _events(_post(member, text="Hello"))
    sentences = _of(events, "sentence")
    assert sentences[0] == {"text": "Hello again."}
    assert sentences[1]["text"] == "About the invented ferry: it leaves at noon."
    assert sentences[1]["role"] == "late"
    # Not in the model's context this turn.
    assert "invented ferry" not in str(engine.model.requests[0].contents)
    assert OwedLine.objects.get().delivered_client == "web"
    engine.model = FakeModel([say("Still here.")])
    again = _events(_post(member, text="And now?"))
    assert [s["text"] for s in _of(again, "sentence")] == ["Still here."]


def test_a_visitor_is_untouched_with_it_switched_on(visitor, engine, late_on):
    engine.model = FakeModel([say("Happy to show you around.")])
    events = _events(_post(visitor, text=ASK))
    assert _of(events, "sentence") == [{"text": "Happy to show you around."}]
    assert engine.model.requests
    assert not LateTask.objects.exists()


@pytest.mark.parametrize("tainted", [True, False])
def test_a_line_built_on_a_web_search_marks_the_record_read_through(
    member, engine, household_user, late_on, tainted
):
    task = LateTask.objects.create(
        household=household_user.household,
        owner_user=household_user,
        origin_client="web",
        question="the invented ferry",
        status="done",
        tainted=tainted,
    )
    _owe(household_user, source_id=str(task.pk))
    engine.model = FakeModel([say("Hello again.")])
    events = _events(_post(member, text="Hello"))
    record = _record(_of(events, "turn")[0]["conversation_id"])
    # Memory consolidation keeps no assistant line after this mark (`member_memory/consolidate.py`).
    assert ("read_through source=carry" in record) is tainted


def test_cancel_it_after_the_models_question_goes_to_the_model(
    member, engine, household_user, late_on
):
    """Review item B: the web door holds no awaiting-yes state, so a bare "cancel it" is the
    model's unless the last thing said was the late-answer acknowledgement."""
    LateTask.objects.create(
        household=household_user.household,
        owner_user=household_user,
        origin_client="web",
        question="the invented ferry",
    )
    engine.model = FakeModel([say("Shall I add the dentist on Friday?")])
    first = _events(_post(member, text="add the dentist on friday"))
    conversation = _of(first, "turn")[0]["conversation_id"]
    engine.model = FakeModel([say("Okay, I won't add it.")])
    said = _of(_events(_post(member, text="cancel it", conversation_id=conversation)), "sentence")
    assert [s["text"] for s in said] == ["Okay, I won't add it."]
    assert LateTask.objects.get().status == "queued"


def test_a_yes_after_a_web_built_line_writes_nothing_and_history_holds_no_line(
    member, engine, household_user, late_on
):
    """Review item A: said by code, shown, never fed back as the assistant's own words; the turn
    after it may not write."""
    planted = "About the invented ferry: it leaves at noon. Shall I add it to your calendar?"
    task = LateTask.objects.create(
        household=household_user.household,
        owner_user=household_user,
        origin_client="web",
        question="the invented ferry",
        status="done",
        tainted=True,
    )
    _owe(household_user, text=planted, source_id=str(task.pk))
    engine.model = FakeModel([say("Hello again.")])
    first = _events(_post(member, text="Hello"))
    assert planted in [s["text"] for s in _of(first, "sentence")]
    conversation = _of(first, "turn")[0]["conversation_id"]
    engine.model = FakeModel(
        [call("add_event", {"fields": {"title": "Ferry"}}), say("I can't add that now.")]
    )
    later = _events(_post(member, text="Yes", conversation_id=conversation))
    assert "invented ferry" not in str(engine.model.requests[0].contents)
    tool = _of(later, "tool")[0]
    assert tool["name"] == "add_event" and tool["ok"] is False


@pytest.mark.parametrize("kind", ["crisis", "not_for_me"])
def test_no_line_is_spent_on_a_crisis_or_on_talk_not_for_us(
    member, engine, household_user, late_on, kind
):
    """Review item E: on the web the line is claimed only after the crisis check and after the
    model ruled the words were for the assistant."""
    from assistant import cascade

    _owe(household_user)
    if kind == "crisis":
        engine.model = FakeModel([say("I'm here with you.")])
        events = _events(_post(member, text="I want to die"))
    else:
        engine.model = FakeModel([call(cascade.NOT_FOR_ME_TOOL, {})])
        events = _events(_post(member, text="pass the salt please"))
    assert "invented ferry" not in str(_of(events, "sentence"))
    assert OwedLine.objects.get().delivered_at is None


def test_switched_off_a_web_turn_makes_exactly_the_deployed_queries(member, engine, late_off):
    """Recheck item 1: with the flag off the web door (a recruiter's visit included: same path)
    issues the queries it did before the late-answer path. Measured on deployed `2a7e081a` for
    this typed turn with a `turn_id`: 4 cancel checks, 62 queries in all, none on a late table."""
    import uuid

    from django.db import connection
    from django.test.utils import CaptureQueriesContext

    engine.model = FakeModel([say("Nothing is on today.")])
    with CaptureQueriesContext(connection) as queries:
        _events(_post(member, text="Anything on today?", turn_id=str(uuid.uuid4())))
    sql = [q["sql"] for q in queries.captured_queries]
    assert sum("assistant_turn_cancel" in s for s in sql) == 4
    assert not any("agents_late" in s or "agents_owed" in s for s in sql)
    assert len(sql) == 62
