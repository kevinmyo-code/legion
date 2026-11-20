"""Filler lines kept in memory per voice, for every voice door (spec 22): a line is said only
from memory; a missing one is fetched in the background for next time.

Moved from the web door (`assistant/cascade.py`, ADR 0102). Which line a tool gets is
`assistant/fillers.py`'s table; which of a tool's lines is next is `picker()` (a door may share one
picker across its instances: the web door does), and which lines are warmed by default is
`default_lines()` (the tool lines and the spoken failure lines).
"""

from __future__ import annotations

import threading
import time
from collections.abc import Callable

from agents.legion.lines import SPOKEN_FAILURES
from agents.voice.speech import OUTPUT_RATE, SpeechFailure, Spoken
from assistant import fillers, speech_tags
from assistant.spoken_words import pronounced

# ---- fillers kept in memory (spec 22) ----------------------------------------------------------


# How long a filler line whose speech failed waits before it is asked for again.
FILLER_RETRY_S = 600.0


class FillerAudio:
    """Each filler line's audio per voice, kept in this process once made. A line is said only from
    memory; a missing one is fetched in the background for next time (the phone's rule: a filler
    that waits for speech holds the answer up behind it)."""

    def __init__(self, spawn: Callable[[Callable[[], None]], None] | None = None):
        self._audio: dict[tuple[str, str], bytes] = {}
        self._fetching: set[tuple[str, str]] = set()
        # A line whose speech failed is not asked for again for a while (finding 6: with speech
        # failing, every spoken turn re-sent the whole set).
        self._failed: dict[tuple[str, str], float] = {}
        self._lock = threading.Lock()
        self._last: str | None = None
        self._spawn = spawn or (lambda work: threading.Thread(target=work, daemon=True).start())
        self._picker = fillers.LinePicker()

    def picker(self) -> fillers.LinePicker:
        """What picks the next of a tool's lines (never the last one said)."""
        return self._picker

    def default_lines(self) -> tuple[str, ...]:
        return (*fillers.all_lines(), *SPOKEN_FAILURES)

    def warm(self, speech, voice: str, lines=None) -> int:
        lines = self.default_lines() if lines is None else lines
        missing = []
        with self._lock:
            now = time.monotonic()
            for line in lines:
                key = (voice, line)
                if now - self._failed.get(key, -FILLER_RETRY_S) < FILLER_RETRY_S:
                    continue
                if key not in self._audio and key not in self._fetching:
                    self._fetching.add(key)
                    missing.append(line)
        if not missing:
            return 0

        def fetch() -> None:
            for line in missing:
                stats = Spoken()
                try:
                    # Through the same tag rules as an answer (round 2 speech review 6).
                    said = speech_tags.for_speech(pronounced(line), expressive=True)
                    audio = b"".join(speech.speak(said, voice, stats))
                except (SpeechFailure, OSError):
                    audio = b""
                if stats.voice not in (None, voice):
                    # Spoken in the fallback voice: never kept under this voice (round 3 S3); it
                    # is asked for again later, when the voice may be back.
                    audio = b""
                with self._lock:
                    self._fetching.discard((voice, line))
                    if audio:
                        self._audio[(voice, line)] = audio
                    else:
                        self._failed[(voice, line)] = time.monotonic()

        self._spawn(fetch)
        return len(missing)

    def missing(self, voice: str, lines=None) -> int:
        """How many lines `warm` would fetch now: what a turn reserves for them first."""
        lines = self.default_lines() if lines is None else lines
        now = time.monotonic()
        with self._lock:
            return sum(
                1
                for line in lines
                if (voice, line) not in self._audio
                and (voice, line) not in self._fetching
                and now - self._failed.get((voice, line), -FILLER_RETRY_S) >= FILLER_RETRY_S
            )

    def get(self, voice: str, line: str) -> bytes | None:
        with self._lock:
            return self._audio.get((voice, line))

    def pick(
        self, voice: str, tool: str, shortest: bool = False, ceiling_ms: int | None = None
    ) -> tuple[str, bytes] | None:
        """One of `tool`'s lines that is in memory, never the last line said; None when the tool
        is silent or none of its lines is in hand yet. `shortest`: the shortest clip in hand (the
        delayed kind, which plays while an answer may already be on its way: a shorter clip holds
        it up less), else the next shortest when that one was the last said. `ceiling_ms`: no clip
        longer than this; when none of the tool's own is short enough, the shortest generic line
        (`fillers.FALLBACK`) that is, else nothing."""
        limit = None if ceiling_ms is None else ceiling_ms * OUTPUT_RATE * 2 // 1000
        with self._lock:
            ready = [line for line in fillers.lines_for(tool) if (voice, line) in self._audio]
            if limit is not None:
                ready = [line for line in ready if len(self._audio[(voice, line)]) <= limit]
                if not ready and fillers.lines_for(tool):
                    ready = [
                        line
                        for line in fillers.FALLBACK
                        if (voice, line) in self._audio and len(self._audio[(voice, line)]) <= limit
                    ]
            if shortest:
                ready.sort(key=lambda line: len(self._audio[(voice, line)]))
        if shortest:
            line = next((one for one in ready if self.picker().pick([one]) is not None), None)
        else:
            line = self.picker().pick(ready)
        if line is None:
            return None
        return line, self._audio[(voice, line)]
