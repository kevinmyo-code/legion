# ruff: noqa: F811 - fixtures imported from the suites they mirror
"""The standing write-guard, conversation-record and read-through scenarios, driven through the
cascade turn (`POST /api/assistant/turn`), the production default (blind review 2026-11-17,
finding 11). On the cascade engine the Live-shaped tests in `test_write_guard.py`,
`test_web_conversation.py` and `test_read_through.py` skip at the Live mint; these are the same
scenarios on the cascade's own path. Google is never called (fake model and speech); GitHub and the
web answer the planted text the write-guard suite uses. Invented data only.
"""

from __future__ import annotations

import pytest

from agents.runtime.model import FakeModel, call, say
from assistant import read_through
from legacy.models.dates import Event
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    _clean,
    _events,
    _of,
    _post,
    _record,
    engine,
    member,
    visitor,
    visitor_user,
)
from tests.test_write_guard import (  # noqa: F401
    PLANTED,
    _guard_sentence,
    fake_outside,
    no_failed_marks,
)

pytestmark = pytest.mark.django_db

WHEN = "2026-12-01T09:00:00Z"


def _turn(client, engine, rounds, conversation=None, words="Go on."):
    engine.model = FakeModel(rounds)
    fields = {"text": words}
    if conversation:
        fields["conversation_id"] = conversation
    events = _events(_post(client, **fields))
    return events, _of(events, "turn")[0]["conversation_id"]


def _tools(events):
    return {t["name"]: t for t in _of(events, "tool")}


def _make_event(client, engine, title="Dentist"):
    events, conversation = _turn(
        client,
        engine,
        [call("add_event", {"fields": {"title": title, "starts_at": WHEN}}), say("Added.")],
    )
    assert _tools(events)["add_event"]["ok"] is True
    return conversation, str(Event.objects.get(title=title).id)


def _live(event_id):
    return Event.objects.filter(id=event_id, deleted_at__isnull=True).exists()


# ---- the write guard (test_write_guard.py) -----------------------------------------------------


def test_github_then_delete_in_one_turn_is_refused_and_nothing_is_deleted(member, engine):
    conversation, event_id = _make_event(member, engine)
    events, _ = _turn(
        member,
        engine,
        [
            call("github_status", {"aspect": "open"}),
            call("delete_event", {"id": event_id}),
            say("Done."),
        ],
        conversation,
    )
    refused = _tools(events)["delete_event"]
    assert refused["ok"] is False
    assert refused["text"] == _guard_sentence("delete_event", destructive=True)
    assert _live(event_id)
    assert "name=delete_event success=false" in _record(conversation)


@pytest.mark.parametrize("source", ["github_status", "web_search", "brief_latest"])
def test_every_third_party_tool_bars_a_change_in_its_turn(member, engine, source):
    args = {"github_status": {"aspect": "open"}, "web_search": {"query": "pump"}}.get(source, {})
    events, _ = _turn(
        member,
        engine,
        [
            call(source, args),
            call("add_event", {"fields": {"title": "Planted", "starts_at": WHEN}}),
            say("Ok."),
        ],
    )
    assert _tools(events)["add_event"]["ok"] is False
    assert not Event.objects.filter(title="Planted").exists()


def test_a_turn_with_no_third_party_read_deletes_normally(member, engine):
    conversation, event_id = _make_event(member, engine)
    events, _ = _turn(
        member, engine, [call("delete_event", {"id": event_id}), say("Gone.")], conversation
    )
    assert _tools(events)["delete_event"]["ok"] is True
    assert not _live(event_id)


def test_the_next_turn_deletes_when_the_person_asks_again(member, engine):
    conversation, event_id = _make_event(member, engine)
    _turn(
        member,
        engine,
        [
            call("github_status", {"aspect": "open"}),
            call("delete_event", {"id": event_id}),
            say("x"),
        ],
        conversation,
    )
    assert _live(event_id)
    events, _ = _turn(
        member, engine, [call("delete_event", {"id": event_id}), say("Gone.")], conversation
    )
    assert _tools(events)["delete_event"]["ok"] is True
    assert not _live(event_id)


def test_reads_still_run_after_a_read_through(member, engine):
    conversation, _ = _make_event(member, engine, title="Still listed")
    events, _ = _turn(
        member,
        engine,
        [
            call("github_status", {"aspect": "open"}),
            call("list_events", {"from": "2026-11-30", "to": "2026-12-02"}),
            say("Here."),
        ],
        conversation,
    )
    listed = _tools(events)["list_events"]
    assert listed["ok"] is True and "Still listed" in listed["text"]


def test_another_conversation_is_not_tainted(member, engine):
    _turn(member, engine, [call("github_status", {"aspect": "open"}), say("Read.")])
    conversation, event_id = _make_event(member, engine)
    events, _ = _turn(
        member, engine, [call("delete_event", {"id": event_id}), say("Gone.")], conversation
    )
    assert _tools(events)["delete_event"]["ok"] is True


def test_a_visitor_is_guarded_after_a_web_search_too(visitor, engine):
    events, _ = _turn(
        visitor,
        engine,
        [
            call("web_search", {"query": "pump"}),
            call("add_checklist_item", {"checklist_id": "none", "text": PLANTED}),
            say("Ok."),
        ],
    )
    item = _tools(events).get("add_checklist_item")
    assert item is None or item["ok"] is False


# ---- the conversation record (test_web_conversation.py) ----------------------------------------


def test_a_turn_opens_a_record_with_the_words_and_the_tool_calls(member, engine):
    events, conversation = _turn(
        member, engine, [call("list_events", {}), say("Nothing on.")], words="What's on?"
    )
    record = _record(conversation)
    assert "user final=true typed=true" in record and "What's on?" in record
    assert "tool_call id=c1 name=list_events" in record
    assert "assistant text=" in record and "Nothing on." in record


def test_a_refused_tool_call_is_on_the_record_with_its_words(visitor, engine):
    events, conversation = _turn(visitor, engine, [call("delete_event", {"id": "x"}), say("No.")])
    refused = _tools(events)["delete_event"]
    assert refused["ok"] is False
    assert "name=delete_event success=false" in _record(conversation)


def test_a_member_cannot_post_into_another_members_conversation(member, engine, user_b):
    from tests.test_assistant import browser

    _events_, conversation = _turn(member, engine, [say("Hi.")])
    other = browser(user_b)
    engine.model = FakeModel([say("Hi.")])
    assert _post(other, text="Hi.", conversation_id=conversation).status_code == 404


# ---- read-through (test_read_through.py) -------------------------------------------------------


def test_remember_is_refused_in_the_turn_that_searched_the_web(member, engine):
    events, _ = _turn(
        member,
        engine,
        [
            call("web_search", {"query": "pump"}),
            call("remember", {"text": "The pump is great."}),
            say("Ok."),
        ],
    )
    refused = _tools(events)["remember"]
    assert refused["ok"] is False and refused["text"] == read_through.REFUSAL


def test_the_turn_after_next_may_remember(member, engine):
    _, conversation = _turn(member, engine, [call("web_search", {"query": "pump"}), say("Read.")])
    # The carry (ADR 0090 amended 2026-11-21): a plain read (no write refused) carries, so the
    # next turn refuses, in the carried words; the one after may.
    events, _ = _turn(
        member,
        engine,
        [call("remember", {"text": "I like oat milk."}), say("Noted.")],
        conversation,
    )
    assert _tools(events)["remember"]["ok"] is False
    assert _tools(events)["remember"]["text"] == read_through.CARRY_REFUSAL
    events, _ = _turn(
        member,
        engine,
        [call("remember", {"text": "I like oat milk."}), say("Noted.")],
        conversation,
    )
    assert _tools(events)["remember"]["ok"] is True


def test_another_members_search_taints_nobody_else(member, engine, user_b):
    from tests.test_assistant import browser

    _turn(member, engine, [call("web_search", {"query": "pump"}), say("Read.")])
    other = browser(user_b)
    events, _ = _turn(other, engine, [call("remember", {"text": "Tea, not coffee."}), say("Ok.")])
    assert _tools(events)["remember"]["ok"] is True
