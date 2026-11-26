"""`tools/tts_cache.py` and the render tools that use it: text already rendered is never paid for
twice (cost cut 5, 2026-11-26). No network: every render is a counting fake. Run from the repo root:

    python -m pytest tools/tests/test_tts_cache.py -p no:cacheprovider
"""

from __future__ import annotations

import math
import struct
import sys
from pathlib import Path

import pytest

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))

import render_fixed_clips as fixed  # noqa: E402
import render_listen_cues as cues  # noqa: E402
import tts_cache  # noqa: E402

MODEL = "tts-model-a"
VOICE = "voice-a"


class Counter:
    def __init__(self):
        self.calls = 0

    def __call__(self):
        self.calls += 1
        return b"\x01\x00" * 24_000, 24_000, {"cost": 0.01}  # one second


def test_the_second_render_of_the_same_text_makes_no_call(tmp_path):
    render = Counter()
    first = tts_cache.cached(MODEL, VOICE, "Good evening.", render, root=tmp_path)
    second = tts_cache.cached(MODEL, VOICE, "Good evening.", render, root=tmp_path)
    assert render.calls == 1
    assert (first.hit, second.hit) == (False, True)
    assert (second.pcm, second.rate, second.extra) == (first.pcm, 24_000, {"cost": 0.01})


@pytest.mark.parametrize(
    "other",
    [
        {"model": "tts-model-b"},
        {"voice": "voice-b"},
        {"text": "Good morning."},
        {"variant": "take1"},
    ],
)
def test_a_different_model_voice_text_or_variant_is_a_new_render(tmp_path, other):
    render = Counter()
    args = {"model": MODEL, "voice": VOICE, "text": "Good evening.", "variant": ""}
    tts_cache.cached(args["model"], args["voice"], args["text"], render, root=tmp_path)
    args.update(other)
    got = tts_cache.cached(
        args["model"], args["voice"], args["text"], render, variant=args["variant"], root=tmp_path
    )
    assert render.calls == 2 and not got.hit


def test_a_render_that_raises_is_not_cached(tmp_path):
    def boom():
        raise OSError("network")

    with pytest.raises(OSError):
        tts_cache.cached(MODEL, VOICE, "Hi.", boom, root=tmp_path)
    render = Counter()
    tts_cache.cached(MODEL, VOICE, "Hi.", render, root=tmp_path)
    assert render.calls == 1


def test_a_half_written_entry_is_a_miss_not_a_crash(tmp_path):
    render = Counter()
    tts_cache.cached(MODEL, VOICE, "Hi.", render, root=tmp_path)
    for f in tmp_path.glob("*.json"):
        f.write_text("{not json", encoding="utf-8")
    tts_cache.cached(MODEL, VOICE, "Hi.", render, root=tmp_path)
    assert render.calls == 2


def test_the_default_cache_lives_outside_the_repo(monkeypatch, tmp_path):
    monkeypatch.delenv("LEGION_TTS_CACHE", raising=False)
    assert tts_cache.default_root().name == "tts-cache"
    assert tts_cache.default_root().parent.name == ".legion"
    monkeypatch.setenv("LEGION_TTS_CACHE", str(tmp_path))
    assert tts_cache.default_root() == tmp_path


# ---- the two renderers ------------------------------------------------------------------------


def _tone(ms: int = 400) -> bytes:
    n = 24_000 * ms // 1000
    return b"".join(struct.pack("<h", int(8000 * math.sin(i / 8))) for i in range(n))


def _fixed_setup(monkeypatch, tmp_path, calls, argv):
    def fake_render(key, text):
        calls.append(text)
        return _tone(max(400, 70 * len(text))), {"candidatesTokenCount": 40, "promptTokenCount": 5}

    key_file = tmp_path / "key.txt"
    key_file.write_text("test-key-not-real", encoding="utf-8")
    monkeypatch.setattr(fixed, "render", fake_render)
    monkeypatch.setattr(fixed, "OUT", tmp_path / "clips")
    monkeypatch.setenv("LEGION_GEMINI_KEY_FILE", str(key_file))
    monkeypatch.setenv("LEGION_TTS_CACHE", str(tmp_path / "cache"))
    monkeypatch.setattr(sys, "argv", ["render_fixed_clips.py", *argv])


def test_the_fixed_clips_second_pass_comes_from_the_cache(monkeypatch, tmp_path):
    calls = []
    _fixed_setup(monkeypatch, tmp_path, calls, [])
    assert fixed.main() == 0
    paid = len(calls)
    assert paid > 0
    for f in (tmp_path / "clips").glob("*.wav"):
        f.unlink()  # the clips are gone but the renders are cached
    assert fixed.main() == 0
    assert len(calls) == paid


def test_force_rerenders_every_clip_and_pays_again(monkeypatch, tmp_path):
    calls = []
    _fixed_setup(monkeypatch, tmp_path, calls, [])
    assert fixed.main() == 0
    paid = len(calls)
    monkeypatch.setattr(sys, "argv", ["render_fixed_clips.py", "--force"])
    assert fixed.main() == 0
    assert len(calls) == 2 * paid  # --force means re-render, as its help says


def test_the_two_takes_of_a_line_are_two_renders(monkeypatch, tmp_path):
    calls = []
    _fixed_setup(monkeypatch, tmp_path, calls, [])
    fixed.main()
    assert calls.count(fixed.ACKS[0]) == 2  # take 0 and take 1 are meant to differ


def test_the_listen_cues_gemini_renderer_reuses_a_line(monkeypatch, tmp_path):
    sent = []

    def fake_call(url, key, body=None):
        sent.append(url)
        return {"candidates": [{"content": {"parts": [{"inlineData": {
            "mimeType": "audio/L16;rate=24000", "data": "AAAA" * 6000}}]}}]}

    monkeypatch.setattr(cues, "_call", fake_call)
    monkeypatch.setenv("LEGION_TTS_CACHE", str(tmp_path))
    first = cues.GeminiRenderer("Puck", "test-key-not-real", "tts-model-a").render("Right.")
    again = cues.GeminiRenderer("Puck", "test-key-not-real", "tts-model-a").render("Right.")
    cues.GeminiRenderer("Kore", "test-key-not-real", "tts-model-a").render("Right.")
    assert first == again
    assert len(sent) == 2  # the other voice was a new render


# ---- a render that is not a render is never stored ---------------------------------------------


@pytest.mark.parametrize("pcm", [b"", b"\x01", b"\x01\x00" * 100])
def test_an_empty_or_implausibly_short_render_is_refused_and_not_stored(tmp_path, pcm):
    with pytest.raises(ValueError):
        tts_cache.cached(MODEL, VOICE, "Good evening.", lambda: (pcm, 24_000, {}), root=tmp_path)
    assert list(tmp_path.iterdir()) == []
    render = Counter()
    tts_cache.cached(MODEL, VOICE, "Good evening.", render, root=tmp_path)
    assert render.calls == 1  # the refusal left nothing behind to replay


def test_the_floor_scales_with_the_text(tmp_path):
    long_text = "word " * 40  # 200 chars: 5 s floor
    one_second = b"\x01\x00" * 24_000
    with pytest.raises(ValueError):
        tts_cache.cached(MODEL, VOICE, long_text, lambda: (one_second, 24_000, {}), root=tmp_path)
    assert tts_cache.min_pcm_bytes("Hi.", 24_000) == 24_000 * 2 * 150 // 1000


def test_a_bad_entry_an_older_run_stored_is_a_miss(tmp_path):
    render = Counter()
    tts_cache.cached(MODEL, VOICE, "Good evening.", render, root=tmp_path)
    for f in tmp_path.glob("*.pcm"):
        f.write_bytes(b"")
    tts_cache.cached(MODEL, VOICE, "Good evening.", render, root=tmp_path)
    assert render.calls == 2


def test_new_pcm_under_old_meta_is_a_miss_not_a_wrong_rate(tmp_path):
    render = Counter()
    tts_cache.cached(MODEL, VOICE, "Good evening.", render, root=tmp_path)
    for f in tmp_path.glob("*.pcm"):
        f.write_bytes(b"\x02\x00" * 30_000)  # a torn overwrite: other length, old meta
    got = tts_cache.cached(MODEL, VOICE, "Good evening.", render, root=tmp_path)
    assert not got.hit and render.calls == 2


def test_a_write_leaves_no_temp_files_and_an_overwrite_replaces_both(tmp_path):
    tts_cache.cached(MODEL, VOICE, "Hi.", Counter(), root=tmp_path)
    tts_cache.cached(MODEL, VOICE, "Hi.", lambda: (b"\x03\x00" * 12_000, 12_000, {}), root=tmp_path, no_cache=True)
    assert sorted(p.suffix for p in tmp_path.iterdir()) == [".json", ".pcm"]
    got = tts_cache.cached(MODEL, VOICE, "Hi.", Counter(), root=tmp_path)
    assert got.hit and got.rate == 12_000
