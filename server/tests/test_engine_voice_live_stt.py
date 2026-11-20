"""The streaming transcription session (`agents/voice/live_stt.py`) against a fake socket: the
client's end signal ends the turn, a model pause before it does not, interim text is never a
transcript, and every fault is a `StreamFailure` the door turns into the batch path. No network."""

from __future__ import annotations

import pytest

from agents.voice import live_stt

PCM = b"\x10\x00" * 16_000  # one second


class FakeSocket:
    """Scripted server with one ordered inbox, as a real socket has: `before_end` messages are in
    it from the start, `after_end` ones join it behind whatever is still unread once the end
    signal (`activityEnd`, or the automatic mode's `audioStreamEnd`) is sent, and `arrive()` adds
    messages now (they landed after the last read). A message the session did not read before the
    end is still delivered after it, first.

    Two orderings are scripted with it: MANUAL mode, what the service does with automatic
    activity detection off (nothing completes before `activityEnd`; the final and the completion
    come after it, `manual_mode()`), and the AUTOMATIC mode's (a completion at a pause, possibly
    still queued at the end), which the finish() rule stays the backstop for."""

    def __init__(self, before_end=(), after_end=(), setup=({"setupComplete": {}},), fail_on=None):
        self.sent: list[dict] = []
        self.setup = list(setup)
        self.inbox = list(before_end)
        self.after_end = list(after_end)
        self.ended = False
        self.closed = False
        self.fail_on = fail_on

    def arrive(self, *messages) -> None:
        self.inbox.extend(messages)

    def send(self, message: dict) -> None:
        if self.fail_on and self.fail_on in message.get("realtimeInput", {}):
            raise ConnectionResetError("gone")
        self.sent.append(message)
        signal = message.get("realtimeInput", {})
        if signal.get("audioStreamEnd") or "activityEnd" in signal:
            self.ended = True
            self.inbox.extend(self.after_end)
            self.after_end = []

    def recv(self, timeout: float):
        if self.setup:
            return self.setup.pop(0)
        if self.inbox:
            return self.inbox.pop(0)
        return None

    def close(self) -> None:
        self.closed = True


def _final(text):
    return {"serverContent": {"inputTranscription": {"text": text}}}


def _interim(text):
    return {"serverContent": {"interimInputTranscription": {"text": text}}}


DONE = {"serverContent": {"turnComplete": True}}


def manual_mode(*words):
    """The manual-mode ordering measured on 66 real runs: no final and no completion before the
    end signal; after `activityEnd`, the final and `generationComplete` together."""
    return FakeSocket(
        before_end=[_interim(words[0])] if words else [],
        after_end=[_final("".join(words)), {"serverContent": {"generationComplete": True}}],
    )


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


def test_automatic_activity_detection_is_off_and_activity_starts_before_any_audio():
    socket = manual_mode("Add milk.")
    session = _session(socket)
    session.feed(PCM)
    session.finish()
    setup = socket.sent[0]["setup"]
    assert setup.get("realtimeInputConfig") == {"automaticActivityDetection": {"disabled": True}}
    assert socket.sent[1] == {"realtimeInput": {"activityStart": {}}}
    assert "audio" in socket.sent[2]["realtimeInput"]


def test_the_client_end_signal_is_activity_end_and_the_manual_final_is_the_transcript():
    socket = manual_mode("Pay the rent", " no, cancel that.")
    session = _session(socket)
    session.feed(PCM)
    assert session.finish().text == "Pay the rent no, cancel that."
    assert socket.sent[-1] == {"realtimeInput": {"activityEnd": {}}}
    assert not any("audioStreamEnd" in m.get("realtimeInput", {}) for m in socket.sent)


def test_audio_goes_in_100_ms_pieces_and_the_end_signal_last():
    socket = FakeSocket(after_end=[_final("Add milk."), DONE])
    session = _session(socket)
    session.feed(PCM[:5000])
    session.feed(PCM[5000:])
    result = session.finish()
    audio = [m for m in socket.sent if "audio" in m.get("realtimeInput", {})]
    assert len(audio) == 10 and session.sent_bytes == len(PCM)
    assert socket.sent[-1] == {"realtimeInput": {"activityEnd": {}}}
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


def test_a_completion_still_unread_when_the_end_is_sent_does_not_cut_the_tail():
    # Review 1120 finding 3: the model's pause-completion landed after the last feed's read, so
    # it is still queued when the end signal goes out. It answers the pause, not the end.
    socket = FakeSocket(after_end=[_final(" no, cancel that."), DONE])
    session = _session(socket)
    session.feed(PCM)
    socket.arrive(_final("Pay the rent"), DONE)
    assert session.finish().text == "Pay the rent no, cancel that."


def test_a_completion_on_the_wire_when_the_end_is_sent_does_not_cut_the_tail():
    # The same completion still travelling: it is the first thing read after the end signal,
    # ahead of the tail's final words and the completion that answers the end.
    socket = FakeSocket(
        before_end=[_final("Pay the rent")],
        after_end=[DONE, _final(" no, cancel that."), DONE],
    )
    session = _session(socket)
    session.feed(PCM)
    assert session.finish().text == "Pay the rent no, cancel that."


def test_a_completion_with_no_final_words_after_the_end_goes_to_the_batch_path():
    # Words were heard, but nothing final came after the end signal: no sign that the completion
    # answers the end, so the session is a failure and the batch path transcribes the clip.
    socket = FakeSocket(before_end=[_final("Add milk.")], after_end=[DONE])
    session = _session(socket)
    session.feed(PCM)
    with pytest.raises(live_stt.StreamFailure, match="no_final_after_end"):
        session.finish()


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


def test_a_completion_with_no_words_at_all_goes_to_the_batch_path():
    # No final words after the end signal, so nothing shows the completion answers it (it may be
    # a pause's, with the words still coming): the batch path decides, and its own gates keep
    # silence from costing a request.
    socket = FakeSocket(after_end=[DONE])
    session = _session(socket)
    session.feed(PCM)
    with pytest.raises(live_stt.StreamFailure, match="no_final_after_end"):
        session.finish()


@pytest.mark.parametrize(
    ("socket", "token"),
    [
        (FakeSocket(setup=[]), "setup_timeout"),
        (FakeSocket(after_end=[]), "final_timeout"),
        (FakeSocket(after_end=[{"error": {"code": 503}}]), "server_error_unavailable"),
        (FakeSocket(fail_on="audio"), "feed_network"),
    ],
    ids=["no_setup", "no_final", "server_error", "socket_dies"],
)
def test_every_fault_is_a_stream_failure(socket, token):
    with pytest.raises(live_stt.StreamFailure, match=token):
        session = live_stt.TranscribeSession("k", lambda key: socket)
        session.open()
        session.feed(PCM)
        session.finish(timeout=0.05)


@pytest.mark.parametrize(
    "blocked",
    [
        {"promptFeedback": {"blockReason": "SAFETY"}},
        {"serverContent": {"promptFeedback": {"blockReason": "OTHER"}}},
    ],
    ids=["top_level", "in_server_content"],
)
def test_a_blocked_transcript_is_the_batch_paths_stt_blocked(blocked):
    socket = FakeSocket(after_end=[blocked, _final("Hi."), DONE])
    session = _session(socket)
    session.feed(PCM)
    with pytest.raises(live_stt.StreamFailure, match="^stt_blocked$"):
        session.finish()


@pytest.mark.parametrize(
    ("code", "token"),
    [
        (429, "server_error_quota"),
        ("RESOURCE_EXHAUSTED", "server_error_quota"),
        (403, "server_error_auth"),
        (400, "server_error_bad_request"),
        (500, "server_error_unavailable"),
        (418, "server_error_other"),
        ("k-secret is not valid", "server_error_other"),
        (None, "server_error_other"),
    ],
)
def test_an_upstream_error_code_is_logged_only_as_one_of_a_few_categories(code, token):
    # Review 1120 finding 12: the failure token reaches a log line; the upstream's own code does
    # not, beyond a fixed category.
    socket = FakeSocket(after_end=[{"error": {"code": code}}])
    session = _session(socket)
    session.feed(PCM)
    with pytest.raises(live_stt.StreamFailure) as caught:
        session.finish()
    assert str(caught.value) == token


@pytest.mark.parametrize(
    ("error", "token"),
    [
        (TimeoutError(), "open_timeout"),
        (ConnectionRefusedError(), "open_network"),
        (ValueError("anything"), "open_error"),
    ],
)
def test_a_socket_fault_is_logged_only_as_one_of_a_few_categories(error, token):
    def socket_for(key):
        raise error

    session = live_stt.TranscribeSession("k", socket_for)
    with pytest.raises(live_stt.StreamFailure) as caught:
        session.open()
    assert str(caught.value) == token


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


def test_the_streaming_modules_quote_no_one():
    # Public repo: the design is said in plain words, not as anyone's quoted words.
    from pathlib import Path

    from agents.voice import streaming

    for module in (live_stt, streaming):
        source = Path(module.__file__).read_text(encoding="utf-8")
        assert "Kevin" not in source, module.__name__
