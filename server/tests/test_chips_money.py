"""The suggestion chips and the money-figure guard together (2026-11-18, merge of the money fix into
the chips branch). Invented figures and an invented profile only."""

# ruff: noqa: F811 - fixtures imported from the cascade, suggestions and money tests

from __future__ import annotations

import pytest

from agents.runtime.model import FakeModel, call, say
from demo import suggestions
from tests.test_assistant_cascade import (  # noqa: F401 - fixtures
    _clean,
    _events,
    _of,
    _post,
    engine,
    member,
    visitor,
    visitor_user,
)
from tests.test_demo_suggestions import loaded, seeded  # noqa: F401 - fixtures
from tests.test_money_bare_figure import _logged_money_dropped, _spend
from tests.test_profile_verbatim import ALL_SENTENCES

pytestmark = pytest.mark.django_db

QUESTION = "What has this household spent this month?"


def _money_chip(user) -> str:
    """A monthly_spend chip offered to `user`, as `POST /api/demo/chips` would sign it."""
    chip = {"tool": "monthly_spend", "args": {}, "fast": False, "question": QUESTION}
    return suggestions.offer(user, {"c": chip})


def test_a_tapped_money_chip_makes_a_money_turn_for_the_guard(
    visitor, visitor_user, engine, monkeypatch, caplog
):
    # ChipFirst runs the money lookup as round 1, in code: the guard must still see it.
    _spend(monkeypatch)
    engine.model = FakeModel([say("Groceries were 84.47. Housing was 1,450.00.")])
    events = _events(_post(visitor, text=QUESTION, chip=_money_chip(visitor_user)))
    assert _of(events, "tool")[0]["name"] == "monthly_spend"
    assert [s["text"] for s in _of(events, "sentence")] == ["Housing was 1,450.00."]
    assert _logged_money_dropped(caplog) == 1
    # Only the wording round reached the model.
    assert len(engine.model.requests) == 1


def test_no_part_of_a_dropped_sentence_is_spoken_first(member, engine, monkeypatch):
    # The speech-only first-clause cut runs after the checks: a long first sentence the money
    # check drops never has its first clause spoken.
    _spend(monkeypatch)
    engine.model = FakeModel(
        [
            call("monthly_spend", {}),
            say(
                "Groceries came to 84.47 across the two stores last month, which is about usual. "
                "Housing was 1,450.00."
            ),
        ]
    )
    events = _events(_post(member, text="What did we spend?", spoken="true"))
    spoken = " ".join(text for text, _voice in engine.speech.spoken)
    assert "Groceries came to" not in spoken
    assert [s["text"] for s in _of(events, "sentence")] == ["Housing was 1,450.00."]


@pytest.mark.parametrize(
    ("reply", "kept"),
    [
        # The check reads the model's sentence, not the speech form: backticks change nothing.
        ("Groceries were `84.47`.", False),
        ("Groceries were `84.27`.", True),
        ("See `docs/money/spend.md`: groceries were 84.47.", False),
        ("See `docs/money/spend.md`: groceries were 84.27.", True),
        ("Details at https://example.invalid/spend: groceries were 84.47.", False),
    ],
)
def test_the_wording_rules_never_change_the_figure_the_check_sees(
    member, engine, monkeypatch, reply, kept
):
    _spend(monkeypatch)
    engine.model = FakeModel([call("monthly_spend", {}), say(f"{reply} Fine.")])
    events = _events(_post(member, text="What did we spend?", spoken="true"))
    said = [s["text"] for s in _of(events, "sentence")]
    assert (reply in said) is kept
    assert "Fine." in said


def test_a_profile_chip_is_untouched_by_the_money_guard(
    loaded, seeded, visitor, visitor_user, engine, caplog
):
    from django.core.cache import cache

    cache.clear()
    chip = {
        "tool": "about_kevin",
        "args": {"sections": ["education"]},
        "fast": True,
        "question": "Where did he study?",
    }
    engine.model = FakeModel([])
    events = _events(
        _post(
            visitor, text="Where did he study?", chip=suggestions.offer(visitor_user, {"c": chip})
        )
    )
    said = [s["text"] for s in _of(events, "sentence")]
    assert engine.model.requests == []
    assert said and all(s in ALL_SENTENCES for s in said)
    assert _logged_money_dropped(caplog) == 0
