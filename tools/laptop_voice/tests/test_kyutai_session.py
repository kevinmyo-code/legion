"""The Kyutai STT session's turn logic (kyutai_core.KyutaiSttSession) on scripted model steps: no GPU,
no weights, no running service. The model step is replaced by a script of (piece, pause prob) pairs,
so this pins what the session does with them: the deferred flush and the maybe_end events
(2026-11-15), and that the default path is unchanged.

Run from tools/:  python -m pytest laptop_voice/tests -q   (skipped where torch/moshi are not installed)
"""

from __future__ import annotations

import sys
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


def test_both_kyutai_engines_name_the_one_gpu_lock_for_keep_warm():
    """Keep-warm try-acquires engine.gpu_lock; a missing or different lock would let a warm pass run
    beside a real model step (the CUDA graph capture crash GPU_LOCK exists to prevent)."""
    from laptop_voice import kyutai as K
    assert K.KyutaiStt.gpu_lock is core.GPU_LOCK
    assert K.KyutaiTts.gpu_lock is core.GPU_LOCK


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
