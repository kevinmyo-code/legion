"""Phone-web parity quick wins (recruiter-demo research 01, 2026-11-02).

The engine tools that moved a phone capability to `/mcp`, the members' web
assistant and a recruiter's visit: next_service, monthly_spend's budget and
verified words, account_balances, grocery_spend, vehicle_recalls, the four
checklist writes, rename_place and skip_event_occurrence. Each is exercised
on hand-made rows and on the demo seed's own data; tenancy lives in
`tests/test_tenancy.py` and the recruiter's visit in
`tests/test_recruiter_isolation.py`, beside every other tool.

No test reaches NHTSA: `tests/conftest.py` makes the lookup fail in words
unless a test hands it an answer.
"""

from __future__ import annotations

import datetime as dt
import json
import uuid

import pytest

from brief.compose import money
from engine_mcp import recalls
from household import demo_seed
from legacy.models.pantry import Receipt
from tests.test_engine_mcp import call, client_with
from tests.test_spend_api import line, provisional, statement

pytestmark = pytest.mark.django_db

TODAY = dt.date.today()
REAL_FETCH = recalls.fetch_recalls


@pytest.fixture
def mcp_write(settings, household_user):
    settings.LEGION_MCP = True
    return client_with(household_user)


@pytest.fixture
def demo(household_a):
    """The demo seed, written into the test member's own household."""
    return demo_seed.seed_household(household_a, today=TODAY)


def _ok(response):
    assert 200 <= response.status_code < 300, (response.status_code, response.data)
    return response.data


def _car(auth_client, guid="m3", **overrides):
    body = {
        "name": "M3",
        "make": "BMW",
        "model": "M3",
        "year": 2003,
        "confirmed": True,
        "archived": False,
        **overrides,
    }
    return str(_ok(auth_client.put(f"/api/fleet/vehicles/{guid}/", body, format="json"))["id"])


# =============================================================================
# next_service
# =============================================================================


def test_next_service_on_the_demo_car_says_date_miles_and_the_estimate(mcp_write, demo):
    is_error, text, structured = call(mcp_write, "next_service", {"date": TODAY.isoformat()})
    assert not is_error, text
    wagon = structured["vehicles"][0]
    assert wagon["name"] == "The Wagon"
    # Oil change: last done 170 days ago at 56,910 mi, every 6 months or 5,000 mi;
    # the car's own entered reading is 61,240 mi.
    assert wagon["next_by_date"]["service_name"] == "Oil change"
    assert wagon["next_by_miles"] == {
        "service_name": "Oil change",
        "due_at_miles": 61_910,
        "miles_to_go_estimate": 670,
    }
    assert wagon["odometer"]["source"] == "vehicle"
    assert wagon["never_logged"] == ["Brake fluid"]
    assert "about 670 mi to go" in text
    assert "ESTIMATE" in text and "not read off the car today" in text
    assert "Never logged as done, so not timed: Brake fluid." in text


def test_next_service_says_what_is_past_due_and_what_cannot_be_timed(mcp_write, auth_client):
    vehicle = _car(auth_client)
    for path, body in (
        (
            "service_history/s1/",
            {
                "vehicle_id": vehicle,
                "service_name": "Oil change",
                "kind": "OBSERVED",
                "service_date": "2025-01-10",
            },
        ),
        (
            "service_history/s2/",
            {
                "vehicle_id": vehicle,
                "service_name": "Spark plugs",
                "kind": "OBSERVED",
                "service_date": "2025-01-10",
                "mileage": 71_200,
            },
        ),
        (
            f"maintenance_schedules/{vehicle}/oil change/",
            {"interval_months": 6, "interval_source": "SEEDED", "never_done": False},
        ),
        (
            f"maintenance_schedules/{vehicle}/spark plugs/",
            {"interval_miles": 30_000, "interval_source": "SEEDED", "never_done": False},
        ),
    ):
        _ok(auth_client.put(f"/api/fleet/{path}", body, format="json"))
    is_error, text, structured = call(mcp_write, "next_service", {"date": "2026-10-01"})
    assert not is_error, text
    m3 = structured["vehicles"][0]
    assert m3["overdue"] == [{"service_name": "oil change", "due_on": "2025-07-10"}]
    assert "Already past due by the records on file: oil change (due Thu Jul 10, 2025)" in text
    # The only odometer reading is the spark plug service's own mileage.
    assert m3["next_by_miles"]["miles_to_go_estimate"] == 30_000


def test_next_service_with_no_car_or_no_match_refuses_in_words(mcp_write, auth_client):
    is_error, text, _ = call(mcp_write, "next_service", {})
    assert is_error and text.startswith("Nothing was read.") and "no vehicle" in text
    _car(auth_client)
    is_error, text, _ = call(mcp_write, "next_service", {"vehicle": "tesla"})
    assert is_error and "No vehicle on file matches 'tesla'" in text and "M3" in text
    is_error, text, _ = call(mcp_write, "next_service", {"vehicle": "bmw"})
    assert not is_error and "no maintenance schedule is logged" in text


def test_the_brief_and_next_service_read_the_same_odometer(demo):
    """The refactor's point: one computation. The demo car's entered reading
    (61,240 mi) is the odometer both read."""
    from types import SimpleNamespace

    from brief.compose import Viewer
    from brief.maintenance import latest_odometer
    from legacy.models.fleet import Vehicle

    vehicle = Vehicle.objects.get(household=demo.household)
    viewer = Viewer(user=SimpleNamespace(household=demo.household, pk=None))
    reading = latest_odometer(viewer, vehicle)
    assert (reading.miles, reading.source) == (61_240, "vehicle")


# =============================================================================
# monthly_spend: budget and verified words
# =============================================================================


def _previous_month(today: dt.date) -> str:
    first = today.replace(day=1) - dt.timedelta(days=1)
    return f"{first:%Y-%m}"


def test_monthly_spend_on_the_demo_month_says_budget_and_verified(mcp_write, demo):
    month = _previous_month(TODAY)
    is_error, text, body = call(mcp_write, "monthly_spend", {"month": month})
    assert not is_error, text
    targets = {c["category"]: c["target_cents"] for c in body["categories"]}
    assert targets["Groceries"] == 45_000 and targets["Housing"] == 172_500
    assert "Against the household's budget: Dining" in text
    assert "Housing 1725.00 of 1725.00 (0.00 left)" in text
    # The card payment is a Transfer, which the seed marks not-spending.
    assert body["excluded"]["not_spending_categories"] == ["Transfer"]
    assert "left out" in text
    assert "Transfer" not in {c["category"] for c in body["categories"]}
    # Every categorised row is the verified checking statement's.
    assert "None of the categorised figure is unverified" in text
    if body["uncategorised_unverified"]:
        assert "The amount with no category includes UNVERIFIED rows" in text


def test_monthly_spend_splits_verified_from_unverified_in_words(mcp_write, monkeypatch):
    body = {
        "month": "2026-09",
        "currency": "USD",
        "accounts": [
            {"account_last4": "1111", "spend_cents": 10_000, "unverified": True,
             "unverified_cents": 2_500},
        ],
        "categories": [
            {"category": "Dining", "spend_cents": 10_000, "target_cents": 8_000,
             "unverified": True},
        ],
        "uncategorised_cents": 0,
        "uncategorised_unverified": False,
        "excluded": {"own_account_moves_cents": 0, "not_spending_cents": 0},
        "complete": True,
    }
    monkeypatch.setattr("api.ledger_spend.spend_for", lambda *a: body)
    is_error, text, _ = call(mcp_write, "monthly_spend", {"month": "2026-09"})
    assert not is_error, text
    assert "Dining 100.00 of 80.00 (20.00 over)" in text
    assert "25.00 is UNVERIFIED" in text and "75.00 is verified" in text


# =============================================================================
# account_balances
# =============================================================================


def test_account_balances_on_the_demo_household(mcp_write, demo):
    last = demo_seed.month_statement(demo_seed.statement_months(TODAY)[-1])
    is_error, text, structured = call(mcp_write, "account_balances", {})
    assert not is_error, text
    by = {a["account_last4"]: a for a in structured["accounts"]}
    checking, card = by[demo_seed.CHECKING_LAST4], by[demo_seed.CARD_LAST4]
    assert checking["verified_balance_cents"] == last["closing_balance_cents"]
    assert checking["verified"] is True
    assert checking["current_cents"] == last["closing_balance_cents"]
    assert f"Now: {money(last['closing_balance_cents'])} (statement balance on " in text
    assert card["verified_balance_cents"] is None and card["verified"] is False
    assert card["unverified_since_count"] > 0
    assert "Demo Rewards Card (ending 0917, USD): no statement on file" in text
    assert "no balance can be stated" in text and "UNVERIFIED" in text


def test_activity_after_the_statement_is_unverified_and_said_so(
    mcp_write, household_a
):
    statement(
        household_a,
        "1234",
        "Everyday",
        [line("2026-07-03", "PAY", 50_000), line("2026-07-09", "SHOP", -2_000)],
        start="2026-07-01",
        end="2026-07-31",
    )
    provisional(household_a, "1234", "Everyday", [line("2026-08-02", "COFFEE", -450)])
    # A provisional row dated inside the statement's period is not counted again.
    provisional(household_a, "1234", "Everyday", [line("2026-07-20", "OLD", -999)])
    is_error, text, structured = call(mcp_write, "account_balances", {"account": "1234"})
    assert not is_error, text
    (account,) = structured["accounts"]
    assert account["verified_balance_cents"] == 1_048_000
    assert account["unverified_since_cents"] == -450
    assert account["with_unverified_cents"] == 1_047_550
    assert account["current_cents"] == 1_048_000
    assert "Now: $10,480.00 (statement balance on Fri Jul 31, 2026, verified)." in text
    assert "Since that statement, 1 UNVERIFIED transaction(s) net -$4.50" in text
    assert "would be $10,475.50, which is UNVERIFIED" in text


def _feed(household, last4, rows):
    from django.db.models.functions import Now

    from legacy.models.ledger import LedgerTransaction

    for index, (day, cents) in enumerate(rows):
        LedgerTransaction.objects.create(
            id=uuid.uuid4(),
            household=household,
            account_last4=last4,
            account_nickname="Adv SafeBalance Banking",
            currency="USD",
            txn_date=day,
            description=f"FEED {index}",
            amount_cents=cents,
            line_ref=f"plaid:{last4}:{index}",
            category_pending=False,
            provenance="BANK_API",
            bank_transaction_id=f"txn-{last4}-{index}",
            created_at=Now(),
        )


def test_the_now_figure_leads_and_is_the_statement_plus_the_feed_since(
    mcp_write, household_a
):
    """Audit of a live conversation, 2026-11-03: asked "money", the assistant
    said the statement-anchored figure without being sure which of two
    figures was the balance. The line now LEADS with the current figure, the
    statement balance and the movement since in brackets after it."""
    # Closing balance $88.50 on Tue Oct 6, 2026 (the helper opens at $10,000).
    statement(
        household_a,
        "9001",
        "Adv SafeBalance Banking",
        [line("2026-10-02", "RENT", 8_850 - 1_000_000)],
        start="2026-09-07",
        end="2026-10-06",
    )
    _feed(household_a, "9001", ((dt.date(2026, 10, 9), -40_000), (dt.date(2026, 10, 20), -3_393)))
    is_error, text, structured = call(mcp_write, "account_balances", {"account": "9001"})
    assert not is_error, text
    (account,) = structured["accounts"]
    assert account["statement_closing_cents"] == 8_850
    assert account["current_cents"] == 8_850 - 43_393 == -34_543
    assert account["verified_balance_cents"] == account["current_cents"]
    assert account["fact_since_bank_feed_count"] == 2
    assert (
        "Adv SafeBalance Banking (ending 9001, USD): Now: -$345.43 (statement balance $88.50 on "
        "Tue Oct 6, 2026, verified, plus -$433.93 of bank-feed transactions since)."
    ) in text
    # The current figure comes first; the statement's figure only after it.
    assert text.index("-$345.43") < text.index("$88.50")
    # The bank's feed is fact: never called unverified.
    assert "unverified" not in text.lower()


def test_the_tool_tells_the_model_to_say_the_now_figure():
    from engine_mcp.tools import TOOLS_BY_NAME

    description = TOOLS_BY_NAME["account_balances"].description
    assert "LEADS with \"Now: <figure>\"" in description
    assert "say it as the balance" in description


def test_a_bank_feed_account_is_fact_with_no_stored_balance(mcp_write, household_a):
    """ADR 0057: the bank's own feed is fact, shown plainly. Plaid sync keeps
    no balance, so the engine says it has none to state, and never calls the
    account or its rows unverified (the phone: "not stated", verified)."""
    from django.db.models.functions import Now

    from legacy.models.ledger import LedgerTransaction

    feed = ((dt.date(2026, 10, 1), -1_250), (dt.date(2026, 10, 3), 90_000))
    for index, (day, cents) in enumerate(feed):
        LedgerTransaction.objects.create(
            id=uuid.uuid4(),
            household=household_a,
            account_last4="5555",
            account_nickname="BofA Checking",
            currency="USD",
            txn_date=day,
            description=f"FEED {index}",
            amount_cents=cents,
            line_ref=f"plaid:{index}",
            category_pending=False,
            provenance="BANK_API",
            bank_transaction_id=f"txn-{index}",
            created_at=Now(),
        )
    is_error, text, structured = call(mcp_write, "account_balances", {})
    assert not is_error, text
    (account,) = structured["accounts"]
    assert account["verified"] is True and account["from_bank_feed"] is True
    assert account["verified_balance_cents"] is None
    assert "the bank's own feed supplies this account's transactions" in text
    assert "stores no balance for it" in text
    assert "unverified" not in text.lower()


def test_account_balances_empty_and_unmatched_are_real_empty_results(mcp_write):
    is_error, text, _ = call(mcp_write, "account_balances", {})
    assert not is_error and "real empty result" in text
    is_error, text, _ = call(mcp_write, "account_balances", {"account": "9999"})
    assert not is_error and "no account matching '9999'" in text


# =============================================================================
# grocery_spend
# =============================================================================


def test_grocery_spend_on_the_demo_receipts(mcp_write, demo):
    expected = sum(
        r["total_cents"]
        for first in demo_seed.statement_months(TODAY)
        for r in demo_seed.month_receipts(first)
    )
    is_error, text, structured = call(mcp_write, "grocery_spend", {})
    assert not is_error, text
    (usd,) = structured["currencies"]
    # The seed's bad receipt was quarantined by the gate, so it is not here.
    assert usd["total_cents"] == expected and usd["unverified_receipts"] == 0
    assert f"USD: {money(expected)}" in text
    assert "Every receipt in it passed the reconciliation gate" in text


def test_grocery_spend_keeps_currencies_apart_and_names_the_unverified(
    mcp_write, auth_client, household_a
):
    from django.db.models.functions import Now

    from tests.test_ingest_api import a_receipt

    _ok(auth_client.post("/api/ingest/receipt", a_receipt(), format="json"))  # SGD 12.42
    _ok(
        auth_client.post(
            "/api/ingest/receipt",
            a_receipt(content_sha256="sha-usd", currency="USD", purchase_date="2026-08-01"),
            format="json",
        )
    )
    Receipt.objects.create(
        id=uuid.uuid4(),
        household=household_a,
        store="OLD STORE",
        purchase_date=dt.date(2026, 7, 20),
        currency="SGD",
        total_cents=500,
        unaccounted_cents=40,
        provenance="UNRECONCILED",
        created_at=Now(),
    )
    is_error, text, structured = call(mcp_write, "grocery_spend", {})
    assert not is_error, text
    by = {c["currency"]: c for c in structured["currencies"]}
    assert by["SGD"]["total_cents"] == 1_742 and by["SGD"]["unverified_cents"] == 500
    assert by["USD"]["total_cents"] == 1_242
    assert "never add them together" in text
    assert "SGD 5.00 of that, on 1 receipt(s), is UNVERIFIED" in text

    is_error, text, structured = call(
        mcp_write, "grocery_spend", {"from": "2026-07-16", "to": "2026-07-31"}
    )
    assert [c["currency"] for c in structured["currencies"]] == ["SGD"]
    assert structured["currencies"][0]["total_cents"] == 500

    is_error, text, _ = call(
        mcp_write, "grocery_spend", {"from": "2026-08-01", "to": "2026-07-01"}
    )
    assert is_error and text.startswith("Nothing was read.")


def test_grocery_spend_with_no_receipts_is_a_real_empty_result(mcp_write):
    is_error, text, _ = call(mcp_write, "grocery_spend", {"from": "2026-01-01"})
    assert not is_error and "real empty result" in text


# =============================================================================
# vehicle_recalls (NHTSA mocked; no network)
# =============================================================================

NHTSA_ANSWER = {
    "Count": 2,
    "results": [
        {
            "NHTSACampaignNumber": "19V123000",
            "Component": "AIR BAGS",
            "Summary": "The passenger air bag may not deploy.",
            "Remedy": "Dealers will replace the inflator.",
            "ReportReceivedDate": "01/02/2019",
        },
        {
            "NHTSACampaignNumber": "20V456000",
            "Component": "FUEL SYSTEM",
            "Summary": "A fuel line may leak.",
            "Remedy": "",
        },
    ],
}


def test_vehicle_recalls_reads_nhtsa_for_the_demo_car(mcp_write, demo, monkeypatch):
    asked = []

    def fake(make, model, year):
        asked.append((make, model, year))
        return recalls.parse_recalls(json.dumps(NHTSA_ANSWER))

    monkeypatch.setattr(recalls, "fetch_recalls", fake)
    is_error, text, structured = call(mcp_write, "vehicle_recalls", {})
    assert not is_error, text
    assert asked == [("Subaru", "Outback", 2019)]
    assert "NHTSA lists 2 recall(s) for the 2019 Subaru Outback (The Wagon)" in text
    assert "19V123000: AIR BAGS" in text and "Remedy: not stated" in text
    assert "not a check of this car's VIN" in text
    assert [r["campaign"] for r in structured["recalls"]] == ["19V123000", "20V456000"]


def test_no_recalls_is_nhtsas_list_not_a_clean_bill(mcp_write, demo, monkeypatch):
    monkeypatch.setattr(recalls, "fetch_recalls", lambda *a: [])
    is_error, text, _ = call(mcp_write, "vehicle_recalls", {"vehicle": "wagon"})
    assert not is_error
    assert "shows no recalls for the 2019 Subaru Outback" in text
    assert "as of this lookup" in text


def test_a_failed_lookup_says_it_is_not_known(mcp_write, demo):
    is_error, text, _ = call(mcp_write, "vehicle_recalls", {})
    assert is_error
    assert text.startswith("Nothing was read.")
    assert "NOT known whether it has recalls" in text and "not a finding of none" in text


def test_recalls_want_one_vehicle_when_several_are_on_file(mcp_write, auth_client):
    _car(auth_client, "a")
    _car(auth_client, "b", name="F-150", make="Ford", model="F-150", year=2018)
    is_error, text, _ = call(mcp_write, "vehicle_recalls", {})
    assert is_error and "one vehicle at a time" in text


def test_fetch_recalls_uses_a_timeout_and_reports_failure(monkeypatch):
    seen = {}

    class Answer:
        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def read(self):
            return json.dumps(NHTSA_ANSWER).encode()

    def fake_urlopen(request, timeout):
        seen["url"], seen["timeout"] = request.full_url, timeout
        return Answer()

    monkeypatch.setattr(recalls.urllib.request, "urlopen", fake_urlopen)
    assert len(REAL_FETCH("Land Rover", "Range Rover", 2010)) == 2
    assert seen["timeout"] == recalls.TIMEOUT_SECONDS
    assert seen["url"] == (
        "https://api.nhtsa.gov/recalls/recallsByVehicle?make=Land+Rover&model=Range+Rover"
        "&modelYear=2010"
    )

    def down(request, timeout):
        raise recalls.urllib.error.URLError("no route")

    monkeypatch.setattr(recalls.urllib.request, "urlopen", down)
    with pytest.raises(recalls.RecallLookupFailed):
        REAL_FETCH("BMW", "M3", 2003)
    with pytest.raises(recalls.RecallLookupFailed):
        recalls.parse_recalls(b"<html>busy</html>")
    with pytest.raises(recalls.RecallLookupFailed):
        recalls.parse_recalls(b'{"Count": 0}')


# =============================================================================
# Checklist writes
# =============================================================================


def _lists(mcp, **args):
    _, _, structured = call(mcp, "list_checklists", args)
    return {row["name"]: row for row in structured["checklists"]}


def test_create_checklist_plain_daily_and_weekly(mcp_write):
    is_error, text, structured = call(mcp_write, "create_checklist", {"name": "bio"})
    assert not is_error, text
    assert structured["row"]["schedule_kind"] is None
    is_error, text, structured = call(
        mcp_write, "create_checklist", {"name": "gym", "schedule": "weekly", "days": "MON,THU"}
    )
    assert not is_error, text
    assert structured["row"]["schedule_kind"] == "WEEKLY"
    assert structured["row"]["schedule_days_of_week"] == "MON,THU"
    call(mcp_write, "create_checklist", {"name": "morning", "schedule": "daily", "sync_id": "s1"})
    is_error, text, _ = call(
        mcp_write, "create_checklist", {"name": "morning", "schedule": "daily", "sync_id": "s1"}
    )
    assert not is_error and text.startswith("Nothing was created")
    assert set(_lists(mcp_write)) >= {"bio", "gym", "morning"}


@pytest.mark.parametrize(
    "args",
    [
        {"name": "gym", "schedule": "weekly"},
        {"name": "gym", "days": "MON"},
        {"name": "gym", "schedule": "weekly", "days": "FUNDAY"},
        {"name": "   "},
    ],
)
def test_create_checklist_refuses_a_bad_schedule_in_words(mcp_write, args):
    is_error, text, _ = call(mcp_write, "create_checklist", args)
    assert is_error and text.startswith("Nothing was written."), text
    assert "gym" not in _lists(mcp_write)


def test_untick_remove_and_archive(mcp_write):
    checklist = call(mcp_write, "create_checklist", {"name": "gym"})[2]["row"]["id"]
    item = call(mcp_write, "add_checklist_item", {"checklist_id": checklist, "text": "bench"})[2]
    item = item["row"]["id"]
    ids = {"checklist_id": checklist, "item_id": item}
    call(mcp_write, "tick_checklist_item", {**ids, "date": "2026-10-02"})

    is_error, text, structured = call(
        mcp_write, "untick_checklist_item", {**ids, "date": "2026-10-02"}
    )
    assert not is_error and structured == {"unticked": True}, text
    assert _lists(mcp_write, date="2026-10-02")["gym"]["items"][0]["ticked_on_date"] is False
    is_error, text, structured = call(
        mcp_write, "untick_checklist_item", {**ids, "date": "2026-10-02"}
    )
    assert not is_error and text.startswith("Nothing was unticked") and not structured["unticked"]

    is_error, text, _ = call(mcp_write, "remove_checklist_item", ids)
    assert not is_error and "Removed 'bench' from 'gym'" in text
    assert _lists(mcp_write)["gym"]["items"] == []
    is_error, text, _ = call(mcp_write, "remove_checklist_item", ids)
    assert not is_error and text.startswith("Nothing was deleted")

    is_error, text, _ = call(mcp_write, "archive_checklist", {"checklist_id": checklist})
    assert not is_error and text.startswith("Archived the checklist 'gym'")
    assert "gym" not in _lists(mcp_write)
    assert "gym" in _lists(mcp_write, include_archived=True)
    is_error, text, _ = call(
        mcp_write, "archive_checklist", {"checklist_id": checklist, "archived": False}
    )
    assert not is_error and "gym" in _lists(mcp_write)


def test_the_groceries_list_cannot_be_archived(mcp_write, household_a):
    from checklists.builtin import ensure_builtin_lists

    groceries = ensure_builtin_lists(household_a)
    is_error, text, _ = call(mcp_write, "archive_checklist", {"checklist_id": str(groceries.id)})
    assert is_error and text.startswith("Nothing was written.")
    groceries.refresh_from_db()
    assert groceries.archived is False


def test_checklist_writes_on_a_missing_item_refuse(mcp_write):
    ids = {"checklist_id": str(uuid.uuid4()), "item_id": str(uuid.uuid4())}
    is_error, text, _ = call(mcp_write, "untick_checklist_item", {**ids, "date": "2026-10-02"})
    assert is_error and text.startswith("Nothing was written.")
    is_error, text, _ = call(mcp_write, "remove_checklist_item", ids)
    assert is_error and text.startswith("Nothing was deleted.")
    is_error, text, _ = call(mcp_write, "archive_checklist", {"checklist_id": ids["item_id"]})
    assert is_error and text.startswith("Nothing was written.")


def test_untick_on_the_demo_routine(mcp_write, demo):
    from checklists.models import ChecklistTick

    tick = (
        ChecklistTick.objects.filter(
            household=demo.household,
            deleted_at__isnull=True,
            item__checklist__system_key__isnull=True,
        )
        .select_related("item")
        .first()
    )
    day = dt.date(1970, 1, 1) + dt.timedelta(days=tick.day)
    is_error, text, _ = call(
        mcp_write,
        "untick_checklist_item",
        {"checklist_id": str(tick.item.checklist_id), "item_id": str(tick.item_id),
         "date": day.isoformat()},
    )
    assert not is_error and text.startswith("Unticked"), text
    tick.refresh_from_db()
    assert tick.deleted_at is not None


# =============================================================================
# rename_place, skip_event_occurrence
# =============================================================================


def test_rename_place_keeps_the_location(mcp_write, auth_client):
    _ok(auth_client.put("/api/places/home/", {"latitude": 1.5, "longitude": 2.5}, format="json"))
    _ok(auth_client.put("/api/places/gym/", {"latitude": 3.0, "longitude": 4.0}, format="json"))
    is_error, text, _ = call(mcp_write, "rename_place", {"from": "home", "to": "cottage"})
    assert not is_error and text.startswith("Renamed the place 'home' to 'cottage'"), text
    live = {
        row["label"]: row for row in auth_client.get("/api/places/?active=1").data["results"]
    }
    assert set(live) == {"cottage", "gym"} and live["cottage"]["latitude"] == 1.5

    is_error, text, _ = call(mcp_write, "rename_place", {"from": "home", "to": "x"})
    assert is_error and text.startswith("Nothing was renamed.") and "no saved place" in text
    is_error, text, _ = call(mcp_write, "rename_place", {"from": "cottage", "to": "gym"})
    assert is_error and text.startswith("Nothing was renamed.")
    is_error, text, _ = call(mcp_write, "rename_place", {"from": "a/b", "to": "c"})
    assert is_error and text.startswith("Nothing was renamed.")


def test_rename_a_demo_place(mcp_write, demo):
    is_error, text, _ = call(mcp_write, "rename_place", {"from": "Gym", "to": "Ironleaf"})
    assert not is_error, text


def _weekly(auth_client, starts="2026-10-06T09:00:00Z", **extra):
    body = {
        "title": "standup",
        "starts_at": starts,
        "repeat_kind": "WEEKLY",
        "repeat_every": 1,
        "repeat_days_of_week": "TUESDAY",
        "repeat_end_kind": "NEVER",
        **extra,
    }
    return str(_ok(auth_client.post("/api/events", body, format="json"))["id"])


def test_skip_one_occurrence_of_a_weekly_event(mcp_write, auth_client):
    event = _weekly(auth_client)
    is_error, text, structured = call(
        mcp_write, "skip_event_occurrence", {"id": event, "date": "2026-10-13"}
    )
    assert not is_error and text.startswith("Skipped 'standup' on Tue Oct 13, 2026"), text
    assert structured["changed"] is True
    skips = auth_client.get(f"/api/events/{event}/skips").data
    assert [s["skip_date"] for s in skips] == ["2026-10-13"]

    is_error, text, structured = call(
        mcp_write, "skip_event_occurrence", {"id": event, "date": "2026-10-13"}
    )
    assert not is_error and "already skipped" in text and structured["changed"] is False
    # Wednesday is not an occurrence.
    is_error, text, _ = call(
        mcp_write, "skip_event_occurrence", {"id": event, "date": "2026-10-14"}
    )
    assert is_error and text.startswith("Nothing was written.") and "no occurrence" in text
    assert len(auth_client.get(f"/api/events/{event}/skips").data) == 1


def test_skip_refuses_an_event_that_does_not_repeat(mcp_write, auth_client):
    once = _ok(
        auth_client.post(
            "/api/events", {"title": "dentist", "starts_at": "2026-10-05T09:00:00Z"}, format="json"
        )
    )["id"]
    is_error, text, _ = call(
        mcp_write, "skip_event_occurrence", {"id": str(once), "date": "2026-10-05"}
    )
    assert is_error and "does not repeat" in text
    is_error, text, _ = call(
        mcp_write, "skip_event_occurrence", {"id": str(uuid.uuid4()), "date": "2026-10-05"}
    )
    assert is_error and text.startswith("Nothing was written.")


def test_skip_the_demo_run_club(mcp_write, demo):
    from legacy.models.dates import Event

    run = Event.objects.get(household=demo.household, origin_guid="demo-event-run-club")
    occurrence = run.starts_at.astimezone(dt.UTC).date() + dt.timedelta(days=7)
    is_error, text, _ = call(
        mcp_write, "skip_event_occurrence", {"id": str(run.id), "date": occurrence.isoformat()}
    )
    assert not is_error and text.startswith("Skipped 'Run club'"), text


# =============================================================================
# The seed's additions
# =============================================================================


def test_the_seed_carries_categories_budgets_and_a_repeating_event(demo):
    from legacy.models.dates import Event
    from legacy.models.ledger import BudgetTarget, Category

    household = demo.household
    categories = {c.name: c for c in Category.objects.filter(household=household)}
    assert set(categories) == {name for name, _, _ in demo_seed.CATEGORIES}
    assert [n for n, c in categories.items() if c.excluded_from_spend] == ["Transfer"]
    assert BudgetTarget.objects.filter(household=household).count() == len(
        demo_seed.BUDGET_TARGETS
    )
    run = Event.objects.get(household=household, origin_guid="demo-event-run-club")
    assert run.repeat_kind == "WEEKLY"
    # A second run upserts in place.
    demo_seed.seed_household(household, today=TODAY)
    assert Category.objects.filter(household=household).count() == len(demo_seed.CATEGORIES)
    assert BudgetTarget.objects.filter(household=household).count() == len(
        demo_seed.BUDGET_TARGETS
    )
