"""Streaming speech-to-text for the agents door (Kevin, 2026-11-20: "a streaming transcription
model"; behind `LEGION_AGENTS_STREAM_STT`, off unless `on`).

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
  signal (its own pause detection, mid-sentence) ends nothing.
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
# Prices per million tokens (official pricing page, 2026-11-20). ESTIMATES like every cost here.
PRICE_IN = 3.50
PRICE_OUT = 21.00
# When the server sends no usage block: about 25 audio tokens a second, and text at about four
# characters a token. Estimates.
AUDIO_TOKENS_PER_S = 25
CHARS_PER_TOKEN = 4
SETUP_TIMEOUT_S = 5.0
# How long after the end signal the final transcript may take before the batch path takes over
# (the spike measured 266 ms median, 307 ms worst).
FINAL_TIMEOUT_S = 2.5
SEND_BYTES = 3200  # 100 ms of 16 kHz PCM16


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
        segments until the turn completes. Anything that ended before this call counts for
        nothing on its own: only what completes after the end signal is the transcript."""
        if self._socket is None:
            raise StreamFailure("not_open")
        started = self.clock()
        try:
            if self._pending:
                self._send_audio(self._pending)
                self._pending = b""
            self._socket.send({"realtimeInput": {"audioStreamEnd": True}})
            until = started + timeout
            while True:
                left = until - self.clock()
                if left <= 0:
                    raise StreamFailure("final_timeout")
                message = self._socket.recv(left)
                if message is None:
                    raise StreamFailure("final_timeout")
                if self._read(message):
                    break
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
        self.sent_bytes += len(pcm)
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

    def _read(self, message: dict) -> bool:
        """Takes in one server message; True when it completes the turn AFTER the end signal is
        sent (the caller only asks after it). Raises on a server error."""
        if isinstance(message.get("error"), dict):
            raise StreamFailure(f"server_error_{message['error'].get('code', '?')}")
        if isinstance(message.get("usageMetadata"), dict):
            self._usage = message["usageMetadata"]
        content = message.get("serverContent") or {}
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

        self._session, self._ws = asyncio.run_coroutine_threadsafe(connect(), self._loop).result(
            SETUP_TIMEOUT_S
        )
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
            self._loop.call_soon_threadsafe(self._loop.stop)
