"""The crisis pre-check, on the engine (CLAUDE.md section 7, "Genuine distress"; ADR 0095 kept it on
the phone until the engine heard the words itself).

A table-driven copy of the phone's `app/src/main/java/com/kevin/legion/ai/CrisisDetector.kt`
(origin/dev 7ef07024): the same 24 phrases, the same 4 exclusions, the same normalising. It is
safety text, so it is copied exactly and changed only together with the phone's; the test
`tests/test_engine_voice_crisis.py` pins it against that file.

Why it is shaped this way is the phone file's own comment, in short: the prompt's crisis clause
(`assistant/shared_clauses.py` CRISIS) is the first line and reads intent; this is the backstop
for when the prompt was not enough. It is tuned for precision: it fires only on phrases with no
innocent reading ("this traffic is killing me", "suicide doors" are ordinary talk), and misses
oblique distress, which falls to the prompt rule.

On a hit the voice turn calls no model and no tool, keeps no words, and ends with the `crisis`
event; what the person then sees or hears is the client's crisis response (the phone's crisis
card), not words written here.
"""

from __future__ import annotations

import re

PHRASES: tuple[str, ...] = (
    "kill myself",
    "killing myself",
    "end my life",
    "ending my life",
    "take my own life",
    "taking my own life",
    "want to die",
    "wanna die",
    "want to be dead",
    "better off dead",
    "better off without me",
    "dont want to live",
    "do not want to live",
    "dont want to be here anymore",
    "no reason to live",
    "nothing to live for",
    "suicidal",
    "suicide",
    "hurt myself",
    "hurting myself",
    "harm myself",
    "harming myself",
    "cut myself",
    "cutting myself",
)

# Innocent phrases that contain a PHRASES entry; removed before matching ("suicide doors" is a
# rear-hinged door).
EXCLUSIONS: tuple[str, ...] = (
    "suicide door",
    "suicide doors",
    "suicide knob",
    "suicide shifter",
)

_NOT_ALNUM = re.compile(r"[^a-z0-9]+")


def normalize(text: str) -> str:
    """Lowercase, apostrophes (straight and curly) dropped so "don't" matches "dont", every other
    run of non-alphanumerics one space, trimmed: the phone's `normalize`."""
    lowered = (text or "").lower().replace("'", "").replace("’", "")
    return _NOT_ALNUM.sub(" ", lowered).strip()


def detect(text: str) -> bool:
    """True if `text` contains an unambiguous distress phrase (the phone's `detect`)."""
    normalized = normalize(text)
    if not normalized:
        return False
    guarded = normalized
    for exclusion in EXCLUSIONS:
        guarded = guarded.replace(exclusion, " ")
    return any(phrase in guarded for phrase in PHRASES)
