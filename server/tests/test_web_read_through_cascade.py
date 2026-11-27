# ruff: noqa: F811 - fixtures imported from the cascade tests
"""#475 through the cascade door (`POST /api/assistant/turn`, ADR 0102): a web search leaves no
page text in the record, the answer heard back on a barge-in is caught as an echo by its keyed
fingerprint and stored nowhere, and a real barge-in is stored as his words. Separate from
`tests/test_web_read_through_record.py` because the cascade's autouse `_clean` unsets the Live
pin those tests need."""

from __future__ import annotations

import json

import pytest

from agents.runtime.model import FakeModel, call, say
from devicelogs import web_conversation as wc
from devicelogs.voice_audit import REDACTED
from engine_mcp import web_search
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    PCM,
    _clean,
    _events,
    _of,
    _post,
    _record,
    engine,
    member,
)

pytestmark = pytest.mark.django_db

PAGE = "Example Motors says the pump fails at 60,000 miles."
ANSWER = f"It says: {PAGE} Worth checking before a long drive."
MARK = json.dumps(REDACTED)


@pytest.fixture(autouse=True)
def fake_web(monkeypatch):
    monkeypatch.setattr(
        web_search,
        "search",
        lambda query, **_: web_search.WebAnswer(
            query=query,
            answer=PAGE,
            sources=[{"title": "Example Motors", "url": "https://example.com/pump"}],
        ),
    )


def _searched(member, engine) -> str:
    asked = "What does the web say about the pump?"
    engine.model = FakeModel([call("web_search", {"query": "pump"}), say(ANSWER)])
    first = _events(_post(member, text=asked))
    assert "Example Motors" in " ".join(s["text"] for s in _of(first, "sentence"))  # said
    conversation = _of(first, "turn")[0]["conversation_id"]
    record = _record(conversation)
    assert PAGE not in record and "60,000" not in record
    assert f"text={json.dumps(asked)}" in record
    assert f"assistant text={MARK}" in record
    return conversation


def test_the_answer_heard_back_is_an_echo_and_is_stored_nowhere(member, engine):
    conversation = _searched(member, engine)
    engine.model = FakeModel([])
    engine.speech.heard = "says the pump fails at 60,000 miles worth checking before"
    events = _events(_post(member, audio=PCM, conversation_id=conversation, barge_in="1"))
    assert engine.model.requests == []  # no model round on its own voice
    assert _of(events, "done")[0]["ignored"] == "no_speech"
    record = _record(conversation)
    assert "status=echo" in record
    assert "60,000" not in record and "Example Motors" not in record


def test_a_real_barge_in_after_it_is_stored_as_his_words(member, engine):
    conversation = _searched(member, engine)
    engine.model = FakeModel([say("Okay.")])
    engine.speech.heard = "Stop, can you add a reminder for the garage on Friday"
    _events(_post(member, audio=PCM, conversation_id=conversation, barge_in="1"))
    record = _record(conversation)
    assert 'text="Stop, can you add a reminder for the garage on Friday"' in record
    assert wc.WITHHELD_USER not in record
    # The read turn's print went when this turn completed; this (carried, withheld) reply has its
    # own until the next turn does.
    assert record.count(" assistant_echo print=") == 1


def test_a_barge_in_before_the_read_turns_reply_is_written_keeps_no_words(
    member, engine, monkeypatch
):
    """The race: the read turn's reply reaches the record when that turn ends, so a barge-in
    can arrive first. Its transcript may be that reply, with nothing yet to check it against."""
    conversation = _searched(member, engine)
    monkeypatch.setattr(wc, "last_reply", lambda row: ("", ()))  # as if not yet written
    engine.model = FakeModel([say("Okay.")])
    engine.speech.heard = "the pump fails at 60,000 miles"
    _events(_post(member, audio=PCM, conversation_id=conversation, barge_in="1"))
    record = _record(conversation)
    assert "60,000" not in record
    assert wc.WITHHELD_USER in record
