"""The reply's speech, for every voice door: each sentence to text-to-speech the moment it is
whole, at most two requests at once, audio out in sentence order (spec 5), under a per-turn speech
budget, and the turn's audio events.

Moved from the web door (`assistant/cascade.py`, ADR 0102), which uses it as it did. A door tunes
it per speaker: `grouping` ("breath" or "sentence") and `wait_s` (how long the turn waits for the
next sentence's speech) default to this module's constants; the web door hands in its own.
"""

from __future__ import annotations

import base64
import queue
import re
import threading
import time
from collections import deque
from collections.abc import Callable, Iterator
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass

from agents.runtime import events as ev
from agents.voice.speech import OUTPUT_RATE, SpeechFailure, Spoken
from assistant import speech_tags, spoken_figures
from assistant.spoken_words import pronounced, speakable_words


def _never() -> bool:
    return False


# How long the turn waits for the next sentence's speech before it gives up on it.
TTS_WAIT_S = 20.0
# How often a turn waiting on speech asks whether it was cancelled (round 3 N4): well inside the
# page's 6 s of `busy` re-sends.
CANCEL_POLL_S = 0.25
# The longest a whole turn may hold its server thread, speech included (finding 1): speech-to-text
# (12 s), the rounds (`LIMITS`), and what is left of the speech is cut here.
TURN_WALL_S = 60.0

# ---- speech, two sentences at a time (spec 5) --------------------------------------------------

_END = object()


@dataclass
class _Job:
    text: str
    chunks: queue.Queue
    stats: Spoken
    cancelled: threading.Event


# How a reply goes to speech (`Speaker`): "breath" (the first clause alone, the rest of each round
# as one request) or "sentence" (one request per sentence, the phone's old shape). Kept as a
# constant while the two are measured against each other (section 8.3); the loser goes.
SPEECH_GROUPING = "breath"
# Speech requests reserved before a turn starts (finding 6). A longer answer reserves more as it
# goes, one request at a time, up to `TTS_REQUEST_CEILING` (round 3 speech S4: the 12-sentence
# limit spoken one sentence a request, plus the closing lines); one is always kept back for a
# closing honesty line.
MAX_TTS_REQUESTS = 6
TTS_REQUEST_CEILING = 16
GROUP_SENTENCES = 3
GROUP_CHARS = 400
_SENTENCE_END = re.compile(r"(?<=[.!?])\s+")
# Speech only (2026-11-18, speed; blind review round 7 moved it here from the runtime, after the
# checks): a first sentence past this many words is spoken in two requests, cut at its first
# , ; or : that leaves at least FIRST_CLAUSE_MIN_WORDS in front.
FIRST_CLAUSE_PAST_WORDS = 8
FIRST_CLAUSE_MIN_WORDS = 6
_CLAUSE_BREAK = re.compile(r"(?<=[,;:])\s+")


def _first_sentence(text: str) -> tuple[str, str]:
    parts = _SENTENCE_END.split(text, maxsplit=1)
    return parts[0], parts[1] if len(parts) > 1 else ""


def first_clause(text: str) -> tuple[str, str]:
    """(what is spoken at once, the rest) for an answer's first sentence; a short one goes whole."""
    if len(text.split()) <= FIRST_CLAUSE_PAST_WORDS:
        return text, ""
    for match in _CLAUSE_BREAK.finditer(text):
        head, rest = text[: match.start()].strip(), text[match.end() :].strip()
        if len(head.split()) >= FIRST_CLAUSE_MIN_WORDS and rest:
            return head, rest
    return text, ""


class _Cancelled(Exception):
    """The turn was cancelled while it waited on speech."""


class Speaker:
    """Each sentence goes to speech the moment it is whole, at most two at once; audio comes out
    in sentence order. `ready()` hands over what has arrived without waiting; `drain()` waits."""

    def __init__(
        self,
        speech,
        voice: str,
        enabled: bool,
        clock=time.monotonic,
        *,
        grouping: str = SPEECH_GROUPING,
        wait_s: float = TTS_WAIT_S,
    ):
        self.speech = speech
        self.voice = voice
        self.enabled = enabled
        self.clock = clock
        self.jobs: deque[_Job] = deque()
        self.done: list[Spoken] = []
        self.started_at = clock()
        # The ANSWER's first audio (no acknowledgement or lookup line), and the first sound of any
        # kind, which is what the page's own `first_audio` line measures from its side.
        self.first_audio_ms: int | None = None
        self.first_sound_ms: int | None = None
        self.total_ms = 0
        # The whole turn, speech included, ends by this time (finding 1).
        self.deadline = self.started_at + TURN_WALL_S
        self._pool = ThreadPoolExecutor(max_workers=2) if enabled else None
        # The person asked for exact figures this turn: nothing is rounded when spoken.
        self.exact = False
        # "One breath per reply" (voice-persona section 8.3): the first words go to speech alone
        # and at once; what follows in the same round is one request, so its intonation carries
        # across sentences. `SPEECH_GROUPING` "sentence" is the old one-request-per-sentence.
        self.grouping = grouping
        # How long `drain` waits for the next sentence's speech before it gives up on it.
        self.wait_s = wait_s
        self._first_out = False
        self._pending: list[str] = []
        self.requests = 0
        self.skipped = 0
        self.cancelled = _never
        self.cancelled_often = _never
        # Speech requests this turn may make now, and how to reserve more (round 3 S4).
        self.allowed = MAX_TTS_REQUESTS
        self.more: Callable[[int], bool] = lambda n: True
        # Set for the turn's closing lines: they may use the request kept back for them.
        self.closing = False
        self.stopped = False
        self._notices: list[ev.Event] = []

    def spoken_text(self, text: str, expressive: bool = False, model: bool = False) -> str:
        """What goes to speech for `text`: for a `model` sentence only, no file span, link or
        backtick read out (`spoken_words`; a line code wrote, his profile or a figure, is never
        reworded); its figures rounded like a person says them (`spoken_figures`; the exact ones
        stay in the caption), and its allowed tags only when `expressive` (a model sentence or a
        code line written with one)."""
        said = speakable_words(text) if model else text
        said = spoken_figures.speakable(pronounced(said), exact=self.exact)
        return speech_tags.for_speech(said, expressive=expressive)

    def add(self, text: str, expressive: bool = False, model: bool = False) -> None:
        if not self.enabled:
            return
        spoken = self.spoken_text(text, expressive, model)
        if self.grouping == "sentence":
            self._submit(spoken)
            return
        if not self._first_out:
            # The answer's first clause is spoken as soon as it exists (section 8.2), and with no
            # tag: tags stay off an answer's first sentence (section 9). Only for words written to
            # be said (`expressive`: the model's, the greeting); code's own lines (a figure, his
            # profile) are never cut, which once put "2026." in a request of its own.
            self._first_out = True
            lead, more = _first_sentence(spoken)
            spoken = f"{speech_tags.untagged(lead)} {more}".strip()
            head, rest = first_clause(spoken) if expressive else (spoken, "")
            self._submit(head)
            if rest:
                self._pending.append(rest)
            return
        self._pending.append(spoken)
        if len(self._pending) >= GROUP_SENTENCES or sum(map(len, self._pending)) >= GROUP_CHARS:
            self.flush()

    def flush(self) -> None:
        """Speak what is held as one request (the round ended, or enough is held)."""
        if self._pending:
            self._submit(" ".join(self._pending))
            self._pending = []

    def _submit(self, spoken: str) -> None:
        if self.cancelled():
            return
        if not self._may_speak():
            # Past what could be reserved: the rest is shown, not said, and the page is told
            # once (round 3 S4). The closing lines still have the request kept back for them.
            self.skipped += 1
            if not self.stopped:
                self.stopped = True
                self._notices.append(ev.Event("speech_stopped", {"reason": "limit"}))
            return
        self.requests += 1
        job = _Job(spoken, queue.Queue(), Spoken(), threading.Event())
        self.jobs.append(job)
        self._pool.submit(self._run, job)

    def _may_speak(self) -> bool:
        def room() -> bool:
            return self.requests < (self.allowed if self.closing else self.allowed - 1)

        if room():
            return True
        if self.allowed < TTS_REQUEST_CEILING and self.more(1):
            self.allowed += 1
        return room()

    def _run(self, job: _Job) -> None:
        started = time.monotonic()
        try:
            for chunk in self.speech.speak(job.text, self.voice, job.stats):
                if job.cancelled.is_set():
                    break
                job.chunks.put(chunk)
        except SpeechFailure as exc:
            job.stats.failed = job.stats.failed or str(exc)
        except Exception as exc:  # noqa: BLE001 - a sentence's speech failing is not the turn's
            job.stats.failed = type(exc).__name__
        finally:
            self.total_ms += int((time.monotonic() - started) * 1000)
            job.chunks.put(_END)

    def audio_event(self, chunk: bytes, filler: bool = False) -> ev.Event:
        at = int((self.clock() - self.started_at) * 1000)
        if self.first_sound_ms is None:
            self.first_sound_ms = at
        if self.first_audio_ms is None and not filler:
            self.first_audio_ms = at
        data = {"pcm": base64.b64encode(chunk).decode("ascii"), "rate": OUTPUT_RATE}
        if filler:
            # The page may drop a filler that has not started once the answer's audio is ready.
            data["filler"] = True
        return ev.Event("audio", data)

    def _take(self, block: bool) -> Iterator[ev.Event]:
        while self._notices:
            yield self._notices.pop(0)
        while self.jobs:
            if self.cancelled_often():
                # Stopped: what is queued or still speaking is dropped, nothing more is asked for.
                for job in self.jobs:
                    job.cancelled.set()
                self.jobs.clear()
                self._pending = []
                return
            head = self.jobs[0]
            # Never past the turn's own deadline, however many sentences are still queued.
            wait = min(self.wait_s, max(self.deadline - self.clock(), 0.05))
            try:
                item = self._next(head, wait) if block else head.chunks.get_nowait()
            except _Cancelled:
                for job in self.jobs:
                    job.cancelled.set()
                self.jobs.clear()
                self._pending = []
                return
            except queue.Empty:
                if block:
                    head.cancelled.set()
                    head.stats.failed = head.stats.failed or "tts_timeout"
                    self.done.append(head.stats)
                    self.jobs.popleft()
                    yield ev.Event("speech_failed", {"reason": "tts"})
                    continue
                return
            if item is _END:
                self.jobs.popleft()
                self.done.append(head.stats)
                if head.stats.failed:
                    yield ev.Event("speech_failed", {"reason": "tts"})
                continue
            yield self.audio_event(item)

    def _next(self, job: _Job, wait: float):
        """The job's next chunk within `wait` seconds, asking about a cancel every
        `CANCEL_POLL_S` meanwhile (round 3 N4: a 20 s wait must not hold a cancelled turn)."""
        until = time.monotonic() + wait
        while True:
            left = until - time.monotonic()
            try:
                return job.chunks.get(timeout=max(min(CANCEL_POLL_S, left), 0.001))
            except queue.Empty:
                if self.cancelled_often():
                    raise _Cancelled from None
                if left <= CANCEL_POLL_S:
                    raise

    def ready(self) -> Iterator[ev.Event]:
        yield from self._take(block=False)

    def drain(self) -> Iterator[ev.Event]:
        self.flush()
        yield from self._take(block=True)

    def close(self) -> None:
        for job in self.jobs:
            job.cancelled.set()
        if self._pool is not None:
            self._pool.shutdown(wait=False, cancel_futures=True)
