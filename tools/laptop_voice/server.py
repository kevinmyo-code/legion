"""LEGION laptop voice service: speech-to-text and text-to-speech for the phone, protocol 1.

Wire contract: tools/laptop_voice/CONTRACT.md (copy of the agreed contract). Speech only: no
Gemini key, no tools, nothing written to disk, no transcripts in logs.

Run (development, by hand):
    set LEGION_VOICE_TOKEN=<long random string>
    python -m laptop_voice.server            (from tools/)
"""

from __future__ import annotations

import argparse
import asyncio
import hmac
import ipaddress
import json
import logging
import os
import shutil
import struct
import subprocess
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field

import numpy as np
from aiohttp import WSMsgType, web

try:
    from . import engines as E
except ImportError:  # run as a script
    import engines as E  # type: ignore

PROTOCOL = 1
VERSION = "0.1.0"
KIND_MIC, KIND_TTS = 0x01, 0x02
HEADER = struct.Struct("<B3xI")
MAX_UTTERANCE_MS = 30_000
MAX_TTS_CHARS = 1_000
IDLE_TIMEOUT_S = 120.0
STT_BUDGET_S = 5.0
TTS_FIRST_BUDGET_S = 3.0
TTS_FRAME_SAMPLES = 4_800  # 200 ms at 24 kHz
BUSY_RAM_PCT = float(os.environ.get("LEGION_VOICE_BUSY_RAM_PCT", "98"))
BUSY_QUEUE = 3
STRIKE_FORGET_S = 60.0               # slow strikes older than this no longer make the service busy
MAX_CONVERSATIONS = 2
MAX_FRAME_BYTES = 64 * 1024          # 100 ms of mic audio is 3,208 bytes; text is at most 1,000 chars
AUTH_FAIL_DELAY_S = 0.5              # every refused request waits this long
AUTH_FAIL_WINDOW_S = 60.0
AUTH_FAIL_MAX = 20                   # more refusals than this in the window locks the service for LOCKOUT_S
LOCKOUT_S = 30.0
# Voices a client may name: a fixed allowlist of keys inside the model's own voices file, never a path.
VOICE_ALLOW = ("am_puck", "am_michael", "am_onyx", "am_fenrir", "am_echo", "am_eric", "am_liam", "am_adam",
               "bm_george", "bm_lewis", "bm_daniel", "bm_fable")
TOKEN_FILE_DEFAULT = os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "legion-voice", "token")

log = logging.getLogger("laptop_voice")
SVC = web.AppKey("svc", object)
SAMPLER = web.AppKey("sampler", asyncio.Task)


def _timed(fn, *args):
    """Run fn on the worker thread and return (result, seconds of compute), excluding queue wait."""
    t = time.perf_counter()
    out = fn(*args)
    return out, time.perf_counter() - t


def log_vram(device: bool = False) -> None:
    """Sizes only: this process's CUDA allocator counters (no driver call). device=True also asks the
    driver for free memory: a CUDA call, so only at startup before any other thread uses the GPU."""
    import sys

    torch = sys.modules.get("torch")
    if torch is None or not torch.cuda.is_initialized():
        return
    extra = ""
    if device:
        free, total = torch.cuda.mem_get_info()
        extra = f" device_free_mb={free >> 20} device_total_mb={total >> 20}"
    log.info("vram self_reserved_mb=%d self_alloc_mb=%d self_peak_alloc_mb=%d%s", torch.cuda.memory_reserved() >> 20,
             torch.cuda.memory_allocated() >> 20, torch.cuda.max_memory_allocated() >> 20, extra)


class GpuWorker:
    """Every model call runs on one thread, so jobs never compete for the GPU."""

    def __init__(self) -> None:
        self.pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="gpu")
        self.queued = 0

    async def run(self, fn, *args):
        loop = asyncio.get_running_loop()
        self.queued += 1
        try:
            return await loop.run_in_executor(self.pool, fn, *args)
        finally:
            self.queued -= 1


@dataclass
class Service:
    stt: E.SttEngine | None
    tts: E.TtsEngine | None
    token: str
    started: float = field(default_factory=time.time)
    gpu: GpuWorker = field(default_factory=GpuWorker)       # STT jobs
    tts_gpu: GpuWorker = field(default_factory=GpuWorker)   # TTS jobs (own thread: speaking never blocks listening)
    connections: int = 0
    loading: bool = False
    ram_pct: float = 0.0
    gpu_free_mb: int | None = None
    slow_strikes: int = 0  # recent jobs that timed out or fell behind real time
    last_strike: float = 0.0
    sample: bool = True  # tests switch off host sampling so the real machine's RAM cannot flip them busy
    auth_fails: list = field(default_factory=list)
    locked_until: float = 0.0

    def strike(self) -> None:
        self.slow_strikes += 1
        self.last_strike = time.monotonic()

    def status(self) -> str:
        if self.loading or self.stt is None or self.tts is None:
            return "loading"
        if self.slow_strikes and time.monotonic() - self.last_strike > STRIKE_FORGET_S:
            self.slow_strikes = 0  # busy from slowness ends on its own (no conversation can reset it)
        if (self.ram_pct >= BUSY_RAM_PCT or self.gpu.queued + self.tts_gpu.queued >= BUSY_QUEUE
                or self.slow_strikes >= 3):
            return "busy"
        return "ready"

    def describe(self) -> dict:
        return {
            "stt": {"model": getattr(self.stt, "name", None), "languages": getattr(self.stt, "languages", None),
                    "sample_rate": E.STT_RATE, "end_of_turn": bool(getattr(self.stt, "end_of_turn", False)),
                    "partials": bool(getattr(self.stt, "partials", False))},
            "tts": {"model": getattr(self.tts, "name", None), "voices": self.voices(),
                    "default_voice": getattr(self.tts, "default_voice", None), "sample_rate": E.TTS_RATE,
                    "text_streaming": bool(getattr(self.tts, "text_streaming", False))},
            "capabilities": {"end_of_turn": bool(getattr(self.stt, "end_of_turn", False)),
                             "partials": bool(getattr(self.stt, "partials", False)),
                             "text_streaming": bool(getattr(self.tts, "text_streaming", False))},
        }

    def authorized(self, request: web.Request) -> bool:
        got = request.headers.get("Authorization", "")
        want = f"Bearer {self.token}"
        return bool(self.token) and hmac.compare_digest(got.encode(), want.encode())

    def voices(self) -> list[str]:
        """Voice ids a client may name. Kokoro: VOICE_ALLOW filtered by what the model has. An engine
        with its own curated table (Kyutai: fixed id -> file mapping inside the engine) supplies it."""
        own = getattr(self.tts, "allowlist", None)
        if own:
            return list(own)
        have = set(getattr(self.tts, "voices", []))
        return [v for v in VOICE_ALLOW if v in have]

    @property
    def max_conversations(self) -> int:
        caps = [getattr(self.stt, "max_sessions", MAX_CONVERSATIONS), getattr(self.tts, "max_sessions", MAX_CONVERSATIONS)]
        return max(1, min([MAX_CONVERSATIONS] + caps))

    async def gate(self, request: web.Request) -> web.Response | None:
        """None when the request may proceed, else the refusal to send. Constant-time token check,
        a delay on every refusal, and a short lockout after a burst of refusals."""
        now = time.monotonic()
        if now < self.locked_until:
            return web.Response(status=429)
        if self.authorized(request):
            return None
        self.auth_fails = [t for t in self.auth_fails if now - t < AUTH_FAIL_WINDOW_S] + [now]
        if len(self.auth_fails) > AUTH_FAIL_MAX:
            self.locked_until = now + LOCKOUT_S
            log.warning("auth: %d refusals in %ds, locked for %ds", len(self.auth_fails), AUTH_FAIL_WINDOW_S, LOCKOUT_S)
        await asyncio.sleep(AUTH_FAIL_DELAY_S)
        return web.Response(status=401)


async def sample_host(svc: Service) -> None:
    """Refresh RAM and GPU readings every 5 s, off the request path."""
    import psutil

    smi = shutil.which("nvidia-smi")
    while True:
        svc.ram_pct = psutil.virtual_memory().percent
        if smi:
            try:
                out = await asyncio.to_thread(
                    subprocess.run, [smi, "--query-gpu=memory.free", "--format=csv,noheader,nounits"],
                    capture_output=True, text=True, timeout=3)
                svc.gpu_free_mb = int(out.stdout.strip().splitlines()[0])
            except Exception:  # noqa: BLE001 - a missing reading is null, never an error
                svc.gpu_free_mb = None
        await asyncio.sleep(5)


async def livez(request: web.Request) -> web.Response:
    """Bare liveness for the tunnel: no auth, no body, reveals nothing."""
    return web.Response(status=204)


async def health(request: web.Request) -> web.Response:
    svc: Service = request.app[SVC]
    refused = await svc.gate(request)
    if refused is not None:
        return refused
    body = {"status": svc.status(), "protocol": PROTOCOL, "version": VERSION, **svc.describe(),
            "ram_pct": round(svc.ram_pct, 1), "gpu_free_mb": svc.gpu_free_mb, "queue": svc.gpu.queued + svc.tts_gpu.queued,
            "connections": svc.connections, "uptime_s": int(time.time() - svc.started)}
    return web.json_response(body)


class Conversation:
    def __init__(self, svc: Service, ws: web.WebSocketResponse):
        self.svc, self.ws = svc, ws
        self.hello = False
        self.voice = svc.tts.default_voice if svc.tts else "am_puck"
        self.lang: str | None = None
        self.utts: dict[int, dict] = {}
        self.decoding: dict[int, dict] = {}
        self.tts_queue: asyncio.Queue = asyncio.Queue()
        self.tts_cancelled: set[int] = set()
        self.tts_active: int | None = None
        self.tts_streams: dict[int, dict] = {}
        self.send_lock = asyncio.Lock()
        self.last_rx = time.monotonic()
        self.tasks: list[asyncio.Task] = []

    async def send_json(self, obj: dict) -> None:
        async with self.send_lock:
            if not self.ws.closed:
                await self.ws.send_str(json.dumps(obj))

    async def send_audio(self, tid: int, pcm: bytes) -> None:
        async with self.send_lock:
            if not self.ws.closed:
                await self.ws.send_bytes(HEADER.pack(KIND_TTS, tid) + pcm)

    async def error(self, code: str, message: str, id_: int | None = None, retry: bool = False) -> None:
        await self.send_json({"type": "error", "id": id_, "code": code, "message": message, "retry": retry})

    # ---- STT ----
    async def stt_start(self, m: dict) -> None:
        uid = m.get("id")
        if not isinstance(uid, int) or uid < 0 or uid in self.utts:
            return await self.error("bad_request", "stt.start needs a new uint32 id", uid if isinstance(uid, int) else None)
        if self.streaming_stt:
            live = [k for k, v in self.utts.items() if "q" in v]
            if live:  # the streaming model holds one live stream (batch size 1)
                return await self.error("bad_request", f"one live utterance at a time: end or cancel id {live[0]} first", uid)
        lang = m.get("lang", self.lang)
        u = {"buf": bytearray(), "lang": None if lang in (None, "auto") else lang, "too_long": False}
        self.utts[uid] = u
        if self.streaming_stt:
            u["q"] = asyncio.Queue()
            u["t0"] = time.perf_counter()
            self.tasks.append(asyncio.create_task(self._stream_stt(uid, u)))

    @property
    def streaming_stt(self) -> bool:
        return bool(getattr(self.svc.stt, "end_of_turn", False)) and hasattr(self.svc.stt, "open_stream")

    async def _stream_stt(self, uid: int, u: dict) -> None:
        """Feed a streaming STT session as frames arrive; it may end the turn itself (contract 4b)."""
        try:
            sess = await asyncio.wait_for(self.svc.gpu.run(self.svc.stt.open_stream, u["lang"]), STT_BUDGET_S)
        except Exception as exc:  # noqa: BLE001
            self.utts.pop(uid, None)
            if isinstance(exc, asyncio.TimeoutError):
                log.warning("stt stream open over %.0f s", STT_BUDGET_S)
                self.svc.strike()
                return await self.error("timeout", f"STT stream did not open within {STT_BUDGET_S} s", uid)
            log.exception("stt stream open failed")
            return await self.error("internal", type(exc).__name__, uid)
        last_pause = 0.0
        reason = None
        r = None
        decode_ms = 0
        feed_s = 0.0  # model compute spent on this stream; feed_s / audio seconds = streaming RTF
        while reason is None:
            item = await u["q"].get()
            if item is None or u.get("cancelled"):  # stt.end / stt.cancel from the phone, or the 30 s cap
                reason = "cancelled" if u.get("cancelled") else "client_end"
                break
            t_feed = time.perf_counter()
            try:
                events, dt = await asyncio.wait_for(self.svc.gpu.run(_timed, sess.feed, item), STT_BUDGET_S)
                feed_s += dt
            except Exception as exc:  # noqa: BLE001
                self.utts.pop(uid, None)
                if isinstance(exc, asyncio.TimeoutError):
                    log.warning("stt stream feed over %.0f s (GPU or host memory starved?)", STT_BUDGET_S)
                    self.svc.strike()
                    return await self.error("timeout", f"STT step over {STT_BUDGET_S} s", uid)
                log.exception("stt stream feed failed")
                return await self.error("internal", type(exc).__name__, uid)
            for kind, value in events:
                if u.get("cancelled"):
                    break
                if kind == "partial":
                    await self.send_json({"type": "stt.partial", "id": uid, "text": value})
                elif kind == "pause" and time.perf_counter() - last_pause >= 0.08:
                    last_pause = time.perf_counter()
                    prob, silence_ms = value
                    await self.send_json({"type": "stt.pause", "id": uid, "prob": round(float(prob), 3),
                                          "silence_ms": silence_ms})
                elif kind == "end_of_turn":
                    # The model ended the turn and already flushed: the event carries the final text
                    # (the session has moved on, so finish() would return an empty string).
                    reason = "end_of_turn"
                    r = E.SttResult(text=value, lang=u["lang"], lang_prob=None)
                    decode_ms = round((time.perf_counter() - t_feed) * 1000)
                    break
        self.utts.pop(uid, None)
        if reason == "cancelled" or u.get("cancelled"):
            return
        if u["too_long"]:
            await self.error("too_long", f"utterance over {MAX_UTTERANCE_MS} ms; decoded the first part", uid)
        if r is None:
            t0 = time.perf_counter()
            r = await self.svc.gpu.run(sess.finish)
            decode_ms = round((time.perf_counter() - t0) * 1000)
        audio_ms = len(u["buf"]) * 1000 // 2 // E.STT_RATE
        rtf = feed_s * 1000 / max(audio_ms, 1)  # includes waits on the shared GPU lock while TTS speaks
        self.svc.slow_strikes = 0  # strikes come from timeouts only
        log.info("stt stream id=%s audio_ms=%d reason=%s decode_ms=%d feed_ms=%d rtf=%.2f chars=%d", uid, audio_ms,
                 reason, decode_ms, feed_s * 1000, rtf, len(r.text))
        await self.send_json({"type": "stt.final", "id": uid, "text": r.text, "lang": r.lang, "lang_prob": r.lang_prob,
                              "audio_ms": audio_ms, "decode_ms": decode_ms, "reason": reason})

    def mic(self, uid: int, pcm: bytes) -> str | None:
        u = self.utts.get(uid)
        if u is None:
            return "audio for an id never started"
        if len(pcm) % 2:
            return "odd byte count"
        if len(u["buf"]) + len(pcm) > MAX_UTTERANCE_MS * E.STT_RATE // 1000 * 2:
            u["too_long"] = True
            room = MAX_UTTERANCE_MS * E.STT_RATE // 1000 * 2 - len(u["buf"])
            pcm = pcm[:max(room, 0)]
        u["buf"] += pcm
        if "q" in u and pcm:
            u["q"].put_nowait(np.frombuffer(pcm, dtype=np.int16).copy())
        if "q" in u and u["too_long"] and not u.get("capped"):
            u["capped"] = True  # streaming: at the cap, close the utterance (too_long, then stt.final)
            u["q"].put_nowait(None)
        return None

    async def stt_end(self, m: dict) -> None:
        uid = m.get("id")
        if isinstance(uid, int) and uid in self.utts and "q" in self.utts[uid]:
            self.utts[uid]["q"].put_nowait(None)
            return
        u = self.utts.pop(uid, None) if isinstance(uid, int) else None
        if u is None:
            return await self.error("bad_request", "stt.end for an unknown id", uid if isinstance(uid, int) else None)
        self.decoding[uid] = u
        self.tasks.append(asyncio.create_task(self._decode(uid, u)))

    async def _decode(self, uid: int, u: dict) -> None:
        try:
            await self._decode_inner(uid, u)
        finally:
            self.decoding.pop(uid, None)

    async def _decode_inner(self, uid: int, u: dict) -> None:
        if u.get("cancelled"):
            return
        pcm = np.frombuffer(bytes(u["buf"]), dtype=np.int16)
        if u["too_long"]:
            await self.error("too_long", f"utterance over {MAX_UTTERANCE_MS} ms; decoded the first part", uid)
        if self.svc.gpu.queued >= BUSY_QUEUE:
            return await self.error("busy", "GPU queue full", uid, retry=True)
        t0 = time.perf_counter()
        try:
            r = await asyncio.wait_for(self.svc.gpu.run(self.svc.stt.transcribe, pcm, u["lang"]), STT_BUDGET_S)
        except asyncio.TimeoutError:
            self.svc.strike()
            return await self.error("timeout", f"STT over {STT_BUDGET_S} s", uid)
        except Exception as exc:  # noqa: BLE001
            log.exception("stt failed")
            return await self.error("internal", type(exc).__name__, uid)
        self.svc.slow_strikes = 0
        if u.get("cancelled"):
            return  # stt.cancel already answered for this id
        decode_ms = round((time.perf_counter() - t0) * 1000)
        log.info("stt id=%s audio_ms=%d decode_ms=%d chars=%d", uid, len(pcm) * 1000 // E.STT_RATE, decode_ms, len(r.text))
        await self.send_json({"type": "stt.final", "id": uid, "text": r.text, "lang": r.lang, "lang_prob": r.lang_prob,
                              "audio_ms": len(pcm) * 1000 // E.STT_RATE, "decode_ms": decode_ms, "reason": "client_end"})

    async def stt_cancel(self, m: dict) -> None:
        uid = m.get("id")
        u = None
        if isinstance(uid, int):
            u = self.utts.pop(uid, None) or self.decoding.get(uid)
        if u is not None and not u.get("cancelled"):
            u["cancelled"] = True
            if "q" in u:
                u["q"].put_nowait(None)
            await self.send_json({"type": "stt.final", "id": uid, "text": "", "lang": None, "lang_prob": None,
                                  "audio_ms": len(u["buf"]) * 1000 // 2 // E.STT_RATE, "decode_ms": 0, "reason": "cancelled",
                                  "cancelled": True})

    # ---- TTS ----
    async def tts_speak(self, m: dict) -> None:
        tid, text = m.get("id"), m.get("text")
        if not isinstance(tid, int) or tid < 0:
            return await self.error("bad_request", "tts.speak needs a uint32 id")
        if not isinstance(text, str) or not text.strip() or len(text) > MAX_TTS_CHARS:
            return await self.error("bad_request", f"text must be 1 to {MAX_TTS_CHARS} chars", tid)
        voice = m.get("voice", self.voice)
        if voice not in self.svc.voices():
            return await self.error("bad_request", f"unknown voice {voice}", tid)
        speed = float(m.get("speed", 1.0))
        if not 0.5 <= speed <= 2.0:
            return await self.error("bad_request", "speed must be 0.5 to 2.0", tid)
        await self.tts_queue.put((tid, text, voice, speed, time.perf_counter()))

    async def tts_stream_start(self, m: dict) -> None:
        tid = m.get("id")
        if not isinstance(tid, int) or tid < 0 or tid in self.tts_streams:
            return await self.error("bad_request", "tts.stream.start needs a new uint32 id")
        voice = m.get("voice", self.voice)
        if voice not in self.svc.voices():
            return await self.error("bad_request", f"unknown voice {voice}", tid)
        speed = float(m.get("speed", 1.0))
        if not 0.5 <= speed <= 2.0:
            return await self.error("bad_request", "speed must be 0.5 to 2.0", tid)
        self.tts_streams[tid] = {"q": asyncio.Queue(), "chars": 0}
        await self.tts_queue.put((tid, None, voice, speed, time.perf_counter()))

    async def tts_stream_text(self, m: dict) -> None:
        tid, text = m.get("id"), m.get("text")
        st = self.tts_streams.get(tid) if isinstance(tid, int) else None
        if st is None or st.get("ended"):
            return await self.error("bad_request", "tts.stream.text for an id not open", tid if isinstance(tid, int) else None)
        if not isinstance(text, str) or st["chars"] + len(text) > MAX_TTS_CHARS:
            # The id is dead after an error (contract): stop it here, silently, so it cannot hold up
            # the speech queued behind it while the phone waits for nothing more on this id.
            st["errored"] = st["ended"] = True
            self.tts_cancelled.add(tid)
            st["q"].put_nowait(None)
            return await self.error("bad_request", f"streamed text over {MAX_TTS_CHARS} chars; this id is closed", tid)
        st["chars"] += len(text)
        st["q"].put_nowait(text)

    async def tts_stream_end(self, m: dict) -> None:
        tid = m.get("id")
        st = self.tts_streams.get(tid) if isinstance(tid, int) else None
        if st is None:
            return await self.error("bad_request", "tts.stream.end for an id not open", tid if isinstance(tid, int) else None)
        st["ended"] = True
        st["q"].put_nowait(None)

    async def tts_loop(self) -> None:
        while True:
            tid, text, voice, speed, t_rx = await self.tts_queue.get()
            if tid in self.tts_cancelled:
                st = self.tts_streams.pop(tid, None)
                if not (st and st.get("errored")):  # an id that got an error hears nothing more
                    await self.send_json({"type": "tts.done", "id": tid, "audio_ms": 0, "first_audio_ms": None,
                                          "cancelled": True})
                continue
            self.tts_active = tid
            try:
                if text is None:
                    await self._speak_stream(tid, voice, speed, t_rx)
                else:
                    await self._speak(tid, text, voice, speed, t_rx)
            finally:
                self.tts_active = None
                self.tts_streams.pop(tid, None)

    async def _speak_stream(self, tid: int, voice: str, speed: float, t_rx: float) -> None:
        st = self.tts_streams[tid]
        try:
            sess = await self.svc.tts_gpu.run(self.svc.tts.open_text_stream, voice, speed)
        except Exception as exc:  # noqa: BLE001
            log.exception("tts stream open failed")
            return await self.error("internal", type(exc).__name__, tid)
        first_ms, samples, ended, end_sent = None, 0, False, False

        async def send(chunk) -> None:
            nonlocal first_ms, samples
            raw = np.asarray(chunk, dtype=np.int16)
            for i in range(0, len(raw), TTS_FRAME_SAMPLES):
                if tid in self.tts_cancelled:
                    return
                if first_ms is None:
                    first_ms = round((time.perf_counter() - t_rx) * 1000)
                await self.send_audio(tid, raw[i:i + TTS_FRAME_SAMPLES].tobytes())
                samples += len(raw[i:i + TTS_FRAME_SAMPLES])

        # Pull audio as it is generated until the session says it is done. Never block on the whole
        # line: a cancel is checked every pass (at most ~50 ms plus one model step late).
        try:
            while tid not in self.tts_cancelled:
                while not st["q"].empty():  # hand over whatever text has arrived
                    piece = st["q"].get_nowait()
                    if piece is None:
                        ended = True
                    else:
                        sess.push(piece)
                if ended and not end_sent:
                    sess.end()
                    end_sent = True
                chunk = await self.svc.tts_gpu.run(sess.pull, 0.05)
                if chunk is not None and len(chunk):
                    await send(chunk)
                    continue
                if sess.done:
                    break
                if not ended and st["q"].empty():
                    try:
                        piece = await asyncio.wait_for(st["q"].get(), 0.05)
                        if piece is None:
                            ended = True
                        else:
                            sess.push(piece)
                    except asyncio.TimeoutError:
                        pass
        except Exception as exc:  # noqa: BLE001 - e.g. the model thread failed
            log.exception("tts stream failed")
            await self.svc.tts_gpu.run(sess.abort)
            return await self.error("internal", type(exc).__name__, tid)
        if tid in self.tts_cancelled:
            await self.svc.tts_gpu.run(sess.abort)
        cancelled = tid in self.tts_cancelled
        log.info("tts stream id=%s chars=%d first_ms=%s audio_ms=%d cancelled=%s", tid, st["chars"], first_ms,
                 samples * 1000 // E.TTS_RATE, cancelled)
        if st.get("errored"):
            return  # the over-cap error already closed this id
        msg = {"type": "tts.done", "id": tid, "audio_ms": samples * 1000 // E.TTS_RATE, "first_audio_ms": first_ms}
        if cancelled:
            msg["cancelled"] = True
        await self.send_json(msg)

    async def _speak(self, tid: int, text: str, voice: str, speed: float, t_rx: float) -> None:
        gen = self.svc.tts.synth(text, voice, speed)
        sentinel = object()
        first_ms, samples, first = None, 0, True
        while True:
            if tid in self.tts_cancelled:
                break
            try:
                coro = self.svc.tts_gpu.run(next, gen, sentinel)
                chunk = await (asyncio.wait_for(coro, TTS_FIRST_BUDGET_S) if first else coro)
            except asyncio.TimeoutError:
                self.svc.strike()
                return await self.error("timeout", f"no TTS audio within {TTS_FIRST_BUDGET_S} s", tid)
            except Exception as exc:  # noqa: BLE001
                log.exception("tts failed")
                return await self.error("internal", type(exc).__name__, tid)
            if chunk is sentinel:
                break
            first = False
            raw = np.asarray(chunk, dtype=np.int16)
            for i in range(0, len(raw), TTS_FRAME_SAMPLES):
                if tid in self.tts_cancelled:
                    break
                if first_ms is None:
                    first_ms = round((time.perf_counter() - t_rx) * 1000)
                await self.send_audio(tid, raw[i:i + TTS_FRAME_SAMPLES].tobytes())
                samples += len(raw[i:i + TTS_FRAME_SAMPLES])
        cancelled = tid in self.tts_cancelled
        self.svc.slow_strikes = 0
        log.info("tts id=%s chars=%d first_ms=%s audio_ms=%d cancelled=%s", tid, len(text), first_ms,
                 samples * 1000 // E.TTS_RATE, cancelled)
        msg = {"type": "tts.done", "id": tid, "audio_ms": samples * 1000 // E.TTS_RATE, "first_audio_ms": first_ms}
        if cancelled:
            msg["cancelled"] = True
        await self.send_json(msg)

    async def tts_cancel(self, m: dict) -> None:
        tid = m.get("id")
        if isinstance(tid, int):
            self.tts_cancelled.add(tid)
            st = self.tts_streams.get(tid)
            if st is not None:
                st["q"].put_nowait(None)

    # ---- main loop ----
    async def idle_watch(self) -> None:
        while not self.ws.closed:
            await asyncio.sleep(1)
            if time.monotonic() - self.last_rx > IDLE_TIMEOUT_S:
                await self.ws.close(code=1001, message=b"idle")

    async def handle(self, m: dict) -> bool:
        t = m.get("type")
        if not self.hello:
            if t != "hello":
                await self.error("protocol", "send hello first")
                await self.ws.close(code=4400, message=b"hello first")
                return False
            if m.get("protocol") != PROTOCOL:
                await self.error("protocol", f"protocol {PROTOCOL} only")
                await self.ws.close(code=4400, message=b"protocol")
                return False
            self.hello = True
            if m.get("voice") in self.svc.voices():
                self.voice = m["voice"]
            self.lang = None if m.get("lang") in (None, "auto") else m.get("lang")
            self.tasks.append(asyncio.create_task(self.tts_loop()))
            await self.send_json({"type": "ready", "protocol": PROTOCOL, "session": str(uuid.uuid4()), **self.svc.describe(),
                                  "limits": {"max_utterance_ms": MAX_UTTERANCE_MS, "max_tts_chars": MAX_TTS_CHARS,
                                             "idle_timeout_ms": int(IDLE_TIMEOUT_S * 1000)}})
            return True
        handlers = {"stt.start": self.stt_start, "stt.end": self.stt_end, "stt.cancel": self.stt_cancel,
                    "tts.speak": self.tts_speak, "tts.cancel": self.tts_cancel}
        if getattr(self.svc.tts, "text_streaming", False):
            handlers.update({"tts.stream.start": self.tts_stream_start, "tts.stream.text": self.tts_stream_text,
                             "tts.stream.end": self.tts_stream_end})
        if t in handlers:
            await handlers[t](m)
        elif t in ("tts.stream.start", "tts.stream.text", "tts.stream.end"):
            await self.error("bad_request", "this backend has no text streaming (capabilities.text_streaming is false)",
                             m.get("id") if isinstance(m.get("id"), int) else None)
        elif t == "ping":
            await self.send_json({"type": "pong", "t": m.get("t")})
        elif t == "bye":
            await self.ws.close(code=1000)
            return False
        else:
            await self.error("bad_request", f"unknown type {t!r}")
        return True

    async def run(self) -> None:
        self.tasks.append(asyncio.create_task(self.idle_watch()))
        try:
            async for msg in self.ws:
                self.last_rx = time.monotonic()
                if msg.type == WSMsgType.TEXT:
                    try:
                        m = json.loads(msg.data)
                        assert isinstance(m, dict)
                    except Exception:  # noqa: BLE001
                        await self.error("bad_request", "frame is not a JSON object")
                        continue
                    if not await self.handle(m):
                        break
                elif msg.type == WSMsgType.BINARY:
                    data = msg.data
                    if not self.hello or len(data) < HEADER.size:
                        await self.error("bad_request", "binary frame before hello or too short")
                        continue
                    kind, uid = HEADER.unpack_from(data)
                    if kind != KIND_MIC:
                        await self.error("bad_request", "phone may only send mic frames (0x01)", uid)
                        continue
                    problem = self.mic(uid, data[HEADER.size:])
                    if problem:
                        await self.error("bad_request", problem, uid)
                elif msg.type in (WSMsgType.ERROR, WSMsgType.CLOSE):
                    break
        finally:
            for t in self.tasks:
                t.cancel()
            self.utts.clear()


async def conversation(request: web.Request) -> web.StreamResponse:
    svc: Service = request.app[SVC]
    refused = await svc.gate(request)
    if refused is not None:
        return refused
    ws = web.WebSocketResponse(heartbeat=30, max_msg_size=MAX_FRAME_BYTES)
    await ws.prepare(request)
    if svc.status() != "ready" or svc.connections >= svc.max_conversations:
        await ws.close(code=1013, message=b"busy")
        return ws
    svc.connections += 1
    try:
        await Conversation(svc, ws).run()
    finally:
        svc.connections -= 1
        log_vram()
    return ws


def make_app(svc: Service) -> web.Application:
    app = web.Application()
    app[SVC] = svc
    app.router.add_get("/livez", livez)
    app.router.add_get("/health", health)
    app.router.add_get("/v1/conversation", conversation)

    async def start_bg(app_: web.Application):
        if svc.sample:
            app_[SAMPLER] = asyncio.create_task(sample_host(svc))

    async def stop_bg(app_: web.Application):
        if SAMPLER in app_:
            app_[SAMPLER].cancel()

    app.on_startup.append(start_bg)
    app.on_cleanup.append(stop_bg)
    return app


def build_engines(stt: str, tts: str):
    stt_engine = {"whisper": E.WhisperStt, "parakeet": E.ParakeetStt, "fake": E.FakeStt,
                  "kyutai": lambda: E.load_kyutai("stt")}[stt]()
    tts_engine = {"kokoro": E.KokoroTts, "fake": E.FakeTts, "kyutai": lambda: E.load_kyutai("tts")}[tts]()
    return stt_engine, tts_engine


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--host", default=os.environ.get("LEGION_VOICE_HOST", "127.0.0.1"))
    p.add_argument("--port", type=int, default=int(os.environ.get("LEGION_VOICE_PORT", "8765")))
    p.add_argument("--stt", default=os.environ.get("LEGION_VOICE_STT", "whisper"),
                   choices=["whisper", "parakeet", "kyutai", "fake"])
    p.add_argument("--tts", default=os.environ.get("LEGION_VOICE_TTS", "kokoro"), choices=["kokoro", "kyutai", "fake"])
    a = p.parse_args()
    addr = ipaddress.ip_address(a.host)
    if addr.is_unspecified or addr.is_multicast:
        raise SystemExit("refusing to bind an all-interfaces address; bind 127.0.0.1 or the one link interface")
    token_file = os.environ.get("LEGION_VOICE_TOKEN_FILE", TOKEN_FILE_DEFAULT)
    try:
        with open(token_file, encoding="ascii") as fh:
            token = fh.read().strip()
    except OSError:
        raise SystemExit("no token file; start through: python -m laptop_voice.run") from None
    if len(token) < 32:
        raise SystemExit("token file too short; run: python -m laptop_voice.run rotate-token")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    t0 = time.perf_counter()
    stt, tts = build_engines(a.stt, a.tts)
    log.info("models loaded in %.1f s: stt=%s tts=%s", time.perf_counter() - t0, stt.name, tts.name)
    # warm both paths so the first conversation does not pay CUDA start-up
    stt.transcribe(np.zeros(E.STT_RATE, dtype=np.int16), "en")
    for _ in tts.synth("Ready.", tts.default_voice, 1.0):
        pass
    log_vram(device=True)
    if not addr.is_loopback:
        log.warning("bound to %s (not loopback): only do this for the private link interface", a.host)
    web.run_app(make_app(Service(stt=stt, tts=tts, token=token)), host=a.host, port=a.port, print=None,
                access_log=None)


if __name__ == "__main__":
    main()
