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
