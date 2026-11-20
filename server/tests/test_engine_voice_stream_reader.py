"""The stream door's frame reader against a body that behaves like gunicorn's (`Body.read(n)` waits
until it has `n` bytes or the body ends), fed from another thread as a client would send it: each
frame must come back as soon as its own bytes are in, never held behind audio not yet sent. Also a
connection that drops mid-body, and the Live socket's thread when its connect fails. No network
beyond a refused local port, no database."""

from __future__ import annotations

import json
import threading
import time

import pytest

from agents.voice import live_stt, streaming
from tests.test_engine_voice_live_stt import FakeSocket
from tests.test_engine_voice_stream import audio, end, frame, meta

LONG = 5.0


class BlockingBody:
    """`read(n)` returns n bytes, waiting for them, or fewer only once the body has ended."""

    def __init__(self):
        self.data = b""
        self.closed = False
        self.cond = threading.Condition()
        self.fail = False

    def send(self, data: bytes) -> None:
        with self.cond:
            self.data += data
            self.cond.notify_all()

    def finish(self) -> None:
        with self.cond:
            self.closed = True
            self.cond.notify_all()

    def drop(self) -> None:
        with self.cond:
            self.fail = True
            self.cond.notify_all()

    def read(self, size: int) -> bytes:
        with self.cond:
            while len(self.data) < size and not self.closed and not self.fail:
                if not self.cond.wait(LONG):
                    raise AssertionError("the reader waited for bytes that were never sent")
            if self.fail:
                raise OSError("connection reset")
            out, self.data = self.data[:size], self.data[size:]
            return out


def test_each_frame_comes_back_as_soon_as_its_own_bytes_are_in():
    body = BlockingBody()
    reader = streaming.FrameReader(body)
    body.send(meta(client="phone"))
    assert reader.next()[0] == b"M"  # before any audio is sent
    body.send(audio(b"\x01\x00" * 1600))
    assert reader.next() == (b"A", b"\x01\x00" * 1600)  # before the next piece is sent
    body.send(end(3200))
    kind, payload = reader.next()  # before the body is closed
    assert kind == b"E" and json.loads(payload) == {"bytes": 3200}
    body.finish()
    assert reader.next() is None


def test_a_connection_that_drops_mid_body_is_a_hang_up_and_the_sent_audio_is_paid_for(monkeypatch):
    body = BlockingBody()
    socket = FakeSocket()
    monkeypatch.setattr(streaming, "socket_for", lambda key: socket)
    reader = streaming.FrameReader(body)
    body.send(meta())
    reader.next()
    body.send(audio(b"\x01\x00" * 16_000))

    def drop_soon():
        threading.Timer(0.05, body.drop).start()

    drop_soon()
    with pytest.raises(streaming.FrameError) as caught:
        streaming.take_utterance(
            reader, key="k", max_audio_bytes=10**7, arrived_at=time.monotonic()
        )
    assert caught.value.code == "hung_up"
    assert caught.value.spent[0] > 0
    assert socket.closed


def test_a_body_that_ends_inside_a_frame_is_a_hang_up():
    body = BlockingBody()
    reader = streaming.FrameReader(body)
    body.send(frame(b"A", b"\x01\x00" * 100)[:50])
    body.finish()
    with pytest.raises(streaming.FrameError) as caught:
        reader.next()
    assert caught.value.code == "hung_up"


def test_a_failed_connect_leaves_no_thread_behind():
    before = threading.active_count()
    with pytest.raises(Exception):  # noqa: B017 - any connect failure
        live_stt.LiveSocket("not-a-real-key", url="ws://127.0.0.1:9/never")
    assert threading.active_count() == before


# ---- review 1120 finding 2: the probe's limits ---------------------------------------------------


def _body(data: bytes):
    import io

    return io.BytesIO(data)


def test_a_zero_length_audio_frame_is_refused():
    reader = streaming.FrameReader(_body(meta() + frame(b"A", b"") + end(0)))
    reader.next()
    with pytest.raises(streaming.FrameError) as caught:
        reader.next()
    assert caught.value.code == "bad_request"


def test_a_body_of_endless_small_frames_is_cut_off_at_the_frame_cap():
    # Each stamp frame is 20 bytes; with no cap a 32 MiB body is 1.6 million of them.
    stamps = frame(b"T", b'{"client_ms":1}') * 20_000
    reader = streaming.FrameReader(_body(meta(probe=True) + stamps))
    reader.next()
    with pytest.raises(streaming.FrameError) as caught:
        streaming.probe(reader, arrived_at=time.monotonic())
    assert caught.value.code == "too_long"


def test_the_probe_stops_at_the_audio_byte_cap():
    # The utterance cap (28 s) plus one more 100 ms frame.
    from agents.voice.speech import MAX_AUDIO_BYTES

    pcm = b"\x10\x00" * (MAX_AUDIO_BYTES // 2 + 1_600)
    reader = streaming.FrameReader(_body(meta(probe=True) + audio(pcm) + end(len(pcm))))
    reader.next()
    with pytest.raises(streaming.FrameError) as caught:
        streaming.probe(reader, arrived_at=time.monotonic())
    assert caught.value.code == "too_long"


def test_the_probe_stops_at_the_upload_wall():
    now = [0.0]

    def clock():
        now[0] += 1.0  # each frame a second later
        return now[0]

    pcm = b"\x10\x00" * 1_600 * 60  # sixty 100 ms frames, one a second
    reader = streaming.FrameReader(_body(meta(probe=True) + audio(pcm) + end(len(pcm))))
    reader.next()
    with pytest.raises(streaming.FrameError) as caught:
        streaming.probe(reader, arrived_at=0.0, clock=clock)
    assert caught.value.code == "too_long"


# ---- review 1120 finding 4: a client that stops sending ------------------------------------------


class TimingSocket:
    """The connection's socket as gunicorn hands it over (`environ["gunicorn.socket"]`): the
    reader sets a timeout on it before each read and puts the old one back when done."""

    def __init__(self):
        self.timeouts: list = []
        self.current = None

    def gettimeout(self):
        return self.current

    def settimeout(self, value):
        self.timeouts.append(value)
        self.current = value


class StalledBody:
    """Sends `data`, then nothing: the next read waits out the socket's timeout."""

    def __init__(self, data: bytes):
        self.data = data

    def read(self, size: int) -> bytes:
        if not self.data:
            raise TimeoutError("timed out")
        out, self.data = self.data[:size], self.data[size:]
        return out


def test_the_first_frame_has_a_short_deadline_and_later_reads_an_idle_timeout():
    sock = TimingSocket()
    reader = streaming.FrameReader(_body(meta() + audio(b"\x10\x00" * 1_600)), sock=sock)
    reader.next()
    reader.next()
    assert sock.timeouts[:1] == [streaming.FIRST_FRAME_S]
    assert set(sock.timeouts[1:-1]) == {streaming.IDLE_S}
    assert streaming.FIRST_FRAME_S <= 5 and streaming.IDLE_S <= 10
    reader.release()
    assert sock.current is None  # gunicorn's own blocking socket again, for the answer


def test_a_client_that_stops_sending_is_refused_as_stalled_not_held():
    reader = streaming.FrameReader(StalledBody(meta()), sock=TimingSocket())
    reader.next()
    with pytest.raises(streaming.FrameError) as caught:
        reader.next()
    assert caught.value.code == "stalled"
