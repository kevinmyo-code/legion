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
    idea = _event(
        "Farmers market", dt.datetime(2026, 11, 4, 15, 0, tzinfo=dt.UTC), kind="suggestion"
    )
    later = _event("Next week", dt.datetime(2026, 11, 10, 15, 0, tzinfo=dt.UTC))
    card = cards.events_timeline(
        [later, dentist, late, idea, holiday], {}, window_start, window_end, CHICAGO, day
    )
    assert card["card"] == "timeline"
    assert card["date"] == "2026-11-04"
    assert card["zone"] == "UTC-06:00"
    assert card["items"] == [
        {"start": None, "end": None, "all_day": True, "title": "Holiday", "kind": "event"},
        {
            "start": "09:00",
            "end": None,
            "all_day": False,
            "title": "Farmers market",
            "kind": "suggestion",
        },
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
        [gym],
        {gym["id"]: {dt.date(2026, 11, 3)}},
        window_start,
        window_end,
        CHICAGO,
        dt.date(2026, 11, 3),
    )
    assert skipped["items"] == []


# =============================================================================
# checklist
# =============================================================================


def test_checklist_needs_a_day_and_says_how_many_lists_are_not_shown():
    lists = [
        {
            "name": "Groceries",
            "items": [
                {"text": "Milk", "ticked_on_date": True},
                {"text": "Eggs", "ticked_on_date": False},
            ],
        },
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
                    {
                        "vehicle_id": VID,
                        "occurred_at": "2026-10-20T10:00:00Z",
                        "codes": ["P0420", "ZZ999"],
                    },
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
    assert card["codes"] == [
        {"code": "P0420", "meaning": meaning},
        {"code": "ZZ999", "meaning": ""},
    ]
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
        "/api/assistant/tool",
        {"name": "monthly_spend", "args": {"month": _last_month()}},
        format="json",
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
        "/api/assistant/tool",
        {"name": "monthly_spend", "args": {"month": _last_month()}},
        format="json",
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
        {
            "name": "add_event",
            "args": {"fields": {"title": "Dentist", "starts_at": "2026-10-06T20:00Z"}},
        },
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

# =============================================================================
# Follow-ups (Kevin, 2026-11-04: "go with ur recommendations")
# =============================================================================


def _door(user):
    from tests.test_assistant import browser

    client = browser(user)

    def call(name, args):
        response = client.post("/api/assistant/tool", {"name": name, "args": args}, format="json")
        assert response.status_code == 200, response.data
        return response.data

    return call


@pytest.fixture
def chicago(household_user, household_a):
    household_a.timezone = "America/Chicago"
    household_a.save(update_fields=["timezone"])
    return _door(household_user)


def _add(call, title, starts_at, **fields):
    made = call("add_event", {"fields": {"title": title, "starts_at": starts_at, **fields}})
    assert made["is_error"] is False, made["text"]
    return made


@pytest.mark.django_db
def test_a_date_is_the_households_own_day_evening_included(chicago):
    # 19:30 CDT on Oct 6 is 00:30Z on Oct 7: UTC-midnight windows missed it.
    _add(chicago, "Late dinner", "2026-10-07T00:30Z")
    # 00:30 CDT on Oct 6 is 05:30Z on Oct 6, and 23:30 CDT on Oct 5 is 04:30Z on Oct 6: the
    # first is on the day, the second is the evening before.
    _add(chicago, "Night shift", "2026-10-06T05:30Z")
    _add(chicago, "Day before", "2026-10-06T04:30Z")
    listed = chicago("list_events", {"from": "2026-10-06", "to": "2026-10-06"})
    assert "Late dinner" in listed["text"] and "Night shift" in listed["text"]
    assert "Day before" not in listed["text"]
    card = listed["card"]
    assert card["date"] == "2026-10-06"
    assert [(i["start"], i["title"]) for i in card["items"]] == [
        ("00:30", "Night shift"),
        ("19:30", "Late dinner"),
    ]
    assert card["more"] == 0


@pytest.mark.django_db
def test_the_day_the_clocks_go_back_is_25_hours_long(chicago):
    # Nov 1 2026, Chicago falls back at 02:00: the day runs 05:00Z to 06:00Z on Nov 2.
    _add(chicago, "Last thing", "2026-11-02T05:30Z")  # 23:30 CST Nov 1
    _add(chicago, "Next day", "2026-11-02T06:30Z")  # 00:30 CST Nov 2
    listed = chicago("list_events", {"from": "2026-11-01", "to": "2026-11-01"})
    assert "Last thing" in listed["text"] and "Next day" not in listed["text"]
    assert listed["card"]["zone"] == "UTC-05:00"  # the offset at the day's start (CDT)
    assert [i["title"] for i in listed["card"]["items"]] == ["Last thing"]


@pytest.mark.django_db
def test_repeating_events_come_with_their_occurrences_the_same_ones_the_card_shows(chicago):
    _add(
        chicago,
        "Gym",
        "2026-10-01T12:00Z",  # 07:00 CDT
        repeat_kind="DAILY",
        repeat_every=1,
        repeat_end_kind="NEVER",
    )
    skipped = chicago("skip_event_occurrence", {"id": _event_id(chicago), "date": "2026-10-07"})
    assert skipped["is_error"] is False, skipped["text"]
    listed = chicago("list_events", {"from": "2026-10-06", "to": "2026-10-08"})
    assert "occurrences: 2 occurrences" in listed["text"]
    assert "NOT expanded" not in listed["text"]
    text = listed["text"]
    occurrences = json.loads(text[text.index("{") :])["occurrences"]
    assert [(o["date"], o["starts_at"]) for o in occurrences] == [
        ("2026-10-06", "2026-10-06T07:00:00-05:00"),
        ("2026-10-08", "2026-10-08T07:00:00-05:00"),
    ]
    card = listed["card"]
    assert card["date"] == "2026-10-06"
    # Three days asked: each occurrence under its day; Oct 7 is skipped.
    assert [(i["date"], i["start"], i["title"]) for i in card["items"]] == [
        ("2026-10-06", "07:00", "Gym"),
        ("2026-10-08", "07:00", "Gym"),
    ]
    assert card["more"] == 0


def _event_id(call):
    listed = call("list_events", {"from": "2026-10-01", "to": "2026-10-01"})
    text = listed["text"]
    sections = json.loads(text[text.index("{") :])
    return (sections["in_window"] + sections["repeating"])[0]["id"]


@pytest.mark.django_db
def test_list_checklists_by_name_with_its_current_state_and_card(chicago):
    # Groceries is every household's built-in list (`checklists.builtin`).
    routine = chicago("create_checklist", {"name": "Morning", "schedule": "daily"})
    assert routine["is_error"] is False, routine["text"]
    lists = chicago("list_checklists", {})["text"]
    rows = {c["name"]: c["id"] for c in json.loads(lists[lists.index("[") :])}
    ids = {}
    for list_name, text in (("Groceries", "Milk"), ("Groceries", "Eggs"), ("Morning", "Meds")):
        added = chicago("add_checklist_item", {"checklist_id": rows[list_name], "text": text})
        assert added["is_error"] is False, added["text"]
        ids[text] = json.loads(added["text"][added["text"].index("{") :])["id"]
    # A plain list keeps a tick from any day; a scheduled one counts only today's.
    for text, list_name, day in (
        ("Milk", "Groceries", "2026-10-01"),
        ("Meds", "Morning", "2026-10-01"),
    ):
        ticked = chicago(
            "tick_checklist_item",
            {"checklist_id": rows[list_name], "item_id": ids[text], "date": day},
        )
        assert ticked["is_error"] is False, ticked["text"]

    named = chicago("list_checklists", {"name": "groceries"})
    assert named["is_error"] is False, named["text"]
    assert "ticked_now" in named["text"] and "Morning" not in named["text"]
    assert named["card"] == {
        "card": "checklist",
        "title": "Groceries",
        "list_name": "Groceries",
        "items": [{"text": "Milk", "done": True}, {"text": "Eggs", "done": False}],
        "more": 0,
    }
    morning = chicago("list_checklists", {"name": "MORNING"})
    assert morning["card"]["items"] == [{"text": "Meds", "done": False}]
    dated = chicago("list_checklists", {"name": "Morning", "date": "2026-10-01"})
    assert dated["card"]["items"] == [{"text": "Meds", "done": True}]
    assert dated["card"]["period"] == "2026-10-01"

    missing = chicago("list_checklists", {"name": "Hardware"})
    assert missing["is_error"] is True
    assert missing["text"].startswith("Nothing was read. No checklist is called 'Hardware'.")
    assert "Groceries" in missing["text"] and "Morning" in missing["text"]
    assert "card" not in missing


# =============================================================================
# Demo fixes (audit of conversation 512699a2, 2026-11-04)
# =============================================================================


def test_next_service_and_vehicle_recalls_each_make_a_car_card_with_only_their_part():
    service = cards.service_car(_car_values()["service"]["structured"])
    assert service["name"] == "Cherokee" and service["mileage"] == 120_400
    assert set(service["parts"]) == {"service"}
    assert service["codes"] == [] and service["recalls"] == []
    assert "Recalls" not in service["note"] and "codes" not in service["note"]
    recalls = cards.recalls_car(
        "Cherokee", {"recalls": [{"component": "AIR BAGS", "summary": "May not deploy."}]}
    )
    assert set(recalls["parts"]) == {"recalls"}
    assert recalls["mileage"] is None and recalls["next_service"] is None
    assert recalls["recalls"] == [{"summary": "Air bags: May not deploy."}]
    full = cards.car_card(_car_values())
    assert set(full["parts"]) == {"service", "codes", "recalls"}
    two = _car_values()["service"]["structured"]
    two["vehicles"] = two["vehicles"] * 2
    assert cards.service_car(two) is None


def test_a_window_of_days_carries_each_items_date():
    window_start = dt.datetime(2026, 11, 4, 5, 0, tzinfo=dt.UTC)  # 00:00 EST
    window_end = dt.datetime(2026, 11, 12, 4, 59, 59, tzinfo=dt.UTC)
    zone = ZoneInfo("America/New_York")
    rows = [
        _event("Dinner", dt.datetime(2026, 11, 5, 0, 0, tzinfo=dt.UTC)),  # 19:00 Nov 4
        _event(
            "Books due", dt.datetime(2026, 11, 4, 0, 0, tzinfo=dt.UTC), all_day=True, kind="task"
        ),
        _event("Conference", dt.datetime(2026, 11, 7, 21, 0, tzinfo=dt.UTC)),
    ]
    card = cards.events_timeline(
        rows, {}, window_start, window_end, zone, dt.date(2026, 11, 4), dt.date(2026, 11, 11)
    )
    assert card["date"] == "2026-11-04"
    assert card["period"] == "Wed Nov 4 to Wed Nov 11"
    assert [(i["date"], i["start"], i["title"]) for i in card["items"]] == [
        ("2026-11-04", None, "Books due"),
        ("2026-11-04", "19:00", "Dinner"),
        ("2026-11-07", "16:00", "Conference"),
    ]
    one_day = cards.events_timeline(rows, {}, window_start, window_end, zone, dt.date(2026, 11, 4))
    assert all("date" not in i for i in one_day["items"]) and "period" not in one_day


@pytest.mark.django_db
def test_a_recruiter_sees_today_and_this_week_and_service_then_recalls_cards(world, monkeypatch):  # noqa: F811
    from tests.test_procedures_builtins import fake_recalls
    from tests.test_recruiter_isolation import _leaks, _tool

    fake_recalls(monkeypatch)
    zone = ZoneInfo(world["demo"].timezone)
    today = dt.datetime.now(zone).date()
    day = _tool(
        world["browser"], "list_events", {"from": today.isoformat(), "to": today.isoformat()}
    )
    titles = [i["title"] for i in day.data["card"]["items"]]
    assert {
        "Library books due",
        "Call the plumber about the kitchen tap",
        "Dinner with the Okafors",
    } <= set(titles)
    week_end = (today + dt.timedelta(days=7)).isoformat()
    week = _tool(world["browser"], "list_events", {"from": today.isoformat(), "to": week_end})
    days = {i["date"] for i in week.data["card"]["items"]}
    assert today.isoformat() in days and len(days) >= 3

    service = _tool(world["browser"], "next_service", {})
    assert set(service.data["card"]["parts"]) == {"service"}
    recalls = _tool(world["browser"], "vehicle_recalls", {})
    assert recalls.data["card"]["name"] == service.data["card"]["name"]
    assert set(recalls.data["card"]["parts"]) == {"recalls"}
    for body in (day.data["card"], week.data["card"], service.data["card"], recalls.data["card"]):
        assert _leaks(world, json.dumps(body)) == []


@pytest.mark.django_db
def test_a_visit_runs_its_own_morning_briefing_from_allowed_tools_and_is_deleted_whole(world):  # noqa: F811
    from assistant.surface import RECRUITER_TOOLS
    from demo.visit_cleanup import delete_visit_household
    from procedures.listing import find, tool_names
    from procedures.models import ProcedureRun, ProcedureVersion
    from tests.test_recruiter_isolation import _tool

    version = find(world["demo"], "morning_briefing")
    assert version.procedure.household_id == world["demo"].id
    assert set(tool_names(version.definition)) <= set(RECRUITER_TOOLS)
    # Kevin's household, and members everywhere, still get the built-in.
    assert find(world["kevin"], "morning_briefing").procedure.household_id is None

    ran = _tool(world["browser"], "run_procedure", {"name": "morning_briefing"})
    assert ran.data["is_error"] is False, ran.data["text"]
    assert "Dinner with the Okafors" in ran.data["text"]
    assert "Checklists: " in ran.data["text"] and "The car: " in ran.data["text"]
    assert ran.data["card"]["card"] == "steps"
    assert [i["status"] for i in ran.data["card"]["items"]] == ["ok"] * 7

    delete_visit_household(world["demo"])
    assert not ProcedureRun.objects.filter(household=world["demo"]).exists()
    assert not ProcedureVersion.objects.filter(household=world["demo"]).exists()
