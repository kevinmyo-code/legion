"""The late-answer path on the streamed agents door (`POST /api/agents/turn/stream`): the same
turn code as the form door (`agents/legion/turn.py`), so the same ask, the same owed line first,
and nothing at all while `LEGION_LATE_ANSWERS` is off. Google is never called; every word is
invented."""

# ruff: noqa: F811 - the `engine` and `stream` fixtures are imported and named in each test

from __future__ import annotations

from datetime import timedelta

import pytest
from django.utils import timezone

from agents.legion import late
from agents.legion.flags import LATE_ANSWERS_ENV
from agents.models import LateTask, OwedLine
from agents.runtime import model as m
from tests.test_engine_voice_live_stt import DONE, FakeSocket, _final
from tests.test_engine_voice_stream import _events, _post, stream, utterance  # noqa: F401
from tests.test_engine_voice_turn import engine  # noqa: F401

pytestmark = pytest.mark.django_db


def _owe(user):
    return OwedLine.objects.create(
        household=user.household,
        owner_user=user,
        source="late_task",
        source_id="made-up",
        text="About the invented ferry: it leaves at noon.",
        expires_at=timezone.now() + timedelta(hours=1),
    )


def _said(events):
    return [(d["text"], d["role"]) for k, d in events if k == "sentence"]


def test_switched_off_the_stream_door_is_unchanged(
    auth_client, household_user, stream, engine, monkeypatch
):
    monkeypatch.delenv(LATE_ANSWERS_ENV, raising=False)
    _owe(household_user)
    engine.rounds = [m.say("Nothing is on today.")]
    assert _said(_events(_post(auth_client, utterance()))) == [("Nothing is on today.", "answer")]
    assert OwedLine.objects.get().delivered_at is None


def test_the_owed_line_is_said_first_on_the_stream_door(
    auth_client, household_user, stream, engine, monkeypatch
):
    monkeypatch.setenv(LATE_ANSWERS_ENV, "on")
    late.saw_tick()
    line = _owe(household_user)
    engine.rounds = [m.say("Nothing is on today.")]
    events = _events(_post(auth_client, utterance()))
    assert _said(events) == [
        ("About the invented ferry: it leaves at noon.", "late"),
        ("Nothing is on today.", "answer"),
    ]
    late_line = next(d for k, d in events if k == "sentence" and d["role"] == "late")
    assert late_line["owed_id"] == str(line.pk) and late_line["line_id"]


def test_an_ask_on_the_stream_door_is_answered_by_code(
    auth_client, household_user, stream, engine, monkeypatch
):
    monkeypatch.setenv(LATE_ANSWERS_ENV, "on")
    late.saw_tick()
    stream.socket = FakeSocket(
        after_end=[_final("look into the invented ferry times and tell me later"), DONE]
    )
    engine.rounds = []
    events = _events(_post(auth_client, utterance()))
    assert [t for t, _ in _said(events)] == [late.ACK]
    assert LateTask.objects.get().question == "the invented ferry times"
