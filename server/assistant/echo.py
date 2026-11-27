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


# ---- a reply that was not kept (#475) ---------------------------------------------------------
#
# A reply that read outside text is stored as a marker (CLAUDE.md section 7), so there are no
# words to compare a barge-in with. Its fingerprint is kept instead: each word 3-gram, keyed with
# the engine's SECRET_KEY (HMAC-SHA256), truncated. No text can be read back from it without the
# key, and the test is the same in spirit: most of the transcript's 3-grams are the reply's.

GRAM = 3
PRINT_HEX = 8
PRINT_MAX = 400
# Of the transcript's 3-grams, this share must be in the reply's print. Lower than MIN_SHARE: one
# misheard word breaks up to three 3-grams.
PRINT_SHARE = 0.5


def _grams(text: str) -> list[str]:
    said = words(text)
    return [" ".join(said[i : i + GRAM]) for i in range(len(said) - GRAM + 1)]


def _key(gram: str) -> str:
    import hashlib
    import hmac

    from django.conf import settings

    digest = hmac.new(settings.SECRET_KEY.encode(), gram.encode(), hashlib.sha256).hexdigest()
    return digest[:PRINT_HEX]


def fingerprint(text: str) -> list[str]:
    """The reply's keyed 3-gram hashes, in order, at most PRINT_MAX."""
    return [_key(gram) for gram in _grams(text)][:PRINT_MAX]


def is_echo_of_print(transcript: str, print_: list[str] | tuple[str, ...]) -> bool:
    """`is_echo` against a fingerprint: the transcript has at least MIN_WORDS words and at least
    PRINT_SHARE of its 3-grams are in the print."""
    if len(words(transcript)) < MIN_WORDS or not print_:
        return False
    grams = [_key(gram) for gram in _grams(transcript)]
    kept = set(print_)
    return sum(1 for gram in grams if gram in kept) / len(grams) >= PRINT_SHARE
