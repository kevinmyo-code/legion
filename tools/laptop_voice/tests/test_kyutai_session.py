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


# ---- word start times (tts.word, 2026-11-21)
class _ScriptedTtsModel:
    """Stands in for the moshi model inside KyutaiTts._synth_held: no CUDA, no weights. Each step
    consumes the next queued word at that step (as moshi's StateMachine.process does into
    State.transcript) and, like the real stream, yields audio only from step `lead` on: the frames
    before it hold the -1 "zero" token and are skipped (kyutai_core._step)."""

    FRAME = 1920  # samples per 80 ms frame at 24 kHz

    def __init__(self, eng, consume_every: int = 3, lead: int = 4):
        from types import SimpleNamespace
        self.consume_every, self.lead = consume_every, lead
        self.state = SimpleNamespace(entries=[], end_step=None, transcript=[])
        self.st = {"offset": 0, "state": self.state}
        eng.m = SimpleNamespace(mimi=SimpleNamespace(frame_rate=12.5), delay_steps=0,
                                lm=SimpleNamespace(delays=[0]), machine=SimpleNamespace(second_stream_ahead=0))
        eng.persistent = True
        eng.default_voice = "ex03_default"
        eng.lock = threading.Lock()
        eng._acquire = lambda voice, speed: (None, self.st, None)
        eng._entries = lambda w, first: [w]
        eng._step = self.step

    def step(self, g, st):
        s = st["offset"]
        if s % self.consume_every == 0 and self.state.entries:
            self.state.transcript.append((self.state.entries.pop(0), s))
        elif not self.state.entries and self.state.end_step is None:
            self.state.end_step = s
        st["offset"] += 1
        return np.zeros(self.FRAME, np.int16) if s >= self.lead else None


def test_tts_reports_each_word_at_its_consumption_step_plus_the_late_bias():
    """A word the model takes at text step s starts at about s frames into the id's audio: moshi
    trains the audio delay_steps behind the text, and every frame before delay_steps is skipped, so
    the stream's frame k lines up with text step k. One frame is added so a stamp is never early."""
    eng = core.KyutaiTts.__new__(core.KyutaiTts)
    model = _ScriptedTtsModel(eng)
    got = []
    chunks = list(eng.synth_stream(["Your train ", "leaves at nine."], on_word=lambda w, ms: got.append((w, ms))))
    steps = [s for _, s in model.state.transcript]
    assert [w for w, _ in got] == ["Your", "train", "leaves", "at", "nine."]
    assert [ms for _, ms in got] == [(s + core.WORD_LAG_FRAMES) * 80 for s in steps]
    assert core.WORD_LAG_FRAMES >= 1, "biased late: an early stamp would count an unheard word as heard"
    assert chunks, "audio still flows"


def test_tts_without_a_word_callback_is_unchanged():
    eng = core.KyutaiTts.__new__(core.KyutaiTts)
    _ScriptedTtsModel(eng)
    assert len(list(eng.synth("Twelve minutes, running."))) > 0


def test_text_stream_passes_the_word_callback_to_the_model_thread():
    from laptop_voice import kyutai as K
    eng = K.KyutaiTts.__new__(K.KyutaiTts)
    _ScriptedTtsModel(eng)
    got = []
    sess = eng.open_text_stream("ex03_default", 1.0, on_word=lambda w, ms: got.append((w, ms)))
    assert isinstance(sess, K._TextStream)
    sess.push("Turn left here.")
    audio = sess.finish()
    assert audio and [w for w, _ in got] == ["Turn", "left", "here."]


# ---- Alfred's moods (2026-11-20): one speaker (Expresso ex03), one reference clip per mood
EXPRESSO = __import__("re").compile(r"expresso/(ex\d\d)-(ex\d\d)_([a-z-]+)_\d{3}_channel([12])_\d+s\.wav")
# key mood -> the Expresso style in the file name, where they differ
STYLE_OF = {"sympathetic": "sad-sympathetic"}
EX03_MOODS = {"default", "happy", "sympathetic", "calm", "awe", "sarcastic", "whisper", "confused", "sleepy",
              "laughing", "fast", "enunciated", "projected", "narration"}


def test_every_expresso_key_is_its_own_speakers_channel():
    """Expresso two-speaker files hold speaker A on channel1 and speaker B on channel2 (exA-exB_...):
    a wrong channel is a different person's voice."""
    for key, path in core.VOICES.items():
        m = EXPRESSO.fullmatch(path)
        if not key.startswith("ex"):
            assert m is None, key
            continue
        assert m, f"{key}: {path} is not an Expresso reference clip"
        a, b, style, channel = m.groups()
        speaker, mood = key.split("_", 1)
        assert (a if channel == "1" else b) == speaker, f"{key}: {path} is the other speaker's channel"
        assert style == STYLE_OF.get(mood, mood), f"{key}: {path} is style {style}"


def test_ex03_has_every_assistant_mood_and_none_that_would_be_wrong():
    ex03 = {k.split("_", 1)[1] for k in core.VOICES if k.startswith("ex03_")}
    assert ex03 == EX03_MOODS
    for wrong in ("angry", "disgusted", "desire", "child", "animal", "nonverbal"):
        assert not any(wrong in p for k, p in core.VOICES.items() if k.startswith("ex03_")), wrong


def test_alfreds_default_voice_is_ex03_default_and_the_old_one_stays_allowed():
    import inspect
    assert inspect.signature(core.KyutaiTts.__init__).parameters["default_voice"].default == "ex03_default"
    assert "ears_p039" in core.VOICES  # switching back is one launcher flag


def test_default_voice_comes_from_the_launcher_flag_only_when_set(monkeypatch):
    from laptop_voice import kyutai as K
    seen = {}
    monkeypatch.setattr(core.KyutaiTts, "__init__", lambda self, *a, **kw: seen.update(kw))
    monkeypatch.delenv("LEGION_VOICE_KYUTAI_VOICE", raising=False)
    K.KyutaiTts()
    assert "default_voice" not in seen, "unset: the code default"
    monkeypatch.setenv("LEGION_VOICE_KYUTAI_VOICE", "ears_p039")
    K.KyutaiTts()
    assert seen["default_voice"] == "ears_p039"
    monkeypatch.setenv("LEGION_VOICE_KYUTAI_VOICE", "ex03_angry")
    with pytest.raises(SystemExit):
        K.KyutaiTts()
