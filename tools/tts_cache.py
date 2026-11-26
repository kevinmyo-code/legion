"""A disk cache for paid TTS renders (cost cut 5, 2026-11-26).

Why: sample, cue, clip and audition renders are paid Gemini TTS calls, and a re-run re-paid for
text it had already rendered (11-18: 551k audio tokens in one day, about $5). A render is cached
by (model, voice, text, variant) and replayed on every later run, so the same text in the same
voice on the same model is paid for once.

`variant` is for renders that are MEANT to differ for the same text (the two takes of an
acknowledgement, a retry after a failed length gate): each gets its own entry, so a re-run replays
the same sequence instead of drawing a new one.

The cache is outside the repo (`~/.legion/tts-cache`, or `LEGION_TTS_CACHE`): audio and the text
it says are never committed. A render that raises is never stored. Use `no_cache` to pay again on
purpose.

An implausible render is never stored (empty, or shorter than `min_pcm_bytes` for its text: a cut
stream or a safety block ends "successfully" with almost no audio). It raises `ValueError`, which
the callers already treat as a failed try. An entry is written atomically (temp file, then rename)
and records its pcm length, so a half-finished overwrite reads as a miss, never as a wrong rate.
"""

from __future__ import annotations

import hashlib
import json
import os
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path


MIN_MS = 150  # no render is shorter than this
MS_PER_CHAR = 25  # a floor, well under real speech (about 60 to 80 ms a character)


def min_pcm_bytes(text: str, rate: int) -> int:
    """The fewest bytes of 16-bit mono pcm that can plausibly be `text` spoken."""
    ms = max(MIN_MS, MS_PER_CHAR * len(text.strip()))
    return rate * 2 * ms // 1000


def plausible(text: str, pcm: bytes, rate: int) -> bool:
    return len(pcm) >= max(2, min_pcm_bytes(text, rate))


@dataclass
class Rendered:
    pcm: bytes
    rate: int
    extra: dict
    hit: bool


def default_root() -> Path:
    env = os.environ.get("LEGION_TTS_CACHE")
    return Path(env) if env else Path.home() / ".legion" / "tts-cache"


def key_of(model: str, voice: str, text: str, variant: str = "") -> str:
    raw = json.dumps([model, voice, text, variant], ensure_ascii=False)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def cached(
    model: str,
    voice: str,
    text: str,
    render: Callable[[], tuple[bytes, int, dict]],
    *,
    variant: str = "",
    root: Path | None = None,
    no_cache: bool = False,
) -> Rendered:
    """The cached render for this key, or `render()` (stored on success). `render` returns
    (pcm, sample rate, extra), where `extra` is any JSON-able detail the caller wants back."""
    root = root or default_root()
    name = key_of(model, voice, text, variant)
    pcm_file, meta_file = root / f"{name}.pcm", root / f"{name}.json"
    if not no_cache and pcm_file.is_file() and meta_file.is_file():
        try:
            meta = json.loads(meta_file.read_text(encoding="utf-8"))
            pcm, rate = pcm_file.read_bytes(), int(meta["rate"])
            # A length that disagrees with the meta is a torn overwrite; an implausible one is a bad
            # entry an older run stored. Both are a miss.
            if len(pcm) == int(meta["bytes"]) and plausible(text, pcm, rate):
                return Rendered(pcm, rate, meta.get("extra") or {}, True)
        except (OSError, ValueError, KeyError, TypeError):
            pass  # a half-written entry is a miss
    pcm, rate, extra = render()
    if not plausible(text, pcm, rate):
        raise ValueError(f"render of {len(text)} chars came back {len(pcm)} bytes: not stored")
    root.mkdir(parents=True, exist_ok=True)
    # Meta goes first out of the way, then pcm, then meta, each by rename: an entry is complete
    # only when both exist and the lengths agree.
    meta_file.unlink(missing_ok=True)
    _atomic(pcm_file, pcm)
    _atomic(meta_file, json.dumps({"rate": rate, "bytes": len(pcm), "extra": extra}).encode("utf-8"))
    return Rendered(pcm, rate, extra, False)


def _atomic(path: Path, data: bytes) -> None:
    tmp = path.with_name(f"{path.name}.{os.getpid()}.tmp")
    try:
        tmp.write_bytes(data)
        os.replace(tmp, path)
    finally:
        tmp.unlink(missing_ok=True)
