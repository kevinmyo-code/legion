"""The Kyutai STT session's turn logic (kyutai_core.KyutaiSttSession) on scripted model steps: no GPU,
no weights, no running service. The model step is replaced by a script of (piece, pause prob) pairs,
so this pins what the session does with them: the deferred flush and the maybe_end events
(2026-11-15), and that the default path is unchanged.

Run from tools/:  python -m pytest laptop_voice/tests -q   (skipped where torch/moshi are not installed)
"""

from __future__ import annotations

import sys
import threading
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))

pytest.importorskip("torch")
pytest.importorskip("moshi")

from laptop_voice import kyutai_core as core  # noqa: E402


class _Resets:
    def reset_streaming(self):
        pass


class _Eng:
    """Only what KyutaiSttSession reads from its engine; never touches CUDA."""

    def __init__(self, pause_threshold=0.6):
        self.mimi, self.lm_gen = _Resets(), _Resets()
        self.ignore_steps, self.pause_threshold = 0, pause_threshold
        self.delay_s, self.flush_extra_s = 0.0, 0.4


def session(script, **kw) -> core.KyutaiSttSession:
    s = core.KyutaiSttSession(_Eng(), **kw)
    steps = iter(script)

    def step(frame):
        s.steps += 1  # the real step counts too (the session ignores its first steps)
        return next(steps, (None, 1.0))  # past the script: silence, certain pause

    s._step = step
    return s


SPEECH = [(" set", 0.0), (" a", 0.1), (" timer", 0.1), (None, 0.4), (None, 0.7)]
FLUSH = [(" now", None), (".", None), (None, None), (None, None), (None, None)]


def feed_all(s, frames=12):
    out = []
    for _ in range(frames):
        out += s.feed(np.zeros(1280, np.int16))
    return out


def test_default_path_flushes_inside_feed_as_before():
    s = session(SPEECH + FLUSH)
    ev = feed_all(s)
    kinds = [k for k, _ in ev]
    assert "maybe_end" not in kinds and "eot_pending" not in kinds
    assert ("end_of_turn", "set a timer now.") in ev


def test_deferred_flush_sends_text_first_then_completes_the_same_turn():
    s = session(SPEECH + FLUSH, defer_flush=True)
    ev = feed_all(s)
    kinds = [k for k, _ in ev]
    assert kinds.count("eot_pending") == 1 and "end_of_turn" not in kinds
    assert ev[kinds.index("eot_pending")] == ("eot_pending", "set a timer")
    assert ev[-1][0] == "eot_pending", "feed stops at the crossing"
    assert s.complete_turn() == "set a timer now."
    assert s.pieces == [] and not s.turn_has_words and not s.pending_eot


def test_maybe_threshold_fires_once_per_stretch_of_words():
    script = [(" set", 0.0), (" a", 0.5), (None, 0.55), (" timer", 0.1), (None, 0.45), (None, 0.7)] + FLUSH
    s = session(script, defer_flush=True, maybe_threshold=0.4)
    ev = feed_all(s)
    maybes = [v for k, v in ev if k == "maybe_end"]
    assert maybes == ["set a", "set a timer"], "new words re-arm it; no repeat inside one pause"
    assert [k for k, _ in ev][-1] == "eot_pending"


def test_no_maybe_before_any_words():
    s = session([(None, 0.5), (None, 0.5), (" hi", 0.0), (None, 0.7)] + FLUSH, defer_flush=True,
                maybe_threshold=0.4)
    ev = feed_all(s)
    assert [v for k, v in ev if k == "maybe_end"] == []
    assert ("eot_pending", "hi") in ev


def test_kyutai_stt_names_the_one_gpu_lock_for_keep_warm():
    """Keep-warm's STT half try-acquires stt.gpu_lock; a missing or different lock would let a warm
    pass run beside a real model step (the CUDA graph capture crash GPU_LOCK exists to prevent). The
    TTS half goes through KyutaiTts.try_warm instead (engine lock first, test below)."""
    from laptop_voice import kyutai as K
    assert K.KyutaiStt.gpu_lock is core.GPU_LOCK
    assert hasattr(K.KyutaiTts, "try_warm")


def test_tts_codebooks_come_from_the_launcher_flag_only_when_set(monkeypatch):
    from laptop_voice import kyutai as K
    seen = {}
    monkeypatch.setattr(core.KyutaiTts, "__init__", lambda self, *a, **kw: seen.update(kw))
    monkeypatch.delenv("LEGION_VOICE_KYUTAI_TTS_NQ", raising=False)
    K.KyutaiTts()
    assert "n_q" not in seen, "unset: the model's own default (32)"
    monkeypatch.setenv("LEGION_VOICE_KYUTAI_TTS_NQ", "16")
    K.KyutaiTts()
    assert seen["n_q"] == 16


class _Watched:
    """A real lock that reports each acquire, and turns a blocking wait over 2 s into a recorded
    deadlock instead of a hung test."""

    def __init__(self, name, inner, on_acquire, stuck):
        self.name, self.inner, self.on_acquire, self.stuck = name, inner, on_acquire, stuck

    def acquire(self, blocking=True, timeout=-1):
        if blocking and timeout < 0:
            ok = self.inner.acquire(timeout=2)
            if not ok:
                self.stuck.append((self.name, threading.current_thread().name))
                raise RuntimeError(f"waited over 2 s for {self.name}: deadlock")
        else:
            ok = self.inner.acquire(blocking, timeout)
        if ok:
            self.on_acquire(self)
        return ok

    def release(self):
        self.inner.release()

    def __enter__(self):
        self.acquire()
        return self

    def __exit__(self, *exc):
        self.release()


def test_tts_warm_takes_the_engine_lock_before_the_gpu_lock_and_never_waits():
    """Review F1 (2026-11-20). Real streamed speech runs on its own thread and takes the engine lock,
    then the GPU lock per step. The warm pass must take them in that same order: if it held the GPU
    lock and then waited for the engine lock, a stream starting in between would wait on the GPU
    lock forever and the whole service would hang. The barrier forces exactly that interleaving: the
    stream thread grabs the engine lock right after the warm pass takes its FIRST lock."""
    from laptop_voice import keep_warm as W

    stuck, warm_started, stream_has_engine = [], threading.Event(), threading.Event()
    fired = []

    def on_acquire(lock):
        me = threading.current_thread().name
        if me == "stream" and lock.name == "engine":
            stream_has_engine.set()
        if me == "warm" and not fired:
            fired.append(lock.name)
            warm_started.set()
            stream_has_engine.wait(0.3)  # give the stream its chance at the engine lock

    eng = core.KyutaiTts.__new__(core.KyutaiTts)  # no model: only the locking code is real
    eng.lock = _Watched("engine", threading.Lock(), on_acquire, stuck)
    eng.default_voice = "dev"
    gpu = _Watched("gpu", threading.RLock(), on_acquire, stuck)
    eng.gpu_lock = gpu

    def held(text_iter, voice, speed):  # stands in for the model loop: GPU lock per step
        for _ in text_iter:
            for _ in range(2):
                with gpu:
                    pass
                yield np.zeros(1920, np.int16)

    eng._synth_held = held
    results = {}

    def stream():
        warm_started.wait(2)
        try:
            results["stream_chunks"] = len(list(eng.synth_stream(["hello there"])))
        except RuntimeError as exc:
            results["stream_error"] = str(exc)

    def warm():
        try:
            results["warm"] = W.tts_half(eng)
        except RuntimeError as exc:
            results["warm_error"] = str(exc)

    threads = [threading.Thread(target=stream, name="stream"), threading.Thread(target=warm, name="warm")]
    for t in threads:
        t.start()
    for t in threads:
        t.join(10)
    assert stuck == [], f"a thread waited on a lock the other held: {stuck}"
    assert fired == ["engine"], "the warm pass's first lock is the engine lock"
    assert results.get("stream_chunks") == 2
    assert "warm" in results
