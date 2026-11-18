"""The server's echo layer (2026-11-17): a transcript that repeats the assistant's own last reply is
the reply heard back through the speaker, not the person. The page has its own layer (echo
cancellation and the barge-in gate); this one holds when that misses, as on the phone and Midcon.

The test is on normalised words: the transcript's words appear, in order, in the reply, allowing a
few dropped or misheard ones, and the transcript has at least `MIN_WORDS` words, so a short real
answer ("yes do that", "the second one") is never caught."""

from __future__ import annotations

import re

MIN_WORDS = 4
# Of the transcript's words, this share must appear in order in the reply...
MIN_SHARE = 0.75
# ...and at most this many (or a fifth, for a long transcript) may be missing or misheard.
MAX_MISSING = 2

_WORD = re.compile(r"[a-z0-9']+")


def words(text: str) -> list[str]:
    return _WORD.findall((text or "").lower().replace("’", "'"))


def _in_order(heard: list[str], said: list[str]) -> int:
    """How many of `heard`'s words appear in `said` in the same order (longest common
    subsequence), in O(len(heard) x len(said))."""
    previous = [0] * (len(said) + 1)
    for word in heard:
        current = [0]
        for j, other in enumerate(said, start=1):
            current.append(previous[j - 1] + 1 if word == other else max(previous[j], current[-1]))
        previous = current
    return previous[-1]


def is_echo(transcript: str, reply: str) -> bool:
    heard, said = words(transcript), words(reply)
    if len(heard) < MIN_WORDS or not said:
        return False
    kept = _in_order(heard, said)
    allowed_missing = max(MAX_MISSING, len(heard) // 5)
    return (
        kept >= MIN_WORDS
        and kept >= len(heard) - allowed_missing
        and (kept / len(heard) >= MIN_SHARE)
    )
