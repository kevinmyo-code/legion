"""The carry on the web door (the owner's ruling, 2026-11-21, ADR 0090 amended): a turn that read
outside text (a web search, the brief's news, a GitHub result) taints his NEXT turn too: every
write there is refused in the carried read-through words, the turn still answers what it can, and
the carry clears after that one turn. Members only: a demo visit keeps today's behaviour
(`read_through.CARRY_ON_DEMO`). Fake model, fake speech; every name is invented."""

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
    member,
    visitor,
    visitor_user,
)

pytestmark = pytest.mark.django_db


def _said(events) -> list[str]:
    return [s["text"] for s in _of(events, "sentence")]


def _tools(events) -> list[dict]:
    return _of(events, "tool")


def _search_turn(client, engine) -> str:
    engine.model = FakeModel(
        [call("web_search", {"query": "invented museum hours"}), say("It opens at nine.")]
    )
    events = _events(_post(client, text="When does the invented museum open?"))
    return _of(events, "turn")[0]["conversation_id"]


def _event_titles() -> list[str]:
    from legacy.models.dates import Event

    return list(Event.objects.values_list("title", flat=True))


def test_add_that_to_my_list_the_turn_after_a_search_is_refused_and_nothing_is_written(
    member, engine
):
    conversation = _search_turn(member, engine)
    engine.model = FakeModel(
        [call("add_event", {"fields": {"title": "Museum visit"}}), say("Done, it's on there.")]
    )
    events = _events(_post(member, text="Add that to my list.", conversation_id=conversation))
    tool = _tools(events)[0]
    assert tool["name"] == "add_event" and tool["ok"] is False
    assert tool["text"].startswith("Nothing was changed: add_event did not run.")
    assert "turn before this one read outside text" in tool["text"]
    assert "Museum visit" not in _event_titles()
    # The closing line is the carried one, never "this turn read text from the web", and the
    # held claim ("Done, it's on there.") is never said as if the write had happened.
    assert _said(events) == [cascade.READ_THROUGH_WAIT_CARRIED]


def test_a_write_he_fully_names_himself_the_turn_after_a_search_also_waits(member, engine):
    # The rule built (owner's "keep it strict"): no early clearing for a self-specified write.
    conversation = _search_turn(member, engine)
    engine.model = FakeModel(
        [call("add_event", {"fields": {"title": "Dentist"}}), say("Added the dentist.")]
    )
    events = _events(
        _post(member, text="Add a dentist appointment on Friday.", conversation_id=conversation)
    )
    assert _tools(events)[0]["ok"] is False
    assert "Dentist" not in _event_titles()
    assert _said(events) == [cascade.READ_THROUGH_WAIT_CARRIED]


def test_the_carry_clears_after_one_turn(member, engine):
    conversation = _search_turn(member, engine)
    engine.model = FakeModel([say("Sure, what should I add?")])
    _events(_post(member, text="Can you add something?", conversation_id=conversation))
    engine.model = FakeModel(
        [call("add_event", {"fields": {"title": "Museum visit"}}), say("Added it.")]
    )
    events = _events(_post(member, text="Add a museum visit.", conversation_id=conversation))
    assert _tools(events)[0]["ok"] is True
    assert "Museum visit" in _event_titles()
    assert _said(events) == ["Added it."]


def test_a_carried_turn_still_answers_what_it_can(member, engine):
    conversation = _search_turn(member, engine)
    engine.model = FakeModel([call("list_events", {}), say("Nothing is on today.")])
    events = _events(_post(member, text="What's on today?", conversation_id=conversation))
    assert _tools(events)[0]["ok"] is True
    assert _said(events) == ["Nothing is on today."]


def test_a_search_in_the_same_turn_keeps_the_same_turn_words(member, engine):
    engine.model = FakeModel(
        [
            call("web_search", {"query": "fair date"}),
            call("add_event", {"fields": {"title": "Fair"}}),
            say("The fair is on Saturday."),
        ]
    )
    events = _events(_post(member, text="Look up the fair and add it."))
    assert _tools(events)[1]["text"].startswith("Nothing was changed: add_event did not run. This")
    assert _said(events) == ["The fair is on Saturday.", cascade.READ_THROUGH_WAIT]


def test_a_carried_turn_that_searches_again_uses_the_same_turn_words(member, engine):
    conversation = _search_turn(member, engine)
    engine.model = FakeModel(
        [
            call("web_search", {"query": "invented museum price"}),
            call("add_event", {"fields": {"title": "Museum visit"}}),
            say("Entry is free."),
        ]
    )
    events = _events(_post(member, text="Is it free? Add it.", conversation_id=conversation))
    assert _said(events) == ["Entry is free.", cascade.READ_THROUGH_WAIT]


def test_a_demo_visit_keeps_todays_behaviour(visitor, engine):
    assert read_through.CARRY_ON_DEMO is False
    conversation = _search_turn(visitor, engine)
    engine.model = FakeModel(
        [call("add_event", {"fields": {"title": "Museum visit"}}), say("Added it.")]
    )
    events = _events(_post(visitor, text="Add that.", conversation_id=conversation))
    assert _tools(events)[0]["ok"] is True
    assert _said(events) == ["Added it."]


def test_a_nothing_heard_request_between_neither_spends_nor_breaks_the_carry(member, engine):
    from tests.test_assistant_cascade import PCM, FakeSpeech

    conversation = _search_turn(member, engine)
    engine.speech = FakeSpeech(heard="")
    quiet = _events(_post(member, audio=PCM, conversation_id=conversation))
    assert _of(quiet, "done")[0]["status"] == "nothing_heard"
    engine.model = FakeModel(
        [call("add_event", {"fields": {"title": "Museum visit"}}), say("Added it.")]
    )
    events = _events(_post(member, text="Add that.", conversation_id=conversation))
    assert _tools(events)[0]["ok"] is False
    assert _said(events) == [cascade.READ_THROUGH_WAIT_CARRIED]


# ---- review 1121: the record line, the cut-off turn, the two word options --------------------


def test_a_carried_turn_writes_the_carry_record_line_and_a_plain_turn_does_not(member, engine):
    from tests.test_assistant_cascade import _record

    conversation = _search_turn(member, engine)
    engine.model = FakeModel([say("Nothing is on today.")])
    _events(_post(member, text="What's on today?", conversation_id=conversation))
    record = _record(conversation)
    assert record.count("read_through source=carry") == 1
    # The line sits after his carried words, so consolidation drops that turn.
    assert record.index("read_through source=carry") > record.index("What's on today?")
    engine.model = FakeModel([say("Still nothing.")])
    _events(_post(member, text="And now?", conversation_id=conversation))
    assert _record(conversation).count("read_through source=carry") == 1


def test_a_demo_visit_writes_no_carry_record_line(visitor, engine):
    from tests.test_assistant_cascade import _record

    conversation = _search_turn(visitor, engine)
    engine.model = FakeModel([say("Nothing is on today.")])
    _events(_post(visitor, text="What's on today?", conversation_id=conversation))
    assert "read_through source=carry" not in _record(conversation)


@pytest.mark.django_db(transaction=True)
def test_a_carried_turn_cut_off_before_its_answer_does_not_spend_the_carry(
    member, engine, household_user
):
    import uuid

    from assistant.models import TurnCancel

    conversation = _search_turn(member, engine)
    turn_id = uuid.uuid4()

    class CutOff(FakeModel):
        def stream(self, req):
            TurnCancel.objects.create(turn_id=turn_id, user=household_user)
            yield from super().stream(req)

    engine.model = CutOff([say("This answer is never heard.")])
    cut = _events(
        _post(member, text="What's on today?", conversation_id=conversation, turn_id=str(turn_id))
    )
    assert _of(cut, "done")[0]["status"] == "cancelled"
    engine.model = FakeModel(
        [call("add_event", {"fields": {"title": "Museum visit"}}), say("Added it.")]
    )
    events = _events(_post(member, text="Add that.", conversation_id=conversation))
    assert _tools(events)[0]["ok"] is False
    assert _said(events) == [cascade.READ_THROUGH_WAIT_CARRIED]
    engine.model = FakeModel(
        [call("add_event", {"fields": {"title": "Museum visit"}}), say("Added it.")]
    )
    events = _events(_post(member, text="Add it now.", conversation_id=conversation))
    assert _tools(events)[0]["ok"] is True


def _search_and_write_turn(client, engine):
    engine.model = FakeModel(
        [
            call("web_search", {"query": "fair date"}),
            call("add_event", {"fields": {"title": "Fair"}}),
            say("The fair is on Saturday."),
        ]
    )
    events = _events(_post(client, text="Look up the fair and add it."))
    return events, _of(events, "turn")[0]["conversation_id"]


def test_option_a1_the_same_turn_words_say_after_his_next_message(member, engine, monkeypatch):
    monkeypatch.setattr(read_through, "HONEST_SAME_TURN_WORDS", True)
    events, _c = _search_and_write_turn(member, engine)
    assert _tools(events)[1]["text"] == read_through.WRITE_REFUSAL_UNTIL_AFTER.format(
        verb="changed", name="add_event"
    )
    assert _said(events) == ["The fair is on Saturday.", cascade.READ_THROUGH_WAIT_UNTIL_AFTER]


def test_option_a1_keeps_the_old_words_on_the_demo(visitor, engine, monkeypatch):
    monkeypatch.setattr(read_through, "HONEST_SAME_TURN_WORDS", True)
    events, _c = _search_and_write_turn(visitor, engine)
    assert _said(events)[-1] == cascade.READ_THROUGH_WAIT


def test_option_a2_a_turn_that_refused_in_its_own_read_does_not_carry(
    member, engine, monkeypatch
):
    monkeypatch.setattr(read_through, "CARRY_AFTER_REFUSAL", False)
    events, conversation = _search_and_write_turn(member, engine)
    assert _said(events)[-1] == cascade.READ_THROUGH_WAIT  # true again under A2
    engine.model = FakeModel([call("add_event", {"fields": {"title": "Fair"}}), say("Added.")])
    later = _events(_post(member, text="Add the fair.", conversation_id=conversation))
    assert _tools(later)[0]["ok"] is True


def test_option_a2_a_plain_read_still_carries(member, engine, monkeypatch):
    monkeypatch.setattr(read_through, "CARRY_AFTER_REFUSAL", False)
    conversation = _search_turn(member, engine)
    engine.model = FakeModel([call("add_event", {"fields": {"title": "Fair"}}), say("Added.")])
    later = _events(_post(member, text="Add that.", conversation_id=conversation))
    assert _tools(later)[0]["ok"] is False
