"""`POST /api/agents/turn/stream` (dark, behind `LEGION_AGENTS_STREAM_STT` on top of
`LEGION_AGENTS_TURN`): one long request per utterance, its body the audio as it is spoken, framed;
a streaming transcription session open the whole time; the final transcript taken at the client's
end frame; the batch path on the held audio when the session fails. Nothing is run, shown or
spoken before the final transcript has passed every check the door applies today.

Google is never called: the session's socket and the batch speech client are fakes. Every clip and
word here is invented."""

# ruff: noqa: F811 - the `engine` fixture is imported from the turn tests and named in each test

from __future__ import annotations

import json
import struct

import pytest
from django.core.cache import cache

from agents.legion.flags import STREAM_STT_ENV
from agents.models import AgentTurn
from agents.voice import crisis, pipeline
from agents.voice.filler_audio import FillerAudio
from llm.models import LlmUsage
from tests.test_engine_voice_live_stt import DONE, FakeSocket, _final
from tests.test_engine_voice_turn import FakeSpeech, engine  # noqa: F401

pytestmark = pytest.mark.django_db

URL = "/api/agents/turn/stream"
SECOND = b"\x10\x00" * 16_000  # one second of a flat, quiet tone


def frame(kind: bytes, payload: bytes) -> bytes:
    return kind + struct.pack(">I", len(payload)) + payload


def meta(**fields) -> bytes:
    return frame(b"M", json.dumps(fields).encode())


def audio(pcm: bytes, piece: int = 3200) -> bytes:
    return b"".join(frame(b"A", pcm[i : i + piece]) for i in range(0, len(pcm), piece))


def end(total: int) -> bytes:
    return frame(b"E", json.dumps({"bytes": total}).encode())


def utterance(pcm: bytes = SECOND, **fields) -> bytes:
    # An absent `go` holds a streamed turn (E2b): these tests say go up front; the hold has its own
    # tests (`test_agents_voice_e2b.py`).
    fields.setdefault("go", True)
    return meta(**fields) + audio(pcm) + end(len(pcm))


def _events(response) -> list[tuple[str, dict]]:
    assert response.status_code == 200, (response.status_code, getattr(response, "data", None))
    body = b"".join(response.streaming_content).decode()
    out = []
    for block in body.strip().split("\n\n"):
        lines = dict(line.split(": ", 1) for line in block.splitlines())
        out.append((lines["event"], json.loads(lines["data"])))
    return out


@pytest.fixture
def stream(engine, monkeypatch):
    """The stream door switched on, its session's socket a fake: set `stream.socket` first."""

    class Stream:
        socket = FakeSocket(after_end=[_final("What is on the calendar today?"), DONE])
        opened: list = []

    def socket_for(key):
        Stream.opened.append(key)
        if isinstance(Stream.socket, Exception):
            raise Stream.socket
        return Stream.socket

    monkeypatch.setenv(STREAM_STT_ENV, "on")
    monkeypatch.setattr("agents.voice.streaming.socket_for", socket_for)
    monkeypatch.setattr(pipeline, "FILLER_AUDIO", FillerAudio(spawn=lambda work: None))
    # A clip loud and voiced enough to pass the gates (the gates are the speech module's own).
    monkeypatch.setattr("agents.voice.speech.voiced_speech", lambda pcm: True)
    monkeypatch.setattr("agents.voice.speech.LOUDNESS_GATE_RMS", 0)
    cache.clear()
    return Stream


def _post(client, body: bytes):
    return client.post(URL, body, content_type="application/octet-stream")


# ---- dark --------------------------------------------------------------------------------------


def test_the_stream_door_is_off_unless_its_own_switch_is_on(auth_client, stream, monkeypatch):
    monkeypatch.delenv(STREAM_STT_ENV, raising=False)
    response = _post(auth_client, utterance())
    assert response.status_code == 404
    assert stream.opened == [] and not AgentTurn.objects.exists()


def test_the_stream_switch_alone_does_not_open_the_agents_door(auth_client, stream, monkeypatch):
    monkeypatch.delenv("LEGION_AGENTS_TURN", raising=False)
    response = _post(auth_client, utterance())
    assert response.status_code == 404
    assert stream.opened == [] and not AgentTurn.objects.exists()


# ---- a streamed utterance ----------------------------------------------------------------------


def test_the_final_transcript_comes_from_the_stream_at_the_end_frame(auth_client, stream, engine):
    events = _events(_post(auth_client, utterance()))
    kinds = [k for k, _ in events]
    assert kinds[:3] == ["waiting", "turn", "heard"] and kinds[-1] == "done"
    assert events[2][1] == {"text": "What is on the calendar today?"}
    assert engine.speech.transcribed == []  # the batch path was not used
    timing = events[-1][1]["timing"]
    assert timing["end_signal_ms"] <= timing["stt_done_ms"] <= timing["heard_ms"]
    assert timing["stt_fallback"] == 0
    sent = [m for m in stream.socket.sent if "audio" in m.get("realtimeInput", {})]
    assert len(sent) == 10


def test_a_failed_session_falls_back_to_the_batch_path_on_the_held_audio(
    auth_client, stream, engine
):
    stream.socket = ConnectionRefusedError("no session")
    events = _events(_post(auth_client, utterance()))
    assert engine.speech.transcribed == [len(SECOND)]
    assert ("heard", {"text": "What is on the calendar today?"}) in events
    assert events[-1][1]["timing"]["stt_fallback"] == 1


def test_a_stalled_session_falls_back_and_one_row_counts_both(
    auth_client, stream, engine, household_user
):
    stream.socket = FakeSocket(after_end=[])  # never answers after the end signal
    _events(_post(auth_client, utterance()))
    assert engine.speech.transcribed == [len(SECOND)]
    rows = LlmUsage.objects.filter(user=household_user, feature="agent_turn", model="stt")
    assert rows.count() == 1
    # The stream's estimate (one second of audio, about 26 tokens) plus the batch's 40.
    assert rows.get().prompt_tokens == 26 + 40


def test_a_transcript_the_service_blocked_is_refused_as_on_the_batch_path(
    auth_client, stream, engine
):
    # Review 1120 finding 9: the batch path refuses `stt_blocked`; so does the stream, and it
    # does not hand the clip to the batch path for a second try.
    from agents.legion.lines import STT_FAILED

    stream.socket = FakeSocket(
        after_end=[
            {"promptFeedback": {"blockReason": "SAFETY"}},
            _final("What is on the calendar today?"),
            DONE,
        ]
    )
    events = _events(_post(auth_client, utterance()))
    assert events[-1] == ("error", {"words": STT_FAILED})
    assert "heard" not in [k for k, _ in events]
    assert engine.speech.transcribed == [] and engine.models == []


# ---- nothing runs before the final transcript has passed every check ---------------------------


def test_a_hang_up_before_the_end_frame_runs_nothing(auth_client, stream, engine):
    response = _post(auth_client, meta() + audio(SECOND))
    assert response.status_code == 400 and response.data["code"] == "hung_up"
    assert engine.models == [] and engine.speech.transcribed == []
    assert not AgentTurn.objects.exists()
    assert stream.socket.closed


def test_audio_that_did_not_all_arrive_runs_nothing(auth_client, stream, engine):
    response = _post(auth_client, meta() + audio(SECOND) + end(len(SECOND) + 3200))
    assert response.status_code == 400 and response.data["code"] == "audio_gap"
    assert engine.models == [] and not AgentTurn.objects.exists()


def test_a_quiet_clip_is_noise_whatever_the_stream_heard(auth_client, stream, engine, monkeypatch):
    monkeypatch.undo()  # the real gates again
    monkeypatch.setenv(STREAM_STT_ENV, "on")
    monkeypatch.setenv("LEGION_AGENTS_TURN", "on")
    monkeypatch.setenv("LEGION_GEMINI_KEY", "test-key-not-real")
    monkeypatch.setattr("agents.voice.streaming.socket_for", lambda key: stream.socket)
    monkeypatch.setattr("agents.views.GeminiSpeech", lambda key, stt_model: engine.speech)
    monkeypatch.setattr("agents.views.GeminiClient", lambda key: engine.models.append(1))
    monkeypatch.setattr(pipeline, "FILLER_AUDIO", FillerAudio(spawn=lambda work: None))
    stream.socket = FakeSocket(after_end=[_final("Buy milk."), DONE])
    events = _events(_post(auth_client, utterance()))
    assert events[-1][1]["status"] == "nothing_heard" and events[-1][1]["ignored"] == "noise"
    assert engine.models == [] and not AgentTurn.objects.exists()


def test_distress_in_the_streamed_transcript_gets_the_crisis_line(auth_client, stream, engine):
    stream.socket = FakeSocket(after_end=[_final("I want to die"), DONE])
    events = _events(_post(auth_client, utterance()))
    assert [d["text"] for k, d in events if k == "crisis"] == [crisis.LINE]
    assert engine.models == [] and not AgentTurn.objects.exists()


def test_over_the_daily_allowance_no_session_is_opened(auth_client, stream, monkeypatch):
    monkeypatch.setattr("llm.cap.over_cap", lambda user: True)
    response = _post(auth_client, utterance())
    assert response.status_code == 429 and response.data["code"] == "member_cap"
    assert stream.opened == []


def test_an_utterance_over_28_seconds_is_refused(auth_client, stream, engine):
    long = b"\x10\x00" * (16_000 * 29)
    response = _post(auth_client, utterance(long))
    assert response.status_code == 413 and response.data["code"] == "too_long"
    assert engine.models == [] and stream.socket.closed


def test_the_first_frame_must_be_the_metadata(auth_client, stream):
    response = _post(auth_client, audio(SECOND) + end(len(SECOND)))
    assert response.status_code == 400 and response.data["code"] == "bad_request"
    assert stream.opened == []


# ---- the diagnostic: does audio arrive while it is being spoken? -------------------------------


def test_the_probe_reports_when_each_frame_arrived_and_costs_nothing(
    auth_client, stream, engine, household_user
):
    body = meta(probe=True)
    for i in range(5):
        body += frame(b"T", json.dumps({"client_ms": i * 100}).encode()) + frame(
            b"A", SECOND[:3200]
        )
    body += end(5 * 3200)
    response = _post(auth_client, body)
    assert response.status_code == 200
    frames = response.json()["frames"]
    assert [f["client_ms"] for f in frames] == [0, 100, 200, 300, 400]
    arrived = [f["arrived_ms"] for f in frames]
    assert arrived == sorted(arrived) and all(f["bytes"] == 3200 for f in frames)
    assert stream.opened == [] and engine.models == [] and engine.speech.transcribed == []
    assert not LlmUsage.objects.filter(user=household_user).exists()


def test_over_the_daily_allowance_the_probe_is_refused_before_it_reads_on(
    auth_client, stream, monkeypatch
):
    # Review 1120 finding 2: the allowance check comes before the probe reads any audio.
    monkeypatch.setattr("llm.cap.over_cap", lambda user: True)
    response = _post(auth_client, meta(probe=True) + audio(SECOND) + end(len(SECOND)))
    assert response.status_code == 429 and response.data["code"] == "member_cap"


# ---- review 1120 finding 4: one open stream per member ---------------------------------------


@pytest.fixture
def other_session():
    """A second database session, as another worker or instance would hold."""
    from django.db import connections

    conn = connections.create_connection("default")
    yield conn
    conn.close()


def _stream_lock_free(conn, user) -> bool:
    from agents.voice import streaming
    from assistant import turn_slots

    key = turn_slots._key(f"stream:{user.pk}")
    with conn.cursor() as cur:
        cur.execute("SELECT pg_try_advisory_lock(%s, %s)", [streaming.STREAM_LOCK_NS, key])
        free = cur.fetchone()[0]
        if free:
            cur.execute("SELECT pg_advisory_unlock(%s, %s)", [streaming.STREAM_LOCK_NS, key])
    return free


def test_a_second_stream_from_the_same_member_is_refused_while_one_is_open(
    auth_client, stream, household_user, other_session
):
    from agents.voice import streaming
    from assistant import turn_slots

    key = turn_slots._key(f"stream:{household_user.pk}")
    with other_session.cursor() as cur:
        cur.execute("SELECT pg_try_advisory_lock(%s, %s)", [streaming.STREAM_LOCK_NS, key])
        assert cur.fetchone()[0]
    response = _post(auth_client, utterance())
    assert response.status_code == 409 and response.data["code"] == "stream_busy"
    assert stream.opened == []


def test_the_member_stream_lock_is_held_while_the_answer_streams(
    auth_client, stream, household_user, other_session
):
    response = _post(auth_client, utterance())
    assert response.status_code == 200
    assert not _stream_lock_free(other_session, household_user)
    b"".join(response.streaming_content)
    response.close()
    assert _stream_lock_free(other_session, household_user)


@pytest.mark.parametrize(
    "body",
    [
        pytest.param(lambda: utterance(), id="answered"),
        pytest.param(lambda: meta(probe=True) + end(0), id="probe"),
        pytest.param(lambda: audio(SECOND), id="refused"),
        pytest.param(lambda: meta() + audio(SECOND), id="hung_up"),
    ],
)
def test_the_member_stream_lock_is_free_again_after_every_exit(
    auth_client, stream, household_user, other_session, body
):
    response = _post(auth_client, body())
    if response.status_code == 200 and hasattr(response, "streaming_content"):
        b"".join(response.streaming_content)
        response.close()
    assert _stream_lock_free(other_session, household_user)


# ---- the head unit (merge with main's head-unit sign-in) ---------------------------------------


def test_the_stream_route_takes_no_head_unit_token():
    from agents.views import AgentTurnStreamView
    from headunit_signin.authentication import HeadUnitTokenAuthentication
    from headunit_signin.scope import ROUTES

    assert HeadUnitTokenAuthentication not in AgentTurnStreamView.authentication_classes
    assert not any(name == "agents-turn-stream" for name, _method in ROUTES)


def test_a_head_unit_token_on_the_stream_route_is_refused(auth_client, stream):
    from tests.test_headunit_signin import _paired, unit_client

    _id, raw = _paired(auth_client)
    response = unit_client(raw).post(URL, utterance(), content_type="application/octet-stream")
    assert response.status_code in (401, 403)
    assert stream.opened == [] and not AgentTurn.objects.exists()
