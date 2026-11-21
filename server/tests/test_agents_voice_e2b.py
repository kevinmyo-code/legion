"""engine-brain slice E2b (dark): the phone client's gaps G1-G6.

- G1 the hold: a turn opened with `go: false` thinks and reads at once but acts only after the
  phone's `POST .../go`; `.../take` keeps the turn on the phone with nothing acted or recorded as
  said; silence is a cancel.
- G2 `speak: false`: no speech requested, no `audio` events, `done` at once.
- G3 `client_facts`: language, carry, prior line, test mode, the device's UTC offset, and what
  he heard of an earlier answer before he cut in.
- The brain enforced at the turn doors; `working` with a kind; `write_fact` on a write.

Google is never called (`FakeModel`, `FakeSpeech`). Every word, list and device is invented.
"""

# ruff: noqa: F811 - fixtures are imported from the voice and relay tests

from __future__ import annotations

import json

import pytest
from django.core.cache import cache

from agents.legion import hold
from agents.models import AgentConversation, AgentToolCall, AgentTurn
from agents.runtime import model as m
from tests.test_agents_relay_api import (  # noqa: F401
    TOOLS,
    attach,
    device_client,
    events_of,
    limb,
    manifest,
    of,
    post_result,
)
from tests.test_engine_voice_turn import engine  # noqa: F401

pytestmark = pytest.mark.django_db

WRITE = ("create_checklist", {"name": "Invented errands"})


def turn(client, text="make me an errands list", **fields):
    body = {"transcript": text, **fields}
    if "client_facts" in body:
        body["client_facts"] = json.dumps(body["client_facts"])
    return client.post("/api/agents/turn", body, format="multipart")


def _wrote() -> int:
    return AgentToolCall.objects.filter(tool="create_checklist", outcome="ok").count()


@pytest.fixture
def phone(monkeypatch):
    """Stands in for the phone at each poll of the hold: set `phone.decision` ("go", "take"),
    and the poll it lands on (`phone.at`, 1 = the first wait)."""

    class Phone:
        decision = None
        at = 1
        polls = 0

    def pause(seconds):
        Phone.polls += 1
        if Phone.decision and Phone.polls >= Phone.at:
            turn_row = AgentTurn.objects.filter(status="running").first()
            fields = {"pending_relay": {"go": Phone.decision}}
            if Phone.decision == hold.TAKE:
                fields["cancel_requested"] = True
            if turn_row is not None:
                AgentTurn.objects.filter(pk=turn_row.pk).update(**fields)

    monkeypatch.setattr(hold, "_pause", pause)
    monkeypatch.setattr(hold, "HOLD_TIMEOUT_S", 2.0)
    return Phone


# ---- G1: the hold -------------------------------------------------------------------------------


def test_a_held_read_streams_its_first_sentence_before_any_wait(auth_client, engine, phone):
    phone.decision = hold.GO
    engine.rounds = [m.call("read_list", {}), m.say("Nothing is on your lists.")]
    response = turn(auth_client, "what is on my lists", go=False)
    polls_at_first_sentence = None
    for chunk in response.streaming_content:
        if b"event: sentence" in chunk and polls_at_first_sentence is None:
            polls_at_first_sentence = phone.polls
    # Reads never wait: the first sentence went out before the hold polled once.
    assert polls_at_first_sentence == 0
    assert AgentTurn.objects.get().status == "done"


def test_a_write_the_model_wants_before_the_go_runs_once_after_it(auth_client, engine, phone):
    phone.decision = hold.GO
    engine.rounds = [m.call(*WRITE), m.say("Made it.")]
    events = events_of(turn(auth_client, go=False))
    assert phone.polls >= 1  # it waited
    made = [t for t in of(events, "tool") if t["name"] == "create_checklist"]
    assert [t["ok"] for t in made] == [True]
    assert made[0]["write_fact"]["tool"] == "create_checklist"
    assert made[0]["write_fact"]["title"] == "Invented errands"
    assert _wrote() == 1


def test_take_after_a_read_cancels_and_records_nothing_as_said(auth_client, engine, phone):
    phone.decision = hold.TAKE
    engine.rounds = [m.say("You have nothing on today.")]
    events_of(turn(auth_client, "and check my mail", go=False))
    row = AgentTurn.objects.get()
    assert row.status == "cancelled" and row.final_text == "" and row.history == []
    assert AgentConversation.objects.get().pending_confirm is None


def test_take_before_a_write_runs_nothing(auth_client, engine, phone):
    phone.decision = hold.TAKE
    engine.rounds = [m.call(*WRITE), m.say("Made it.")]
    events = events_of(turn(auth_client, "add milk and check my mail", go=False))
    assert _wrote() == 0
    assert of(events, "tool")[0]["guard"] == "cancelled"
    assert AgentTurn.objects.get().status == "cancelled"


def test_a_lost_go_is_a_cancel_and_nothing_acts(auth_client, engine, phone, monkeypatch):
    monkeypatch.setattr(hold, "HOLD_TIMEOUT_S", 0.0)
    engine.rounds = [m.call(*WRITE), m.say("Made it.")]
    events = events_of(turn(auth_client, go=False))
    assert _wrote() == 0
    assert of(events, "tool")[0]["guard"] == "cancelled"
    assert events[-1][1]["status"] == "cancelled"


def test_the_default_is_go_and_never_waits(auth_client, engine, phone):
    engine.rounds = [m.call(*WRITE), m.say("Made it.")]
    events_of(turn(auth_client))
    assert phone.polls == 0 and _wrote() == 1


def test_no_relay_request_leaves_before_the_go(auth_client, limb, engine, phone, monkeypatch):
    monkeypatch.setattr(hold, "HOLD_TIMEOUT_S", 0.0)
    engine.rounds = [m.call("car_status", {}), m.say("I can't check now.")]
    events = events_of(turn(auth_client, "how is the car", go=False))
    assert not of(events, "tool_request")


def _running_turn(user) -> AgentTurn:
    conversation = AgentConversation.objects.create(
        household=user.household, owner_user=user, client="phone"
    )
    return AgentTurn.objects.create(
        household=user.household,
        owner_user=user,
        conversation=conversation,
        seq=1,
        transcript="invented",
    )


def test_go_and_take_routes(auth_client, engine, household_user, household_a):
    from household.models import HouseholdMember, User

    row = _running_turn(household_user)
    go = auth_client.post(f"/api/agents/turn/{row.pk}/go")
    assert go.status_code == 200 and go.data["decision"] == "go"
    again = auth_client.post(f"/api/agents/turn/{row.pk}/go")
    assert again.status_code == 200  # a duplicate go is idempotent
    taken = auth_client.post(f"/api/agents/turn/{row.pk}/take")
    assert taken.status_code == 409 and taken.data["code"] == "already_go"
    stranger = User.objects.create_user(email="other-e2b@example.invalid", first_name="Sam")
    HouseholdMember.objects.create(user=stranger, household=household_a)
    other = device_client(stranger).post(f"/api/agents/turn/{row.pk}/go")
    assert other.status_code == 404
    row2 = _running_turn(household_user)
    AgentTurn.objects.filter(pk=row2.pk).update(seq=2)
    assert auth_client.post(f"/api/agents/turn/{row2.pk}/take").status_code == 200
    row2.refresh_from_db()
    assert row2.cancel_requested
    assert auth_client.post(f"/api/agents/turn/{row2.pk}/go").data["code"] == "taken"


def test_the_go_routes_are_dark_and_recruiter_denied(auth_client, monkeypatch):
    from household.recruiter_routes import RECRUITER_DENIED

    monkeypatch.delenv("LEGION_AGENTS_TURN", raising=False)
    url = "/api/agents/turn/00000000-0000-0000-0000-000000000001/go"
    assert auth_client.post(url).status_code == 404
    assert "agents.relay_views.AgentTurnGoView" in RECRUITER_DENIED
    assert "agents.relay_views.AgentTurnTakeView" in RECRUITER_DENIED


def test_no_late_answer_starts_before_the_go(auth_client, engine, phone, monkeypatch):
    from agents.legion import late
    from agents.legion.flags import LATE_ANSWERS_ENV
    from agents.models import LateTask

    monkeypatch.setenv(LATE_ANSWERS_ENV, "on")
    late.saw_tick()
    monkeypatch.setattr(hold, "HOLD_TIMEOUT_S", 0.0)
    engine.rounds = [m.say("Sure.")]
    events_of(turn(auth_client, "look into the invented ferry times and tell me later", go=False))
    assert not LateTask.objects.exists()
    phone.decision = hold.GO
    monkeypatch.setattr(hold, "HOLD_TIMEOUT_S", 2.0)
    events_of(turn(auth_client, "look into the invented ferry times and tell me later", go=False))
    assert LateTask.objects.count() == 1


# ---- G2: speak false ----------------------------------------------------------------------------


def test_an_audio_turn_with_speak_false_makes_no_speech(auth_client, engine, monkeypatch):
    from django.core.files.uploadedfile import SimpleUploadedFile

    monkeypatch.setattr("agents.voice.speech.voiced_speech", lambda pcm: True)
    monkeypatch.setattr("agents.voice.speech.LOUDNESS_GATE_RMS", 0)
    from tests.test_engine_voice_turn import _wav

    engine.rounds = [m.say("Nothing is on today. Enjoy it.")]
    response = auth_client.post(
        "/api/agents/turn",
        {"audio": SimpleUploadedFile("u.wav", _wav(), "audio/wav"), "speak": "false"},
        format="multipart",
    )
    events = events_of(response)
    assert not of(events, "audio") and engine.speech.spoken == []
    assert [s["text"] for s in of(events, "sentence")] == ["Nothing is on today.", "Enjoy it."]
    assert events[-1][0] == "done"


def test_the_stream_route_takes_speak_false(auth_client, engine, monkeypatch):
    from agents.legion.flags import STREAM_STT_ENV
    from tests.test_engine_voice_live_stt import DONE, FakeSocket, _final
    from tests.test_engine_voice_stream import utterance

    monkeypatch.setenv(STREAM_STT_ENV, "on")
    socket = FakeSocket(after_end=[_final("What is on today?"), DONE])
    monkeypatch.setattr("agents.voice.streaming.socket_for", lambda key: socket)
    monkeypatch.setattr("agents.voice.speech.voiced_speech", lambda pcm: True)
    monkeypatch.setattr("agents.voice.speech.LOUDNESS_GATE_RMS", 0)
    cache.clear()
    engine.rounds = [m.say("Nothing is on today.")]
    body = utterance(speak=False)
    events = events_of(
        auth_client.post("/api/agents/turn/stream", body, content_type="application/octet-stream")
    )
    assert not of(events, "audio") and engine.speech.spoken == []
    assert of(events, "heard") and events[-1][0] == "done"


# ---- G3: client facts ---------------------------------------------------------------------------


def _contents(engine) -> str:
    return json.dumps(engine.models[-1].requests[0].contents)


def test_language_and_the_prior_line_reach_his_turn(auth_client, engine):
    engine.rounds = [m.say("Bien.")]
    facts = {"language": "Spanish", "prior_line": "How did the invented exam go?"}
    events_of(turn(auth_client, "muy bien", client_facts=facts))
    sent = _contents(engine)
    assert "This conversation is in Spanish: reply in Spanish." in sent
    assert "How did the invented exam go?" in sent


def test_a_carried_turn_refuses_writes(auth_client, engine):
    engine.rounds = [m.call(*WRITE), m.say("Ask me again.")]
    events = events_of(turn(auth_client, client_facts={"carry_tainted": True}))
    assert _wrote() == 0 and of(events, "tool")[0]["guard"] == "tainted"
    assert AgentTurn.objects.get().tainted


def test_test_mode_refuses_writes_and_reaches_the_engine_whatever_the_brain(
    auth_client, engine, limb
):
    from agents.models import Limb

    Limb.objects.update(manifest={**Limb.objects.get().manifest, "brain_asked": "phone"})
    assert turn(auth_client, "hello").status_code == 409
    engine.rounds = [m.call(*WRITE), m.call("set_timer", {"seconds": 5}), m.say("Test only.")]
    events = events_of(turn(auth_client, client_facts={"test_mode": True}))
    assert _wrote() == 0 and not of(events, "tool_request")
    assert [t["guard"] for t in of(events, "tool")] == ["not_allowed", "not_allowed"]


def test_the_device_offset_sets_today(auth_client, engine, monkeypatch):
    import datetime as dt

    from django.utils import timezone

    # 03:00 UTC on a Wednesday is still Tuesday at UTC-6.
    monkeypatch.setattr(timezone, "now", lambda: dt.datetime(2026, 3, 11, 3, 0, tzinfo=dt.UTC))
    engine.rounds = [m.say("It is Tuesday.")]
    events_of(turn(auth_client, "what day is it", client_facts={"utc_offset_minutes": -360}))
    assert "Today is Tuesday 2026-03-10" in _contents(engine)


def test_what_he_heard_before_cutting_in_is_what_the_next_turn_remembers(auth_client, engine):
    engine.rounds = [m.say("The first part. The second part he never heard.")]
    first = events_of(turn(auth_client, "tell me two things"))
    turn_id = first[0][1]["turn_id"]
    conversation = first[0][1]["conversation_id"]
    engine.rounds = [m.say("Go on.")]
    facts = {"heard_until": {"turn_id": turn_id, "chars": len("The first part.")}}
    events_of(turn(auth_client, "wait", conversation_id=conversation, client_facts=facts))
    sent = _contents(engine)
    assert "The first part." in sent and "never heard" not in sent
    assert "He cut in here" in sent


def test_client_facts_that_do_not_fit_are_refused(auth_client, engine):
    response = turn(auth_client, client_facts={"utc_offset_minutes": 9999})
    assert response.status_code == 400 and response.data["code"] == "bad_request"


# ---- the brain, working kinds -------------------------------------------------------------------


def test_a_device_whose_brain_is_phone_gets_409(auth_client, engine):
    attach(auth_client, [])
    from agents.models import Limb

    Limb.objects.update(manifest={**Limb.objects.get().manifest, "brain_asked": "phone"})
    response = turn(auth_client, "hello")
    assert response.status_code == 409 and response.data["code"] == "brain_phone"
    assert not AgentTurn.objects.exists()


def test_working_names_every_calls_kind(auth_client, limb, engine):
    engine.rounds = [m.call("read_list", {}), m.say("Empty.")]
    events = events_of(turn(auth_client, "what is on my lists"))
    assert {"tool": "read_list", "kind": "local"} in of(events, "working")
    _ = manifest  # the relay tests' manifest carries `brain_asked: engine`
    _ = TOOLS
    _ = post_result
