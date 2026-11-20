"""The agents door's streamed utterance (`POST /api/agents/turn/stream`, DARK: behind
`LEGION_AGENTS_STREAM_STT` on top of `LEGION_AGENTS_TURN`). The client streams the audio to the
engine while the member speaks, and a streaming transcription model hears it as it comes.

## The transport: one long request per utterance

The request body is the utterance, sent as it is spoken (HTTP/1.1 chunked), in frames:
`<type: 1 byte><length: 4 bytes, big-endian><payload>`.

- `M` metadata, first, JSON: `conversation_id`, `client`, `contract_version`, `probe`.
- `A` audio: 16 kHz PCM16 mono samples, any size up to `MAX_FRAME_BYTES`.
- `E` the client's end of turn (its own end-of-turn rule fired), JSON `{"bytes": <audio total>}`.
- `T` (probe only) the client's clock, JSON `{"client_ms": n}`, before the audio frame it stamps.

The response (Server-Sent Events, the agents door's own schema, `agents/voice/pipeline.py`) starts
only after the `E` frame: nothing is shown or said before the final transcript.

Why one request: the transcription session is a socket held by one process, and two workers and
any number of Cloud Run instances serve the engine. One request is served by one worker thread
from its first byte to its last, so every frame of an utterance reaches the process that holds its
session, with no routing rule, no affinity and no bus. What that needs of the deploy (the
hand-back lists what is verified): the front end forwards the body as it arrives (Cloud Run with
HTTP/1.1 to the container answered an anonymous slow-fed POST in 58 to 134 ms while its body was
still uploading, so it forwards before the body ends; the `probe` mode below proves the rest after
a dark deploy), the request timeout (300 s) exceeds an utterance plus its answer, and the body
under the 32 MiB HTTP/1 cap (a 28 s utterance is 0.9 MB).

## The rules

- Every refusal that costs nothing comes first: the switches, authentication, the member's one
  open stream, the metadata, the member's allowance, the key. Only then is a session opened (or
  the probe reads on).
- A client that stops sending is refused (`stalled`) after `FIRST_FRAME_S` before the metadata
  frame or `IDLE_S` between later reads; the whole upload is bounded by `UPLOAD_WALL_MS`.
- The audio is kept in this request's memory (never stored) for the batch fallback and the gates.
- No `E` frame (the client hung up, or the connection broke): nothing runs.
- `E` names how many audio bytes were sent; fewer arrived means a gap: nothing runs, the client
  resends the whole clip to `POST /api/agents/turn` (`audio_gap`).
- The final transcript is the stream's, unless the session failed, stalled past
  `live_stt.FINAL_TIMEOUT_S` or disagreed with itself: then the batch path transcribes the held
  audio (with the hedge rule when its switch is on). `stt_fallback` says which.
- Whatever the stream heard, the clip still passes the speech module's own gates (too short, too
  quiet, no voice): a clip they call noise is `ignored: noise`, and the stream's words are dropped.
  Then the pipeline's checks run on the final words as on every turn (no words, the crisis check,
  the write gate in the turn).
- Partial transcripts are not used for anything (no warm-up, no draft round) in this slice.
"""

from __future__ import annotations

import json
import struct
import time
from collections.abc import Callable
from dataclasses import dataclass, field

from agents.voice import speech as speech_mod
from agents.voice.live_stt import LiveSocket, StreamFailure, TranscribeSession

HEADER = struct.Struct(">cI")
# The longest an utterance's upload may run (the 28 s utterance cap plus slack for a slow network),
# checked between frames.
UPLOAD_WALL_MS = 45_000
MAX_FRAME_BYTES = 64 * 1024
MAX_META_BYTES = 4 * 1024
# The most frames one body may carry, of every kind (review 1120, finding 2): 28 s of audio in
# 20 ms frames is 1,400, plus a stamp before each in the probe. Without a cap, 5-byte frames would
# make a 32 MiB body millions of objects.
MAX_FRAMES = 3_000
# A client that stops sending (review 1120, finding 4). Each read of the body waits at most this
# long for its next bytes, by a timeout on the connection's own socket (gunicorn hands it over as
# `environ["gunicorn.socket"]`): the metadata frame must start within `FIRST_FRAME_S` of the view
# starting, and every later read within `IDLE_S` (a client sends audio every 100 ms or so while he
# speaks). A stall is refused as `stalled`, so a silent client holds a thread for at most about
# 10 s, not the 300 s request timeout. The timeout is per read: a client dripping a byte at a time
# is bounded by the 45 s upload wall instead, and by the per-member lock to one thread at a time.
FIRST_FRAME_S = 5.0
IDLE_S = 10.0
# One open stream per member (review 1120, finding 4): a Postgres advisory lock in this namespace,
# keyed by the member, held from before the first read until the answer's stream closes. It holds
# across workers and instances (one database); a crashed process releases it with its connection.
STREAM_LOCK_NS = 0x5354  # "ST"
STREAM_BUSY = (
    "Nothing was answered: another spoken turn of yours is still streaming in or being answered. "
    "Wait for it to finish, then speak again."
)


def socket_for(key: str) -> LiveSocket:
    """The Live API socket a session uses. Tests replace it."""
    return LiveSocket(key)


class FrameError(Exception):
    """The body did not fit the frame format; `code` is the refusal's."""

    def __init__(self, code: str, detail: str):
        super().__init__(detail)
        self.code = code
        self.detail = detail


class FrameReader:
    """Frames off a raw `wsgi.input`, read as they arrive. `None` at the end of the body."""

    def __init__(self, stream, length: int | None = None, sock=None):
        # `length`: the declared Content-Length, when the client sent one (a chunked upload,
        # the normal case, has none and is read to its end). `sock`: the connection's socket,
        # when the server hands it over, for the read deadlines; `release()` restores it.
        self.stream = stream
        self.left = length
        self.buffer = b""
        self.eof = False
        self.broken = False
        self.stalled = False
        self.sock = sock
        self._restore = None
        if sock is not None:
            try:
                self._restore = (sock.gettimeout(),)
            except Exception:  # noqa: BLE001 - no socket to time, no deadline
                self.sock = None
        self.frames = 0

    def _fill(self, wanted: int) -> bool:
        # Asks for exactly what the next frame still needs: gunicorn's `Body.read(n)` waits until
        # it has `n` bytes or the body ends, so asking for more would hold a frame back behind
        # audio not yet spoken (and the end frame until the body closes).
        while len(self.buffer) < wanted and not self.eof:
            size = wanted - len(self.buffer)
            if self.left is not None:
                size = min(size, self.left)
            self._deadline()
            try:
                chunk = self.stream.read(size) if size and hasattr(self.stream, "read") else b""
            except TimeoutError:
                # The client stopped sending for longer than the deadline (`socket.timeout` is
                # `TimeoutError`).
                chunk = b""
                self.stalled = True
            except Exception:  # noqa: BLE001 - a dropped connection mid-body is a hang-up
                chunk = b""
                self.broken = True
            if not chunk:
                self.eof = True
            else:
                self.buffer += chunk
                if self.left is not None:
                    self.left -= len(chunk)
        return len(self.buffer) >= wanted

    def _deadline(self) -> None:
        if self.sock is not None:
            try:
                self.sock.settimeout(FIRST_FRAME_S if self.frames == 0 else IDLE_S)
            except Exception:  # noqa: BLE001 - a closed socket fails its read next
                pass

    def release(self) -> None:
        """Puts the socket's own timeout back (None: blocking), before the answer is written.
        Idempotent."""
        if self.sock is not None and self._restore is not None:
            try:
                self.sock.settimeout(self._restore[0])
            except Exception:  # noqa: BLE001
                pass
            self._restore = None

    def next(self) -> tuple[bytes, bytes] | None:
        if not self._fill(HEADER.size):
            if self.stalled:
                raise FrameError("stalled", "The client stopped sending: nothing was run.")
            if self.buffer:
                raise FrameError("hung_up", "The body ended inside a frame.")
            return None
        kind, length = HEADER.unpack(self.buffer[: HEADER.size])
        if kind not in (b"M", b"A", b"E", b"T"):
            raise FrameError("bad_request", f"Unknown frame type {kind!r}.")
        if length > MAX_FRAME_BYTES:
            raise FrameError("bad_request", f"A frame is at most {MAX_FRAME_BYTES} bytes.")
        if kind == b"A" and length == 0:
            raise FrameError("bad_request", "An audio frame carries at least one byte.")
        self.frames += 1
        if self.frames > MAX_FRAMES:
            raise FrameError("too_long", f"A body carries at most {MAX_FRAMES} frames.")
        if not self._fill(HEADER.size + length):
            if self.stalled:
                raise FrameError("stalled", "The client stopped sending: nothing was run.")
            raise FrameError("hung_up", "The body ended inside a frame.")
        payload = self.buffer[HEADER.size : HEADER.size + length]
        self.buffer = self.buffer[HEADER.size + length :]
        return kind, payload


def json_payload(payload: bytes, limit: int = MAX_META_BYTES) -> dict:
    if len(payload) > limit:
        raise FrameError("bad_request", "A metadata frame is too large.")
    try:
        data = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise FrameError("bad_request", "A metadata frame is not JSON.") from None
    if not isinstance(data, dict):
        raise FrameError("bad_request", "A metadata frame is not a JSON object.")
    return data


@dataclass
class Streamed:
    """What the stream gave one utterance, for the pipeline: the held audio, the final transcript
    (or None: the batch path transcribes `pcm`), the stream's usage, and its timing marks
    (offsets in ms from the request's arrival)."""

    pcm: bytes
    heard: object | None
    stream_prompt_tokens: int = 0
    stream_output_tokens: int = 0
    fallback: int = 0
    failure: str = ""
    marks: dict = field(default_factory=dict)


def take_utterance(
    reader: FrameReader,
    *,
    key: str,
    max_audio_bytes: int,
    arrived_at: float,
    clock: Callable[[], float] = time.monotonic,
) -> Streamed:
    """Reads the audio frames up to `E`, feeding a streaming session as they come, and returns
    the final transcript with the audio held. Raises `FrameError` (hung up, gap, too long, bad
    frame); the session is closed whatever happens."""

    def ms() -> int:
        return int((clock() - arrived_at) * 1000)

    marks: dict = {}
    session: TranscribeSession | None = TranscribeSession(key, socket_for, clock=clock)
    failure = ""
    try:
        session.open()
    except StreamFailure as exc:
        session, failure = None, str(exc)
    pcm = bytearray()
    try:
        return _read_and_finish(reader, session, failure, pcm, marks, max_audio_bytes, ms)
    except FrameError as exc:
        # Nothing runs, but audio already sent to the session was paid for.
        exc.spent = _estimate(session, bytes(pcm)) if session is not None else (0, 0)
        raise
    finally:
        if session is not None:
            session.close()


def _read_and_finish(reader, session, failure, pcm, marks, max_audio_bytes, ms) -> Streamed:
    try:
        while True:
            item = reader.next()
            if item is None:
                raise FrameError("hung_up", "No end frame came: nothing was run.")
            if ms() > UPLOAD_WALL_MS:
                raise FrameError("too_long", "The utterance took too long to arrive.")
            kind, payload = item
            if kind == b"A":
                if not pcm:
                    marks["audio_first_ms"] = ms()
                if len(pcm) + len(payload) > max_audio_bytes:
                    raise FrameError("too_long", "The utterance is over the length limit.")
                pcm += payload
                if session is not None:
                    try:
                        session.feed(payload)
                    except StreamFailure as exc:
                        session.close()
                        session, failure = None, str(exc)
            elif kind == b"E":
                marks["end_signal_ms"] = ms()
                total = json_payload(payload).get("bytes")
                if total != len(pcm):
                    raise FrameError(
                        "audio_gap",
                        "Not all the audio arrived. Send the whole clip to /api/agents/turn.",
                    )
                break
            else:
                raise FrameError("bad_request", "Only audio and the end frame follow the metadata.")
        pcm_bytes = bytes(pcm)
        prompt = output = 0
        heard = None
        if session is not None:
            try:
                result = session.finish()
                prompt, output = result.prompt_tokens, result.output_tokens
                heard = _gated_or_heard(pcm_bytes, result.text, result.final_s)
            except StreamFailure as exc:
                failure = str(exc)
                prompt, output = _estimate(session, pcm_bytes)
            finally:
                session = None
        if heard is not None:
            marks["stt_done_ms"] = ms()
        return Streamed(
            pcm_bytes,
            heard,
            prompt,
            output,
            fallback=int(heard is None),
            failure=failure,
            marks=marks,
        )
    finally:
        if session is not None:
            session.close()


def _estimate(session: TranscribeSession, pcm: bytes) -> tuple[int, int]:
    """A session that failed after audio was sent is still paid for: its audio at the estimate."""
    from agents.voice.live_stt import AUDIO_TOKENS_PER_S

    if not session.sent_bytes:
        return 0, 0
    return session.sent_bytes * AUDIO_TOKENS_PER_S // 32_000 + 1, 0


def _gated_or_heard(pcm: bytes, text: str, final_s: float):
    """The speech module's own gates on the whole clip, whatever the stream heard; then its
    no-speech rules on the words (`clean_transcript`)."""
    audio_ms = len(pcm) // 32
    if (
        len(pcm) < speech_mod.MIN_AUDIO_BYTES
        or speech_mod.loudest_100ms(pcm) < speech_mod.LOUDNESS_GATE_RMS
        or not speech_mod.voiced_speech(pcm)
    ):
        return speech_mod.Heard("", audio_ms, 0, gated=True)
    return speech_mod.Heard(speech_mod.clean_transcript(text), audio_ms, int(final_s * 1000))


def probe(
    reader: FrameReader,
    *,
    arrived_at: float,
    clock=time.monotonic,
    max_audio_bytes: int = speech_mod.MAX_AUDIO_BYTES,
) -> dict:
    """The diagnostic: when each audio frame arrived (ms after the request reached this view),
    beside the client's own stamp. No session, no model, no cost. Bounded like an utterance
    (review 1120, finding 2): the reader's frame cap, the utterance's audio cap and the upload
    wall, so the list it builds is at most `MAX_FRAMES` small entries."""
    frames, stamp, total = [], None, 0
    while True:
        item = reader.next()
        if item is None:
            break
        arrived_ms = int((clock() - arrived_at) * 1000)
        if arrived_ms > UPLOAD_WALL_MS:
            raise FrameError("too_long", "The probe took too long to arrive.")
        kind, payload = item
        if kind == b"T":
            stamp = json_payload(payload).get("client_ms")
        elif kind == b"A":
            total += len(payload)
            if total > max_audio_bytes:
                raise FrameError("too_long", "The probe is over the utterance length limit.")
            frames.append({"client_ms": stamp, "arrived_ms": arrived_ms, "bytes": len(payload)})
            stamp = None
        elif kind == b"E":
            break
    return {"frames": frames}
