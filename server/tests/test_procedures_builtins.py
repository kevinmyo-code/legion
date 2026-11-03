"""procedures ticket 15: the built-ins `car_check`, `money_check`, `morning_briefing`.

Rulings: ticket 09 (their steps), ADR 0070, CLAUDE.md sections 4 and 7. Each runs on the demo
seed and on a Kevin-like household (three cars, trouble codes with and without a freeze frame
and a clear, a statement, the bank's own feed and balance, an unverified card, receipts in two
currencies, a brief, events, checklists) through a real door, with exact text expected. The
recruiter half runs on a real visit (`tests/test_recruiter_isolation.world`).

No test reaches NHTSA: `tests/conftest.py` fails the recall lookup in words unless a test hands
it an answer, so a plain run here IS the "lookup failed" path.
"""

from __future__ import annotations

import datetime as dt
import json
import uuid
from zoneinfo import ZoneInfo

import pytest
from django.db.models.functions import Now
from django.utils import timezone

from engine_mcp import recalls
from household import demo_seed
from procedures import steps
from procedures.loader import BUILTIN_DIR, load_builtins
from procedures.models import Procedure, ProcedureRun, Source
from tests.test_engine_mcp import call, client_with
from tests.test_parity_tools import NHTSA_ANSWER, _car, _ok
from tests.test_recruiter_isolation import _leaks, _run, world  # noqa: F401 - fixture
from tests.test_spend_api import line, provisional, statement

pytestmark = pytest.mark.django_db

BUILTINS = ("car_check", "money_check", "morning_briefing")
ZONE = "America/Chicago"
# Ticket 04's engine-side budget, p95, with no decide step (none of these has one).
BUDGET_MS = 1500
ENGINE_MS: dict[str, list[int]] = {}


@pytest.fixture
def mcp(settings, household_user):
    settings.LEGION_MCP = True
    return client_with(household_user)


@pytest.fixture
def demo(household_a):
    return demo_seed.seed_household(household_a, today=dt.date.today())


def run(client, name, **inputs):
    """`run_procedure` over `/mcp`, returning `(is_error, text, structured, run)`; the run's
    engine time is kept for the latency report."""
    args = {"name": name}
    if inputs:
        args["inputs"] = inputs
    is_error, text, structured = call(client, "run_procedure", args)
    row = ProcedureRun.objects.order_by("-started_at").first()
    if row is not None:
        ENGINE_MS.setdefault(name, []).append(row.ms)
    return is_error, text, structured, row


def fake_recalls(monkeypatch, answer=None):
    asked = []

    def fake(make, model, year):
        asked.append((make, model, year))
        if answer is None:
            return recalls.parse_recalls(json.dumps(NHTSA_ANSWER))
        return answer

    monkeypatch.setattr(recalls, "fetch_recalls", fake)
    return asked


# =============================================================================
# A Kevin-like household
# =============================================================================


def _today_local() -> dt.date:
    return dt.datetime.now(ZoneInfo(ZONE)).date()


def _code(auth_client, sync_id, vehicle, when, codes, frame):
    _ok(
        auth_client.put(
            f"/api/fleet/code_events/{sync_id}/",
            {
                "vehicle_id": vehicle,
                "occurred_at": when.isoformat(),
                "mileage": 120_000,
                "codes": codes,
                "freeze_frame": frame,
            },
            format="json",
        )
    )


def _clear(auth_client, sync_id, vehicle, when):
    _ok(
        auth_client.put(
            f"/api/fleet/code_clear_events/{sync_id}/",
            {
                "vehicle_id": vehicle,
                "occurred_at": when.isoformat(),
                "mileage": 119_000,
                "codes_before": ["P0300"],
                "freeze_frame": None,
                "codes_after": [],
                "outcome": "CLEARED",
                "ack_raw": "44",
            },
            format="json",
        )
    )


def _bank_feed(household, last4, nickname, rows):
    from legacy.models.ledger import LedgerTransaction

    for index, (day, cents) in enumerate(rows):
        LedgerTransaction.objects.create(
            id=uuid.uuid4(),
            household=household,
            account_last4=last4,
            account_nickname=nickname,
            currency="USD",
            txn_date=day,
            description=f"FEED {last4} {index}",
            amount_cents=cents,
            line_ref=f"plaid:{last4}:{index}",
            category_pending=False,
            provenance="BANK_API",
            bank_transaction_id=f"txn-{last4}-{index}",
            created_at=Now(),
        )


@pytest.fixture
def kevin(household_a, household_user, auth_client):
    """Three cars, the way Kevin's garage looks: the Jeep with codes since its last clear and a
    freeze frame, the M3 with a code read that came back with no freeze frame and a service past
    due, the Civic with no code reads at all."""
    from brief.models import Brief, Trigger
    from ingest.models import PlaidItem
    from legacy.models.pantry import Receipt

    household_a.timezone = ZONE
    household_a.save(update_fields=["timezone"])
    now = timezone.now()
    today = _today_local()

    jeep = _car(auth_client, "jeep", name="Bucky", make="Jeep", model="Cherokee", year=2001)
    m3 = _car(auth_client, "m3")
    civic = _car(auth_client, "civic", name="Daily", make="Honda", model="Civic", year=2019)
    for path, body in (
        (
            "service_history/s1/",
            {
                "vehicle_id": m3,
                "service_name": "Oil change",
                "kind": "OBSERVED",
                "service_date": "2025-01-10",
            },
        ),
        (
            f"maintenance_schedules/{m3}/oil change/",
            {"interval_months": 6, "interval_source": "SEEDED", "never_done": False},
        ),
    ):
        _ok(auth_client.put(f"/api/fleet/{path}", body, format="json"))
    _code(auth_client, "j-old", jeep, now - dt.timedelta(days=30), ["P0300"], {"rpm": 800})
    _clear(auth_client, "j-clear", jeep, now - dt.timedelta(days=20))
    _code(auth_client, "j-1", jeep, now - dt.timedelta(days=5), ["P0128"], None)
    _code(auth_client, "j-2", jeep, now - dt.timedelta(days=2), ["P0420", "P0128"], {"rpm": 2100})
    _code(auth_client, "m-1", m3, now - dt.timedelta(days=3), ["P0171"], None)

    # Money. Checking: a verified statement last month, the bank's own feed since. The card:
    # the bank's feed and the bank's own balance (Plaid). An old card with an UNVERIFIED row.
    # monthly_spend picks the month in UTC when no `tz` is sent, so the money rows are dated by
    # UTC's today: a run at 7 PM Chicago on the 31st is in next month's figures.
    money_day = dt.datetime.now(dt.UTC).date()
    first = money_day.replace(day=1)
    last_month_end = first - dt.timedelta(days=1)
    statement(
        household_a,
        "3119",
        "Adv SafeBalance Banking",
        [line(last_month_end.replace(day=2).isoformat(), "RENT", -150_000)],
        start=last_month_end.replace(day=1).isoformat(),
        end=last_month_end.isoformat(),
    )
    _bank_feed(household_a, "3119", "Adv SafeBalance Banking", [(money_day, -4_250)])
    _bank_feed(household_a, "4242", "Cash Rewards Visa", [(money_day, -1_999)])
    PlaidItem.objects.create(
        household=household_a,
        item_id="item-kevin",
        access_token_ciphertext=b"sealed",
        institution_name="Bank of America",
        accounts=[
            {
                "account_id": "acc-card",
                "mask": "4242",
                "name": "Cash Rewards Visa",
                "type": "credit",
                "balance_current_cents": 41_007,
                "balance_available_cents": 458_993,
                "balance_currency": "USD",
                "balance_as_of": "2026-11-03T14:14:00+00:00",
            }
        ],
    )
    provisional(household_a, "7777", "Old Card", [line(money_day.isoformat(), "COFFEE", -450)])
    for store, cents, currency, provenance in (
        ("HEB", 5_312, "USD", "LLM_RECONCILED"),
        ("KROGER", 1_100, "USD", "UNRECONCILED"),
        ("COLD STORAGE", 1_242, "SGD", "DETERMINISTIC"),
    ):
        Receipt.objects.create(
            id=uuid.uuid4(),
            household=household_a,
            store=store,
            purchase_date=money_day,
            currency=currency,
            total_cents=cents,
            unaccounted_cents=40 if provenance == "UNRECONCILED" else None,
            provenance=provenance,
            created_at=Now(),
        )
    Receipt.objects.create(
        id=uuid.uuid4(),
        household=household_a,
        store="LAST MONTH",
        purchase_date=last_month_end,
        currency="USD",
        total_cents=9_999,
        provenance="DETERMINISTIC",
        created_at=Now(),
    )

    # The brief, as compose.py writes it, and the day.
    Brief.objects.create(
        household=household_a,
        user=household_user,
        brief_date=today,
        trigger=Trigger.SCHEDULED,
        sections=[
            {
                "key": "canvas",
                "title": "Canvas",
                "status": "ok",
                "lines": [{"text": "MATH 2414 homework due today 11:59 PM.", "unverified": False}],
            },
            {
                "key": "money",
                "title": "Money",
                "status": "ok",
                "lines": [{"text": "Spent $62.49 so far this month.", "unverified": True}],
            },
            {
                "key": "car",
                "title": "Car",
                "status": "ok",
                "lines": [{"text": "Bucky: trouble codes since the last clear: P0128, P0420."}],
            },
            {
                "key": "news",
                "title": "News",
                "status": "ok",
                "lines": [
                    {
                        "text": "WEB HEADLINE that must not be repeated.",
                        "links": [{"title": "x", "url": "https://example.com"}],
                    }
                ],
            },
        ],
        notes=[],
        news_ok=True,
    )
    nine = dt.datetime.combine(today, dt.time(9, 30), tzinfo=ZoneInfo(ZONE))
    for body in (
        {"title": "Dentist", "starts_at": nine.isoformat(), "kind": "event"},
        {
            "title": "Run club",
            "starts_at": (nine - dt.timedelta(days=21)).isoformat(),
            "repeat_kind": "WEEKLY",
            "repeat_every": 1,
            "repeat_days_of_week": "TUESDAY",
            "repeat_end_kind": "NEVER",
        },
        {"title": "Farmers market", "starts_at": nine.isoformat(), "kind": "suggestion"},
    ):
        _ok(auth_client.post("/api/events", body, format="json"))
    return {"jeep": jeep, "m3": m3, "civic": civic, "today": today}


def _checklist(mcp, today):
    _, _, made = call(mcp, "create_checklist", {"name": "morning", "schedule": "daily"})
    checklist = made["row"]["id"]
    items = []
    for text in ("meds", "water", "stretch"):
        _, _, added = call(mcp, "add_checklist_item", {"checklist_id": checklist, "text": text})
        items.append(added["row"]["id"])
    call(
        mcp,
        "tick_checklist_item",
        {"checklist_id": checklist, "item_id": items[0], "date": today.isoformat()},
    )


def footer(name: str, ran: str) -> str:
    return f"(Procedure {name} v1; ran {ran}. Nothing was written.)"


# =============================================================================
# 1. The loader picks them up
# =============================================================================


def test_migrate_loaded_the_three_built_ins_active_and_read_only():
    found = {
        p.name: p.active_version()
        for p in Procedure.objects.filter(household__isnull=True, name__in=BUILTINS)
    }
    assert sorted(found) == sorted(BUILTINS)
    assert sorted(p.stem for p in BUILTIN_DIR.glob("*.yaml")) == sorted(BUILTINS)
    for name, version in found.items():
        assert version is not None and version.source == Source.BUILTIN, name
        assert version.writes is False, name
        assert version.approved_at is not None, name
        # Ruling 02 and ticket 09: none of the first procedures has a model in it.
        assert not [s for s in version.definition["steps"] if s["kind"] == "decide"], name
        assert version.definition["steps"][-1]["kind"] == "say", name


def test_a_second_load_writes_nothing():
    report = load_builtins()
    assert sorted(report.unchanged) == sorted(BUILTINS)
    assert report.created == report.updated == report.deactivated == []


def test_the_steps_are_ticket_09s():
    def kinds(name):
        version = Procedure.objects.get(household__isnull=True, name=name).active_version()
        return [
            (s["id"], s["kind"], s.get("tool") or s.get("function"))
            for s in version.definition["steps"]
        ]

    assert kinds("car_check") == [
        ("service", "tool", "next_service"),
        ("codes", "tool", "read_records"),
        ("clears", "tool", "read_records"),
        ("lead", "compute", "car_lead"),
        ("trouble", "compute", "trouble_codes"),
        ("which_car", "check", "one_car"),
        ("recalls", "tool", "vehicle_recalls"),
        ("answer", "say", None),
    ]
    assert kinds("money_check") == [
        ("spend", "tool", "monthly_spend"),
        ("month", "compute", "month_window"),
        ("balances", "tool", "account_balances"),
        ("groceries", "tool", "grocery_spend"),
        ("answer", "say", None),
    ]
    assert kinds("morning_briefing") == [
        ("brief", "tool", "brief_latest"),
        ("events", "tool", "list_events"),
        ("lists", "tool", "list_checklists"),
        ("today", "compute", "day_events"),
        ("ticks", "compute", "checklist_day"),
        ("answer", "say", None),
    ]


# =============================================================================
# 2. The step functions, pure
# =============================================================================


def test_car_lead_names_the_car_and_what_is_past_due():
    assert steps.car_lead([]) == "No vehicle is on file."
    assert steps.car_lead([{"name": "Bucky", "overdue": []}]) == (
        "The car: Bucky. Nothing is past due by the records on file."
    )
    several = [
        {"name": "Bucky", "overdue": [{"service_name": "Oil change", "due_on": "2025-07-10"}]},
        {"name": "M3", "overdue": [{"service_name": "Spark plugs", "due_at_miles": 90000}]},
    ]
    assert steps.car_lead(several) == (
        "2 vehicles are on file: Bucky and M3. Past due by the records on file: Oil change on "
        "Bucky (due Thu Jul 10, 2025); Spark plugs on M3 (due at 90,000 mi)."
    )


def _event(vid, when, codes, frame=None, **extra):
    return {
        "vehicle_id": vid,
        "occurred_at": when,
        "codes": codes,
        "freeze_frame": frame,
        "deleted_at": None,
        **extra,
    }


def test_trouble_codes_since_the_clear_with_the_freeze_frame_said():
    cars = [
        {"vehicle_id": "a", "name": "Bucky"},
        {"vehicle_id": "b", "name": "M3"},
        {"vehicle_id": "c", "name": "Daily"},
        {"vehicle_id": "d", "name": "Wagon"},
    ]
    events = [
        _event("a", "2026-10-01T12:00:00Z", ["P0300"], {"rpm": 1}),
        _event("a", "2026-10-20T12:00:00Z", ["P0128"]),
        # 03:00 UTC on Nov 2 is still Nov 1 in Chicago (UTC-6).
        _event("a", "2026-11-02T03:00:00Z", ["P0420", "P0128"], {"rpm": 2100}),
        _event("b", "2026-10-31T12:00:00Z", ["P0171"], {}),
        _event("d", "2026-10-31T12:00:00Z", []),
        _event("d", "2026-10-30T12:00:00Z", ["P0999"], deleted_at="2026-10-31T00:00:00Z"),
    ]
    clears = [{"vehicle_id": "a", "occurred_at": "2026-10-10T12:00:00Z", "deleted_at": None}]
    assert steps.trouble_codes(cars, events, clears, utc_offset_minutes=-360) == (
        "Trouble codes, as the OBD dongle logged them when it last connected (not read from the "
        "car now): Bucky: trouble codes since the last clear (Sat Oct 10, 2026): P0128, P0420. "
        "The latest read with codes was Sun Nov 1, 2026, and a freeze frame was stored with "
        "it. M3: trouble codes on record: P0171. The latest read with codes was Sat Oct 31, "
        "2026, and its freeze frame came back empty. Daily: no trouble-code reads are on file. "
        "Wagon: no trouble codes on record."
    )


def test_trouble_codes_says_when_only_the_newest_reads_were_looked_at():
    cars = [{"vehicle_id": "a", "name": "Bucky"}]
    events = [_event("a", f"2026-10-0{i}T12:00:00Z", ["P0128"]) for i in range(1, 3)]
    text = steps.trouble_codes(cars, events, [], limit=2)
    assert text.endswith("Only the newest 2 code reads on file were looked at.")
    assert "no freeze frame came back with it" in text
    assert steps.trouble_codes([], [], []) == ""


def test_month_window_is_the_whole_month():
    assert steps.month_window("2026-11") == {"from": "2026-11-01", "to": "2026-11-30"}
    assert steps.month_window("2028-02") == {"from": "2028-02-01", "to": "2028-02-29"}
    with pytest.raises(ValueError):
        steps.month_window(None)


def test_day_events_lists_today_and_never_expands_a_repeat():
    assert steps.day_events({}) == "No one-off events are on file for today."
    events = {
        "in_window": [
            {"title": "Dentist", "starts_at": "2026-11-03T15:30:00Z", "kind": "event"},
            {"title": "Holiday", "starts_at": "2026-11-03T06:00:00Z", "all_day": True},
            {"title": "File taxes", "starts_at": "2026-11-03T23:00:00Z", "kind": "task"},
        ],
        "repeating": [{"title": "Run club"}],
        "undated": [{"title": "Fix gate"}],
        "suggestions": [{"title": "Farmers market"}],
    }
    assert steps.day_events(events, utc_offset_minutes=-360) == (
        "Today on file: Dentist at 9:30 AM; Holiday (all day). Tasks dated today: File taxes at "
        "5:00 PM. 1 repeating event(s) are on file (Run club); whether each falls today is not "
        "worked out here, so check the calendar before saying one is on. 1 task(s) with no date "
        "are open. 1 suggestion(s) for today are things the household could do, not plans: "
        "Farmers market."
    )


def test_checklist_day_says_ticked_never_done():
    assert steps.checklist_day([]) == "No checklists are on file."
    lists = [
        {
            "name": "morning",
            "items": [
                {"text": "meds", "ticked_on_date": True},
                {"text": "water", "ticked_on_date": False},
            ],
        },
        {"name": "gym", "items": [{"text": "squat", "ticked_on_date": True}]},
        {"name": "empty", "items": []},
    ]
    text = steps.checklist_day(lists, "2026-11-03")
    assert text == (
        "Checklists: morning: 1 of 2 ticked Tue Nov 3, 2026; not ticked: water. gym: all 1 "
        "ticked Tue Nov 3, 2026. empty: no items."
    )
    assert "done" not in text


# =============================================================================
# 3. car_check
# =============================================================================


def test_car_check_on_the_demo_seed_is_the_same_text_every_run(mcp, demo, monkeypatch):
    asked = fake_recalls(monkeypatch)
    is_error, text, structured, row = run(mcp, "car_check")
    assert not is_error, text
    assert asked == [("Subaru", "Outback", 2019)]
    _, service, _ = call(mcp, "next_service", {})
    _, recall_text, _ = call(mcp, "vehicle_recalls", {})
    assert text == (
        "The car: The Wagon. Nothing is past due by the records on file.\n\n"
        f"{service}\n\n"
        "Trouble codes, as the OBD dongle logged them when it last connected (not read from the "
        "car now): The Wagon: no trouble-code reads are on file.\n\n"
        f"Recalls: {recall_text}\n\n"
        + footer(
            "car_check", "service, codes, clears, lead, trouble, which_car, recalls, answer"
        )
    )
    # Every caveat the tools state rides along: the estimate, the scope of the recall list.
    assert "about 670 mi to go. That distance is an ESTIMATE" in text
    assert "not read off the car today" in text
    assert "NHTSA lists 2 recall(s) for the 2019 Subaru Outback (The Wagon)" in text
    assert "not a check of this car's VIN" in text and "is not known here" in text
    assert row.status == "ok" and structured["status"] == "ok"
    assert run(mcp, "car_check")[1] == text


def test_a_failed_recall_lookup_is_not_known_never_no_recalls(mcp, demo):
    """Ticket 15's verify: conftest's NHTSA is down. The household reads already ran and are
    said; the recall line is the tool's own "NOT known"."""
    is_error, text, structured, row = run(mcp, "car_check")
    assert is_error and row.status == "partial"
    assert text.startswith(
        "The procedure car_check did not finish. Step 'recalls' (vehicle_recalls) failed: "
        "Nothing was read. The lookup of NHTSA's recall list for the 2019 Subaru Outback did "
        "not complete (the network is off in tests), so it is NOT known whether it has recalls. "
        "This is not a finding of none; try again later."
    )
    assert "no recalls" not in text.lower()
    assert "did not run: answer. Nothing was written." in text
    assert "lead: The car: The Wagon. Nothing is past due by the records on file." in text
    assert "trouble: Trouble codes, as the OBD dongle logged them" in text
    assert "about 670 mi to go. That distance is an ESTIMATE" in text
    # The raw code rows were put into words by `trouble`; their JSON is not repeated.
    assert '"sync_id"' not in text and "| codes: " not in text
    assert structured["not_run"] == ["answer"]


def test_car_check_on_a_kevin_like_garage_names_one_car(mcp, kevin, monkeypatch):
    fake_recalls(monkeypatch, answer=[])
    is_error, text, _, row = run(mcp, "car_check", vehicle="Bucky")
    assert not is_error, text
    assert text.startswith("The car: Bucky. Nothing is past due by the records on file.\n\n")
    assert "Bucky: no maintenance schedule is logged, so nothing can be timed." in text
    assert "Bucky: trouble codes since the last clear (" in text
    assert "): P0128, P0420. The latest read with codes was " in text
    assert ", and a freeze frame was stored with it." in text
    assert "P0300" not in text  # read before the clear
    assert "M3" not in text and "Daily" not in text and "P0171" not in text
    assert (
        "Recalls: NHTSA's recall list shows no recalls for the 2001 Jeep Cherokee (Bucky) as of "
        "this lookup. That is NHTSA's public list for that model year, make and model, not a "
        "check of this car's VIN"
    ) in text
    assert "unverified" not in text.lower()


def test_the_m3_is_past_due_and_its_code_read_had_no_freeze_frame(mcp, kevin, monkeypatch):
    fake_recalls(monkeypatch, answer=[])
    is_error, text, _, _ = run(mcp, "car_check", vehicle="M3")
    assert not is_error, text
    assert text.startswith(
        "The car: M3. Past due by the records on file: oil change on M3 (due Thu Jul 10, 2025)."
    )
    assert "M3: trouble codes on record: P0171." in text
    assert "and no freeze frame came back with it." in text


def test_one_car_passes_with_one_or_none_and_fails_with_several():
    assert steps.one_car([{"vehicle_id": "a"}]) is True
    assert steps.one_car([]) is True and steps.one_car(None) is True
    assert steps.one_car([{"vehicle_id": "a"}, {"vehicle_id": "b"}]) is False


def test_several_cars_and_none_named_skips_recalls_and_asks_which_car(mcp, kevin, monkeypatch):
    """Kevin, 2026-11-03 (ticket 15's open item, option a): the v1 runner has no fan-out and
    vehicle_recalls takes one car, so with several cars and none named the run is ok, service
    and codes cover every car, and recalls are skipped with a sentence asking which car."""
    asked = fake_recalls(monkeypatch)
    is_error, text, structured, row = run(mcp, "car_check")
    assert not is_error, text
    assert row.status == "ok" and structured["status"] == "ok" and asked == []
    _, service, _ = call(mcp, "next_service", {})
    assert text.startswith(
        "3 vehicles are on file: Bucky, Daily and M3. Past due by the records on file: "
        "oil change on M3 (due Thu Jul 10, 2025).\n\n"
        f"{service}\n\n"
        "Trouble codes, as the OBD dongle logged them when it last connected (not read from the "
        "car now): "
    )
    assert "Daily: no trouble-code reads are on file." in text
    assert "M3: trouble codes on record: P0171." in text
    assert "Bucky: trouble codes since the last clear (" in text
    assert (
        "\n\nRecalls are looked up for one car at a time: say which car.\n\n"
        "(Procedure car_check v1; ran service, codes, clears, lead, trouble, which_car; not on "
        "this path: recalls, answer. Nothing was written.)"
    ) in text
    assert "no recalls" not in text.lower() and "refused" not in text
    assert structured["not_run"] == ["recalls", "answer"]
    (check,) = [s for s in structured["steps"] if s["step_id"] == "which_car"]
    assert check["branch"] == "fail" and check["chosen_by"] == "code"


def test_several_cars_and_one_named_looks_up_that_cars_recalls(mcp, kevin, monkeypatch):
    asked = fake_recalls(monkeypatch, answer=[])
    is_error, text, structured, row = run(mcp, "car_check", vehicle="Daily")
    assert not is_error and row.status == "ok", text
    assert len(asked) == 1
    assert "say which car" not in text and "Recalls: NHTSA's recall list" in text
    assert structured["not_run"] == []


def test_a_car_that_is_not_on_file_reads_nothing_and_says_so(mcp, kevin):
    is_error, text, _, row = run(mcp, "car_check", vehicle="Ferrari")
    assert is_error and row.status == "failed"
    assert (
        "Step 'service' (next_service) was refused: Nothing was read. No vehicle on file "
        "matches 'Ferrari'. Vehicles on file: Bucky, Daily, M3."
    ) in text


# =============================================================================
# 4. money_check
# =============================================================================


def _money_by_hand(mcp) -> str:
    _, spend, body = call(mcp, "monthly_spend", {})
    window = steps.month_window(body["month"])
    _, balances, _ = call(mcp, "account_balances", {})
    _, groceries, _ = call(mcp, "grocery_spend", window)
    return f"Spending: {spend}\n\n{balances}\n\nGroceries: {groceries}"


def test_money_check_on_the_demo_seed_is_the_three_tools_words(mcp, demo):
    is_error, text, _, row = run(mcp, "money_check")
    assert not is_error, text
    assert text == _money_by_hand(mcp) + "\n\n" + footer(
        "money_check", "spend, month, balances, groceries, answer"
    )
    assert "Against the household's budget: Dining" in text
    assert "Demo Everyday Checking (ending 4821, USD): Now: $" in text
    assert "Demo Rewards Card (ending 0917, USD): no statement on file" in text
    assert "UNVERIFIED transaction(s)" in text
    assert run(mcp, "money_check")[1] == text


def test_money_check_on_a_kevin_like_household(mcp, kevin):
    is_error, text, _, _ = run(mcp, "money_check")
    assert not is_error, text
    assert text == _money_by_hand(mcp) + "\n\n" + footer(
        "money_check", "spend, month, balances, groceries, answer"
    )
    month = dt.datetime.now(dt.UTC).date().strftime("%Y-%m")
    # Spend: nothing categorised yet, so the amount to categorise is said, and its unverified
    # part. 42.50 + 19.99 from the bank's feed, 4.50 UNVERIFIED from the old card.
    assert f"Categorised spend for {month} is 0.00 USD" in text
    assert "A further 66.99 was spent but has no category yet, so it is NOT in that total" in text
    assert "The amount with no category includes UNVERIFIED rows" in text
    lines = {ln[2:].split(" (ending")[0]: ln for ln in text.splitlines() if ln.startswith("- ")}
    checking = lines["Adv SafeBalance Banking"]
    card = lines["Cash Rewards Visa"]
    old = lines["Old Card"]
    assert "Now: $" in checking and "plus -$42.50 of bank-feed transactions since" in checking
    assert card == (
        "- Cash Rewards Visa (ending 4242, USD): Now: $410.07 owed (the bank's own figure as of "
        "8:14 AM Tue Nov 3, 2026, $4,589.93 of credit available)."
    )
    # The bank's own feed and balance are fact: never "unverified" (CLAUDE.md section 4).
    for fact in (checking, card):
        assert "unverified" not in fact.lower()
    assert "1 UNVERIFIED transaction(s) net -$4.50: no gate checked them" in old
    # Groceries: this month only, per currency, the unverified receipt named.
    assert "- USD: $64.12 across 2 receipt(s)" in text
    assert "$11.00 of that, on 1 receipt(s), is UNVERIFIED" in text
    assert "- SGD: SGD 12.42 across 1 receipt(s)" in text
    assert "$99.99" not in text  # last month's receipt


# =============================================================================
# 5. morning_briefing
# =============================================================================


def test_morning_briefing_wraps_the_brief_and_says_the_day(mcp, kevin):
    _checklist(mcp, kevin["today"])
    is_error, text, _, row = run(mcp, "morning_briefing")
    assert not is_error, text
    day = steps._long_date(kevin["today"])
    assert text == (
        f"Your latest daily brief is for {day}. Built just now, by the 05:00 build.\n"
        "Canvas: MATH 2414 homework due today 11:59 PM.\n"
        "Money: Spent $62.49 so far this month. (UNVERIFIED)\n"
        "Car: Bucky: trouble codes since the last clear: P0128, P0420.\n"
        "News: not repeated here, because it is text from the web; it is on the Brief page.\n\n"
        "Today on file: Dentist at 9:30 AM. 1 repeating event(s) are on file (Run club); "
        "whether each falls today is not worked out here, so check the calendar before saying "
        "one is on. 1 suggestion(s) for today are things the household could do, not plans: "
        "Farmers market.\n\n"
        f"Checklists: Groceries: no items. morning: 1 of 3 ticked {day}; not ticked: water, "
        "stretch.\n\n" + footer("morning_briefing", "brief, events, lists, today, ticks, answer")
    )
    assert "WEB HEADLINE" not in text and "example.com" not in text
    stored = " ".join(s.result for s in row.steps.all()) + row.result
    assert "WEB HEADLINE" not in stored  # ruling 06: web text never in a trace


def test_a_brief_from_an_earlier_day_says_so(mcp, kevin):
    from brief.models import Brief

    Brief.objects.update(brief_date=kevin["today"] - dt.timedelta(days=2))
    _, text, _, _ = run(mcp, "morning_briefing")
    assert "It is not today's brief: nothing has been built for today yet." in text


def test_a_news_section_that_was_not_built_says_why(mcp, kevin):
    from brief.models import Brief

    brief = Brief.objects.get()
    brief.sections = [
        *brief.sections[:3],
        {
            "key": "news",
            "title": "News",
            "status": "failed",
            "lines": [],
            "note": "News could not be built: the household has no Gemini key.",
        },
    ]
    brief.save()
    _, text, _, _ = run(mcp, "morning_briefing")
    assert "News: not built. News could not be built: the household has no Gemini key." in text


def test_morning_briefing_with_no_brief_says_none_was_built(mcp, demo):
    is_error, text, _, _ = run(mcp, "morning_briefing")
    assert not is_error, text
    assert text.startswith(
        "No daily brief has been built for you yet, so there is none to read. That is a real "
        "empty result, not a failure to read"
    )
    assert "Checklists: " in text


def test_brief_latest_reads_only_the_callers_own_brief(mcp, kevin, household_a):
    from brief.models import Brief, Trigger
    from tests.conftest import _member

    mia = _member(household_a, "mia@example.com")
    Brief.objects.create(
        household=household_a,
        user=mia,
        brief_date=kevin["today"],
        trigger=Trigger.REFRESH,
        sections=[
            {
                "key": "canvas",
                "title": "Canvas",
                "status": "ok",
                "lines": [{"text": "MIA PRIVATE COURSE"}],
            }
        ],
    )
    is_error, text, structured = call(mcp, "brief_latest", {})
    assert not is_error and "MIA PRIVATE COURSE" not in text
    assert "MATH 2414" in text
    assert structured["today"] == kevin["today"].isoformat()
    assert structured["utc_offset_minutes"] in (-360, -300)


# =============================================================================
# 6. Recruiters
# =============================================================================


def test_a_visit_runs_car_check_and_money_check_on_its_own_copy(world, monkeypatch):  # noqa: F811
    fake_recalls(monkeypatch, answer=[])
    for name in ("car_check", "money_check"):
        data = _run(world, name).data
        assert data["is_error"] is False, data["text"]
        assert _leaks(world, data["text"]) == [], name
        run_row = ProcedureRun.objects.order_by("-started_at").first()
        assert run_row.household_id == world["demo"].id and run_row.caller_kind == "recruiter"
    car = _run(world, "car_check").data["text"]
    assert car.startswith("The car: The Wagon.")
    assert "shows no recalls for the 2019 Subaru Outback (The Wagon)" in car


def test_a_visit_may_not_run_morning_briefing_and_hears_why(world):  # noqa: F811
    before = ProcedureRun.objects.count()
    data = _run(world, "morning_briefing").data
    assert data["is_error"] is True
    assert data["text"] == (
        "Nothing ran. The procedure 'morning_briefing' is not available on this demo visit: it "
        "uses brief_latest (the daily brief is built from outside feeds on the household's own "
        "key), which a visit cannot use."
    )
    assert ProcedureRun.objects.count() == before


# =============================================================================
# 7. Engine time against ticket 04's budget
# =============================================================================


RUNS = 20


def test_engine_time_of_each_built_in(mcp, kevin, household_user, monkeypatch, capsys):
    """Engine-side ms (`ProcedureRun.ms`) over 20 runs each on the Kevin-like household. NHTSA
    is faked, so car_check's time excludes the real lookup (`recalls.fetch_recalls`' timeout
    bounds it). Asserts the runner's hard stop (twice the budget); prints p50 and p95 against
    ticket 04's 1.5 s p95."""
    fake_recalls(monkeypatch, answer=[])
    _checklist(mcp, kevin["today"])
    report = {}
    for name, inputs in (
        ("car_check", {"vehicle": "Bucky"}),
        ("money_check", {}),
        ("morning_briefing", {}),
    ):
        times = []
        # A fresh token per procedure: /mcp allows a token 60 calls a minute.
        client = client_with(household_user)
        for _ in range(RUNS):
            is_error, text, _, row = run(client, name, **inputs)
            assert not is_error, text
            times.append(row.ms)
        times.sort()
        p50, p95 = times[len(times) // 2], times[int(len(times) * 0.95) - 1]
        report[name] = (p50, p95, times[-1])
        assert times[-1] < 2 * BUDGET_MS, (name, times)
    with capsys.disabled():
        for name, (p50, p95, worst) in report.items():
            verdict = "within" if p95 < BUDGET_MS else "OVER"
            print(
                f"\n[engine ms] {name}: p50 {p50}, p95 {p95}, max {worst} over {RUNS} runs "
                f"(budget p95 {BUDGET_MS}; {verdict})"
            )
