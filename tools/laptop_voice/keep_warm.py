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
- Never waits for a lock, and takes locks in the real paths' order (engine lock, then GPU lock):
  - STT half: the streaming path takes no engine lock, only the GPU lock per step; the pass
    try-acquires the GPU lock and holds it across its few steps.
  - TTS half: real speech takes the engine lock, then the GPU lock per step (a streamed line on its
    own thread). The pass try-acquires the ENGINE lock first (the engine's try_warm) and lets the
    normal steps take the GPU lock. Holding the GPU lock while waiting for the engine lock could
    deadlock against a streamed line (review F1, 2026-11-20).
- Sends nothing to any client, never strikes, never changes the default voice.
- The hourly summary says why passes were skipped, so a lock that never frees shows up there.

Imports nothing from server.py: it reads the Service it is given (connections, last_conversation,
loading, stt, tts, gpu.pool, tts_gpu.pool).
"""

from __future__ import annotations

import asyncio
import collections
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

# Why a pass (or its TTS half) did not run; the hourly summary counts each.
CONVERSATION, LOADING, GPU_BUSY, TTS_BUSY = "conversation_open", "loading", "gpu_busy", "tts_busy"


def stt_pass(stt, ms: int) -> None:
    """Silence through the STT path the phone uses: the streaming session when the engine has one."""
    pcm = np.zeros(STT_RATE * ms // 1000, dtype=np.int16)
    if hasattr(stt, "open_stream"):
        stt.open_stream("en").feed(pcm)
    else:
        stt.transcribe(pcm, "en")


def tts_pass(tts, text: str = TTS_WARM_TEXT) -> bool:
    """Synthesis with the default voice up to the first audio chunk (every model part has run by
    then). False when the engine was busy and nothing ran. An engine with try_warm (Kyutai) takes its
    own lock without waiting; others have no lock to wait on."""
    try_warm = getattr(tts, "try_warm", None)
    if try_warm is not None:
        return try_warm(text)
    gen = tts.synth(text, tts.default_voice, 1.0)
    try:
        next(gen, None)
    finally:
        gen.close()
    return True


def stt_half(stt) -> float | str:
    """On the STT worker thread: seconds the pass took, or why it was skipped."""
    gpu = getattr(stt, "gpu_lock", None)
    if gpu is not None and not gpu.acquire(blocking=False):
        return GPU_BUSY
    try:
        t = time.perf_counter()
        stt_pass(stt, STT_WARM_MS)
        return time.perf_counter() - t
    finally:
        if gpu is not None:
            gpu.release()


def tts_half(tts) -> float | str:
    """On the TTS worker thread: seconds the pass took, or why it was skipped."""
    t = time.perf_counter()
    if not tts_pass(tts):
        return TTS_BUSY
    return time.perf_counter() - t


def why_not_idle(svc) -> str | None:
    if svc.loading or svc.stt is None or svc.tts is None:
        return LOADING
    if svc.connections or time.monotonic() - svc.last_conversation < QUIET_S:
        return CONVERSATION
    return None


@dataclass
class Pass:
    stt_ms: int | None = None   # None: the STT half did not run
    tts_ms: int | None = None   # None: the TTS half did not run
    skipped: str | None = None  # why a half did not run

    @property
    def ran(self) -> bool:
        return self.stt_ms is not None


async def warm_once(svc) -> Pass:
    """One pass. The idle check and the submit happen with no await between them, and a conversation
    that starts after the submit queues behind the pass on the same pool."""
    reason = why_not_idle(svc)
    if reason:
        return Pass(skipped=reason)
    loop = asyncio.get_running_loop()
    s = await loop.run_in_executor(svc.gpu.pool, stt_half, svc.stt)
    if isinstance(s, str):
        return Pass(skipped=s)
    done = Pass(stt_ms=round(s * 1000))
    done.skipped = why_not_idle(svc)
    if done.skipped:
        return done
    t = await loop.run_in_executor(svc.tts_gpu.pool, tts_half, svc.tts)
    if isinstance(t, str):
        done.skipped = t
    else:
        done.tts_ms = round(t * 1000)
    return done


class KeepWarm:
    def __init__(self, svc, interval_s: float, slow_ms: int = SLOW_MS, summary_s: float = SUMMARY_S):
        self.svc, self.interval_s, self.slow_ms, self.summary_s = svc, interval_s, slow_ms, summary_s
        self.passes = self.failed = 0
        self.skips: collections.Counter = collections.Counter()
        self.ms: list[int] = []
        self.next_summary = time.monotonic() + summary_s

    async def tick(self) -> None:
        try:
            p = await warm_once(self.svc)
        except Exception as exc:  # noqa: BLE001 - a failed pass must never end the task or the service
            self.failed += 1
            log.warning("keep-warm pass failed: %s", type(exc).__name__)
        else:
            if p.skipped:
                self.skips[p.skipped] += 1
            if p.ran:
                self.passes += 1
                self.ms.append(p.stt_ms + (p.tts_ms or 0))
                if p.stt_ms > self.slow_ms or (p.tts_ms or 0) > self.slow_ms:
                    log.warning("keep-warm slow: stt_ms=%d tts_ms=%s", p.stt_ms, p.tts_ms)
        if time.monotonic() >= self.next_summary:
            self.next_summary = time.monotonic() + self.summary_s
            skips = " ".join(f"skip_{k}={self.skips[k]}" for k in (CONVERSATION, LOADING, GPU_BUSY, TTS_BUSY))
            log.info("keep-warm summary: passes=%d failed=%d %s median_ms=%s max_ms=%s", self.passes,
                     self.failed, skips, round(statistics.median(self.ms)) if self.ms else None,
                     max(self.ms) if self.ms else None)
            self.passes = self.failed = 0
            self.skips.clear()
            self.ms = []

    async def run(self) -> None:
        log.info("keep-warm on: every %g s while no conversation is open", self.interval_s)
        while True:
            await asyncio.sleep(self.interval_s)
            await self.tick()
