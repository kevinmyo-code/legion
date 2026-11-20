"""The streaming transcription session (`agents/voice/live_stt.py`) against a fake socket: the
client's end signal ends the turn, a model pause before it does not, interim text is never a
transcript, and every fault is a `StreamFailure` the door turns into the batch path. No network."""

from __future__ import annotations

import pytest

from agents.voice import live_stt

PCM = b"\x10\x00" * 16_000  # one second


class FakeSocket:
    """Scripted server: `before_end` messages are delivered while audio is fed, `after_end` once
    `audioStreamEnd` arrives. `None` in a list means 'nothing yet' for one recv."""

    def __init__(self, before_end=(), after_end=(), setup=({"setupComplete": {}},), fail_on=None):
        self.sent: list[dict] = []
        self.setup = list(setup)
        self.before_end = list(before_end)
        self.after_end = list(after_end)
        self.ended = False
        self.closed = False
        self.fail_on = fail_on

    def send(self, message: dict) -> None:
        if self.fail_on and self.fail_on in message.get("realtimeInput", {}):
            raise ConnectionResetError("gone")
        self.sent.append(message)
        if message.get("realtimeInput", {}).get("audioStreamEnd"):
            self.ended = True

    def recv(self, timeout: float):
        if self.setup:
            return self.setup.pop(0)
        queue = self.after_end if self.ended else self.before_end
        if queue:
            return queue.pop(0)
        return None

    def close(self) -> None:
        self.closed = True


def _final(text):
    return {"serverContent": {"inputTranscription": {"text": text}}}


def _interim(text):
    return {"serverContent": {"interimInputTranscription": {"text": text}}}


DONE = {"serverContent": {"turnComplete": True}}


def _session(socket):
    session = live_stt.TranscribeSession("not-a-real-key", lambda key: socket)
    session.open()
    return session


def test_the_setup_names_the_model_and_asks_for_input_transcription():
    socket = FakeSocket(after_end=[_final("hi"), DONE])
    _session(socket).finish()
    setup = socket.sent[0]["setup"]
    assert setup["model"] == "models/gemini-3.5-transcribe-live"
    assert "inputAudioTranscription" in setup


def test_audio_goes_in_100_ms_pieces_and_the_end_signal_last():
    socket = FakeSocket(after_end=[_final("Add milk."), DONE])
    session = _session(socket)
    session.feed(PCM[:5000])
    session.feed(PCM[5000:])
    result = session.finish()
    audio = [m for m in socket.sent if "audio" in m.get("realtimeInput", {})]
    assert len(audio) == 10 and session.sent_bytes == len(PCM)
    assert socket.sent[-1] == {"realtimeInput": {"audioStreamEnd": True}}
    assert result.text == "Add milk." and socket.closed


def test_a_pause_the_model_hears_before_the_end_signal_ends_nothing():
    # The model's own turnComplete mid-sentence: its words are kept, the turn goes on, and only
    # what completes after the client's end signal finishes it.
    socket = FakeSocket(
        before_end=[_final("Remind me to"), DONE],
        after_end=[_final(" call the garage."), DONE],
    )
    session = _session(socket)
    session.feed(PCM)
    assert session.finish().text == "Remind me to call the garage."


def test_interim_text_is_never_the_transcript():
    socket = FakeSocket(
        before_end=[_interim("Remind me to cancel")], after_end=[_final("Remind me."), DONE]
    )
    session = _session(socket)
    session.feed(PCM)
    assert session.finish().text == "Remind me."


def test_interim_words_and_no_final_words_is_a_failure():
    socket = FakeSocket(before_end=[_interim("Add eggs")], after_end=[DONE])
    session = _session(socket)
    session.feed(PCM)
    with pytest.raises(live_stt.StreamFailure, match="no_final_after_interim"):
        session.finish()


def test_silence_with_no_words_at_all_is_an_empty_transcript_not_a_failure():
    socket = FakeSocket(after_end=[DONE])
    session = _session(socket)
    session.feed(PCM)
    assert session.finish().text == ""


@pytest.mark.parametrize(
    ("socket", "token"),
    [
        (FakeSocket(setup=[]), "setup_timeout"),
        (FakeSocket(after_end=[]), "final_timeout"),
        (FakeSocket(after_end=[{"error": {"code": 503}}]), "server_error_503"),
        (FakeSocket(fail_on="audio"), "feed_ConnectionResetError"),
    ],
    ids=["no_setup", "no_final", "server_error", "socket_dies"],
)
def test_every_fault_is_a_stream_failure(socket, token):
    with pytest.raises(live_stt.StreamFailure, match=token):
        session = live_stt.TranscribeSession("k", lambda key: socket)
        session.open()
        session.feed(PCM)
        session.finish(timeout=0.05)


def test_usage_reported_is_used_and_missing_usage_is_estimated():
    reported = FakeSocket(
        after_end=[
            _final("Hi."),
            {"usageMetadata": {"promptTokenCount": 40, "candidatesTokenCount": 3}},
            DONE,
        ]
    )
    session = _session(reported)
    session.feed(PCM)
    result = session.finish()
    assert (result.prompt_tokens, result.output_tokens, result.estimated) == (40, 3, False)
    session = _session(FakeSocket(after_end=[_final("Hi there."), DONE]))
    session.feed(PCM)
    result = session.finish()
    # One second of audio at about 25 tokens a second; nine characters at about four a token.
    assert result.estimated and result.prompt_tokens == 26 and result.output_tokens == 3


def test_the_key_never_appears_in_a_failure():
    socket = FakeSocket(after_end=[{"error": {"code": 400, "message": "bad key k-secret"}}])
    session = live_stt.TranscribeSession("k-secret", lambda key: socket)
    session.open()
    with pytest.raises(live_stt.StreamFailure) as caught:
        session.finish()
    assert "k-secret" not in str(caught.value)
