"""Warm passes through both models: once at start-up, then (keep-warm) at an interval while no
conversation is open.

Why keep-warm (2026-11-20, a theory, not proven): after a long idle on a nearly full 8 GB card the
first stream step of a conversation took over the 5 s budget, and a check right after was normal.
Windows may page an idle process's GPU memory (or its host pages) out, so a tiny real pass every
interval keeps them resident. The slow-pass log line shows whether a pass ever comes back cold.

Rules the pass keeps:
- Never while a conversation is open or starting (the STT model holds ONE streaming state, and a
  pass resets it).
- Runs on the same single-thread pools as the real paths (STT pool, TTS pool), submitted straight to
  the pool so the client-visible queue count never moves.
- Takes the engine's GPU lock without waiting, and skips when the lock or the engine's own lock is
  held. The real paths take the engine lock first and then the GPU lock per step, so a pass that held
  the GPU lock and then waited on the engine lock could deadlock: it never waits on either.
- Sends nothing to any client, never strikes, never changes the default voice.

Imports nothing from server.py: it reads the Service it is given (connections, last_conversation,
loading, stt, tts, gpu.pool, tts_gpu.pool).
"""

from __future__ import annotations

import asyncio
import logging
import statistics
import time
from dataclasses import dataclass

import numpy as np

try:
    from .engines import STT_RATE
except ImportError:  # run as a script
    from engines import STT_RATE  # type: ignore

log = logging.getLogger("laptop_voice")

STT_WARM_MS = 500          # 6 Kyutai model steps of silence (the resampler holds 2 ms back): no flush
STARTUP_STT_MS = 1000
TTS_WARM_TEXT = "Ready."
QUIET_S = 5.0              # a conversation started or ended this recently counts as open
SLOW_MS = 1500             # a pass slower than this is logged (a cold pass)
SUMMARY_S = 3600.0


def stt_pass(stt, ms: int) -> None:
    """Silence through the STT path the phone uses: the streaming session when the engine has one."""
    pcm = np.zeros(STT_RATE * ms // 1000, dtype=np.int16)
    if hasattr(stt, "open_stream"):
        stt.open_stream("en").feed(pcm)
    else:
        stt.transcribe(pcm, "en")


def tts_pass(tts, text: str = TTS_WARM_TEXT) -> None:
    """Synthesis up to the first audio chunk (every model part has run by then), then stop. Closing the
    generator early is the same exit a cancelled real stream takes."""
    gen = tts.synth(text, tts.default_voice, 1.0)
    try:
        next(gen, None)
    finally:
        gen.close()


def _try_locked(engine, fn, *args) -> float | None:
    """On the engine's worker thread: run fn holding the GPU lock, or return None without waiting."""
    own = getattr(engine, "lock", None)
    if own is not None and getattr(own, "locked", lambda: False)():
        return None
    gpu = getattr(engine, "gpu_lock", None)
    if gpu is not None and not gpu.acquire(blocking=False):
        return None
    try:
        t = time.perf_counter()
        fn(engine, *args)
        return time.perf_counter() - t
    finally:
        if gpu is not None:
            gpu.release()


def idle(svc) -> bool:
    return (not svc.loading and svc.stt is not None and svc.tts is not None and svc.connections == 0
            and time.monotonic() - svc.last_conversation >= QUIET_S)


@dataclass
class Pass:
    stt_ms: int
    tts_ms: int | None  # None: a conversation began between the halves


async def warm_once(svc) -> Pass | None:
    """One pass, or None when skipped. The idle check and the submit happen with no await between
    them, and a conversation that starts after the submit queues behind the pass on the same pool."""
    if not idle(svc):
        return None
    loop = asyncio.get_running_loop()
    s = await loop.run_in_executor(svc.gpu.pool, _try_locked, svc.stt, stt_pass, STT_WARM_MS)
    if s is None:
        return None
    if not idle(svc):
        return Pass(round(s * 1000), None)
    t = await loop.run_in_executor(svc.tts_gpu.pool, _try_locked, svc.tts, tts_pass)
    return Pass(round(s * 1000), None if t is None else round(t * 1000))


class KeepWarm:
    def __init__(self, svc, interval_s: float, slow_ms: int = SLOW_MS, summary_s: float = SUMMARY_S):
        self.svc, self.interval_s, self.slow_ms, self.summary_s = svc, interval_s, slow_ms, summary_s
        self.passes = self.skipped = self.failed = 0
        self.ms: list[int] = []
        self.next_summary = time.monotonic() + summary_s

    async def tick(self) -> None:
        try:
            p = await warm_once(self.svc)
        except Exception as exc:  # noqa: BLE001 - a failed pass must never end the task or the service
            self.failed += 1
            log.warning("keep-warm pass failed: %s", type(exc).__name__)
            p = None
        else:
            if p is None:
                self.skipped += 1
            else:
                self.passes += 1
                total = p.stt_ms + (p.tts_ms or 0)
                self.ms.append(total)
                if p.stt_ms > self.slow_ms or (p.tts_ms or 0) > self.slow_ms:
                    log.warning("keep-warm slow: stt_ms=%d tts_ms=%s", p.stt_ms, p.tts_ms)
        if time.monotonic() >= self.next_summary:
            self.next_summary = time.monotonic() + self.summary_s
            log.info("keep-warm summary: passes=%d skipped=%d failed=%d median_ms=%s max_ms=%s", self.passes,
                     self.skipped, self.failed, round(statistics.median(self.ms)) if self.ms else None,
                     max(self.ms) if self.ms else None)
            self.passes = self.skipped = self.failed = 0
            self.ms = []

    async def run(self) -> None:
        log.info("keep-warm on: every %g s while no conversation is open", self.interval_s)
        while True:
            await asyncio.sleep(self.interval_s)
            await self.tick()
