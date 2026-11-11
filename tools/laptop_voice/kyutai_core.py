"""Kyutai STT + TTS engine adapters for the laptop voice service (native Windows + CUDA, moshi PyTorch).

Tested with: Python 3.12, torch 2.9.1+cu128, moshi 0.2.13, RTX 4060 Laptop 8 GB, driver 621.01.
Install (own venv):
    uv pip install torch==2.9.1 torchaudio==2.9.1 --index-url https://download.pytorch.org/whl/cu128
    uv pip install moshi==0.2.13
Set HF_HOME to a private cache before import. Weights: kyutai/stt-1b-en_fr-candle (the repo whose
config has the 4 semantic-VAD heads; the plain kyutai/stt-1b-en_fr PyTorch repo has none),
kyutai/tts-1.6b-en_fr, voice embeddings from kyutai/tts-voices.

Sample rates: the phone sends 16 kHz int16 mic audio; Kyutai (Mimi codec) runs at 24 kHz.
  - STT: 16k -> 24k inside the session with a stateful windowed-sinc resampler
    (32 samples of left context, 32 samples (2 ms) of held-back lookahead).
  - TTS: emits 24 kHz int16, 1920 samples (80 ms) per chunk. Resample on the phone or here if 16k is wanted.

Threading: each engine owns one model with batch size 1. Use one session / one synth at a time
(engines hold a lock). STT and TTS can run concurrently from two threads (separate models, separate
CUDA work), but they share the GPU, so each slows the other a bit.
"""
from __future__ import annotations

import contextlib
import math
import re
import threading
import typing as tp
from dataclasses import dataclass, field

import numpy as np
import torch
import torchaudio

from moshi.conditioners import dropout_all_conditions
from moshi.models import LMGen
from moshi.models.loaders import CheckpointInfo
from moshi.models.tts import DEFAULT_DSM_TTS_REPO, TTSModel, script_to_entries
from moshi.modules.transformer import StreamingMultiheadAttention

DEVICE = "cuda"
# One lock around every model step of both engines. Needed: moshi captures CUDA graphs lazily, and a
# capture in one thread while the other thread launches kernels fails with
# cudaErrorStreamCaptureInvalidated (measured: STT thread + TTS thread crashed without it).
GPU_LOCK = threading.RLock()


# --------------------------------------------------------------------------- STT
@dataclass
class SttResult:
    text: str
    lang: str | None
    lang_prob: float | None
    words: list[tuple[str, float]] = field(default_factory=list)  # (piece, time_s) of emission


class _Resampler16to24:
    """Stateful 16k -> 24k (ratio 3/2) resampler: windowed sinc with overlap, so chunk edges are clean."""
    L = 32  # left context (16k samples)
    H = 32  # held-back lookahead (16k samples) = 2 ms latency

    def __init__(self):
        self.buf = np.zeros(self.L, np.float32)  # starts with L samples of silence as context

    def push(self, x16: np.ndarray, final: bool = False) -> np.ndarray:
        self.buf = np.concatenate([self.buf, x16.astype(np.float32)])
        avail = len(self.buf) - self.L - (0 if final else self.H)
        avail -= avail % 2  # keep 16k chunk length even so 24k output length is integer
        if avail <= 0:
            return np.zeros(0, np.float32)
        seg = self.buf[: self.L + avail + (0 if final else self.H)]
        y = torchaudio.functional.resample(torch.from_numpy(seg)[None], 16000, 24000)[0].numpy()
        out = y[self.L * 3 // 2: (self.L + avail) * 3 // 2]
        self.buf = self.buf[avail:]  # keep last L (+H) samples as context
        return out


class KyutaiSttSession:
    """Streaming session. feed() 16 kHz int16 chunks of any size; returns events:
        ("partial", text_so_far)      when new word pieces arrive
        ("pause", prob)               every 80 ms step, semantic-VAD pause probability (head 2, class 0)
        ("end_of_turn", final_text)   once per turn, after pause prob > threshold and a fast flush
    finish() flushes and returns the final text of the current turn.
    """

    def __init__(self, eng: "KyutaiStt"):
        self.e = eng
        self.rs = _Resampler16to24()
        self.pcm24 = np.zeros(0, np.float32)
        self.pieces: list[str] = []
        self.steps = 0
        self.turn_has_words = False
        self.pause = 0.0
        with GPU_LOCK:
            eng.mimi.reset_streaming()
            eng.lm_gen.reset_streaming()

    # one 80 ms model step; returns (piece or None, pause_prob or None)
    def _step(self, frame24: np.ndarray):
        e = self.e
        with GPU_LOCK:  # every CUDA touch, including the host-to-device copy (the line that crashed)
            x = torch.from_numpy(frame24).to(DEVICE)[None, None]
            codes = e.mimi.encode(x)
            out = e.lm_gen.step_with_extra_heads(codes)
            self.steps += 1
            if out is None:
                return None, None
            tt, heads = out
            t = int(tt[0, 0, 0].item())
            pr = float(heads[e.vad_head][0, 0, 0].item()) if heads else None
        piece = None
        if t not in (0, e.pad_id):
            piece = e.tok.id_to_piece(t).replace("▁", " ")
        return piece, pr

    def _text(self) -> str:
        return "".join(self.pieces).strip()

    def _flush(self) -> None:
        """Feed silence as fast as possible until the text has caught up (Unmute's trick, extended).

        Always delay+1 frames; then keep going until the text ends with sentence punctuation and two
        steps in a row add nothing, up to flush_extra_s more. Measured 2026-11-10 (STT-only sweep): the
        model writes a trailing number late, as digits, up to 1.6 s after speech ends ("4417" at +0.94 s,
        "14th" at +1.55 s), and its pause head can cross 0.6 at the end of speech, so a fixed delay+1
        flush cut "ending in 4417" to "ending in 44". The model ends every sentence with . ? or ! as its
        last piece; the two quiet steps keep "4.45" from stopping at its dot."""
        e = self.e
        n_min = int(math.ceil(e.delay_s * 12.5)) + 1
        n_max = n_min + int(math.ceil(e.flush_extra_s * 12.5))
        z = np.zeros(1920, np.float32)
        quiet = 0
        for i in range(n_max):
            piece, _ = self._step(z)
            if piece and piece.strip():
                self.pieces.append(piece)
                quiet = 0
            else:
                if piece:
                    self.pieces.append(piece)
                quiet += 1
            if i + 1 >= n_min and quiet >= 2 and self._text().endswith((".", "?", "!")):
                break
        self.flush_steps = i + 1

    def feed(self, pcm_int16_16k: np.ndarray) -> list[tuple[str, tp.Any]]:
        ev: list[tuple[str, tp.Any]] = []
        x = pcm_int16_16k.astype(np.float32) / 32768.0
        self.pcm24 = np.concatenate([self.pcm24, self.rs.push(x)])
        with torch.no_grad():
            while len(self.pcm24) >= 1920:
                fr, self.pcm24 = self.pcm24[:1920], self.pcm24[1920:]
                piece, pr = self._step(fr)
                if piece:
                    self.pieces.append(piece)
                    self.turn_has_words = True
                    ev.append(("partial", self._text()))
                if pr is not None and self.steps > self.e.ignore_steps:
                    self.pause = pr
                    ev.append(("pause", pr))
                    if self.turn_has_words and pr > self.e.pause_threshold:
                        self._flush()
                        ev.append(("end_of_turn", self._text()))
                        self.pieces, self.turn_has_words = [], False
        return ev

    def finish(self) -> str:
        with torch.no_grad():
            tail = self.rs.push(np.zeros(0, np.float32), final=True)
            self.pcm24 = np.concatenate([self.pcm24, tail])
            if len(self.pcm24):
                fr = np.pad(self.pcm24, (0, 1920 - len(self.pcm24) % 1920 if len(self.pcm24) % 1920 else 0))
                for i in range(0, len(fr), 1920):
                    piece, _ = self._step(fr[i:i + 1920])
                    if piece:
                        self.pieces.append(piece)
                self.pcm24 = np.zeros(0, np.float32)
            self._flush()
        text = self._text()
        self.pieces, self.turn_has_words = [], False
        return text


class KyutaiStt:
    name = "kyutai-stt-1b-en_fr"
    languages = ["en", "fr"]

    def __init__(self, repo: str = "kyutai/stt-1b-en_fr-candle", pause_threshold: float = 0.6,
                 vad_head: int = 2, ignore_steps: int = 12, flush_extra_s: float = 1.6):
        info = CheckpointInfo.from_hf_repo(repo)
        self.mimi = info.get_mimi(device=DEVICE)
        self.tok = info.get_text_tokenizer()
        self.lm = info.get_moshi(device=DEVICE, dtype=torch.bfloat16)
        self.lm_gen = LMGen(self.lm, temp=0, temp_text=0.0)
        self.pad_id = info.raw_config.get("text_padding_token_id", 3)
        self.delay_s = (info.stt_config or {}).get("audio_delay_seconds", 0.5)
        self.pause_threshold, self.vad_head, self.ignore_steps = pause_threshold, vad_head, ignore_steps
        self.flush_extra_s = flush_extra_s
        self.mimi.streaming_forever(1)
        self.lm_gen.streaming_forever(1)
        self.lock = threading.Lock()
        if "2.6b" in repo:
            self.name, self.languages = "kyutai-stt-2.6b-en", ["en"]

    def start(self) -> KyutaiSttSession:
        """One live session at a time (batch size 1). Caller holds it for the whole conversation."""
        return KyutaiSttSession(self)

    def transcribe(self, pcm_int16_16k: np.ndarray, lang: str | None = None) -> SttResult:
        """Whole-utterance convenience call. The model has no language-ID output; it transcribes en/fr
        as heard. lang is echoed back (or None), lang_prob is None."""
        with self.lock:
            s = self.start()
            s.feed(pcm_int16_16k)
            text = s.finish()
        return SttResult(text=text, lang=lang, lang_prob=None)


# --------------------------------------------------------------------------- TTS
# Curated voices (path in kyutai/tts-voices). Licence matters: expresso = CC BY-NC 4.0 (non-commercial).
VOICES = {
    "dev": "unmute-prod-website/developer-1.mp3",                       # CC0 (Kyutai's own recording)
    "vctk_p226": "vctk/p226_023.wav",                                   # CC BY 4.0, English male
    "ex03_calm": "expresso/ex03-ex01_calm_001_channel1_1143s.wav",     # CC BY-NC 4.0
    "ex01_calm": "expresso/ex03-ex01_calm_001_channel2_1081s.wav",     # CC BY-NC 4.0
    # style variants of the same speakers (emotion comes from the reference clip, not from tags)
    "ex03_happy": "expresso/ex03-ex01_happy_001_channel1_334s.wav",
    "ex03_sympathetic": "expresso/ex03-ex02_sad-sympathetic_001_channel1_454s.wav",
    "ex01_happy": "expresso/ex03-ex01_happy_001_channel2_257s.wav",
    "ex01_sympathetic": "expresso/ex04-ex01_sad-sympathetic_001_channel2_346s.wav",
}


class KyutaiTts:
    name = "kyutai-tts-1.6b-en_fr"
    sample_rate = 24000

    def __init__(self, n_q: int = 32, temp: float = 0.6, cfg_coef: float = 2.0, default_voice: str = "dev",
                 persistent: bool = True):
        """n_q: codebooks generated per 80 ms frame (32 = best audio; 16-24 = faster, see results).
        persistent: keep one LMGen in streaming mode and reset it between utterances, so CUDA graphs
        are captured once instead of per utterance (cuts time to first audio)."""
        info = CheckpointInfo.from_hf_repo(DEFAULT_DSM_TTS_REPO)
        self.m = TTSModel.from_checkpoint_info(info, n_q=n_q, temp=temp, device=DEVICE)
        self.cfg_coef = cfg_coef
        self.voices = list(VOICES)
        self.default_voice = default_voice
        self.persistent = persistent
        self._cond_cache: dict[str, tp.Any] = {}
        self._active = None  # (voice, LMGen, st, exit_stack)
        self.lock = threading.Lock()
        with GPU_LOCK, torch.no_grad():  # load every allowlisted voice now: a missing file fails at start
            for v in VOICES:
                self._cond(v)
        if persistent:
            self.m.mimi.streaming_forever(1)
            with torch.no_grad():  # warm up: captures CUDA graphs for the default voice
                for _ in self.synth("Ready.", default_voice):
                    pass

    def set_n_q(self, n_q: int) -> None:
        """Change codebooks per frame at runtime (<= the n_q loaded). Rebuilds the LMGen on next use."""
        with self.lock, GPU_LOCK:
            if self._active is not None:
                self._active[3].close()
                self._active = None
            self.m.n_q = n_q

    def _cond(self, voice: str):
        if voice not in self._cond_cache:
            p = VOICES.get(voice, voice)
            path = p if p.endswith(".safetensors") else self.m.get_voice_path(p)
            self._cond_cache[voice] = self.m.make_condition_attributes([path], cfg_coef=self.cfg_coef)
        return self._cond_cache[voice]

    def _cond_tensors(self, voice: str):
        m = self.m
        attrs = [self._cond(voice)]
        if m.cfg_coef != 1.0:  # not the case for tts-1.6b (CFG-distilled), kept for other checkpoints
            attrs = attrs + dropout_all_conditions(attrs)
        return m.lm.condition_provider(m.lm.condition_provider.prepare(attrs))

    def _acquire(self, voice: str, speed: float):
        """Returns (LMGen, st) ready for a new utterance."""
        m = self.m
        m.padding_bonus = float(np.clip((1.0 - speed) * 4.0, -2, 2))  # >0 slower, <0 faster (approx.)
        if not self.persistent:
            g, st = self._gen(voice)
            if self._active is not None:  # leaving persistent mode: free its streaming state first
                self._active[3].close(); self._active = None
            stack = g.streaming(1); stack.__enter__()
            return g, st, stack
        if self._active is not None:
            v0, g, st, stack = self._active
            if v0 != voice:
                # swap the speaker conditioning in place (same shapes) so captured CUDA graphs stay valid
                ct = self._cond_tensors(voice)
                s = g._streaming_state
                new_cross = m.lm.fuser.get_cross(ct)
                new_sum = m.lm.fuser.get_sum(ct)
                ok = (s.condition_cross is None or (new_cross is not None and new_cross.shape == s.condition_cross.shape)) and \
                     (s.condition_sum is None or (new_sum is not None and new_sum.shape == s.condition_sum.shape))
                if ok:
                    if s.condition_cross is not None:
                        s.condition_cross.copy_(new_cross.to(s.condition_cross.dtype))
                        # The cross-attention layers cache K/V computed from the first voice
                        # (k_cross / v_cross) and never read condition_cross again: without this
                        # the swap was a no-op and every voice spoke as the first one (measured).
                        # update_* writes in place, so the captured CUDA graphs stay valid.
                        for mod in m.lm.modules():
                            ms = getattr(mod, "_streaming_state", None)
                            if (isinstance(mod, StreamingMultiheadAttention) and mod.cross_attention
                                    and ms is not None and getattr(ms, "k_cross", None) is not None):
                                mod.update_streaming_cross_attention_src(s.condition_cross)
                    if s.condition_sum is not None:
                        s.condition_sum.copy_(new_sum.to(s.condition_sum.dtype))
                    self._active = (voice, g, st, stack)
                else:
                    stack.close(); self._active = None
        if self._active is None:
            g, st = self._gen(voice)
            stack = g.streaming(1); stack.__enter__()
            self._active = (voice, g, st, stack)
        _, g, st, stack = self._active
        g.reset_streaming()
        m.mimi.reset_streaming()
        st["offset"] = 0
        st["state"] = m.machine.new_state([])
        return g, st, None

    # ---- generation core: a step loop that yields audio as soon as frames exist
    def _gen(self, voice: str):
        m = self.m
        cond = self._cond_tensors(voice)
        st = {"offset": 0, "state": m.machine.new_state([])}

        def on_text_logits(tl):
            if m.padding_bonus:
                tl[..., m.machine.token_ids.pad] += m.padding_bonus
            return tl

        def on_audio(at):
            for q in range(at.shape[1]):
                if st["offset"] < m.lm.delays[q + m.lm.audio_offset] + m.delay_steps:
                    at[:, q] = m.machine.token_ids.zero

        def on_text(tt):
            out = [m.machine.process(st["offset"], st["state"], t)[0] for t in tt.tolist()]
            tt[:] = torch.tensor(out, dtype=torch.long, device=tt.device)

        m.lm.dep_q = m.n_q
        g = LMGen(m.lm, temp=m.temp, temp_text=m.temp, cfg_coef=m.cfg_coef, condition_tensors=cond,
                  on_text_logits_hook=on_text_logits, on_text_hook=on_text, on_audio_hook=on_audio,
                  cfg_is_masked_until=None, cfg_is_no_text=True)
        return g, st

    def _step(self, g, st) -> np.ndarray | None:
        m = self.m
        with GPU_LOCK:
            inp = torch.full((1, m.lm.n_q - m.lm.dep_q, 1), m.machine.token_ids.zero, dtype=torch.long, device=DEVICE)
            frame = g.step(inp)
            st["offset"] += 1
            if frame is not None and (frame != -1).all():
                pcm = m.mimi.decode(frame[:, 1:, :]).cpu().numpy()[0, 0]
                return (np.clip(pcm, -1, 1) * 32767).astype(np.int16)
        return None

    def _entries(self, text: str, first: bool):
        return script_to_entries(self.m.tokenizer, self.m.machine.token_ids, self.m.mimi.frame_rate, [text],
                                 multi_speaker=first and self.m.multi_speaker, padding_between=1)

    def synth(self, text: str, voice: str | None = None, speed: float = 1.0) -> tp.Iterator[np.ndarray]:
        """Whole text known. Yields 24 kHz int16 chunks (1920 samples) as soon as each exists."""
        yield from self.synth_stream([text], voice, speed)

    def synth_stream(self, text_iter: tp.Iterable[str], voice: str | None = None,
                     speed: float = 1.0) -> tp.Iterator[np.ndarray]:
        """Text arrives incrementally (LLM deltas of any size). Words are cut at whitespace; a word is
        sent to the model once the next whitespace arrives (or at end of stream). The model needs
        `second_stream_ahead` words queued beyond the one being spoken, so audio starts after
        ~2-3 words and stalls (no audio, client buffer underruns) if the LLM falls behind speech.
        For a queue.Queue, pass iter(q.get, None) and put None at the end."""
        voice = voice or self.default_voice
        with self.lock, torch.no_grad(), contextlib.ExitStack() as es:
            m = self.m
            with GPU_LOCK:  # setup touches CUDA (resets, conditioning tensors, in-place voice swap)
                if not self.persistent:
                    if m.mimi._streaming_state is None:
                        es.enter_context(m.mimi.streaming(1))
                    else:
                        m.mimi.reset_streaming()
                g, st, stack = self._acquire(voice, speed)
            if stack is not None:
                es.enter_context(stack)
            if True:
                buf, first = "", True

                def push_words(words):
                    nonlocal first
                    for w in words:
                        for e in self._entries(w, first):
                            st["state"].entries.append(e)
                        first = False
                        while len(st["state"].entries) > m.machine.second_stream_ahead:
                            pcm = self._step(g, st)
                            if pcm is not None:
                                yield pcm

                for delta in text_iter:
                    buf += delta
                    parts = re.split(r"(\s+)", buf)
                    # complete words are all but the last piece unless buf ends in whitespace
                    if buf and buf[-1].isspace():
                        done, buf = [p for p in parts if p.strip()], ""
                    else:
                        done, buf = [p for p in parts[:-1] if p.strip()], parts[-1]
                    yield from push_words(done)
                if buf.strip():
                    yield from push_words([buf.strip()])
                # drain
                while len(st["state"].entries) > 0 or st["state"].end_step is not None:
                    pcm = self._step(g, st)
                    if pcm is not None:
                        yield pcm
                for _ in range(m.delay_steps + max(m.lm.delays) + 8):
                    pcm = self._step(g, st)
                    if pcm is not None:
                        yield pcm


if __name__ == "__main__":
    # smoke test: python -I kyutai_engines.py <wav16k> <out.wav>
    import sys, time, wave
    import soundfile as sf
    x, sr = sf.read(sys.argv[1], dtype="int16")
    assert sr == 16000
    stt = KyutaiStt()
    t = time.perf_counter(); r = stt.transcribe(x); print("STT", round(time.perf_counter() - t, 2), "s:", r.text)
    s = stt.start(); evs = []
    for i in range(0, len(x), 1600):
        evs += [e for e in s.feed(x[i:i + 1600]) if e[0] != "pause"]
    evs += [e for e in s.feed(np.zeros(16000 * 2, np.int16)) if e[0] != "pause"]
    print("events:", evs[-3:], "| finish:", s.finish())
    tts = KyutaiTts()
    t = time.perf_counter(); chunks = []
    for c in tts.synth_stream(iter(["Morning, there. ", "Sixty-one ", "degrees, light rain ", "after three."])):
        if not chunks:
            print("TTS first chunk", round(time.perf_counter() - t, 3), "s")
        chunks.append(c)
    y = np.concatenate(chunks)
    with wave.open(sys.argv[2], "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(24000); w.writeframes(y.tobytes())
    print("TTS", len(y) / 24000, "s audio in", round(time.perf_counter() - t, 2), "s; peak VRAM alloc MiB",
          torch.cuda.max_memory_allocated() // 2**20)
