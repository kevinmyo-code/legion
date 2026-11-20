"""The crisis pre-check, on the engine (CLAUDE.md section 7, "Genuine distress"; ADR 0095 kept it on
the phone until the engine heard the words itself).

A table-driven copy of the phone's `app/src/main/java/com/kevin/legion/ai/CrisisDetector.kt`
(origin/dev 7ef07024): the same 24 phrases, the same 4 exclusions, the same normalising (matched
as whole words: see `detect`). It is safety text, so the tables are copied exactly and change only
together with the phone's; `tests/test_engine_voice_crisis.py` pins them against that file.

Why it is shaped this way is the phone file's own comment, in short: the prompt's crisis clause
(`assistant/shared_clauses.py` CRISIS) is the first line and reads intent; this is the backstop
for when the prompt was not enough. It is tuned for precision: it fires only on phrases with no
innocent reading ("this traffic is killing me", "suicide doors" are ordinary talk), and misses
oblique distress, which falls to the prompt rule.

On a hit the voice turn calls no model and no tool and keeps no words. It says `LINE` once, by
code, the same on every client, and sends the `crisis` event so a client with a screen shows its
card (voice client contract v1 section 10, wire section 5: an orchestrator default, not a ruling).
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

# The words are the head unit's crisis card's, its two text blocks joined
# (`headunit/app/src/main/java/com/kevin/midnightai/ui/CrisisCard.kt`, lines 61-70): no crisis copy
# is written here. `tests/test_engine_voice_review.py` pins them to that file.
LINE = (
    "I'm not equipped for this, and I'm not going to pretend otherwise. "
    "If you're in crisis, please talk to someone who is. "
    "In the US, call or text 988 to reach the Suicide and Crisis Lifeline, any time."
)

_TOKEN = re.compile(r"[a-z0-9]+|[^a-z0-9\s]")


def _words(text: str) -> tuple[list[str], list[bool]]:
    """The words of `text` as the phone normalises them (lowercase, apostrophes dropped, so "don't"
    is "dont"), and for each word whether punctuation stood between it and the word before."""
    lowered = (text or "").lower().replace("'", "").replace("’", "")
    words: list[str] = []
    broken: list[bool] = []
    gap = False
    for token in _TOKEN.findall(lowered):
        if token[0].isalnum() and token.isascii():
            words.append(token)
            broken.append(gap)
            gap = False
        else:
            gap = True
    return words, broken


# What may follow a phrase's last word and still be that word (review recheck 2026-11-20): plural,
# possessive (its apostrophe dropped), and -ly / -ally / -ity / -ality, so "suicides",
# "suicide's", "suicidally" and "suicidality" are hits as on the phone. Nothing else: "diet" is not
# "die", "lifetime" is not "life".
SUFFIXES = ("", "s", "es", "ly", "ally", "ity", "ality")

# The words that put an excluded phrase in car talk: a part someone has, wants or describes ("has
# suicide doors", "a suicide knob", "with suicide doors"). An excluded phrase after any other word,
# or first in what was said, hides nothing (review recheck, C7): speech-to-text writes no
# punctuation, so "thinking about suicide door is locked" must still be a hit.
CAR_TALK_LEAD = frozenset(
    "a an the its his her their our your my this that these those with has have had of rear "
    "front back two four both any some no got get gets getting want wants wanted like likes love "
    "loves".split()
)


def _phrase_at(words: list[str], phrase: list[str], i: int) -> bool:
    n = len(phrase)
    if words[i : i + n - 1] != phrase[:-1]:
        return False
    last, said = phrase[-1], words[i + n - 1]
    return any(said == last + suffix for suffix in SUFFIXES)


def _excluded_at(words: list[str], broken: list[bool], exclusion: list[str], j: int) -> bool:
    n = len(exclusion)
    return (
        words[j : j + n] == exclusion
        and not any(broken[j + 1 : j + n])
        and j > 0
        and not broken[j]
        and words[j - 1] in CAR_TALK_LEAD
    )


def detect(text: str) -> bool:
    """True if `text` says one of the phone's phrases as whole words.

    Where the engine differs from the phone's `detect` (review 2026-11-19, C2 and C7; recheck
    2026-11-20), on purpose:
    - a phrase starts on a word boundary and its last word may take only `SUFFIXES`, so "I want
      to diet" and "end my lifetime subscription" are not hits, while "suicides" and
      "suicidality" still are. Every other string the phone hits is a hit here
      (`tests/test_engine_voice_crisis.py` checks a generated list);
    - an exclusion hides a hit only when its own words are all there, together, with no
      punctuation between, after a word that makes them car talk (`CAR_TALK_LEAD`): "has suicide
      doors" is quiet, "suicide, door's locked" and "about suicide door is locked" are hits.
    Kept as hits on the safe side, where whole words cannot tell: "cut myself a check", "hurt
    myself at the gym", "Suicide Squad", "dont want to live with these fees", and an utterance
    that starts "suicide doors ...". A help line said there costs a moment; a miss could cost more.
    """
    words, broken = _words(text)
    if not words:
        return False
    hidden: set[int] = set()
    for exclusion in EXCLUSIONS:
        shape = exclusion.split()
        for j in range(len(words) - len(shape) + 1):
            if _excluded_at(words, broken, shape, j):
                hidden.update(range(j, j + len(shape)))
    for phrase in PHRASES:
        shape = phrase.split()
        for i in range(len(words) - len(shape) + 1):
            if _phrase_at(words, shape, i) and not set(range(i, i + len(shape))) <= hidden:
                return True
    return False
