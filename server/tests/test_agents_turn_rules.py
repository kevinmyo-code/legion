"""Characterization tests for the turn rules lifted out of the web door in slice E0
(`agents/legion/ask_again.py`, `sentence_rules.py`, `closing.py`). They pin what the web door does
today, oddities included, so slice E3 can apply the same rules to the agent turn. Pure functions:
no database, no model."""

from __future__ import annotations

import pytest

from agents.legion import sentence_gate as gates
from agents.legion.ask_again import asks_again
from agents.runtime.loop import NO_ANSWER_WORDS

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


# ---- the sentence gate: said, held or dropped --------------------------------------------------

CLAIM = "I added milk to your list."
WROTE = [{"name": "add_item", "writes": True, "ok": True}]


def _gate(backed=None, torque=None, actions=()):
    return gates.SentenceGate(
        backed=lambda: backed, torque=lambda: torque, actions=lambda: list(actions)
    )


def _answer(gate, text, sentences=0, verbatim_said=False, max_spoken=12):
    return gate.in_answer(
        text, sentences=sentences, verbatim_said=verbatim_said, max_spoken=max_spoken
    )


def test_an_ordinary_sentence_is_said_everywhere():
    gate = _gate()
    assert _answer(gate, "Your day is clear.") == gates.SAY
    assert gate.in_ask_again("Your day is clear.", verbatim_said=False) == gates.SAY
    assert gate.held("Your day is clear.") == gates.SAY


def test_blank_is_skipped_uncounted():
    assert _answer(_gate(), "") == gates.SKIP
    assert _gate().in_ask_again("", verbatim_said=False) == gates.SKIP


def test_the_runtime_no_answer_line_is_held_back_in_the_answer():
    assert _answer(_gate(), NO_ANSWER_WORDS) == gates.NO_ANSWER


def test_oddity_the_no_answer_line_is_not_special_in_the_ask_again_round():
    # Pinned, not fixed: the ask-again round has no check for it, so it would be said.
    assert _gate().in_ask_again(NO_ANSWER_WORDS, verbatim_said=False) == gates.SAY


def test_oddity_filler_is_dropped_only_as_the_first_sentence_of_the_answer():
    assert _answer(_gate(), "Checking now.", sentences=0) == gates.FILLER
    assert _answer(_gate(), "Checking now.", sentences=1) == gates.SAY


def test_oddity_filler_is_dropped_anywhere_in_the_ask_again_round_and_not_counted():
    assert _gate().in_ask_again("Checking now.", verbatim_said=False) == gates.SKIP


def test_money_after_a_code_money_line_is_counted_in_the_answer():
    assert _answer(_gate(), "That is 40 dollars.", verbatim_said=True) == gates.MONEY
    assert _answer(_gate(), "That is 40 dollars.", verbatim_said=False) == gates.SAY


def test_oddity_money_after_a_code_money_line_is_skipped_uncounted_in_the_ask_again_round():
    assert _gate().in_ask_again("That is 40 dollars.", verbatim_said=True) == gates.SKIP


def test_a_figure_no_money_tool_returned_is_dropped_and_counted():
    gate = _gate(backed={8427})
    assert _answer(gate, "You spent 84.27 on food.") == gates.SAY
    assert _answer(gate, "You spent 84.30 on food.") == gates.MONEY
    assert gate.in_ask_again("You spent 84.30 on food.", verbatim_said=False) == gates.MONEY
    assert gate.held("I paid 84.30 for you.") == gates.MONEY


def test_with_no_money_tool_a_figure_passes():
    assert _answer(_gate(backed=None), "You spent 84.30 on food.") == gates.SAY


def test_an_unbacked_torque_figure_is_dropped():
    import re

    from agents.legion.speech_rules import TorqueGuard

    guard = TorqueGuard(frozenset({"185"}), re.compile(r"pitman", re.I), whole_turn=False)
    gate = _gate(torque=guard)
    assert _answer(gate, "Torque it to 185 ft-lbs.") == gates.SAY
    assert _answer(gate, "Torque it to 190 ft-lbs.") == gates.TORQUE
    assert gate.in_ask_again("Torque it to 190 ft-lbs.", verbatim_said=False) == gates.TORQUE


def test_oddity_past_the_cap_a_sentence_is_dropped_but_money_and_torque_still_count_first():
    gate = _gate()
    assert _answer(gate, "Your day is clear.", sentences=12) == gates.OVER_CAP
    # Not the first sentence, so filler is no longer dropped as filler: the cap drops it.
    assert _answer(gate, "Checking now.", sentences=12) == gates.OVER_CAP
    assert _answer(gate, "That is 40 dollars.", sentences=12, verbatim_said=True) == gates.MONEY


def test_oddity_a_claim_past_the_cap_is_dropped_not_held():
    # Pinned, not fixed: the cap comes before the honesty check, so the claim never reaches the
    # held list and no closing line speaks for it.
    assert _answer(_gate(), CLAIM, sentences=12) == gates.OVER_CAP


def test_oddity_the_ask_again_round_has_no_cap():
    assert _gate().in_ask_again("Your day is clear.", verbatim_said=False) == gates.SAY


def test_a_claim_with_no_write_is_held_then_unbacked_at_the_end():
    gate = _gate()
    assert _answer(gate, CLAIM) == gates.HOLD
    assert gate.in_ask_again(CLAIM, verbatim_said=False) == gates.HOLD
    assert gate.held(CLAIM) == gates.UNBACKED


def test_a_claim_backed_by_a_write_is_said():
    assert _answer(_gate(actions=WROTE), CLAIM) == gates.SAY
    assert _gate(actions=WROTE).held(CLAIM) == gates.SAY


def test_a_held_claim_is_said_once_a_write_backs_it_by_the_end():
    actions: list[dict] = []
    gate = gates.SentenceGate(backed=lambda: None, torque=lambda: None, actions=lambda: actions)
    assert _answer(gate, CLAIM) == gates.HOLD
    actions.extend(WROTE)
    assert gate.held(CLAIM) == gates.SAY


def test_oddity_a_held_claim_skips_the_verbatim_money_check():
    # Held claims are checked for unbacked figures and torque only, never `has_money`.
    gate = _gate(actions=WROTE)
    assert gate.held("I paid 40 dollars for you.") == gates.SAY


def test_inputs_are_asked_lazily_in_the_web_doors_order():
    asked: list[str] = []

    def backed():
        asked.append("backed")
        return None

    def torque():
        asked.append("torque")
        return None

    def actions():
        asked.append("actions")
        return []

    gate = gates.SentenceGate(backed=backed, torque=torque, actions=actions)
    assert _answer(gate, "Checking now.") == gates.FILLER
    assert asked == []
    assert _answer(gate, "That is 40 dollars.", verbatim_said=True) == gates.MONEY
    assert asked == []
    assert _answer(gate, "Your day is clear.") == gates.SAY
    assert asked == ["backed", "torque", "actions"]
    asked.clear()
    assert _answer(gate, "Your day is clear.", sentences=12) == gates.OVER_CAP
    assert asked == ["backed", "torque"]
