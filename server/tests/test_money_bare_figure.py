# ruff: noqa: F811 - fixtures imported from the cascade tests
"""A money figure the tools never returned is not spoken (2026-11-18, the chips ship's live gate).

The model answered a monthly spend with its first figure as "N USD" and the rest as bare decimals,
one of them a cent off what the tool returned. Invented figures only."""

from __future__ import annotations

import dataclasses

import pytest

from agents.runtime.model import FakeModel, call, say
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

pytestmark = pytest.mark.django_db


def _fake(monkeypatch, name, text, body):
    from engine_mcp import tools as engine_tools

    def handler(request, arguments):
        return engine_tools.ok(text, body)

    tool = dataclasses.replace(engine_tools.TOOLS_BY_NAME[name], handler=handler)
    monkeypatch.setitem(engine_tools.TOOLS_BY_NAME, name, tool)


def _spend(monkeypatch):
    _fake(
        monkeypatch,
        "monthly_spend",
        # The total and the daily average are in the tool's own words only, as the real handler
        # adds its total up and states it without storing it.
        "Categorised spend for 2031-04 is 1,962.48 USD, about 65.42 a day.",
        {
            "month": "2031-04",
            "accounts": [{"label": "Checking", "spend_cents": 190000}],
            "categories": [
                {"category": "Housing", "spend_cents": 145000},
                {"category": "Groceries", "spend_cents": 8427},
                {"category": "Utilities", "spend_cents": 9912},
            ],
            "uncategorised_cents": 21836,
        },
    )


def _groceries(monkeypatch):
    _fake(
        monkeypatch,
        "grocery_spend",
        "USD: 45.23 across 2 receipt(s).",
        {"currencies": [{"currency": "USD", "total_cents": 4523, "receipts": 2}]},
    )


def test_a_bare_decimal_the_money_tool_did_not_return_is_not_spoken(member, engine, monkeypatch):
    _spend(monkeypatch)
    engine.model = FakeModel(
        [
            call("monthly_spend", {}),
            say(
                "1962.48 USD. Housing was the biggest at 1450, then groceries at 84.47, "
                "and utilities at 99.12 that month."
            ),
        ]
    )
    events = _events(_post(member, text="How much went out in April?", spoken="true"))
    said = " ".join(s["text"] for s in _of(events, "sentence"))
    spoken = " ".join(text for text, _voice in engine.speech.spoken)
    assert "84.47" not in said
    assert "84.47" not in spoken and "84 point 47" not in spoken.lower()


M, G, E = "monthly_spend", "grocery_spend", "list_events"

# id | tools run before the reply | the reply's sentences | the sentences said
TABLE = [
    ("exact with $", [M], ["Groceries were $84.27."], ["Groceries were $84.27."]),
    ("exact with USD", [M], ["Groceries were 84.27 USD."], ["Groceries were 84.27 USD."]),
    ("exact bare", [M], ["Groceries were 84.27."], ["Groceries were 84.27."]),
    ("cent miss bare", [M], ["Groceries were 84.47.", "That is all."], ["That is all."]),
    ("cent miss with $", [M], ["It was $84.47 on groceries.", "Fine."], ["Fine."]),
    ("cent miss with USD", [M], ["Groceries were 84.47 USD.", "Fine."], ["Fine."]),
    ("comma exact", [M], ["Housing was 1,450.00."], ["Housing was 1,450.00."]),
    ("comma miss", [M], ["Housing was 1,450.01.", "Fine."], ["Fine."]),
    ("total from the tool's text", [M], ["In all 1962.48 USD."], ["In all 1962.48 USD."]),
    ("total from the text, comma", [M], ["In all $1,962.48."], ["In all $1,962.48."]),
    ("average from the text", [M], ["About 65.42 a day."], ["About 65.42 a day."]),
    ("uncategorised field", [M], ["218.36 has no category."], ["218.36 has no category."]),
    (
        "negative of a backed amount",
        [M],
        ["Utilities came to -99.12."],
        ["Utilities came to -99.12."],
    ),
    (
        "figure from another tool, same turn",
        [M, G],
        ["Receipts came to 45.23."],
        ["Receipts came to 45.23."],
    ),
    ("figure from a tool that did not run", [G], ["Housing was 1,450.00.", "Fine."], ["Fine."]),
    ("one good, one bad", [M], ["Housing 1,450.00 and groceries 84.47.", "Fine."], ["Fine."]),
    ("parenthesis", [M], ["Groceries (84.27) were next."], ["Groceries (84.27) were next."]),
    # Known limit: whole figures are not checked (a bare number cannot be told from a count).
    ("whole number, unchecked", [M], ["Housing took about 1,400."], ["Housing took about 1,400."]),
    ("whole number miss, unchecked", [M], ["Housing took 1451."], ["Housing took 1451."]),
    # No money tool ran: a decimal is not money and nothing is checked.
    (
        "no money tool, a date",
        [E],
        ["The fair is on 11.18 this year."],
        ["The fair is on 11.18 this year."],
    ),
    ("no money tool, a version", [E], ["It runs version 3.12."], ["It runs version 3.12."]),
    ("no tool at all", [], ["Pi is about 3.14."], ["Pi is about 3.14."]),
    # Known cost: in a money turn every two-place decimal must be one the tools returned.
    ("money turn, a version", [M], ["It runs version 3.12.", "Fine."], ["Fine."]),
    ("money turn, a percentage", [M], ["Rent is 74.88% of it.", "Fine."], ["Fine."]),
]


@pytest.mark.parametrize(("case", "tools", "reply", "said"), TABLE, ids=[row[0] for row in TABLE])
def test_what_is_said_of_a_money_answer(member, engine, monkeypatch, case, tools, reply, said):
    _spend(monkeypatch)
    _groceries(monkeypatch)
    engine.model = FakeModel([*(call(t, {}) for t in tools), say(" ".join(reply))])
    events = _events(_post(member, text="What did we spend?"))
    assert [s["text"] for s in _of(events, "sentence")] == said


def test_a_money_answer_with_every_sentence_dropped_says_the_existing_line(
    member, engine, monkeypatch
):
    from assistant import cascade

    _spend(monkeypatch)
    engine.model = FakeModel([call(M, {}), say("Groceries were 84.47.")])
    events = _events(_post(member, text="What did we spend?"))
    said = [s["text"] for s in _of(events, "sentence")]
    assert said == [cascade.NO_TEXT + cascade.NOTHING_CHANGED]


# ---- blind review, 2026-11-18 ----------------------------------------------------------------


def _logged_money_dropped(caplog) -> int:
    import re

    lines = [r.getMessage() for r in caplog.records if "cascade_turn" in r.getMessage()]
    return int(re.search(r"money_dropped=(\d+)", lines[-1]).group(1))


def test_a_figure_only_a_non_money_tool_stated_does_not_back_a_money_sentence(
    member, engine, monkeypatch
):
    # Review finding 1: only a money tool's own text backs figures, never another tool's.
    _spend(monkeypatch)
    _fake(monkeypatch, E, "Fair tickets are 84.47 each.", {"events": []})
    engine.model = FakeModel([call(M, {}), call(E, {}), say("Groceries were 84.47. Fine.")])
    events = _events(_post(member, text="What did we spend?"))
    assert [s["text"] for s in _of(events, "sentence")] == ["Fine."]


def test_a_drop_in_the_ask_again_round_is_counted_in_the_log(member, engine, monkeypatch, caplog):
    # Review finding 2: the ask-again round's drops reached the log as 0.
    _spend(monkeypatch)
    empty = [*say("")[2:]]
    engine.model = FakeModel([call(M, {}), empty, say("Groceries were 84.47.")])
    _events(_post(member, text="What did we spend?"))
    assert _logged_money_dropped(caplog) == 1


def test_the_drop_count_reaches_the_log(member, engine, monkeypatch, caplog):
    _spend(monkeypatch)
    engine.model = FakeModel(
        [call(M, {}), say("Groceries were 84.47. Utilities were 99.13. Fine.")]
    )
    _events(_post(member, text="What did we spend?"))
    assert _logged_money_dropped(caplog) == 2


def test_a_recruiters_money_answer_is_checked_too(visitor, engine, monkeypatch):
    _spend(monkeypatch)
    engine.model = FakeModel([call(M, {}), say("Groceries were 84.47. Housing was 1,450.00.")])
    events = _events(_post(visitor, text="What did we spend?"))
    assert [s["text"] for s in _of(events, "sentence")] == ["Housing was 1,450.00."]
