"""One transcription, asked a second time while the first is still out: the phone's speech-to-text
hedge (`app/.../service/cascade/SttHedge.kt` on origin/feat/phone-voice-speed-1119, Kevin
2026-11-19, speed plan item 4a), ported as an OPTION that is off unless
`LEGION_AGENTS_STT_HEDGE=on` (`agents/legion/flags.py`).

The rule, as the phone's:
- the first request starts at once;
- if it has not answered after `hedge_after_for(audio_ms)` (1.5 s for up to 4 s of audio, 250 ms
  more per further second, at most 3 s), a second identical request starts beside it, and the first
  GOOD answer wins;
- a first request that fails in a way worth retrying before then gets its second request at once
  (the retry rule it replaces; a hung request is the transport's own timeout, which fails);
- never more than `MAX_REQUESTS` (2) requests, the retry included;
- only one result is ever used. The loser is cancelled: its answer is dropped unread. A request
  already on the wire cannot be stopped from here (a blocking HTTP read on a worker thread), so
  "cancelled" means abandoned. It is still paid for: when two requests went out, the turn is
  metered at twice the winner's tokens (the same clip and ask, so the same price; an estimate,
  since the loser's own counts may never arrive).
"""

from __future__ import annotations

import queue
import threading
from collections.abc import Callable
from dataclasses import dataclass, replace

from agents.runtime.model import ModelFailure
from agents.voice.speech import STT_TIMEOUT_S, SpeechFailure, worth_retry

MAX_REQUESTS = 2
HEDGE_AFTER_MS = 1_500
HEDGE_CAP_MS = 3_000
_BASE_AUDIO_MS = 4_000
_PER_SECOND_MS = 250


def hedge_after_for(audio_ms: int) -> int:
    """When the second request starts (the phone's `hedgeAfterFor`)."""
    extra_seconds = (max(audio_ms - _BASE_AUDIO_MS, 0) + 999) // 1000
    return min(HEDGE_AFTER_MS + extra_seconds * _PER_SECOND_MS, HEDGE_CAP_MS)


@dataclass(frozen=True)
class Won[T]:
    value: T
    request: int  # 0 the first, 1 the second


@dataclass(frozen=True)
class Lost:
    # The last failure that came back, or None when every request hung.
    last: BaseException | None


def race[T](
    attempt: Callable[[], T],
    *,
    hedge_after_s: float,
    attempt_timeout_s: float,
    worth_retry: Callable[[BaseException], bool],
    on_second: Callable[[str], None] = lambda why: None,
    spawn: Callable[[Callable[[], None]], None] | None = None,
) -> Won[T] | Lost:
    """Runs `attempt` once, and at most once more by the rule above. An attempt that returns is
    good; one that raises failed (`worth_retry` decides whether that earns the second request).
    `on_second` is told why the second started: "slow" or "failed". A request still out
    `attempt_timeout_s` after the last wait began counts as hung, and the race ends without it."""
    spawn = spawn or (lambda work: threading.Thread(target=work, daemon=True).start())
    answers: queue.Queue = queue.Queue()

    def start(i: int) -> None:
        def work() -> None:
            try:
                value = attempt()
            except BaseException as exc:  # noqa: BLE001 - handed to the race as a failure
                answers.put((i, None, exc))
                return
            answers.put((i, value, None))

        spawn(work)

    def take(timeout: float):
        try:
            return answers.get(timeout=max(timeout, 0.0))
        except queue.Empty:
            return None

    start(0)
    running, last = 1, None
    early = take(hedge_after_s)
    if early is None:
        on_second("slow")
        start(1)
        running += 1
    else:
        running -= 1
        i, value, exc = early
        if exc is None:
            return Won(value, i)
        last = exc
        if worth_retry(exc):
            on_second("failed")
            start(1)
            running += 1
    while running > 0:
        got = take(attempt_timeout_s)
        if got is None:
            # Every request still out has hung past its own deadline.
            break
        running -= 1
        i, value, exc = got
        if exc is None:
            return Won(value, i)
        last = exc
    return Lost(last)


class HedgedSpeech:
    """`agents.voice.speech.GeminiSpeech` with its transcription raced by the rule above; speech
    out is the inner client's, unchanged. Failures read as the inner client's own: a
    `SpeechFailure` as it came, a failure not worth a second request as "stt_failed", a lost
    race as "stt_unreachable"."""

    def __init__(self, inner):
        self.inner = inner

    @property
    def stt_sent(self) -> int:
        return self.inner.stt_sent

    def transcribe(self, pcm: bytes):
        sent = []

        def attempt():
            sent.append(1)
            return self.inner._transcribe_once(pcm)

        result = race(
            attempt,
            hedge_after_s=hedge_after_for(len(pcm) // 32) / 1000,
            attempt_timeout_s=STT_TIMEOUT_S + 1.0,
            worth_retry=_worth_a_second,
        )
        if isinstance(result, Won):
            heard = result.value
            if len(sent) < 2:
                return heard
            # The loser was sent and billed: metered at the winner's counts (review C8).
            return replace(
                heard,
                prompt_tokens=heard.prompt_tokens * 2,
                output_tokens=heard.output_tokens * 2,
            )
        if isinstance(result.last, SpeechFailure):
            raise result.last
        if result.last is not None and not _worth_a_second(result.last):
            raise SpeechFailure("stt_failed") from None
        raise SpeechFailure("stt_unreachable") from None

    def speak(self, text: str, voice: str, spoken):
        return self.inner.speak(text, voice, spoken)


def _worth_a_second(exc: BaseException) -> bool:
    return (
        isinstance(exc, ModelFailure)
        and not isinstance(exc, SpeechFailure)
        and worth_retry(str(exc))
    )
