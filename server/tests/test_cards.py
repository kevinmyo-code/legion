"""Cards (`engine_mcp/cards.py`): what the web chat draws beside the transcript.

One test per builder on plain rows (shape, cents, flags in words, zone), then the tool door:
the card comes back beside `response`, and `response` (what the browser forwards to Gemini) is
exactly what it was before cards existed. A builder that raises costs the card, never the read.
"""

from __future__ import annotations

import datetime as dt
import json
import uuid
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest

from engine_mcp import cards

CHICAGO = ZoneInfo("America/Chicago")


# =============================================================================
# bars
# =============================================================================


def _spend_body(**over):
    body = {
        "month": "2026-11",
        "accounts": [
            {"spend_cents": 70_000, "unverified_cents": 1_240},
        ],
        "categories": [
            {"category": f"Cat{i}", "spend_cents": 1_000 * (i + 1), "unverified": False}
            for i in range(9)
        ]
        + [
            {"category": "Groceries", "spend_cents": 25_050, "unverified": True},
            {"category": "Budgeted", "spend_cents": 0, "unverified": False},
        ],
        "uncategorised_cents": 3_000,
        "complete": False,
    }
    body.update(over)
    return body


def test_bars_are_cents_largest_first_with_unverified_flagged_in_words():
    card = cards.spend_bars(_spend_body(), "usd")
    assert card["card"] == "bars" and card["unit"] == "usd"
    assert card["period"] == "November 2026"
    points = card["points"]
    assert len(points) == 8
    assert points[0] == {"label": "Groceries", "value": 25_050, "flag": "unverified"}
    assert all(isinstance(p["value"], int) for p in points)
    assert "flag" not in points[1]
    # 10 categories with spend, 8 shown; the zero-spend budget line is not a bar.
    assert card["more"] == 2
    assert "Includes $12.40 unverified." in card["note"]
    assert "Total $700.00." in card["note"]
    assert "$30.00 has no category" in card["note"]
    assert "does not cover the whole month" in card["note"]


def test_bars_are_usd_only_and_absent_for_an_empty_month():
    assert cards.spend_bars(_spend_body(), "SGD") is None
    assert cards.spend_bars(_spend_body(categories=[]), "USD") is None


def test_money_words():
    assert cards.money_words(123_456) == "$1,234.56"
    assert cards.money_words(-5) == "-$0.05"


# =============================================================================
# timeline
# =============================================================================


def _event(title, starts_at, *, ends_at=None, all_day=False, kind="event", **repeat):
    return {
        "id": uuid.uuid4(),
        "title": title,
        "starts_at": starts_at,
        "ends_at": ends_at,
        "all_day": all_day,
        "kind": kind,
        "repeat_kind": None,
        **repeat,
    }


def test_timeline_is_one_local_day_all_day_first_with_an_offset_zone():
    day = dt.date(2026, 11, 4)
    # `from: "2026-11-04"` reaches list_events as UTC midnight (`_parse_instant`).
    window_start = dt.datetime(2026, 11, 4, 0, 0, tzinfo=dt.UTC)
    window_end = window_start + dt.timedelta(days=14)
    dentist = _event(
        "Dentist",
        dt.datetime(2026, 11, 4, 21, 30, tzinfo=dt.UTC),  # 15:30 Chicago
        ends_at=dt.datetime(2026, 11, 4, 22, 15, tzinfo=dt.UTC),
    )
    # 22:30 Chicago is 04:30Z the NEXT day: still Nov 4 on the household's clock.
    late = _event("Bins out", dt.datetime(2026, 11, 5, 4, 30, tzinfo=dt.UTC), kind="task")
    # All-day rows are UTC midnight of the date they name; never read in the zone.
    holiday = _event("Holiday", dt.datetime(2026, 11, 4, 0, 0, tzinfo=dt.UTC), all_day=True)
    idea = _event("Farmers market", dt.datetime(2026, 11, 4, 15, 0, tzinfo=dt.UTC), kind="suggestion")
    later = _event("Next week", dt.datetime(2026, 11, 10, 15, 0, tzinfo=dt.UTC))
    card = cards.events_timeline(
        [later, dentist, late, idea, holiday], {}, window_start, window_end, CHICAGO, day
    )
    assert card["card"] == "timeline"
    assert card["date"] == "2026-11-04"
    assert card["zone"] == "UTC-06:00"
    assert card["items"] == [
        {"start": None, "end": None, "all_day": True, "title": "Holiday", "kind": "event"},
        {"start": "09:00", "end": None, "all_day": False, "title": "Farmers market", "kind": "suggestion"},
        {"start": "15:30", "end": "16:15", "all_day": False, "title": "Dentist", "kind": "event"},
        {"start": "22:30", "end": None, "all_day": False, "title": "Bins out", "kind": "todo"},
    ]
    assert card["more"] == 1
    assert card["note"] == "Suggestions are ideas, not plans."


def test_timeline_expands_a_repeating_event_and_honours_a_skip():
    window_start = dt.datetime(2026, 11, 2, 6, 0, tzinfo=dt.UTC)
    window_end = window_start + dt.timedelta(days=7)
    gym = _event(
        "Gym",
        dt.datetime(2026, 10, 1, 12, 0, tzinfo=dt.UTC),  # 07:00 Chicago (CDT)
        repeat_kind="DAILY",
        repeat_every=1,
        repeat_end_kind="NEVER",
    )
    on = cards.events_timeline([gym], {}, window_start, window_end, CHICAGO, dt.date(2026, 11, 3))
    # A local wall-clock time, kept across the DST change on Nov 1.
    assert [i["start"] for i in on["items"]] == ["07:00"]
    skipped = cards.events_timeline(
        [gym], {gym["id"]: {dt.date(2026, 11, 3)}}, window_start, window_end, CHICAGO,
        dt.date(2026, 11, 3),
    )
    assert skipped["items"] == []


# =============================================================================
# checklist
# =============================================================================


def test_checklist_needs_a_day_and_says_how_many_lists_are_not_shown():
    lists = [
        {"name": "Groceries", "items": [
            {"text": "Milk", "ticked_on_date": True},
            {"text": "Eggs", "ticked_on_date": False},
        ]},
        {"name": "Packing", "items": []},
    ]
    assert cards.checklist_card(lists, None) is None
    card = cards.checklist_card(lists, dt.date(2026, 11, 4))
    assert card["card"] == "checklist" and card["list_name"] == "Groceries"
    assert card["items"] == [{"text": "Milk", "done": True}, {"text": "Eggs", "done": False}]
    assert card["more"] == 0
    assert card["note"] == "1 other list not shown."


# =============================================================================
# car and steps
# =============================================================================

VID = "7d1c7b9e-0000-4000-8000-000000000001"


def _car_values(*, recalls_ok=True):
    values = {
        "service": {
            "outcome": "ok",
            "structured": {
                "vehicles": [
                    {
                        "vehicle_id": VID,
                        "name": "Cherokee",
                        "overdue": [],
                        "next_by_date": {"service_name": "Oil change", "due_on": "2026-12-01"},
                        "next_by_miles": {"service_name": "Oil change", "due_at_miles": 121_000},
                        "odometer": {"miles": 120_400, "read_on": "2026-10-30", "source": "code"},
                    }
                ]
            },
        },
        "codes": {
            "outcome": "ok",
            "structured": {
                "rows": [
                    {"vehicle_id": VID, "occurred_at": "2026-09-01T10:00:00Z", "codes": ["P0300"]},
                    {"vehicle_id": VID, "occurred_at": "2026-10-20T10:00:00Z", "codes": ["P0420", "ZZ999"]},
                ]
            },
        },
        "clears": {
            "outcome": "ok",
            "structured": {"rows": [{"vehicle_id": VID, "occurred_at": "2026-10-01T10:00:00Z"}]},
        },
    }
    if recalls_ok:
        values["recalls"] = {
            "outcome": "ok",
            "structured": {"recalls": [{"component": "AIR BAGS", "summary": "May not deploy."}]},
        }
    else:
        values["recalls"] = {"outcome": "failed", "structured": None}
    return values


def test_car_card_codes_since_the_clear_with_meanings_and_an_estimate():
    card = cards.car_card(_car_values())
    assert card["card"] == "car" and card["name"] == "Cherokee"
    assert card["mileage"] == 120_400 and card["mileage_as_of"] == "2026-10-30"
    assert card["next_service"] == {
        "label": "Oil change",
        "due_date": "2026-12-01",
        "due_miles": 121_000,
        "estimate": True,
    }
    # P0300 was before the clear; an unknown code keeps an empty meaning, never a guess.
    meaning = cards.dtc_titles()["P0420"]
    assert card["codes"] == [{"code": "P0420", "meaning": meaning}, {"code": "ZZ999", "meaning": ""}]
    assert card["recalls"] == [{"summary": "Air bags: May not deploy."}]
    assert "estimate" in card["note"]


def test_a_recall_lookup_that_failed_is_said_not_drawn_as_none():
    card = cards.car_card(_car_values(recalls_ok=False))
    assert card["recalls"] == []
    assert "Recalls were not looked up, so whether there are any is not known." in card["note"]


def test_several_cars_is_no_car_card():
    values = _car_values()
    car = values["service"]["structured"]["vehicles"][0]
    values["service"]["structured"]["vehicles"] = [car, {**car, "vehicle_id": "x", "name": "M3"}]
    assert cards.car_card(values) is None


def test_steps_card_labels_in_words_status_in_words_and_ms():
    done = [
        {"step_id": "service", "outcome": "ok", "ms": 12, "tool": "next_service"},
        {"step_id": "which_car", "outcome": "refused", "ms": 3, "tool": ""},
        {"step_id": "confirm", "outcome": "asked", "ms": 1, "tool": ""},
    ]
    card = cards.steps_card("money_check", done, ["recalls"], "partial")
    assert card["card"] == "steps" and card["name"] == "Money check"
    assert card["items"] == [
        {"label": "Service", "status": "ok", "ms": 12},
        {"label": "Which car", "status": "failed", "ms": 3},
        {"label": "Recalls", "status": "skipped", "ms": 0},
    ]
    assert card["total_ms"] == 15
    assert card["note"] == "Stopped part way."
    assert "next_service" not in json.dumps(card), "a tool name never reaches the screen"


def test_dtc_titles_match_the_phone_seed():
    seed = Path(__file__).resolve().parents[2] / "app/src/main/assets/dtc_descriptions_seed.json"
    if not seed.exists():
        pytest.skip("the phone's seed is not in this checkout")
    phone = json.loads(seed.read_text(encoding="utf-8"))
    assert cards.dtc_titles() == {code: row["title"] for code, row in phone.items()}


def test_a_builder_that_raises_is_no_card():
    def boom():
        raise RuntimeError("bug")

    assert cards.safe(boom) is None


# =============================================================================
# The tool door
# =============================================================================


def _last_month() -> str:
    """The demo seeds categorised spend for whole past months; the current one may have none."""
    first = dt.date.today().replace(day=1)
    return (first - dt.timedelta(days=1)).strftime("%Y-%m")


@pytest.mark.django_db
def test_the_card_is_beside_response_and_never_in_what_is_forwarded(household_user, household_a):
    from household import demo_seed
    from tests.test_assistant import browser

    demo_seed.seed_household(household_a, today=dt.date.today())
    client = browser(household_user)
    response = client.post(
        "/api/assistant/tool", {"name": "monthly_spend", "args": {"month": _last_month()}}, format="json"
    )
    assert response.status_code == 200, response.data
    assert "card" in response.data, response.data["text"]
    card = response.data["card"]
    assert card["card"] == "bars" and card["unit"] == "usd" and card["points"]
    assert response.data["response"] == {
        "success": True,
        "message": response.data["text"],
    }
    assert "card" not in json.dumps(response.data["response"])


@pytest.mark.django_db
def test_a_card_builder_bug_never_fails_the_read(household_user, household_a, monkeypatch):
    from household import demo_seed
    from tests.test_assistant import browser

    demo_seed.seed_household(household_a, today=dt.date.today())

    def boom(*args, **kwargs):
        raise RuntimeError("bug in a card")

    monkeypatch.setattr(cards, "spend_bars", boom)
    response = browser(household_user).post(
        "/api/assistant/tool", {"name": "monthly_spend", "args": {"month": _last_month()}}, format="json"
    )
    assert response.status_code == 200
    assert response.data["is_error"] is False
    assert "card" not in response.data


@pytest.mark.django_db
def test_list_events_returns_a_timeline_in_the_households_zone(household_user, household_a):
    from tests.test_assistant import browser

    household_a.timezone = "America/Chicago"
    household_a.save(update_fields=["timezone"])
    client = browser(household_user)
    made = client.post(
        "/api/assistant/tool",
        {"name": "add_event", "args": {"fields": {"title": "Dentist", "starts_at": "2026-10-06T20:00Z"}}},
        format="json",
    )
    assert made.data["is_error"] is False, made.data
    assert "card" not in made.data
    listed = client.post(
        "/api/assistant/tool",
        {"name": "list_events", "args": {"from": "2026-10-06", "to": "2026-10-07"}},
        format="json",
    )
    card = listed.data["card"]
    assert card["date"] == "2026-10-06"
    assert card["zone"] == "UTC-05:00"
    assert [(i["start"], i["title"]) for i in card["items"]] == [("15:00", "Dentist")]


@pytest.mark.django_db
def test_a_recruiter_visit_gets_cards_from_its_own_demo(world, monkeypatch):  # noqa: F811
    from tests.test_procedures_builtins import fake_recalls
    from tests.test_recruiter_isolation import _leaks, _tool

    fake_recalls(monkeypatch)
    spend = _tool(world["browser"], "monthly_spend", {"month": _last_month()})
    assert spend.data["card"]["card"] == "bars"
    car = _tool(world["browser"], "run_procedure", {"name": "car_check"})
    assert car.data["is_error"] is False, car.data["text"]
    card = car.data["card"]
    assert card["card"] == "car", card
    assert card["recalls"], "the fake NHTSA answer is on the card"
    for body in (spend.data["card"], card):
        assert _leaks(world, json.dumps(body)) == []


from tests.test_recruiter_isolation import world  # noqa: E402, F401 - fixture
