# ruff: noqa: F811 - fixtures imported from the cascade tests
"""#475 through the cascade door (`POST /api/assistant/turn`, ADR 0102): a web search leaves no
third-party text in the durable record; the next turn still has the reply in its history (the
transient context); the answer heard back on a barge-in is caught as an echo against the reply's
own words and stored nowhere, and a real question asked over it is answered and stored as his
words. Separate from `tests/test_web_read_through_record.py` because the cascade's autouse
`_clean` unsets the Live pin those tests need."""

from __future__ import annotations

import json

import pytest

from agents.runtime.model import FakeModel, call, say
from assistant import context as turn_context
from devicelogs.models import DeviceLog
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


def _searched(member, engine, answer: str) -> str:
    asked = "What does the web say about it?"
    engine.model = FakeModel([call("web_search", {"query": "pump"}), say(answer)])
    first = _events(_post(member, text=asked))
    conversation = _of(first, "turn")[0]["conversation_id"]
    record = _record(conversation)
    for word in ("Example Motors", "60,000", "Nvidia", '"query"'):
        assert word not in record
    assert f"text={json.dumps(asked)}" in record  # his words stay
    assert "name=web_search success=true" in record and "assistant kept=false" in record
    return conversation


def test_the_next_turn_has_the_reply_in_its_history_and_the_record_never_does(member, engine):
    answer = f"It says: {PAGE}"
    conversation = _searched(member, engine, answer)
    engine.model = FakeModel([say("You're welcome.")])
    _events(_post(member, text="Thanks", conversation_id=conversation))
    replayed = json.dumps(engine.model.requests[0].contents)
    assert "60,000" in replayed  # the live turn reads it, from the transient context
    assert "60,000" not in _record(conversation)


def test_the_answer_heard_back_is_an_echo_and_is_stored_nowhere(member, engine):
    """The r4 review's leak pair: a spoken figure misheard as words is still the reply."""
    conversation = _searched(
        member,
        engine,
        f"It says: {PAGE} Worth checking before a long drive.",
    )
    engine.model = FakeModel([])
    engine.speech.heard = "the pump fails at sixty thousand miles worth checking"
    events = _events(_post(member, audio=PCM, conversation_id=conversation, barge_in="1"))
    assert engine.model.requests == []  # no model round on its own voice
    assert _of(events, "done")[0]["ignored"] == "no_speech"
    record = _record(conversation)
    assert "status=echo" in record
    assert "sixty thousand" not in record and "pump" not in record


def test_a_real_question_over_the_reply_is_answered_and_kept_as_his_words(member, engine):
    """The r4 review's false-drop pair: his question shares words with the reply, and is his."""
    conversation = _searched(
        member,
        engine,
        "The stock price of Nvidia today is one hundred and forty dollars, up three percent on "
        "the week.",
    )
    engine.model = FakeModel([say("That was today's price.")])
    engine.speech.heard = "wait what is the stock price of nvidia today"
    events = _events(_post(member, audio=PCM, conversation_id=conversation, barge_in="1"))
    assert len(engine.model.requests) == 1  # answered
    assert _of(events, "done")[0].get("ignored") is None
    record = _record(conversation)
    assert 'text="wait what is the stock price of nvidia today"' in record
    assert "status=echo" not in record


def test_the_reply_is_in_the_context_from_its_first_sentence(member, engine):
    """Written as each sentence goes out, not when the turn ends: a barge-in that arrives while
    the reply plays finds it for the echo check."""
    engine.model = FakeModel([say("First part. Second part.")])
    events = _post(member, text="Hello")
    stream = iter(events.streaming_content)
    seen = b""
    while b"event: sentence" not in seen:
        seen += next(stream)
    conversation = json.loads(seen.split(b"data: ")[1].split(b"\n")[0])["conversation_id"]
    row = DeviceLog.objects.get(batch_id=conversation)
    assert turn_context.last_reply(row).startswith("First part.")
    for _ in stream:
        pass
