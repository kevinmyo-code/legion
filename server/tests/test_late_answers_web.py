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
from agents.runtime.model import FakeModel, say
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    _clean,
    _events,
    _kinds,
    _of,
    _post,
    _record,
    engine,
    member,
    visitor,
    visitor_user,
)

pytestmark = pytest.mark.django_db

ASK = "look into the invented ferry times and tell me later"


@pytest.fixture
def late_on(monkeypatch):
    monkeypatch.setenv(LATE_ANSWERS_ENV, "on")


@pytest.fixture
def late_off(monkeypatch):
    monkeypatch.delenv(LATE_ANSWERS_ENV, raising=False)


def _owe(user, text="About the invented ferry: it leaves at noon."):
    return OwedLine.objects.create(
        household=user.household,
        owner_user=user,
        source="late_task",
        source_id="made-up",
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


def test_a_member_hears_the_owed_line_first_and_once(member, engine, household_user, late_on):
    _owe(household_user)
    engine.model = FakeModel([say("Hello again.")])
    events = _events(_post(member, text="Hello"))
    sentences = _of(events, "sentence")
    assert sentences[0]["text"] == "About the invented ferry: it leaves at noon."
    assert sentences[0]["role"] == "late"
    assert sentences[1] == {"text": "Hello again."}
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
