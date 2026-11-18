# ruff: noqa: F811 - fixtures imported from the cascade tests
"""Figures said like a person, shown exactly (`assistant/spoken_figures.py`; Kevin, 2026-11-17:
"round when spoken like a real person. exact on screen for verification"). Invented amounts."""

from __future__ import annotations

from decimal import Decimal

import pytest

from agents.runtime.model import FakeModel, say
from assistant.spoken_figures import rounded_cents, speakable, spoken_money, wants_exact
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    PCM,
    _clean,
    _events,
    _of,
    _post,
    engine,
    member,
)

pytestmark = pytest.mark.django_db


@pytest.mark.parametrize(
    ("amount", "said"),
    [
        ("4.50", "$4.50"),
        ("19.99", "$19.99"),
        ("12", "$12"),
        ("43.20", "about $43"),
        ("285", "$285"),
        ("284.60", "about $285"),
        ("999.40", "about $999"),
        ("1280", "$1,280"),
        ("1283.40", "about $1,280"),
        ("2333.61", "about $2,330"),
        ("43217", "about $43,200"),
        ("999999", "about $1,000,000"),
        ("13742000", "about $13.7 million"),
        ("13700000", "$13.7 million"),
        ("2500000000", "$2.5 billion"),
    ],
)
def test_the_rounding_table(amount, said):
    assert spoken_money(Decimal(amount)) == said


def test_the_sign_is_said_once():
    # Round 2 speech review: "overdrawn by minus about" said the negative twice. Left out only
    # when the sign word is right before the amount (round 3 S1).
    assert speakable("You're overdrawn by -$2,333.61.") == "You're overdrawn by about $2,330."
    assert speakable("Balance: -$2,333.61.") == "Balance: minus about $2,330."


@pytest.mark.parametrize(
    ("sentence", "said"),
    [
        (
            "Short version: checking is at -$1,234.56.",
            "Short version: checking is at minus about $1,230.",
        ),
        (
            "Spending is down a bit and checking sits at -$45.20.",
            "Spending is down a bit and checking sits at minus about $45.",
        ),
        ("You owe them and the card is at -$300.", "You owe them and the card is at minus $300."),
    ],
)
def test_a_negative_amount_is_always_said_negative(sentence, said):
    # Round 3 S1: a sign word anywhere earlier in the sentence dropped the "minus".
    assert speakable(sentence) == said


@pytest.mark.parametrize(
    ("amount", "said"),
    [
        ("19.99", "$19.99"),
        ("20", "$20"),
        ("20.00", "$20"),
        ("999.50", "about $1,000"),
        ("999950", "about $1,000,000"),
        ("1000000", "$1 million"),
        ("0", "$0"),
    ],
)
def test_the_band_edges(amount, said):
    assert spoken_money(Decimal(amount)) == said


@pytest.mark.parametrize(
    "sentence",
    [
        "Rent ran $1,450-$1,500.",
        "Rent ran $1,450 -$1,500.",
        "Rent ran $1,450 – $1,500.",
        "It was $1,450 - $1,500 a month.",
        "Somewhere between $10 and $20.",
        "From $10 to $20, roughly.",
        "Either $45.10 or $52.80.",
    ],
)
def test_a_range_is_said_exactly_never_as_a_negative(sentence):
    assert speakable(sentence) == sentence


def test_percentages_are_whole_and_keep_one_decimal_under_ten():
    assert speakable("Up 12.4% this month.") == "Up about 12% this month."
    assert speakable("Down 4.25% this month.") == "Down about 4.3% this month."
    assert speakable("Down 4.5% this month.") == "Down 4.5% this month."


@pytest.mark.parametrize(
    "sentence",
    [
        "Order 48213 shipped on November 3 at 4:15.",
        "Your PO is PO-2333.61 and the part is AB-1283.",
        "Call 555-0142.",
        "You logged 3 coffees.",
    ],
)
def test_ids_dates_times_codes_and_quantities_are_never_rounded(sentence):
    assert speakable(sentence) == sentence


def test_a_confirmation_of_a_change_says_the_exact_amount():
    line = "Log $318.40 at the pharmacy?"
    assert speakable(line) == line


def test_in_a_turn_that_made_a_change_figures_are_said_exactly(member, engine):
    # Round 2 speech review 9: "Logged $318.40 at the pharmacy." was heard as "about $318".
    from agents.runtime.model import call

    engine.model = FakeModel(
        [call("create_checklist", {"name": "Pharmacy"}), say("Logged $318.40 at the pharmacy.")]
    )
    _events(_post(member, audio=PCM))
    assert "Logged $318.40 at the pharmacy." in [t for t, _v in engine.speech.spoken]


def test_exact_on_request():
    assert wants_exact("What did I spend, to the cent?")
    assert wants_exact("Exactly how much was it?")
    assert not wants_exact("How much did I spend?")
    assert speakable("You spent $2,333.61.", exact=True) == "You spent $2,333.61."


def test_already_hedged_is_not_hedged_twice():
    assert speakable("It's about $2,333.61.") == "It's about $2,330."


def test_the_heard_back_check_accepts_the_rounded_form_and_rejects_a_wrong_one():
    tool_cents = 233_361
    allowed = {tool_cents, rounded_cents(tool_cents)}
    assert 233_000 in allowed
    assert 240_000 not in allowed
    assert rounded_cents(-4_320) == -4_300


def test_a_rounded_spoken_figure_has_its_exact_form_on_screen_in_the_same_turn(member, engine):
    engine.model = FakeModel([say("You spent $2,333.61 last month.")])
    events = _events(_post(member, audio=PCM))
    shown = [s["text"] for s in _of(events, "sentence")]
    assert shown == ["You spent $2,333.61 last month."]
    spoken = [t for t, _v in engine.speech.spoken]
    assert spoken == ["You spent about $2,330 last month."]


def test_asked_for_exactly_it_is_said_exactly(member, engine):
    engine.speech.heard = "Exactly how much did we spend last month?"
    engine.model = FakeModel([say("You spent $2,333.61 last month.")])
    _events(_post(member, audio=PCM))
    assert [t for t, _v in engine.speech.spoken] == ["You spent $2,333.61 last month."]
