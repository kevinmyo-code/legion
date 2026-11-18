"""Blind review round 3, speech (`review-demo-voice-first-1117-speech-r3.md`): one test per finding,
written to fail before its fix. Ruling B stands: the failure mode is "spoken exactly", never
"spoken wrong", so heuristics are removed, not tuned. Google is never called; every word is
invented."""

from __future__ import annotations

import pytest

from assistant import cascade

# ---- S2: a model-worded greeting carries no figure in any form ----------------------------------


@pytest.mark.parametrize(
    "greeting",
    [
        "Groceries at nine hundred, wow.",
        "Four hundred on the nose this month.",
        "Half your grocery budget is gone.",
        "Your quiz is at nine tonight.",
        "That's your third workout this week.",
        "Three-quarters of the budget is spent.",
        "A quarter of the month is left.",
        "Dentist at noon.",
        "Twice this week already.",
        "Back at seven.",
    ],
)
def test_a_greeting_with_a_number_word_ordinal_fraction_or_clock_time_is_refused(greeting):
    assert cascade.greeting_states_a_figure(greeting)


@pytest.mark.parametrize(
    "greeting",
    [
        "Morning, Sam. One thing before you head out: the car wants a look.",
        "Evening. A quiet one, by the look of it.",
        "Good to see you. Nothing pressing today.",
    ],
)
def test_one_as_a_pronoun_or_article_is_allowed(greeting):
    assert not cascade.greeting_states_a_figure(greeting)
