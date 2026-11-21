"""A socket that closes while a streamed line is speaking must stop the speech thread (review F8,
2026-11-20). The real Kyutai text-stream adapter (kyutai._TextStream: a producer thread that holds
the engine lock until its text ends) runs here on a fake engine: no GPU, no model weights.

Run from tools/:  python -m pytest laptop_voice/tests -q   (skipped where torch/moshi are not installed)
"""

from __future__ import annotations

import asyncio
import sys
import threading
import time
from pathlib import Path

import numpy as np
import pytest
from aiohttp.test_utils import TestServer

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

pytest.importorskip("torch")
pytest.importorskip("moshi")

from laptop_voice import engines as E  # noqa: E402
from laptop_voice import kyutai as K  # noqa: E402
from laptop_voice import server as S  # noqa: E402
from laptop_voice.client import VoiceClient  # noqa: E402

TOKEN = "test-token-0123456789abcdef"


class LockedStreamTts:
    """The engine side of Kyutai's streamed speech: the whole line runs under the engine lock, on the
    adapter's own thread, until the text iterator ends."""

    name = "fake-locked-stream-tts"
    text_streaming = True
    default_voice = "v"
    allowlist = voices = ["v"]

    def __init__(self):
        self.lock = threading.Lock()

    def open_text_stream(self, voice, speed):
        return K._TextStream(self, voice, speed)

    def synth_stream(self, text_iter, voice=None, speed=1.0):
        with self.lock:
            for _ in text_iter:
                yield np.zeros(480, np.int16)


@pytest.mark.asyncio
async def test_socket_closing_mid_line_frees_the_speech_engine():
    tts = LockedStreamTts()
    svc = S.Service(stt=E.FakeStt(), tts=tts, token=TOKEN, sample=False)
    server = TestServer(S.make_app(svc), host="127.0.0.1")
    await server.start_server()
    c = VoiceClient(f"http://127.0.0.1:{server.port}", TOKEN)
    try:
        await c.connect()
        await c.send({"type": "tts.stream.start", "id": 2})
        await c.send({"type": "tts.stream.text", "id": 2, "text": "hello there "})
        for _ in range(200):  # the line is speaking: audio arrived, the engine lock is held
            await asyncio.sleep(0.01)
            if c.tts.get(2) and c.tts[2].pcm:
                break
        assert tts.lock.locked()
        await c.ws.close()  # no tts.stream.end, no bye: the phone just went away
        t0 = time.monotonic()
        while tts.lock.locked() and time.monotonic() - t0 < 3:
            await asyncio.sleep(0.02)
        assert not tts.lock.locked(), "the speech thread still holds the engine: the next line would wait forever"
    finally:
        await c.close()
        await server.close()
