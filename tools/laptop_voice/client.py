"""Reference client for the laptop voice service (protocol 1). Used by the tests and by hand:

    python -m laptop_voice.client --wav some16k.wav --say "Twelve minutes, running." --out reply.wav

The token comes from LEGION_VOICE_TOKEN; it is never printed.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import struct
import time
from dataclasses import dataclass, field

import aiohttp
import numpy as np

def read_token() -> str:
    """The service's token file (never printed). LEGION_VOICE_TOKEN_FILE overrides the default path."""
    path = os.environ.get("LEGION_VOICE_TOKEN_FILE") or os.path.join(
        os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "legion-voice", "token")
    with open(path, encoding="ascii") as fh:
        return fh.read().strip()


HEADER = struct.Struct("<B3xI")
KIND_MIC, KIND_TTS = 0x01, 0x02


@dataclass
class TtsResult:
    pcm: bytearray = field(default_factory=bytearray)
    done: dict | None = None
    first_frame_s: float | None = None


class VoiceClient:
    def __init__(self, url: str, token: str):
        self.url, self.token = url.rstrip("/"), token
        self.session: aiohttp.ClientSession | None = None
        self.ws: aiohttp.ClientWebSocketResponse | None = None
        self.events: asyncio.Queue = asyncio.Queue()
        self.tts: dict[int, TtsResult] = {}
        self._reader: asyncio.Task | None = None

    @property
    def headers(self) -> dict:
        return {"Authorization": f"Bearer {self.token}"}

    async def health(self) -> tuple[int, dict | None]:
        async with aiohttp.ClientSession() as s:
            async with s.get(self.url + "/health", headers=self.headers, timeout=aiohttp.ClientTimeout(total=2)) as r:
                return r.status, (await r.json() if r.status == 200 else None)

    async def connect(self, voice: str | None = None, lang: str = "auto") -> dict:
        self.session = aiohttp.ClientSession()
        ws_url = self.url.replace("http", "ws", 1) + "/v1/conversation"
        self.ws = await self.session.ws_connect(ws_url, headers=self.headers, max_msg_size=1 << 20)
        self._reader = asyncio.create_task(self._read())
        hello = {"type": "hello", "protocol": 1, "client": "legion-test-client/0.1", "lang": lang}
        if voice:
            hello["voice"] = voice
        await self.ws.send_str(json.dumps(hello))
        return await self.next_event("ready")

    async def _read(self) -> None:
        async for msg in self.ws:
            if msg.type == aiohttp.WSMsgType.TEXT:
                await self.events.put(json.loads(msg.data))
            elif msg.type == aiohttp.WSMsgType.BINARY:
                kind, tid = HEADER.unpack_from(msg.data)
                assert kind == KIND_TTS
                r = self.tts.setdefault(tid, TtsResult())
                if r.first_frame_s is None:
                    r.first_frame_s = time.perf_counter()
                r.pcm += msg.data[HEADER.size:]
        await self.events.put({"type": "_closed", "code": self.ws.close_code})

    async def next_event(self, *types: str, timeout: float = 15.0) -> dict:
        while True:
            ev = await asyncio.wait_for(self.events.get(), timeout)
            if not types or ev["type"] in types or ev["type"] in ("error", "_closed"):
                return ev

    async def send(self, obj: dict) -> None:
        await self.ws.send_str(json.dumps(obj))

    async def stream_utterance(self, uid: int, pcm16k: np.ndarray, chunk_ms: int = 100, realtime: bool = False,
                               end: bool = True) -> None:
        await self.send({"type": "stt.start", "id": uid})
        step = 16 * chunk_ms
        for i in range(0, len(pcm16k), step):
            await self.ws.send_bytes(HEADER.pack(KIND_MIC, uid) + pcm16k[i:i + step].astype("<i2").tobytes())
            if realtime:
                await asyncio.sleep(chunk_ms / 1000)
        if end:
            await self.send({"type": "stt.end", "id": uid})

    async def speak(self, tid: int, text: str, voice: str | None = None) -> float:
        self.tts[tid] = TtsResult()
        msg = {"type": "tts.speak", "id": tid, "text": text}
        if voice:
            msg["voice"] = voice
        t0 = time.perf_counter()
        await self.send(msg)
        return t0

    async def close(self) -> None:
        if self.ws is not None and not self.ws.closed:
            await self.send({"type": "bye"})
            await self.ws.close()
        if self._reader:
            await asyncio.gather(self._reader, return_exceptions=True)
        if self.session:
            await self.session.close()


async def _main() -> None:
    import soundfile as sf

    p = argparse.ArgumentParser()
    p.add_argument("--url", default="http://127.0.0.1:8765")
    p.add_argument("--wav", help="16 kHz mono WAV to transcribe")
    p.add_argument("--say", help="text to synthesize")
    p.add_argument("--voice", default=None)
    p.add_argument("--out", default="reply.wav")
    a = p.parse_args()
    c = VoiceClient(a.url, read_token())
    print("health", await c.health())
    print("ready", (await c.connect(a.voice))["tts"])
    if a.wav:
        x, sr = sf.read(a.wav, dtype="int16")
        assert sr == 16000, "need 16 kHz"
        await c.stream_utterance(1, x)
        t_end = time.perf_counter()
        ev = await c.next_event("stt.final")
        print(f"stt ({(time.perf_counter() - t_end) * 1000:.0f} ms after stt.end):", ev)
    if a.say:
        t0 = await c.speak(1, a.say, a.voice)
        done = await c.next_event("tts.done")
        r = c.tts[1]
        print(f"tts first frame {(r.first_frame_s - t0) * 1000:.0f} ms, done:", done)
        sf.write(a.out, np.frombuffer(bytes(r.pcm), dtype="<i2"), 24000, subtype="PCM_16")
    await c.close()


if __name__ == "__main__":
    asyncio.run(_main())
