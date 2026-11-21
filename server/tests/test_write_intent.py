"""The write-intent check behind the carry's one exception (re-check R1): his own words must ask
for the change. Its misses are pinned, not fixed: a false positive weakens that one turn to the
plain exception (his next message may write); a false negative costs him one more ask."""

from __future__ import annotations

import pytest

from assistant.write_intent import asks_for_change


@pytest.mark.parametrize(
    "words",
    [
        "add milk to my list",
        "Search the invented fair and add it to my calendar.",
        "please delete the dentist on Friday",
        "Could you please rename the garage to Shop?",
        "remove eggs from the groceries",
        "mark the essay done",
        "remind me to call the plumber tomorrow",
        "look it up, then put it on my to-do list",
        "remember that I like oat milk",
        "can you move the meeting to Thursday",
        "Okay, set a reminder for noon",
    ],
)
def test_his_plain_requests_for_a_change_are_asks(words):
    assert asks_for_change(words) is True


@pytest.mark.parametrize(
    "words",
    [
        "what's on my list",
        "search for how to delete a file",
        "tell me about adding fractions",
        "search the invented museum hours",
        "when does the invented fair open?",
        "",
        None,
        "   ",
        "añade leche a mi lista",  # a language it cannot read: fails closed
    ],
)
def test_questions_and_reads_are_not_asks(words):
    assert asks_for_change(words) is False


@pytest.mark.parametrize(
    "words,got",
    [
        # Known false negatives (he asks once more): no change verb at a clause start.
        ("milk on my list please", False),
        ("the dentist should go on Friday instead", False),
        # Known false positives (that turn weakens to the plain exception): a verb used otherwise.
        ("add up these numbers for me", True),
        ("search how much it costs and save me the trouble of reading it", True),
    ],
)
def test_known_misses_are_pinned(words, got):
    assert asks_for_change(words) is got
