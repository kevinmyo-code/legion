"""The web-search caution on the public /try demo (the owner's word, 2026-11-21: the member rule,
A2 with "only when he asked", now on recruiter visits too; ADR 0090 amended). Nothing else about
the demo changes. Fake model, fake speech; the visit's household is the invented one."""

# ruff: noqa: F811 - the fixtures are imported from the cascade tests

from __future__ import annotations

import pytest

from agents.runtime.model import FakeModel, call, say
from assistant import cascade, read_through
from tests.test_assistant_cascade import (  # noqa: F401
    _clean,
    _events,
    _of,
    _post,
    engine,
    visitor,
    visitor_user,
)

pytestmark = pytest.mark.django_db

# Every write a visitor's assistant has (`assistant.surface.RECRUITER_TOOLS` with `writes=True`).
DEMO_WRITES = (
    "add_checklist_item",
    "add_event",
    "archive_checklist",
    "create_checklist",
    "delete_event",
    "delete_purchase",
    "delete_record",
    "log_purchase",
    "remove_checklist_item",
    "rename_place",
    "skip_event_occurrence",
    "tick_checklist_item",
    "untick_checklist_item",
    "update_event",
    "write_record",
)


def _said(events) -> list[str]:
    return [s["text"] for s in _of(events, "sentence")]


def _tools(events) -> list[dict]:
    return _of(events, "tool")


def _search(client, engine) -> str:
    engine.model = FakeModel(
        [call("web_search", {"query": "invented museum hours"}), say("It opens at nine.")]
    )
    events = _events(_post(client, text="When does the invented museum open?"))
    return _of(events, "turn")[0]["conversation_id"]


def _add(client, engine, conversation, words="Add a museum visit."):
    engine.model = FakeModel(
        [call("add_event", {"fields": {"title": "Museum visit"}}), say("Added it.")]
    )
    return _events(_post(client, text=words, conversation_id=conversation))


def test_the_demo_has_the_member_rule():
    from assistant.surface import RECRUITER_TOOLS
    from engine_mcp.tools import TOOLS_BY_NAME

    assert not hasattr(read_through, "CARRY_ON_DEMO")
    writes = {n for n in RECRUITER_TOOLS if getattr(TOOLS_BY_NAME.get(n), "writes", False)}
    assert writes == set(DEMO_WRITES)


def test_a_visitor_search_then_a_write_is_refused_once_then_done(visitor, engine):
    conversation = _search(visitor, engine)
    carried = _add(visitor, engine, conversation)
    assert _tools(carried)[0]["ok"] is False
    assert "turn before this one read outside text" in _tools(carried)[0]["text"]
    assert _said(carried) == [cascade.READ_THROUGH_WAIT_CARRIED]
    later = _add(visitor, engine, conversation)
    assert _tools(later)[0]["ok"] is True
    assert _said(later) == ["Added it."]


def test_a_visitor_search_and_add_in_one_message_then_the_next_message_adds(visitor, engine):
    engine.model = FakeModel(
        [
            call("web_search", {"query": "invented fair"}),
            call("add_event", {"fields": {"title": "Fair"}}),
            say("The fair is on Saturday."),
        ]
    )
    events = _events(_post(visitor, text="Look up the invented fair and add it."))
    assert _said(events) == ["The fair is on Saturday.", cascade.READ_THROUGH_WAIT]
    conversation = _of(events, "turn")[0]["conversation_id"]
    later = _add(visitor, engine, conversation, "Add the fair.")
    assert _tools(later)[0]["ok"] is True


def test_a_write_the_page_asked_for_promises_nothing_on_the_demo(visitor, engine):
    engine.model = FakeModel(
        [
            call("web_search", {"query": "invented fair"}),
            call("add_event", {"fields": {"title": "Page says add me"}}),
            say("The fair is on Saturday."),
        ]
    )
    events = _events(_post(visitor, text="When is the invented fair?"))
    assert _tools(events)[1]["text"] == read_through.WRITE_REFUSAL_UNASKED.format(
        verb="changed", name="add_event"
    )
    assert _said(events) == ["The fair is on Saturday.", cascade.READ_THROUGH_NOTHING_CHANGED]
    conversation = _of(events, "turn")[0]["conversation_id"]
    ok = _add(visitor, engine, conversation, "ok")
    assert _tools(ok)[0]["ok"] is False


@pytest.mark.parametrize("tool", DEMO_WRITES)
def test_every_demo_write_is_refused_on_a_carried_turn(visitor, engine, tool):
    conversation = _search(visitor, engine)
    engine.model = FakeModel([call(tool, {}), say("Done.")])
    events = _events(_post(visitor, text="Do that.", conversation_id=conversation))
    result = _tools(events)[0]
    assert result["ok"] is False
    assert "turn before this one read outside text" in result["text"]


def test_a_carried_visit_turn_writes_the_carry_record_line(visitor, engine):
    from tests.test_assistant_cascade import _record

    conversation = _search(visitor, engine)
    engine.model = FakeModel([say("Nothing is on today.")])
    _events(_post(visitor, text="What's on today?", conversation_id=conversation))
    assert _record(conversation).count("read_through source=carry") == 1


def test_no_bleed_between_two_visitors_on_the_same_invite(visitor, engine, db):
    """Each visitor is their own user and household (ADR 0069), and the marks are keyed to the
    conversation's own record row and its user: one visitor's search never taints another's."""
    from household.models import Household, HouseholdMember, User
    from tests.test_recruiter import _recruiter_browser, _visitor_token

    _search(visitor, engine)
    demo = Household.objects.create(name="Demo visit two", is_demo_visit=True)
    other_user = User.objects.create_user(email="visitor-two@demo.invalid", first_name="Sam")
    other_user.set_unusable_password()
    other_user.save()
    HouseholdMember.objects.create(user=other_user, household=demo, role=HouseholdMember.RECRUITER)
    token, _raw = _visitor_token(other_user)
    other = _recruiter_browser(other_user, token)
    engine.model = FakeModel(
        [call("add_event", {"fields": {"title": "Museum visit"}}), say("Added it.")]
    )
    events = _events(_post(other, text="Add a museum visit."))
    assert _tools(events)[0]["ok"] is True


def test_a_visitor_turn_asks_once_whether_it_is_carried_and_makes_no_paid_call(visitor, engine):
    """Cost: no extra model or speech call; one read of the read-through marks per visit turn,
    inside a savepoint (3 queries; `read_through.carried_turn`), as on a member's turn."""
    import uuid

    from django.db import connection
    from django.test.utils import CaptureQueriesContext

    engine.model = FakeModel([say("Nothing is on today.")])
    with CaptureQueriesContext(connection) as queries:
        _events(_post(visitor, text="Anything on today?", turn_id=str(uuid.uuid4())))
    sql = [q["sql"] for q in queries.captured_queries]
    assert sum("assistant_read_through_turns" in s for s in sql) == 1
    assert len(engine.model.requests) == 1
    assert len(sql) == RECRUITER_TURN_QUERIES


# Measured on this branch for the turn above; 3 more than before the demo had the carry.
RECRUITER_TURN_QUERIES = 75
