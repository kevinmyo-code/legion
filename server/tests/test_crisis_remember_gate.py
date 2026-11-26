# ruff: noqa: F811 - fixtures imported from the suites they mirror
"""Nothing from a crisis conversation is saved as a memory (ticket #462).

Driven through the cascade turn (`POST /api/assistant/turn`), the production default and the one
door that says a crisis turn's words to a model holding `remember`: the voice door answers by
code and calls no model (`agents/voice/pipeline.py`), and the hourly consolidation of a device's
record is #460's gate. Google is never called (fake model and fake speech).

Invented text only: a made-up member Sam, a made-up wagon, a made-up gym on Elm Street.

What is asserted is behaviour, not the gate's internals: no `MemberMemory` row exists, the tool
result the model is handed is a refusal, and the reply does not claim a save.
"""

from __future__ import annotations

import pytest
from django.db import DatabaseError

from agents.legion.lines import CRISIS_NOTHING_SAVED
from agents.runtime.model import FakeModel, call, say
from assistant import crisis_memory
from devicelogs import web_conversation
from member_memory.models import MemberMemory
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    _clean,
    _events,
    _of,
    _post,
    _record,
    engine,
    member,
)

pytestmark = pytest.mark.django_db

# A listed crisis phrase, in an invented sentence.
CRISIS_WORDS = "I don't want to live anymore."
# Ordinary car talk that CONTAINS a listed phrase and is excluded from it on purpose.
CAR_TALK = "Does the wagon have suicide doors?"
MEMORY = "The gym on Elm Street opens at six."


@pytest.fixture(autouse=True)
def _no_failed_marks():
    """Each test starts with no conversation carried over from another's failed mark."""
    crisis_memory._UNMARKED.clear()
    yield
    crisis_memory._UNMARKED.clear()


def _turn(client, engine, rounds, conversation=None, words="Go on."):
    engine.model = FakeModel(rounds)
    fields = {"text": words}
    if conversation:
        fields["conversation_id"] = conversation
    events = _events(_post(client, **fields))
    return events, _of(events, "turn")[0]["conversation_id"]


def _tools(events):
    return {t["name"]: t for t in _of(events, "tool")}


def _said(events) -> str:
    return " ".join(s["text"] for s in _of(events, "sentence"))


# What a model says when it believes the save went through. It must never be heard on a refused
# turn: round 2 finding 5 on the read-through gate was exactly this, "Noted." over nothing saved.
CLAIM = "Noted, I've saved that."


def _remembering(text=MEMORY):
    return [call("remember", {"text": text}), say(CLAIM)]


# ---- the gate ----------------------------------------------------------------------------------


def test_remember_is_refused_in_the_crisis_turn_itself(member, engine):
    events, _ = _turn(member, engine, _remembering(), words=CRISIS_WORDS)
    refused = _tools(events)["remember"]
    assert refused["ok"] is False
    assert refused["text"] == crisis_memory.REFUSAL
    assert MemberMemory.objects.count() == 0


def test_remember_is_refused_in_every_later_turn_of_that_conversation(member, engine):
    _, conversation = _turn(member, engine, [say("I'm here.")], words=CRISIS_WORDS)
    for _ in range(2):
        events, _ = _turn(
            member,
            engine,
            _remembering(),
            conversation,
            words="Remember when the gym opens.",
        )
        assert _tools(events)["remember"]["ok"] is False
        assert _tools(events)["remember"]["text"] == crisis_memory.REFUSAL
    assert MemberMemory.objects.count() == 0


def test_the_words_said_before_the_phrase_are_not_saved_either(member, engine):
    """The whole conversation goes, not the one turn: the rule #458 settled for the car."""
    _, conversation = _turn(
        member, engine, _remembering(), words="Remember that the gym opens at six."
    )
    assert MemberMemory.objects.count() == 1
    saved = MemberMemory.objects.get()
    _turn(member, engine, [say("I'm here.")], conversation, words=CRISIS_WORDS)
    events, _ = _turn(
        member, engine, _remembering("Sam felt low."), conversation, words="Save that."
    )
    assert _tools(events)["remember"]["ok"] is False
    # Nothing new, and the memory from before the phrase is not deleted by this gate either: it
    # only ever refuses a write (`forget_memory` is how a memory goes).
    assert [m.id for m in MemberMemory.objects.all()] == [saved.id]


def test_the_reply_does_not_claim_it_saved(member, engine):
    events, _ = _turn(member, engine, _remembering(), words=CRISIS_WORDS)
    said = _said(events)
    assert CRISIS_NOTHING_SAVED in said
    # The model's own claim is held back, not said beside the correction.
    assert CLAIM not in said
    # No promise that asking again works: this gate does not lift next turn.
    assert "ask me again" not in said.lower()


def test_the_conversation_is_marked_without_keeping_his_words(member, engine):
    _, conversation = _turn(member, engine, [say("I'm here.")], words=CRISIS_WORDS)
    body = _record(conversation)
    assert web_conversation.CRISIS_MARK in body
    mark = [text for text in body.splitlines() if web_conversation.CRISIS_MARK in text]
    assert len(mark) == 1
    assert "want to live" not in mark[0]


# ---- the controls: what must STILL save --------------------------------------------------------


def test_an_ordinary_conversation_still_saves(member, engine):
    events, _ = _turn(member, engine, _remembering(), words="Remember the gym hours.")
    assert _tools(events)["remember"]["ok"] is True
    assert MemberMemory.objects.get().text == MEMORY


def test_car_talk_that_contains_a_listed_phrase_still_saves(member, engine):
    events, conversation = _turn(member, engine, _remembering(), words=CAR_TALK)
    assert _tools(events)["remember"]["ok"] is True
    assert MemberMemory.objects.count() == 1
    assert web_conversation.CRISIS_MARK not in _record(conversation)


def test_the_gate_is_per_conversation_not_per_member(member, engine):
    _turn(member, engine, [say("I'm here.")], words=CRISIS_WORDS)
    events, _ = _turn(member, engine, _remembering(), words="Remember the gym hours.")
    assert _tools(events)["remember"]["ok"] is True
    assert MemberMemory.objects.count() == 1


def test_the_crisis_turn_still_gets_its_reply(member, engine):
    """The crisis reply path is untouched: the turn runs as it did, model and all. Only the
    memory write is refused."""
    events, _ = _turn(member, engine, [say("I'm here with you.")], words=CRISIS_WORDS)
    assert "I'm here with you." in _said(events)
    assert _of(events, "done")[0]["status"] == "done"


# ---- failing closed ----------------------------------------------------------------------------


def test_a_mark_that_cannot_be_written_refuses_every_later_remember(member, engine, monkeypatch):
    real = web_conversation.append

    def _fail_the_mark(row, stamped):
        """Only the mark's own write fails: the rest of the record is written as usual, so the
        turn still happens (the record must never fail a turn)."""
        if any(web_conversation.CRISIS_MARK in text for _, text in stamped):
            raise DatabaseError("no")
        return real(row, stamped)

    monkeypatch.setattr(web_conversation, "append", _fail_the_mark)
    _, conversation = _turn(member, engine, [say("I'm here.")], words=CRISIS_WORDS)
    assert web_conversation.CRISIS_MARK not in _record(conversation)
    events, _ = _turn(member, engine, _remembering(), conversation, words="Save that.")
    assert _tools(events)["remember"]["ok"] is False
    assert _tools(events)["remember"]["text"] == crisis_memory.REFUSAL
    assert MemberMemory.objects.count() == 0
    # Another conversation is not refused by one conversation's failed mark.
    events, _ = _turn(member, engine, _remembering(), words="Remember the gym hours.")
    assert _tools(events)["remember"]["ok"] is True


def test_a_mark_that_cannot_be_read_refuses_remember(member, engine, monkeypatch):
    _, conversation = _turn(member, engine, [say("Hi.")], words="Hello.")

    def _fail(*_args, **_kwargs):
        raise DatabaseError("no")

    monkeypatch.setattr(crisis_memory, "_marked", _fail)
    events, _ = _turn(member, engine, _remembering(), conversation, words="Save that.")
    assert _tools(events)["remember"]["ok"] is False
    assert _tools(events)["remember"]["text"] == crisis_memory.REFUSAL
    assert MemberMemory.objects.count() == 0
