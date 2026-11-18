# ruff: noqa: F811 - fixtures imported from the cascade tests
"""Round 3 N5 (`review-demo-voice-first-1117-turn-r3.md`): a `turn_id` answers `duplicate` only once
that turn reached a state the user could have received: a write ran, or its `done` event was sent.
A refusal before the turn runs, or a failure before either, leaves the id free, so the page's
re-send (the same `turn_id`) runs. Google is never called; every word is invented."""

from __future__ import annotations

import uuid

import pytest

from agents.runtime.model import FakeModel, call, say
from assistant import turn_slots
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    _events,
    _of,
    _post,
    engine,
    member,
    visitor,
    visitor_user,
)

pytestmark = pytest.mark.django_db


class _Crashing(FakeModel):
    """Serves its rounds, then crashes the turn outright (not a model failure the loop answers):
    the stream ends in an error, with no `done`."""

    def stream(self, req):
        if not self.rounds:
            raise RuntimeError("the turn broke")
        yield from super().stream(req)


def _said(events) -> list[str]:
    return [s["text"] for s in _of(events, "sentence")]


def test_a_turn_refused_no_conversation_runs_when_resent_as_a_new_conversation(visitor, engine):
    turn_id = str(uuid.uuid4())
    dead = _post(visitor, text="Hi.", turn_id=turn_id, conversation_id=str(uuid.uuid4()))
    assert dead.status_code == 404 and dead.data["code"] == "no_conversation"
    engine.model = FakeModel([say("Hello.")])
    assert _said(_events(_post(visitor, text="Hi.", turn_id=turn_id))) == ["Hello."]


def test_a_turn_refused_demo_full_runs_when_resent(visitor, engine, monkeypatch):
    real = turn_slots.acquire
    answers = iter([(None, turn_slots.BUSY_DEMO, 429)])
    monkeypatch.setattr(turn_slots, "acquire", lambda *a: next(answers, None) or real(*a))
    turn_id = str(uuid.uuid4())
    full = _post(visitor, text="Hi.", turn_id=turn_id)
    assert full.status_code == 429 and full.data["code"] == "demo_full"
    engine.model = FakeModel([say("Hello.")])
    assert _said(_events(_post(visitor, text="Hi.", turn_id=turn_id))) == ["Hello."]


def test_a_turn_that_failed_before_it_ran_is_not_a_duplicate(visitor, engine, monkeypatch):
    from devicelogs import web_conversation

    turn_id = str(uuid.uuid4())
    real = web_conversation.history

    def broken(*a, **k):
        raise RuntimeError("history unreadable")

    monkeypatch.setattr(web_conversation, "history", broken)
    visitor.raise_request_exception = False
    assert _post(visitor, text="Hi.", turn_id=turn_id).status_code == 500
    monkeypatch.setattr(web_conversation, "history", real)
    engine.model = FakeModel([say("Hello.")])
    assert _said(_events(_post(visitor, text="Hi.", turn_id=turn_id))) == ["Hello."]


def test_a_turn_whose_stream_broke_before_any_write_or_done_runs_again(member, engine):
    turn_id = str(uuid.uuid4())
    engine.model = _Crashing([])
    events = _events(_post(member, text="What's on?", turn_id=turn_id))
    assert _of(events, "error") and not _of(events, "done")
    engine.model = FakeModel([say("Nothing today.")])
    assert _said(_events(_post(member, text="What's on?", turn_id=turn_id))) == ["Nothing today."]


def test_a_turn_whose_write_ran_is_a_duplicate_even_if_its_stream_broke(member, engine):
    turn_id = str(uuid.uuid4())
    engine.model = _Crashing([call("create_checklist", {"name": "Hardware"})])
    events = _events(_post(member, text="Make a hardware list.", turn_id=turn_id))
    assert _of(events, "tool")[0]["ok"] is True and not _of(events, "done")
    again = _post(member, text="Make a hardware list.", turn_id=turn_id)
    assert again.status_code == 409 and again.data["code"] == "duplicate"


def test_a_turn_that_sent_done_is_a_duplicate(visitor, engine):
    turn_id = str(uuid.uuid4())
    engine.model = FakeModel([say("Hello.")])
    _events(_post(visitor, text="Hi.", turn_id=turn_id))
    again = _post(visitor, text="Hi.", turn_id=turn_id)
    assert again.status_code == 409 and again.data["code"] == "duplicate"
