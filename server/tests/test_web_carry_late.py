"""The carry and a late answer built on web pages (ADR 0090 amended, 2026-11-21): the owed line's
own turn is marked; the carry refuses the turn after it; the turn after that may write again
(marking both turns, as before, would have carried twice). Every name is invented."""

# ruff: noqa: F811 - the fixtures are imported from the late-answer web tests

from __future__ import annotations

import pytest

from agents.models import LateTask
from agents.runtime.model import FakeModel, call, say
from tests.test_assistant_cascade import _events, _of, _post
from tests.test_late_answers_web import _clean, _owe, engine, late_on, member  # noqa: F401

pytestmark = pytest.mark.django_db


def test_after_a_web_built_owed_line_the_next_turn_refuses_and_the_one_after_writes(
    member, engine, household_user, late_on
):
    task = LateTask.objects.create(
        household=household_user.household,
        owner_user=household_user,
        origin_client="web",
        question="the invented ferry",
        status="done",
        tainted=True,
    )
    _owe(household_user, source_id=str(task.pk))
    engine.model = FakeModel([say("Hello again.")])
    first = _events(_post(member, text="Hello"))
    conversation = _of(first, "turn")[0]["conversation_id"]
    add = [call("add_event", {"fields": {"title": "Ferry"}}), say("Added it.")]
    engine.model = FakeModel(list(add))
    carried = _events(_post(member, text="Add the ferry.", conversation_id=conversation))
    assert _of(carried, "tool")[0]["ok"] is False
    engine.model = FakeModel(list(add))
    later = _events(_post(member, text="Add the ferry now.", conversation_id=conversation))
    assert _of(later, "tool")[0]["ok"] is True
