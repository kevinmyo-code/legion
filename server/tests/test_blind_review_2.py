# ruff: noqa: F811 - fixtures imported from the cascade tests
"""Blind review round 2, part 1 (turn path and safety), 2026-11-17
(`review-demo-voice-first-1117-turn.md`): one test per finding, written to fail before its fix.
Google is never called; every word is invented."""

from __future__ import annotations

import http.client

import pytest

from agents.runtime import model as runtime_model
from assistant.speech import GeminiSpeech, SpeechFailure
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
from tests.test_no_speech import _pcm, _speechlike

pytestmark = pytest.mark.django_db

# The real transport, taken at import: `conftest._no_agent_model` replaces it for every test.
REAL_LINES = runtime_model.UrllibTransport.lines


# ---- finding 6: a transcription cut off mid-response is retried, then a spoken miss -------------


class _CutOff:
    """What `urlopen` hands back when the connection drops while the body streams."""

    def __init__(self, exc):
        self.exc = exc

    def __iter__(self):
        yield b'data: {"candidates": []}\n'
        raise self.exc

    def close(self):
        pass


@pytest.mark.parametrize(
    "exc",
    [
        ConnectionResetError("reset by peer"),
        http.client.IncompleteRead(b"partial"),
        TimeoutError("read timed out"),  # a control: handled before the fix too
    ],
)
def test_a_transcription_cut_off_mid_response_is_retried_then_a_speech_failure(monkeypatch, exc):
    calls = []

    def urlopen(req, timeout):
        calls.append(req.full_url)
        return _CutOff(exc)

    monkeypatch.setattr(runtime_model.UrllibTransport, "lines", REAL_LINES)
    monkeypatch.setattr(runtime_model.urllib.request, "urlopen", urlopen)
    real = GeminiSpeech(lambda: "key", stt_model="stt")
    with pytest.raises(SpeechFailure):
        real.transcribe(_pcm(_speechlike()))
    assert len(calls) == 2  # tried once more, then said as a miss


# ---- finding 10: the robotic tap works outside a test transaction --------------------------------


@pytest.mark.django_db(transaction=True)
def test_the_robotic_tap_works_in_autocommit_as_in_production(visitor, visitor_user, engine):
    from agents.runtime.model import FakeModel, say
    from tests.test_demo_strip_tester_only import make_visit

    # The marker is the tester visit's only (`tests/test_demo_strip_tester_only.py`).
    make_visit(visitor_user, unmetered=True)
    engine.model = FakeModel([say("Hello.")])
    conversation = _of(_events(_post(visitor, text="Hi.")), "turn")[0]["conversation_id"]
    response = visitor.post(
        "/api/assistant/robotic", {"conversation_id": conversation, "turn": 0}, format="json"
    )
    assert response.status_code == 204
    assert "robotic turn=0 source=tap" in _record(conversation)


# ---- finding 5: remember refused after a web read is said, not passed over --------------------


def test_remember_refused_after_a_web_search_says_why(member, engine):
    from agents.runtime.model import FakeModel, call, say
    from assistant import cascade

    engine.model = FakeModel(
        [
            call("web_search", {"query": "oat milk"}),
            call("remember", {"text": "Oat milk is the best."}),
            say("Noted."),
        ]
    )
    events = _events(_post(member, text="Look up oat milk and remember I like it."))
    remember = [t for t in _of(events, "tool") if t["name"] == "remember"][0]
    assert remember["ok"] is False
    said = [s["text"] for s in _of(events, "sentence")]
    assert said[-1] == cascade.READ_THROUGH_WAIT


# ---- finding 4: a turn in which a change went through is never "not for me" ---------------------


def test_not_for_me_after_a_write_says_what_was_done(member, engine):
    from agents.runtime.model import FakeModel, Finish, PartEvent, ToolCallEvent, Usage, say
    from assistant import cascade
    from checklists.models import Checklist

    both = [
        PartEvent({"functionCall": {"name": "create_checklist", "args": {"name": "Hardware"}}}),
        ToolCallEvent("", "create_checklist", {"name": "Hardware"}),
        PartEvent({"functionCall": {"name": cascade.NOT_FOR_ME_TOOL, "args": {}}}),
        ToolCallEvent("", cascade.NOT_FOR_ME_TOOL, {}),
        Usage(100, 0, 5, 105),
        Finish("STOP"),
    ]
    engine.model = FakeModel([both, say("I made the hardware list.")])
    events = _events(_post(member, text="Make a hardware list."))
    done = _of(events, "done")[0]
    assert "ignored" not in done
    assert done["writes_ran"] is True
    assert [s["text"] for s in _of(events, "sentence")] == ["I made the hardware list."]
    assert Checklist.objects.filter(name="Hardware").exists()


def test_the_door_runs_no_write_after_not_for_me(household_user, rf):
    # The door itself refuses (the loop also stops at "not for me", so a turn alone cannot show
    # this guard; on 7d4664e5 the door ran the write).
    from agents.runtime.tools import ToolCall
    from assistant import cascade
    from checklists.models import Checklist

    request = rf.post("/api/assistant/turn")
    request.user = household_user
    door = cascade.WebDoor(request, None, 0)
    door.call(None, None, ToolCall("c1", cascade.NOT_FOR_ME_TOOL, {}), 8.0)
    door.call(None, None, ToolCall("c2", "create_checklist", {"name": "Side talk"}), 8.0)
    assert door.calls[-1].ok is False
    assert not Checklist.objects.filter(name="Side talk").exists()


# ---- finding 3: a failure between the slot and the stream gives both slot and budget back --------


def test_a_failure_before_the_stream_releases_the_slot_and_the_reservation(
    visitor, visitor_user, engine, monkeypatch
):
    from django.db import connections

    from assistant import turn_slots
    from demo.models import DemoBudgetDay
    from devicelogs import web_conversation

    def broken(*a, **k):
        raise RuntimeError("the record could not be opened")

    monkeypatch.setattr(web_conversation, "start", broken)
    with pytest.raises(RuntimeError):
        _post(visitor, text="Hi.")
    assert DemoBudgetDay.objects.get().reserved_cents == 0
    other = connections.create_connection("default")
    try:
        with other.cursor() as cur:
            key = turn_slots._key(f"user:{visitor_user.pk}")
            cur.execute("SELECT pg_try_advisory_lock(%s, %s)", [turn_slots._NS_USER, key])
            assert cur.fetchone()[0] is True
            for slot in range(turn_slots.demo_slots()):
                cur.execute("SELECT pg_try_advisory_lock(%s, %s)", [turn_slots._NS_DEMO, slot])
                assert cur.fetchone()[0] is True
    finally:
        other.close()


# ---- finding 1: one address holds at most one demo turn; the cap follows the thread count -------


def test_one_address_cannot_hold_every_demo_slot(visitor, engine, monkeypatch):
    from django.db import connections

    from assistant import turn_slots

    other = connections.create_connection("default")
    try:
        with other.cursor() as cur:
            # Another visit from the same address is mid-turn.
            key = turn_slots._key("ip:127.0.0.1")
            cur.execute("SELECT pg_try_advisory_lock(%s, %s)", [turn_slots._NS_ADDRESS, key])
            assert cur.fetchone()[0] is True
        response = _post(visitor, text="Hi.")
        assert response.status_code == 429
        assert response.data["detail"] == turn_slots.BUSY_ADDRESS
    finally:
        other.close()


def test_the_demo_cap_is_half_one_instances_threads(monkeypatch):
    from assistant import turn_slots

    monkeypatch.delenv(turn_slots.SLOTS_ENV, raising=False)
    monkeypatch.setenv("LEGION_GUNICORN_WORKERS", "2")
    monkeypatch.setenv("LEGION_GUNICORN_THREADS", "4")
    assert turn_slots.demo_slots() == 4
    monkeypatch.setenv("LEGION_GUNICORN_THREADS", "1")
    assert turn_slots.demo_slots() == 1
    monkeypatch.setenv(turn_slots.SLOTS_ENV, "3")
    assert turn_slots.demo_slots() == 3


# ---- finding 2: replaced by round 3 design change D1, `tests/test_tool_deadline.py` -------------


# ---- finding 7: a broken turn is said, not only shown (the tour half went with the tour) ------


def test_a_broken_spoken_turn_says_so_from_memory(member, engine, monkeypatch, household_user):
    import base64

    from agents.runtime.model import FakeModel, call, say
    from assistant import cascade, voice_engine
    from engine_mcp import cards

    class Distinct:
        """Speech whose audio is the line's own text, so a test can tell which line it is."""

        def speak(self, text, voice, spoken):
            yield text.encode()

    audio = cascade.FillerAudio(spawn=lambda work: work())
    audio.warm(Distinct(), voice_engine.voice_for(household_user))
    monkeypatch.setattr(cascade, "FILLER_AUDIO", audio)

    def broken(card, recruiter):
        raise RuntimeError("card drawing failed")

    monkeypatch.setattr(cards, "for_viewer", broken)
    engine.model = FakeModel([call("list_events", {}), say("Clear.")])
    events = _events(_post(member, audio=PCM))
    kinds = [k for k, _ in events]
    assert kinds[-2:] == ["audio", "error"]
    # The audio is the failure line itself, not an earlier clip (the acknowledgement).
    said = base64.b64decode(events[-2][1]["pcm"]).decode()
    assert said == cascade.FAILED_SPOKEN


# ---- finding 8: every paid call is counted, speech is reserved per request ----------------------


def test_a_cache_creation_and_a_resend_are_each_counted():
    from assistant import cascade

    class Inner:
        def stream(self, req):
            return iter(())

        def create_cache(self, *a, **k):
            return "cachedContents/x"

    counted = cascade.CountingModel(Inner())
    counted.create_cache("m", "s", [])
    list(counted.stream(None))
    list(counted.stream(None))  # the resend after a broken cache
    assert counted.calls == 3


def test_speech_is_reserved_per_request_and_given_back_when_unused(visitor, engine):
    from agents.runtime.model import FakeModel, say
    from demo import budget
    from demo.models import DemoBudgetDay

    engine.model = FakeModel([say("Hello there.")])
    _events(_post(visitor, text="Hi.", spoken="true"))
    day = DemoBudgetDay.objects.get()
    from assistant import cascade, fillers

    lines = len(set(fillers.all_lines()) | set(cascade.SPOKEN_FAILURES))
    requests = len(engine.speech.spoken)
    # One text call used; the speech share; the turn's speech requests and the lines set out to be
    # made are kept; the rest of the reserve (six requests, the cache call) went back.
    assert day.reserved_cents == 1 + 1 + budget.speech_requests_cents(requests + lines)


# ---- finding 9: the record rollover does not reset a demo conversation's time limit -------------


def test_a_rolled_over_record_keeps_the_conversations_time_window(visitor, engine, monkeypatch):
    from datetime import timedelta

    from agents.runtime.model import FakeModel, say
    from assistant import views
    from devicelogs.models import DeviceLog

    engine.model = FakeModel([say("Hello.")])
    first = _of(_events(_post(visitor, text="Hi.")), "turn")[0]["conversation_id"]
    row = DeviceLog.objects.get(batch_id=first)
    row.window_start = row.window_start - timedelta(minutes=3)
    row.save(update_fields=["window_start"])
    monkeypatch.setattr(views, "RECORD_ROLLOVER_BYTES", 0)
    engine.model = FakeModel([say("Still here.")])
    second = _of(_events(_post(visitor, text="Again.", conversation_id=first)), "turn")[0]
    rolled = DeviceLog.objects.get(batch_id=second["conversation_id"])
    assert rolled.batch_id != row.batch_id
    assert rolled.window_start == row.window_start


# ---- finding 12: an IPv6 caller counts as its /64 ----------------------------------------------


def test_an_ipv6_caller_is_keyed_on_its_slash_64(rf):
    from legion.client_ip import client_ip

    a = rf.get("/", HTTP_X_FORWARDED_FOR="2001:db8:1:2::10")
    b = rf.get("/", HTTP_X_FORWARDED_FOR="2001:db8:1:2:ffff::99")
    c = rf.get("/", HTTP_X_FORWARDED_FOR="2001:db8:1:3::10")
    assert client_ip(a) == client_ip(b) == "2001:db8:1:2::/64"
    assert client_ip(c) != client_ip(a)
    assert client_ip(rf.get("/", REMOTE_ADDR="192.0.2.4")) == "192.0.2.4"
