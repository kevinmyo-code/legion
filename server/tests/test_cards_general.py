"""The general card kinds (`engine_mcp/cards.py`): readings, status_list, trend.

Their shape is held by `cards.check()`, and `shared/cards/fixtures.json` holds one of each that
the web's and the phone's tests read too, so the three sides cannot drift apart unnoticed.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from engine_mcp import cards
from tests.test_recruiter_isolation import world  # noqa: F401 - fixture

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


def test_a_recruiter_gets_only_the_kinds_try_always_had():
    general = cards.readings_card("x", [cards.reading("a", 1, "")])
    bars = {"card": "bars", "title": "x", "unit": "", "points": [], "more": 0}
    assert cards.for_viewer(general, recruiter=True) is None
    assert cards.for_viewer(general, recruiter=False) == general
    assert cards.for_viewer(bars, recruiter=True) == bars
    assert cards.for_viewer(None, recruiter=True) is None


# =============================================================================
# School
# =============================================================================


def _course(name, score, letter=None, **over):
    body = {
        "course": name,
        "canvas_current_score": score,
        "canvas_current_grade": letter,
        "read_at": "2026-11-15T20:20:00+00:00",
    }
    body.update(over)
    return body


def test_grades_are_canvas_percents_and_a_hidden_total_is_words_not_a_figure():
    from zoneinfo import ZoneInfo

    card = cards.grades_card(
        [_course("Chemistry 101", 91.5, "A-"), _course("History 210", None)],
        ZoneInfo("America/Chicago"),
    )
    assert cards.check(card)
    assert card["title"] == "Grades"
    assert card["at"] == "2026-11-15T14:20"
    chem, hist = card["items"]
    assert chem == {"label": "Chemistry 101", "value": 91.5, "unit": "%", "note": "Canvas says A-."}
    assert hist == {"label": "History 210", "value": None, "unit": "", "text": "No total shown"}
    assert cards.grades_card([], ZoneInfo("UTC")) is None


def test_one_course_by_name_lists_its_assignments_in_words():
    from zoneinfo import ZoneInfo

    assignments = [
        {"name": "Lab 1", "score": 8.5, "points_possible": 10.0, "late": True},
        {"name": "Lab 2", "score": None, "points_possible": 10.0},
        {"name": "Quiz", "score": None, "missing": True},
        {"name": "Essay", "score": None, "excused": True},
    ]
    chem = _course("Chemistry 101", 91.5, assignments=assignments)
    card = cards.grades_card([chem], ZoneInfo("UTC"))
    assert cards.check(card)
    assert card["title"] == "Chemistry 101"
    lab1, lab2, quiz, essay = card["items"][1:]
    assert lab1 == {"label": "Lab 1", "value": 8.5, "unit": "of 10", "note": "Late."}
    assert lab2["text"] == "Not graded"
    assert quiz["text"] == "Missing"
    assert essay["text"] == "Excused"


def test_coursework_alone_is_titled_due_and_a_mixed_day_whats_on():
    import datetime as dt
    import uuid
    from zoneinfo import ZoneInfo

    def row(title, hour, kind):
        return {
            "id": uuid.uuid4(),
            "title": title,
            "starts_at": dt.datetime(2026, 11, 4, hour, 0, tzinfo=dt.UTC),
            "ends_at": None,
            "all_day": False,
            "kind": kind,
            "repeat_kind": None,
        }

    start = dt.datetime(2026, 11, 4, 6, 0, tzinfo=dt.UTC)
    end = start + dt.timedelta(days=7)
    zone = ZoneInfo("America/Chicago")
    day = dt.date(2026, 11, 4)
    due = [row("Lab report", 20, "task"), row("Problem set", 22, "task")]
    assert cards.events_timeline(due, {}, start, end, zone, day)["title"] == "Due"
    mixed = [*due, row("Class", 15, "event")]
    assert cards.events_timeline(mixed, {}, start, end, zone, day)["title"] == "What's on"
    assert cards.events_timeline([], {}, start, end, zone, day)["title"] == "What's on"


# =============================================================================
# Money
# =============================================================================


def _line(category, spent, cap, status, projection=None, **over):
    line = {
        "category": category,
        "spent_cents": spent,
        "cap_cents": cap,
        "status": status,
        "projection_cents": projection,
        "pending_cents": 0,
        "pending_count": 0,
        "unverified": False,
    }
    line.update(over)
    return line


def test_budget_pace_says_on_track_or_over_in_words_attention_first():
    body = {
        "month": "2026-11",
        "currency": "USD",
        "days_in_month": 30,
        "days_elapsed": 15,
        "projection_from_day": 10,
        "categories": [
            _line("Dining", 9_000, 20_000, "ok", 18_000),
            _line("Groceries", 52_000, 50_000, "over", 104_000, unverified=True),
            _line("Fuel", 6_000, 10_000, "ok", 12_000, pending_cents=1_500, pending_count=1),
            _line("Gifts", 3_000, None, "no cap"),
        ],
        "total": {"spent_cents": 67_000, "cap_cents": 80_000},
    }
    card = cards.budget_pace_card(body)
    assert cards.check(card)
    assert card["period"] == "November 2026, day 15 of 30"
    assert [(r["name"], r["status"]) for r in card["items"]] == [
        ("Fuel", "attention"),
        ("Groceries", "attention"),
        ("Dining", "ok"),
    ]
    fuel, groceries, dining = card["items"]
    assert fuel["note"].startswith("On pace to go over: $60.00 of $100.00.")
    assert "$15.00 of it pending." in fuel["note"]
    assert groceries["note"] == (
        "Over: $520.00 of $500.00. About $1,040.00 by month end (estimate). "
        "Includes unverified rows."
    )
    assert dining["note"] == "On track: $90.00 of $200.00. About $180.00 by month end (estimate)."
    assert card["note"] == "Altogether $670.00 of $800.00."


def test_budget_pace_is_no_card_without_caps_or_in_another_currency():
    body = {"currency": "USD", "categories": [_line("Gifts", 3_000, None, "no cap")]}
    assert cards.budget_pace_card(body) is None
    assert cards.budget_pace_card({"currency": "SGD", "categories": []}) is None


@pytest.mark.django_db
def test_a_member_gets_a_general_card_and_a_recruiter_visit_never_does(
    world,  # noqa: F811
    household_user,
    monkeypatch,
):
    """The door drops a kind /try never had, whichever tool made it (here `budgets`, which is
    on the recruiter's list), and leaves the words exactly as they were."""
    import finance.budgets
    from tests.test_cards import _door
    from tests.test_recruiter_isolation import _tool

    body = {
        "month": "2026-11",
        "currency": "USD",
        "days_in_month": 30,
        "days_elapsed": 15,
        "projection_from_day": 10,
        "categories": [_line("Food", 9_000, 20_000, "ok", 18_000)],
        "total": {"spent_cents": 9_000, "cap_cents": 20_000, "percent": 45},
        "fixed": [],
        "new_subscriptions": [],
    }
    for line in body["categories"]:
        line["percent"] = 45
    monkeypatch.setattr(finance.budgets, "budgets", lambda *a, **k: body)
    visit = _tool(world["browser"], "budgets", {})
    assert visit.data["is_error"] is False, visit.data["text"]
    assert "Food" in visit.data["text"]
    assert "card" not in visit.data
    member = _door(household_user)("budgets", {})
    assert member["card"]["card"] == "status_list"
    assert member["card"]["items"][0]["note"].startswith("On track: $90.00 of $200.00.")


# =============================================================================
# Weather
# =============================================================================


def _conditions(state, rows=None):
    section = {"kind": "alerts", "status": state, "text": "", "rows": rows or []}
    return {"where": "Home", "sections": [section]}


def test_alerts_are_rows_needing_attention_with_severity_and_until_in_words():
    alert = {
        "event": "Flood Watch",
        "severity": "Moderate",
        "ends": "2026-11-15T18:00:00-06:00",
        "expires": None,
        "sender": "NWS Sample Office",
    }
    card = cards.conditions_card(_conditions("found", [alert]))
    assert cards.check(card)
    assert card["scope"] == "Home"
    assert card["items"] == [
        {
            "name": "Flood Watch",
            "status": "attention",
            "note": "Moderate, until Nov 15 6:00 PM, UTC-6, from NWS Sample Office.",
        }
    ]


def test_no_alert_is_ok_a_failed_read_is_unknown_never_all_clear():
    assert cards.conditions_card(_conditions("empty"))["items"][0]["status"] == "ok"
    failed = cards.conditions_card(_conditions("failed"))["items"][0]
    assert failed["status"] == "unknown" and "not known" in failed["note"]
    assert cards.conditions_card({"sections": [{"kind": "rivers", "status": "empty"}]}) is None


# =============================================================================
# Body, memory, drives
# =============================================================================


def test_weight_trend_is_the_weighed_line_and_an_estimated_trend_gaps_left_out():
    data = {
        "points": [
            {"day": "2026-11-01", "weight_lbs": 186.2, "trend_lbs": 186.0},
            {"day": "2026-11-02", "weight_lbs": None, "trend_lbs": 185.8},
            {"day": "2026-11-03", "weight_lbs": 184.9, "trend_lbs": 185.5},
        ],
        "rate_lbs_per_week": -0.8,
    }
    card = cards.weight_trend_card(data)
    assert cards.check(card)
    weighed, trend = card["series"]
    assert [p["at"] for p in weighed["points"]] == ["2026-11-01", "2026-11-03"]
    assert trend["flag"] == "estimate" and len(trend["points"]) == 3
    assert card["note"] == "Losing 0.8 lb a week (estimate). Days with no weigh-in are left out."
    assert cards.weight_trend_card({"points": []}) is None


def test_memories_are_words_under_their_day_never_a_forget_on_the_web():
    rows = [
        {
            "id": "m1",
            "text": "Prefers window seats.",
            "shared": False,
            "created_at": "2026-11-02T10:00:00Z",
            "source": "phone",
        }
    ]
    card = cards.memories_card(rows)
    assert cards.check(card)
    item = card["items"][0]
    assert item["text"] == "Prefers window seats." and item["value"] is None
    assert item["label"].startswith("2026-11-02, private")
    assert "forget" not in json.dumps(card).lower()


def test_drives_are_miles_and_minutes_and_never_fuel_economy():
    from zoneinfo import ZoneInfo

    rows = [
        {
            "started_at": "2026-11-14T13:05:00Z",
            "ended_at": "2026-11-14T13:37:00Z",
            "miles": 12.34,
            "gallons": 0.5,
        },
        {"started_at": "2026-11-13T22:00:00Z", "ended_at": None, "miles": None},
    ]
    card = cards.drives_card(rows, ZoneInfo("America/Chicago"))
    assert cards.check(card)
    first, second = card["items"]
    assert first == {"label": "Sat Nov 14, 07:05", "value": 12.3, "unit": "mi", "note": "32 min."}
    assert second["value"] is None and second["note"] == "End time not stored."
    assert "mpg" not in json.dumps(card).lower()
    assert cards.drives_card([], ZoneInfo("UTC")) is None
