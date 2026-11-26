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
"""

from __future__ import annotations

import hashlib
import json
import os
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path


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
            return Rendered(pcm_file.read_bytes(), int(meta["rate"]), meta.get("extra") or {}, True)
        except (OSError, ValueError, KeyError, TypeError):
            pass  # a half-written entry is a miss
    pcm, rate, extra = render()
    root.mkdir(parents=True, exist_ok=True)
    pcm_file.write_bytes(pcm)
    # The meta file is written last: an entry is complete only when both exist.
    meta_file.write_text(json.dumps({"rate": rate, "extra": extra}), encoding="utf-8")
    return Rendered(pcm, rate, extra, False)
