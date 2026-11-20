"""The engine's crisis pre-check (`agents/voice/crisis.py`): the phone's `CrisisDetector.kt` table,
copied exactly and pinned to that file, and its behaviour on the phone's own hard cases. Every
sentence here is invented."""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from agents.voice import crisis

PHONE_FILE = (
    Path(__file__).resolve().parents[2] / "app/src/main/java/com/kevin/legion/ai/CrisisDetector.kt"
)


def _kotlin_list(name: str) -> tuple[str, ...]:
    text = PHONE_FILE.read_text(encoding="utf-8")
    body = re.search(rf"val {name} = listOf\((.*?)\n    \)", text, re.S).group(1)
    return tuple(re.findall(r'"([^"]*)"', body))


@pytest.mark.skipif(not PHONE_FILE.exists(), reason="the phone source is not in this checkout")
def test_the_tables_are_the_phones_word_for_word():
    assert crisis.PHRASES == _kotlin_list("PHRASES")
    assert crisis.EXCLUSIONS == _kotlin_list("EXCLUSIONS")
    assert len(crisis.PHRASES) == 24 and len(crisis.EXCLUSIONS) == 4


@pytest.mark.parametrize(
    "said",
    [
        "I want to die",
        "Honestly I don't want to live like this",
        "I don’t want to be here anymore",
        "Sometimes I think everyone is better off without me.",
        "I've been feeling SUICIDAL lately",
        "I keep wanting to hurt myself",
    ],
)
def test_unambiguous_distress_is_caught(said):
    assert crisis.detect(said)


@pytest.mark.parametrize(
    "said",
    [
        "Does the old coupe come with suicide doors?",
        "this traffic is killing me",
        "I'm dying to get home",
        "kill the music",
        "the battery is dead",
        "I don't see the point anymore",  # oblique: left to the prompt's crisis clause, by design
        "",
        "   ",
    ],
)
def test_ordinary_talk_is_not(said):
    assert not crisis.detect(said)


def test_an_exclusion_does_not_hide_a_real_phrase_beside_it():
    assert crisis.detect("forget the suicide doors, I want to die")


# ---- review 2026-11-19, C2/C7: word boundaries, and an exclusion only where it covers the hit ----

_SHAPES = (
    "Honestly, {p}.",
    "I think {P}!",
    "{p}, that's how I feel",
    "lately... {p}?",
)


@pytest.mark.parametrize("phrase", crisis.PHRASES)
def test_every_phone_phrase_is_still_a_hit_in_a_sentence(phrase):
    # Never fewer true hits than the phone's table: each phrase inside a sentence, with
    # punctuation around it, in another case, and with the apostrophe the phone drops put back.
    spoken = phrase.replace("dont", "don't")
    for shape in _SHAPES:
        said = shape.format(p=spoken, P=spoken.upper())
        assert crisis.detect(said), said


@pytest.mark.parametrize(
    "said",
    [
        "I want to diet before summer",
        "can you end my lifetime subscription",
        "they're ending my lifetime membership",
        "I'm killing myselfie game",  # a run-on word is not the phrase
    ],
)
def test_a_phrase_inside_a_longer_word_is_not_a_hit(said):
    assert not crisis.detect(said)


@pytest.mark.parametrize(
    "said",
    [
        # The safe side: a spoken help line here costs a moment; a miss could cost more.
        "I cut myself a check last week",
        "I hurt myself at the gym",
        "watching Suicide Squad tonight",
        "I dont want to live with these fees",
    ],
)
def test_kept_hits_where_a_word_boundary_cannot_tell(said):
    assert crisis.detect(said)


def test_an_exclusion_split_by_punctuation_does_not_hide_the_hit():
    # "suicide, door's locked" normalises to "suicide doors locked": the comma between them means
    # the exclusion "suicide doors" is not what was said.
    assert crisis.detect("I keep thinking about suicide, door's locked")
    assert not crisis.detect("the coupe has suicide doors, sadly")
