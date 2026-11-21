"""Characterization tests for the turn rules lifted out of the web door in slice E0
(`agents/legion/ask_again.py`, `sentence_rules.py`, `closing.py`). They pin what the web door does
today, oddities included, so slice E3 can apply the same rules to the agent turn. Pure functions:
no database, no model."""

from __future__ import annotations

import pytest

from agents.legion.ask_again import asks_again

# ---- ask-again: when a turn is asked once more -----------------------------------------------

_ASKED = dict(
    failed=False, status="done", sentences=0, filler_only=True, tools_ran=True, ending=False
)


def test_a_done_turn_with_tools_and_only_filler_is_asked_again():
    assert asks_again(**_ASKED) is True


@pytest.mark.parametrize(
    "change",
    [
        {"failed": True},
        {"status": "budget"},
        {"status": "timeout"},
        {"status": None},
        {"sentences": 1},
        {"filler_only": False},
        {"tools_ran": False},
        {"ending": True},
    ],
)
def test_any_one_condition_missing_and_the_turn_is_not_asked_again(change):
    assert asks_again(**{**_ASKED, **change}) is False
