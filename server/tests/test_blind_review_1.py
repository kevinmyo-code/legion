# ruff: noqa: F811 - fixtures imported from the cascade tests
"""Blind review round 1 of the web cascade (2026-11-17, `review-web-cascade-1117.md` in the
reports): one test per finding that shows it, fixed. Google is never called; all words invented."""

from __future__ import annotations

import json

import pytest
from django.db import DatabaseError, connections

from agents.runtime.model import FakeModel, ModelFailure, call, say
from assistant import cascade, speech, turn_slots, views
from assistant.speech import GeminiSpeech, SpeechFailure
from devicelogs import web_conversation
from legion.client_ip import client_ip
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    PCM,
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

pytestmark = pytest.mark.django_db


# ---- finding 1: one turn per caller, a small engine-wide cap on demo turns ---------------------


@pytest.fixture
def other_session():
    """A second database session, as another worker or instance would hold."""
    conn = connections.create_connection("default")
    yield conn
    conn.close()


def _hold(conn, ns, key):
    with conn.cursor() as cur:
        cur.execute("SELECT pg_try_advisory_lock(%s, %s)", [ns, key])
        return cur.fetchone()[0]


def test_a_second_turn_from_the_same_visitor_is_refused_while_one_runs(
    visitor, visitor_user, engine, other_session
):
    assert _hold(other_session, turn_slots._NS_USER, turn_slots._key(f"user:{visitor_user.pk}"))
    response = _post(visitor, text="Hi.")
    assert response.status_code == 409
    assert response.data["detail"] == turn_slots.BUSY_SELF
    assert engine.model.requests == []


def test_demo_turns_are_capped_engine_wide_and_members_are_not(
    visitor, member, engine, other_session, monkeypatch
):
    monkeypatch.setenv(turn_slots.SLOTS_ENV, "2")
    for slot in range(2):
        assert _hold(other_session, turn_slots._NS_DEMO, slot)
    response = _post(visitor, text="Hi.")
    assert response.status_code == 429
    assert response.data["detail"] == turn_slots.BUSY_DEMO
    engine.model = FakeModel([say("Hello.")])
    assert _post(member, text="Hi.").status_code == 200


def test_the_slot_is_released_when_the_stream_ends(visitor, engine, other_session, visitor_user):
    engine.model = FakeModel([say("Hello.")])
    _events(_post(visitor, text="Hi."))
    # Another session can take this visitor's lock now: the turn gave it back.
    assert _hold(other_session, turn_slots._NS_USER, turn_slots._key(f"user:{visitor_user.pk}"))


def test_the_speech_drain_is_bounded_by_the_turns_wall_clock(monkeypatch):
    class Slow:
        def speak(self, text, voice, spoken):
            import time

            time.sleep(1.0)
            yield b"\x01\x00"

    speaker = cascade.Speaker(Slow(), "Puck", enabled=True)
    speaker.deadline = speaker.clock() + 0.2
    speaker.add("A sentence that will not be heard in time.")
    events = list(speaker.drain())
    speaker.close()
    assert [e.type for e in events] == ["speech_failed"]


@pytest.mark.parametrize(
    ("meta", "ident"),
    [
        # A made-up first entry never becomes the key: the hop the front end recorded does.
        (
            {"HTTP_X_FORWARDED_FOR": "1.2.3.4, 203.0.113.9", "REMOTE_ADDR": "10.0.0.1"},
            "203.0.113.9",
        ),
        (
            {"HTTP_X_FORWARDED_FOR": "9.9.9.9, 203.0.113.9", "REMOTE_ADDR": "10.0.0.1"},
            "203.0.113.9",
        ),
        # Through Cloudflare: its own header, trusted only from its edge.
        (
            {
                "HTTP_X_FORWARDED_FOR": "5.5.5.5, 172.70.1.1",
                "HTTP_CF_CONNECTING_IP": "198.51.100.7",
            },
            "198.51.100.7",
        ),
        (
            {"HTTP_X_FORWARDED_FOR": "203.0.113.9", "HTTP_CF_CONNECTING_IP": "198.51.100.7"},
            "203.0.113.9",
        ),
        ({"REMOTE_ADDR": "192.0.2.4"}, "192.0.2.4"),
    ],
)
def test_the_throttle_address_cannot_be_chosen_by_the_caller(rf, meta, ident):
    request = rf.get("/", **meta)
    assert client_ip(request) == ident


def test_the_demo_enter_and_turn_throttles_use_that_address():
    from demo.views import DemoEnterThrottle, DemoFunnelThrottle

    for throttle in (DemoEnterThrottle, DemoFunnelThrottle, views.AssistantTurnIpThrottle):
        assert throttle.get_ident is not None
        assert "client_ip" in throttle.get_ident.__code__.co_names


# ---- findings 2 and 3: the closing line says what happened ---------------------------------------


def test_an_error_after_a_write_says_the_write_went_through(member, engine, monkeypatch):
    from engine_mcp import cards

    def broken(card, recruiter):
        raise RuntimeError("card drawing failed")

    monkeypatch.setattr(cards, "for_viewer", broken)
    engine.model = FakeModel([call("create_checklist", {"name": "Hardware"}), say("Made it.")])
    events = _events(_post(member, text="Make a hardware list."))
    error = _of(events, "error")[0]
    assert "went through" in str(error)
    assert "Nothing was changed" not in str(error)


def test_a_failed_audit_row_does_not_fail_a_write(member, engine, monkeypatch):
    create = cascade.AssistantCall.objects.create

    def broken(*a, **k):
        # Only the tool door's audit row fails; the turn's own session row still writes.
        if k.get("kind") == cascade.AssistantCall.TOOL:
            raise DatabaseError("audit table locked")
        return create(*a, **k)

    monkeypatch.setattr(cascade.AssistantCall.objects, "create", broken)
    engine.model = FakeModel([call("create_checklist", {"name": "Hardware"}), say("Made it.")])
    events = _events(_post(member, text="Make a hardware list."))
    assert _of(events, "tool")[0]["ok"] is True
    assert [s["text"] for s in _of(events, "sentence")] == ["Made it."]


def test_a_claim_said_before_its_own_call_is_said_once_the_write_went_through(member, engine):
    engine.model = FakeModel(
        [
            say("I've added the hardware list for you.")[:2]
            + call("create_checklist", {"name": "Hardware"}),
            say("Anything else?"),
        ]
    )
    events = _events(_post(member, text="Make a hardware list."))
    said = [s["text"] for s in _of(events, "sentence")]
    assert "I've added the hardware list for you." in said
    assert cascade.CHANGE_NOT_MADE not in said


def test_a_write_then_a_refused_one_after_a_web_read_says_the_first_went_through(member, engine):
    engine.model = FakeModel(
        [
            call("create_checklist", {"name": "Fair"}),
            call("web_search", {"query": "fair date"}),
            call("add_event", {"fields": {"title": "Fair"}}),
            say("The fair is on Saturday."),
        ]
    )
    events = _events(_post(member, text="Make a fair list, look it up and add it."))
    said = [s["text"] for s in _of(events, "sentence")]
    assert said[-1] == cascade.READ_THROUGH_WAIT_AFTER_WRITE
    assert cascade.READ_THROUGH_WAIT not in said


# ---- findings 4 and 10 are in test_assistant_cascade.py; the reviewer's own example: -------------


def test_a_cant_find_a_list_sentence_after_a_web_read_is_kept(member, engine):
    line = "I couldn't find a list of today's scores."
    engine.model = FakeModel([call("web_search", {"query": "scores"}), say(line)])
    events = _events(_post(member, text="What were today's scores?"))
    assert [s["text"] for s in _of(events, "sentence")] == [line]


# ---- finding 5: a change the page never saw is in the next turn's history -----------------------


def test_a_write_the_page_never_saw_is_in_the_next_turns_history(member, engine):
    engine.model = FakeModel([call("create_checklist", {"name": "Hardware"}), say("Made it.")])
    response = _post(member, text="Make a hardware list.")
    stream = iter(response.streaming_content)
    first = next(stream)
    conversation = json.loads(first.decode().split("data: ")[1])["conversation_id"]
    seen = b""
    while b"event: tool" not in seen:
        seen += next(stream)
    response._iterator.close()  # the write ran; the page hung up before the answer
    engine.model = FakeModel([say("Already done.")])
    _events(_post(member, text="Make a hardware list.", conversation_id=conversation))
    assert "(Done: create_checklist.)" in str(engine.model.requests[0].contents)


# ---- finding 6: billed rounds are counted; speech fits its reserve ------------------------------


def test_every_started_model_call_counts():
    counted = cascade.CountingModel(FakeModel([say("a"), say("b")]))
    list(counted.stream(None))
    list(counted.stream(None))
    assert counted.calls == 2


def test_speech_requests_per_turn_are_capped():
    from tests.test_assistant_cascade import FakeSpeech

    speech_ = FakeSpeech()
    speaker = cascade.Speaker(speech_, "Puck", enabled=True)
    speaker.grouping = "sentence"
    for i in range(cascade.MAX_TTS_REQUESTS + 4):
        speaker.add(f"Sentence {i}.")
    list(speaker.drain())
    speaker.close()
    assert len(speech_.spoken) == cascade.MAX_TTS_REQUESTS
    assert speaker.skipped == 4


def test_a_filler_line_whose_speech_failed_is_not_asked_for_every_turn():
    class Failing:
        calls = 0

        def speak(self, text, voice, spoken):
            Failing.calls += 1
            raise SpeechFailure("tts_failed")
            yield b""

    audio = cascade.FillerAudio(spawn=lambda work: work())
    audio.warm(Failing(), "Puck", ["Checking the calendar."])
    audio.warm(Failing(), "Puck", ["Checking the calendar."])
    assert Failing.calls == 1


# ---- finding 7: the member greeting goes through the answers' checks ----------------------------


def test_a_greeting_figure_must_be_in_its_facts():
    facts = "Net cash: $2,333.61 as of 9am."
    assert cascade.greeting_is_honest("Morning. Net cash is $2,333.61.", facts)
    assert cascade.greeting_is_honest("Morning. Net cash is about $2,330.", facts)
    assert not cascade.greeting_is_honest("Morning. Net cash is $3,100.", facts)
    assert not cascade.greeting_is_honest("Morning. I've added your dentist visit.", facts)


# ---- finding 8: speech-to-text that cannot be reached -------------------------------------------


def test_stt_network_failure_is_retried_once_then_a_spoken_miss(member, engine):
    class Flaky:
        def __init__(self):
            self.calls = 0

        def lines(self, url, headers, data, timeout):
            self.calls += 1
            raise ModelFailure("Gemini could not be reached (TimeoutError)")

    transport = Flaky()
    real = GeminiSpeech(lambda: "key", stt_model="stt", transport=transport)
    from tests.test_no_speech import _pcm, _speechlike

    with pytest.raises(SpeechFailure):
        real.transcribe(_pcm(_speechlike()))
    assert transport.calls == 2

    def fail(pcm):
        raise SpeechFailure("stt_unreachable")

    engine.speech.transcribe = fail
    events = _events(_post(member, audio=PCM))
    assert _of(events, "error")
    assert [t for t, _v in engine.speech.spoken] == [cascade.MISSED_LINE]
    assert speech  # module under test


# ---- finding 9: a full record does not freeze the conversation ----------------------------------


def test_a_full_record_continues_in_a_new_one(member, engine, monkeypatch):
    engine.model = FakeModel([say("Hello.")])
    first = _of(_events(_post(member, text="Hi.")), "turn")[0]["conversation_id"]
    monkeypatch.setattr(views, "RECORD_ROLLOVER_BYTES", 0)
    engine.model = FakeModel([say("Still here.")])
    second = _of(_events(_post(member, text="Hi again.", conversation_id=first)), "turn")[0]
    assert second["conversation_id"] != first
    assert "Still here." in _record(second["conversation_id"])
    assert web_conversation  # module under test
