"""Streaming speech-to-text for the agents door: the words are transcribed while the audio is
still arriving (behind `LEGION_AGENTS_STREAM_STT`, off unless `on`).

`gemini-3.5-transcribe-live` over the Live API socket, on the engine's own key (the same key and
supplier as today). Price, from the official pricing page (aistudio.google.com/docs/pricing, read
2026-11-20): $3.50 per million audio tokens in, $21.00 per million text tokens out. The protocol,
from the official guide (aistudio.google.com/docs/live-api/live-transcribe):

- `setup` names the model and turns on `inputAudioTranscription`;
- `realtimeInput.audio` carries 16 kHz PCM16 mono, base64, about 100 ms at a time;
- `realtimeInput.audioStreamEnd` says the audio is over;
- the server sends `serverContent.interimInputTranscription` (speculative, may change) and
  `serverContent.inputTranscription` (final segments, appended in the default mode), then
  `turnComplete` / `generationComplete`.

The rules this module keeps, and the door's tests pin:
- **The client's end signal ends the turn**, not the model: `finish()` sends `audioStreamEnd` and
  takes the final segments that arrive after it. A `turnComplete` the model sends BEFORE the end
  signal (its own pause detection, mid-sentence) ends nothing, even when it is read after the end
  signal went out (`finish()` says the rule).
- **Interim text is never a transcript.** It is kept only to notice a session that "disagrees with
  itself" (interim words, then no final words), which the door treats as a failure.
- **Any failure is a `StreamFailure`**: the door then transcribes the audio it holds the batch way.

`LiveSocket` is the only part that touches the network: aiohttp's WebSocket client (already a
pinned dependency) on an event loop of its own thread, so the WSGI thread uses it as plain blocking
calls. Tests hand the session a fake socket with the same three methods.
"""

from __future__ import annotations

import asyncio
import base64
import json
import queue
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass, field

MODEL = "gemini-3.5-transcribe-live"
URL = (
    "wss://generativelanguage.googleapis.com/ws/"
    "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
)
# Its prices are `stage.PRICE_LIVE_STT_IN` / `_OUT`, where every speech price lives.
# When the server sends no usage block: about 25 audio tokens a second, and text at about four
# characters a token. Estimates.
AUDIO_TOKENS_PER_S = 25
CHARS_PER_TOKEN = 4
SETUP_TIMEOUT_S = 5.0
# How long after the end signal the final transcript may take before the batch path takes over
# (the spike measured 266 ms median, 307 ms worst).
FINAL_TIMEOUT_S = 2.5
SEND_BYTES = 3200  # 100 ms of 16 kHz PCM16
# The service refused to transcribe (a block reason): the batch path's own refusal, never a retry.
BLOCKED = "stt_blocked"


class StreamFailure(Exception):
    """The streaming session could not give a final transcript. `str()` is a short token."""


@dataclass
class StreamResult:
    text: str
    # Usage as the server reported it, or estimated (`estimated` True).
    prompt_tokens: int = 0
    output_tokens: int = 0
    estimated: bool = False
    # Seconds from `finish()` (the end signal) to the final transcript.
    final_s: float = 0.0


@dataclass
class TranscribeSession:
    """One utterance's streaming transcription. `socket_for(key)` opens a socket with `send(dict)`,
    `recv(timeout) -> dict | None` (None: nothing within `timeout`) and `close()`."""

    key: str
    socket_for: Callable[[str], object]
    clock: Callable[[], float] = time.monotonic
    sent_bytes: int = 0
    interim: str = ""
    _final: list = field(default_factory=list)
    _usage: dict = field(default_factory=dict)
    _socket: object = None
    _pending: bytes = b""

    def open(self) -> None:
        try:
            self._socket = self.socket_for(self.key)
            self._socket.send(
                {
                    "setup": {
                        "model": f"models/{MODEL}",
                        "generationConfig": {"responseModalities": ["TEXT"]},
                        "inputAudioTranscription": {},
                    }
                }
            )
            until = self.clock() + SETUP_TIMEOUT_S
            while True:
                message = self._socket.recv(max(until - self.clock(), 0.01))
                if message is None:
                    raise StreamFailure("setup_timeout")
                if "setupComplete" in message:
                    return
                self._read(message)
        except StreamFailure:
            self.close()
            raise
        except Exception as exc:  # noqa: BLE001 - any socket fault is a failure, said as a token
            self.close()
            raise StreamFailure(f"open_{type(exc).__name__}") from None

    def feed(self, pcm: bytes) -> None:
        """Sends audio in about 100 ms pieces, and reads what has arrived without waiting."""
        if self._socket is None:
            raise StreamFailure("not_open")
        self._pending += pcm
        try:
            while len(self._pending) >= SEND_BYTES:
                self._send_audio(self._pending[:SEND_BYTES])
                self._pending = self._pending[SEND_BYTES:]
            while (message := self._socket.recv(0)) is not None:
                self._read(message)
        except StreamFailure:
            raise
        except Exception as exc:  # noqa: BLE001
            raise StreamFailure(f"feed_{type(exc).__name__}") from None

    def finish(self, timeout: float = FINAL_TIMEOUT_S) -> StreamResult:
        """The client's end signal: the rest of the audio, `audioStreamEnd`, then the final
        segments until a completion that answers the end signal.

        The protocol ties no completion to the end signal, so this is a rule, and its doubtful
        case goes to the batch path, never to the transcript (review 1120, finding 3):
        - everything already in the inbox is read BEFORE the end signal goes out, and a completion
          read then counts for nothing (it answered a pause);
        - after the end signal, a completion counts only once at least one final segment has
          arrived after the end signal. A completion before any such final (one sent at a pause,
          still on the wire when the end went out) is passed over;
        - no counted completion within `timeout`: a `StreamFailure`, and the batch path
          transcribes the held audio.
        A completion and final words BOTH still on the wire when the end goes out remain
        indistinguishable from the answer; only the live check can say how often that happens."""
        if self._socket is None:
            raise StreamFailure("not_open")
        started = self.clock()
        completed_unanswered = False
        try:
            if self._pending:
                self._send_audio(self._pending)
                self._pending = b""
            while (message := self._socket.recv(0)) is not None:
                self._read(message)
            finals_before_end = len(self._final)
            self._socket.send({"realtimeInput": {"audioStreamEnd": True}})
            until = started + timeout
            while True:
                left = until - self.clock()
                message = self._socket.recv(left) if left > 0 else None
                if message is None:
                    if self.interim.strip() and not self._final:
                        # Interim words and no final words: the session disagrees with itself.
                        raise StreamFailure("no_final_after_interim")
                    if completed_unanswered:
                        raise StreamFailure("no_final_after_end")
                    raise StreamFailure("final_timeout")
                if self._read(message):
                    if len(self._final) > finals_before_end:
                        break
                    completed_unanswered = True
        except StreamFailure:
            raise
        except Exception as exc:  # noqa: BLE001
            raise StreamFailure(f"finish_{type(exc).__name__}") from None
        finally:
            self.close()
        text = "".join(self._final).strip()
        if not text and self.interim.strip():
            # Interim words and no final words: the session disagrees with itself.
            raise StreamFailure("no_final_after_interim")
        prompt = int(self._usage.get("promptTokenCount") or 0)
        output = int(
            self._usage.get("candidatesTokenCount") or self._usage.get("responseTokenCount") or 0
        )
        estimated = not (prompt or output)
        if estimated:
            prompt = self.sent_bytes * AUDIO_TOKENS_PER_S // 32_000 + 1
            output = len(text) // CHARS_PER_TOKEN + 1
        return StreamResult(text, prompt, output, estimated, self.clock() - started)

    def close(self) -> None:
        socket, self._socket = self._socket, None
        if socket is not None:
            try:
                socket.close()
            except Exception:  # noqa: BLE001 - closing a dead socket is not a failure
                pass

    def _send_audio(self, pcm: bytes) -> None:
        # Counted once sent: a piece whose send failed did not reach the service.
        self._socket.send(
            {
                "realtimeInput": {
                    "audio": {
                        "mimeType": "audio/pcm;rate=16000",
                        "data": base64.b64encode(pcm).decode("ascii"),
                    }
                }
            }
        )
        self.sent_bytes += len(pcm)

    def _read(self, message: dict) -> bool:
        """Takes in one server message; True when it completes the turn AFTER the end signal is
        sent (the caller only asks after it). Raises on a server error."""
        if isinstance(message.get("error"), dict):
            raise StreamFailure(f"server_error_{message['error'].get('code', '?')}")
        content = message.get("serverContent") or {}
        for feedback in (message.get("promptFeedback"), content.get("promptFeedback")):
            if isinstance(feedback, dict) and feedback.get("blockReason"):
                # The batch path's refusal (`speech.py`, `stt_blocked`): where the Live API puts
                # a block is not in its guide, so both places are read.
                raise StreamFailure(BLOCKED)
        if isinstance(message.get("usageMetadata"), dict):
            self._usage = message["usageMetadata"]
        interim = (content.get("interimInputTranscription") or {}).get("text")
        if interim:
            self.interim = interim
        final = (content.get("inputTranscription") or {}).get("text")
        if final:
            self._final.append(final)
        return bool(content.get("turnComplete") or content.get("generationComplete"))


class LiveSocket:
    """aiohttp's WebSocket client on its own event loop thread, as blocking calls. The key goes in
    the URL as the Live API requires; it is never logged (errors are reduced to type names)."""

    def __init__(self, key: str, url: str = URL):
        import aiohttp

        self._inbox: queue.Queue = queue.Queue()
        self._loop = asyncio.new_event_loop()
        self._thread = threading.Thread(target=self._loop.run_forever, daemon=True)
        self._thread.start()

        async def connect():
            session = aiohttp.ClientSession()
            try:
                ws = await session.ws_connect(f"{url}?key={key}", max_msg_size=0, heartbeat=20)
            except Exception:
                await session.close()
                raise
            return session, ws

        try:
            self._session, self._ws = asyncio.run_coroutine_threadsafe(
                connect(), self._loop
            ).result(SETUP_TIMEOUT_S)
        except BaseException:
            # No socket to close later: the loop and its thread stop here, or every failed
            # connect (an outage) would leave one behind.
            self._stop_loop()
            raise
        asyncio.run_coroutine_threadsafe(self._pump(), self._loop)

    async def _pump(self):
        try:
            async for message in self._ws:
                if message.data and isinstance(message.data, str | bytes):
                    raw = message.data
                    self._inbox.put(json.loads(raw if isinstance(raw, str) else raw.decode()))
        finally:
            self._inbox.put(None)

    def send(self, message: dict) -> None:
        asyncio.run_coroutine_threadsafe(self._ws.send_str(json.dumps(message)), self._loop).result(
            SETUP_TIMEOUT_S
        )

    def recv(self, timeout: float):
        try:
            message = self._inbox.get(timeout=timeout) if timeout > 0 else self._inbox.get_nowait()
        except queue.Empty:
            return None
        if message is None:
            raise StreamFailure("socket_closed")
        return message

    def close(self) -> None:
        async def shut():
            await self._ws.close()
            await self._session.close()

        try:
            asyncio.run_coroutine_threadsafe(shut(), self._loop).result(2)
        finally:
            self._stop_loop()

    def _stop_loop(self) -> None:
        self._loop.call_soon_threadsafe(self._loop.stop)
        self._thread.join(2)
        if not self._thread.is_alive():
            self._loop.close()
