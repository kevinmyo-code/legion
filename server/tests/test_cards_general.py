"""The general card kinds (`engine_mcp/cards.py`): readings, status_list, trend.

Their shape is held by `cards.check()`, and `shared/cards/fixtures.json` holds one of each that
the web's and the phone's tests read too, so the three sides cannot drift apart unnoticed.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from engine_mcp import cards

FIXTURES = Path(__file__).resolve().parents[2] / "shared/cards/fixtures.json"


def _fixtures() -> list[dict]:
    if not FIXTURES.exists():
        pytest.skip("shared/cards/fixtures.json is not in this checkout")
    return json.loads(FIXTURES.read_text(encoding="utf-8"))["cards"]


def test_every_shared_fixture_fits_its_shape():
    kinds = set()
    for entry in _fixtures():
        assert cards.check(entry["card"]), entry["card"]["title"]
        kinds.add(entry["card"]["card"])
    assert kinds == set(cards.GENERAL_KINDS)


def test_status_words_are_the_ones_every_screen_says():
    statuses = next(e for e in _fixtures() if e["card"]["card"] == "status_list")
    said = [cards.STATUS_WORDS[i["status"]] for i in statuses["card"]["items"]]
    assert said == statuses["words"]["statuses"]
    assert set(cards.STATUS_WORDS) == set(cards.STATUSES)


def test_readings_keep_twelve_count_the_rest_and_say_unread_as_null():
    items = [cards.reading(f"R{i}", i, "°C") for i in range(14)]
    items.append(cards.reading("Fan", None, "rpm"))
    card = cards.readings_card("Laptop", items, at="2026-11-15T14:20", note="From the laptop.")
    assert cards.check(card)
    assert len(card["items"]) == cards.READINGS_MAX
    assert card["more"] == 3
    assert card["at"] == "2026-11-15T14:20"
    assert cards.reading("Fan", None, "rpm") == {"label": "Fan", "value": None, "unit": "rpm"}


def test_money_in_a_reading_is_integer_cents():
    assert cards.check(cards.readings_card("Spent", [cards.reading("Food", 1_250, "usd")]))
    assert not cards.check(cards.readings_card("Spent", [cards.reading("Food", 12.5, "usd")]))


def test_a_status_must_be_one_of_the_five_words():
    good = cards.status_list_card("Services", [cards.status("Voice", "not_reached")])
    assert cards.check(good)
    assert not cards.check(cards.status_list_card("Services", [cards.status("Voice", "green")]))


def test_a_trend_has_one_or_two_series_of_dated_points_newest_kept():
    points = [(f"2026-{1 + i // 28:02d}-{1 + i % 28:02d}", float(i)) for i in range(150)]
    line = cards.series("Weight", points)
    assert len(line["points"]) == cards.TREND_MAX
    assert line["points"][-1] == {"at": "2026-06-10", "value": 149.0}
    second = cards.series("Trend", points[:3], flag="estimate")
    card = cards.trend_card("Weight", "lb", [line, second])
    assert cards.check(card)
    assert not cards.check(cards.trend_card("Weight", "lb", []))
    three = cards.trend_card("Weight", "lb", [line, line, line])
    assert len(three["series"]) == 2
    bad_at = cards.trend_card("Weight", "lb", [cards.series("Weight", [("Nov 14", 1.0)])])
    assert not cards.check(bad_at)


def test_safe_drops_a_general_card_that_does_not_fit():
    def wrong():
        return {"card": "readings", "title": "x", "items": [{"label": "a"}], "more": 0}

    def right():
        return cards.readings_card("x", [cards.reading("a", 1, "")])

    assert cards.safe(wrong) is None
    assert cards.safe(right) == right()
