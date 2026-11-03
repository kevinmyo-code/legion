"""recruiter-demo ticket 03: `manage.py seed_demo_household`.

What the ticket's own Verify line asks for, and what it says beside it:

- the seed is idempotent, and `--reset` reseeds to the same rows;
- the one receipt whose lines do not add up reaches the ENGINE's gate
  (`ingest.views.commit_receipt` -> `ingest.gate.check_receipt`) and the engine
  quarantines it - the seed never writes a quarantined row itself;
- its anchors: on dev a quarantine writes ONLY the `ingested_files` row
  (`ingest.views._quarantine`), so no receipt header with `total_cents` /
  `subtotal_cents` / `tax_cents` columns exists for a refused document. The
  figures the gate checked survive in `quarantine_reason`'s sentence, and
  that is what is asserted - stated here so the test is not mistaken for
  proof of column storage. The COMMITTED receipts and statements do carry
  their anchors in their own columns, and that is asserted too;
- nothing the seed does reaches any other household.
"""
from __future__ import annotations

import datetime as dt
import hashlib
from types import SimpleNamespace

import pytest
from django.core.management import call_command
from django.db import connection

from household.demo import DemoHouseholdError, demo_household, find_demo_household
from household.demo_seed import (
    BAD_RECEIPT_ITEMS_CENTS,
    BAD_RECEIPT_KEY,
    BAD_RECEIPT_SUBTOTAL_CENTS,
    STATEMENT_MONTHS,
    bad_receipt,
    month_receipts,
    seed,
    statement_months,
)
from household.models import Household
from household.tenancy import TENANT_TABLES
from legacy.models.dates import Event
from legacy.models.fleet import MaintenanceSchedule, ServiceHistory, Vehicle
from legacy.models.ingest import IngestedFile
from legacy.models.ledger import LedgerTransaction, Statement
from legacy.models.notes import VoiceNote
from legacy.models.pantry import Receipt, ReceiptLineItem
from legacy.models.places import Place
from tests.test_ingest_api import RECEIPT_URL, a_receipt

pytestmark = pytest.mark.django_db

TODAY = dt.date(2026, 11, 2)
BAD_SHA = hashlib.sha256(BAD_RECEIPT_KEY.encode("utf-8")).hexdigest()
EXPECTED_REASON = (
    f"The 7 extracted items come to {BAD_RECEIPT_ITEMS_CENTS} cents but the receipt prints a "
    f"subtotal of {BAD_RECEIPT_SUBTOTAL_CENTS} cents. Nothing was saved."
)


def _existing_tenant_tables() -> list[str]:
    with connection.cursor() as cursor:
        cursor.execute(
            "select table_name from information_schema.columns "
            "where table_schema = 'public' and column_name = 'household_id'"
        )
        present = {row[0] for row in cursor.fetchall()}
    return [table for table in TENANT_TABLES if table in present]


def _counts(*, inside, household) -> dict[str, int]:
    """Row count per tenant table, inside or outside one household."""
    op = "=" if inside else "<>"
    counts = {}
    with connection.cursor() as cursor:
        for table in _existing_tenant_tables():
            cursor.execute(
                f'select count(*) from public."{table}" where household_id {op} %s',
                [household.id],
            )
            counts[table] = cursor.fetchone()[0]
    return counts


# =============================================================================
# What the seed builds
# =============================================================================


def test_the_seed_builds_every_aspect_with_invented_data():
    report = seed(today=TODAY)
    demo = report.household

    assert report.created_household
    assert demo.name == "Demo"
    assert report.statements == {"COMMITTED": STATEMENT_MONTHS}
    assert report.receipts == {"COMMITTED": 3 * STATEMENT_MONTHS}

    # Ledger: verified statement rows AND unverified card rows, both present.
    statements = Statement.objects.filter(household=demo)
    assert statements.count() == STATEMENT_MONTHS
    gated = LedgerTransaction.objects.filter(household=demo, provenance="DETERMINISTIC")
    provisional = LedgerTransaction.objects.filter(household=demo, provenance="UNRECONCILED")
    assert gated.count() > 40
    assert provisional.count() > 0
    assert report.card["inserted"] == provisional.count()
    first, last = statement_months(TODAY)[0], TODAY - dt.timedelta(days=1)
    dates = LedgerTransaction.objects.filter(household=demo).values_list("txn_date", flat=True)
    assert min(dates) == first
    assert max(dates) == last

    # Section 4 rule 8: every committed statement's three anchors are stored.
    for statement in statements:
        lines = LedgerTransaction.objects.filter(statement=statement)
        total = sum(lines.values_list("amount_cents", flat=True))
        assert statement.stated_total_cents == total
        assert statement.closing_balance_cents - statement.opening_balance_cents == total

    # Pantry: receipts gated, anchors in their own columns, estimates on items.
    receipts = Receipt.objects.filter(household=demo)
    assert receipts.count() == 3 * STATEMENT_MONTHS
    for receipt in receipts:
        items = ReceiptLineItem.objects.filter(receipt=receipt)
        assert receipt.provenance == "LLM_RECONCILED"
        assert receipt.subtotal_cents == sum(items.values_list("total_price_cents", flat=True))
        assert receipt.total_cents == receipt.subtotal_cents + receipt.tax_cents
    assert ReceiptLineItem.objects.filter(
        household=demo, estimated_calories_kcal__isnull=False
    ).exists()

    # Fleet, places, notes, dates.
    assert Vehicle.objects.filter(household=demo).count() == 1
    assert ServiceHistory.objects.filter(household=demo).count() == 5
    assert MaintenanceSchedule.objects.filter(household=demo).count() == 5
    assert Place.objects.filter(household=demo).count() == 4
    assert VoiceNote.objects.filter(household=demo).count() == 3
    events = Event.objects.filter(household=demo)
    assert events.count() == 6
    # Ledger categories and budgets (parity quick wins), Transfer not spending.
    from legacy.models.ledger import BudgetTarget, Category

    assert Category.objects.filter(household=demo).count() == 8
    assert BudgetTarget.objects.filter(household=demo).count() == 6
    # No user was created; a seeded event is shared and attributed to nobody.
    assert set(events.values_list("owner_user_id", flat=True)) == {None}
    assert set(events.values_list("created_by_id", flat=True)) == {None}


def test_the_seed_creates_no_users_and_no_members():
    from household.models import HouseholdMember, User

    users, members = User.objects.count(), HouseholdMember.objects.count()
    seed(today=TODAY)
    assert User.objects.count() == users
    assert HouseholdMember.objects.count() == members


def test_checklists_ticks_and_the_bought_log_are_seeded():
    from checklists.models import Checklist, ChecklistItem, ChecklistTick
    from household.demo_seed import GROCERY_TICKS, MANUAL_PURCHASES, ROUTINE_TICKS
    from purchases.models import Purchase

    demo = seed(today=TODAY).household
    lists = Checklist.objects.filter(household=demo, deleted_at__isnull=True)
    assert set(lists.values_list("name", flat=True)) == {"Groceries", "Morning routine"}
    assert set(lists.values_list("owner_user_id", flat=True)) == {None}
    assert ChecklistItem.objects.filter(household=demo).count() == len(GROCERY_TICKS) + len(
        ROUTINE_TICKS
    )
    ticks = sum(len(days) for _, _, days in GROCERY_TICKS + ROUTINE_TICKS)
    assert ChecklistTick.objects.filter(household=demo).count() == ticks

    # Every Groceries tick became a bought entry through the engine's own hook.
    grocery_ticks = sum(len(days) for _, _, days in GROCERY_TICKS)
    from_ticks = Purchase.objects.filter(household=demo, source="GROCERIES_TICK")
    assert from_ticks.count() == grocery_ticks
    assert not from_ticks.filter(tick__isnull=True).exists()
    assert set(from_ticks.values_list("created_by_id", flat=True)) == {None}
    manual = Purchase.objects.filter(household=demo, source="MANUAL")
    assert manual.count() == len(MANUAL_PURCHASES)
    assert not manual.filter(price_cents__isnull=True).exists()
    # The newest oat milk tick is two days before TODAY.
    newest = from_ticks.filter(item="Oat milk").order_by("-bought_on").first()
    assert newest.bought_on == (TODAY - dt.date(1970, 1, 1)).days - 2


def test_the_car_has_a_maintenance_item_coming_due():
    """Read the way the morning brief reads it, so "due" is the engine's
    verdict and not the seed's own arithmetic."""
    from brief.compose import Viewer, _maintenance_lines

    demo = seed(today=TODAY).household
    vehicle = Vehicle.objects.get(household=demo)
    viewer = Viewer(user=SimpleNamespace(household=demo, pk=None))
    lines = _maintenance_lines(viewer, vehicle, TODAY)
    assert any(line.startswith("Oil change due") for line in lines), lines
    assert any("Never logged as done: Brake fluid" in line for line in lines), lines


# =============================================================================
# Idempotency and reset
# =============================================================================


def test_a_second_run_writes_nothing_new():
    first = seed(today=TODAY)
    demo = first.household
    before = _counts(inside=True, household=demo)

    second = seed(today=TODAY)

    assert not second.created_household
    assert second.household.id == demo.id
    assert second.statements == {"ALREADY_COMMITTED": STATEMENT_MONTHS}
    assert second.receipts == {"ALREADY_COMMITTED": 3 * STATEMENT_MONTHS}
    assert second.card["outcome"] == "ALREADY_COMMITTED"
    assert _counts(inside=True, household=demo) == before
    assert Household.objects.filter(name="Demo").count() == 1


def test_reset_deletes_what_it_may_keeps_what_is_gated_and_reseeds_the_same_rows():
    demo = seed(today=TODAY).household
    before = _counts(inside=True, household=demo)

    report = seed(reset_first=True, today=TODAY)

    assert report.household.id == demo.id
    # Authored and provisional rows were really deleted, not merely kept...
    assert report.reset_deleted["vehicles"] == 1
    assert report.reset_deleted["events"] == 6
    assert report.reset_deleted["categories"] == 8
    assert report.reset_deleted["budget_targets"] == 6
    assert report.reset_deleted["ledger_transactions (UNRECONCILED)"] > 0
    # ...the bad receipt's file row went too, and the gate refused it again.
    assert report.reset_deleted["ingested_files (not behind a gated row)"] >= 2
    assert report.bad_receipt["outcome"] == "QUARANTINED"
    # forbid_mutation_of_facts keeps every gated row; the reseed finds them.
    assert report.reset_kept["statements"] == STATEMENT_MONTHS
    assert report.reset_kept["receipts"] == 3 * STATEMENT_MONTHS
    assert report.statements == {"ALREADY_COMMITTED": STATEMENT_MONTHS}
    assert _counts(inside=True, household=demo) == before


def test_a_later_day_rolls_forward_without_duplicating_a_month():
    """The nightly run on a new month adds that month and keeps the old ones;
    a month already committed is never written twice."""
    demo = seed(today=TODAY).household
    report = seed(reset_first=True, today=dt.date(2026, 12, 3))
    assert report.statements == {"ALREADY_COMMITTED": STATEMENT_MONTHS - 1, "COMMITTED": 1}
    assert Statement.objects.filter(household=demo).count() == STATEMENT_MONTHS + 1


# =============================================================================
# The receipt that does not add up
# =============================================================================


def test_the_unbalanced_receipt_is_quarantined_by_the_engine_gate():
    report = seed(today=TODAY)
    demo = report.household

    assert report.bad_receipt == {
        "outcome": "QUARANTINED",
        "reason": EXPECTED_REASON,
        "inserted": 0,
    }

    row = IngestedFile.objects.get(household=demo, content_sha256=BAD_SHA)
    assert row.state == "QUARANTINED"
    assert row.quarantine_reason == EXPECTED_REASON
    # The anchors the gate checked, as dev stores them for a refused document:
    # in the reason's sentence. No receipt header or line item was written.
    assert str(BAD_RECEIPT_ITEMS_CENTS) in row.quarantine_reason
    assert str(BAD_RECEIPT_SUBTOTAL_CENTS) in row.quarantine_reason
    assert not Receipt.objects.filter(ingested_file=row).exists()
    assert not ReceiptLineItem.objects.filter(household=demo, name="Laundry pods").exists()
    assert IngestedFile.objects.filter(household=demo, state="QUARANTINED").count() == 1


def test_the_unbalanced_receipt_really_is_unbalanced_and_only_by_its_subtotal():
    """Guards the fixture: if someone 'fixes' its numbers, the demo silently
    loses the one thing it exists to show."""
    payload = bad_receipt()
    items = sum(item["total_price_cents"] for item in payload["items"])
    assert items == BAD_RECEIPT_ITEMS_CENTS
    assert payload["subtotal_cents"] == BAD_RECEIPT_SUBTOTAL_CENTS
    assert items != payload["subtotal_cents"]
    assert payload["subtotal_cents"] + payload["tax_cents"] == payload["total_cents"]


def test_a_rerun_puts_the_unbalanced_receipt_through_the_gate_again():
    seed(today=TODAY)
    report = seed(today=TODAY)
    assert report.bad_receipt["outcome"] == "QUARANTINED"
    assert IngestedFile.objects.filter(content_sha256=BAD_SHA).count() == 1


def test_what_the_engine_says_about_the_unbalanced_receipt():
    """The assistant's own read tool, scoped to the demo household: the
    quarantined file is there, with its reason in words."""
    from engine_mcp.tools import run_tool

    demo = seed(today=TODAY).household
    request = SimpleNamespace(user=SimpleNamespace(household=demo, pk=None))
    result = run_tool(request, "read_records", {"table": "ingested_files", "limit": 200})
    assert not result.is_error, result.text
    rows = [r for r in result.structured["rows"] if r["content_sha256"] == BAD_SHA]
    assert len(rows) == 1
    assert rows[0]["state"] == "QUARANTINED"
    assert rows[0]["quarantine_reason"] == EXPECTED_REASON

    card = run_tool(request, "read_records", {"table": "ledger_transactions", "limit": 200})
    assert "UNVERIFIED (UNRECONCILED)" in card.text
    assert "verified: passed the reconciliation gate" in card.text


def test_a_recruiter_can_ask_which_card_charges_are_unverified():
    """`/try` offers "Which card charges are unverified?" (Kevin, 2026-11-02,
    in place of the rejected-receipt chip). Through the recruiter's own tool
    door, scoped to the visitor's own seeded copy (ticket 13), the answer is
    there in words."""
    import uuid

    from household.demo_seed import seed_household
    from household.models import HouseholdMember, User
    from tests.test_recruiter import _recruiter_browser, _visitor_token

    demo = Household.objects.create(name="Demo visit seed", is_demo_visit=True)
    seed_household(demo, today=TODAY, created=True)
    user = User.objects.create_user(email=f"visitor-{uuid.uuid4().hex[:12]}@demo.invalid")
    HouseholdMember.objects.create(user=user, household=demo, role=HouseholdMember.RECRUITER)
    token, _raw = _visitor_token(user)
    browser = _recruiter_browser(user, token)
    response = browser.post(
        "/api/assistant/tool",
        {"name": "read_records", "args": {"table": "ledger_transactions", "limit": 200}},
        format="json",
    )
    assert response.status_code == 200, response.data
    assert response.data["is_error"] is False, response.data["text"]
    assert "UNVERIFIED (UNRECONCILED)" in response.data["text"]


# =============================================================================
# Isolation
# =============================================================================


def test_the_seed_never_writes_into_another_household(token_b, household_a, household_b):
    """Household B holds rows of its own, including a receipt with the SAME
    content hash as one of the demo's. Seeding changes no row outside the demo
    household, and B's copy does not make the demo's look already committed."""
    shared = month_receipts(statement_months(TODAY)[0])[0]
    response = token_b.post(RECEIPT_URL, shared, format="json")
    assert response.status_code == 201, response.data
    response = token_b.post(RECEIPT_URL, a_receipt(), format="json")
    assert response.status_code == 201, response.data

    before = {
        household_a.name: _counts(inside=True, household=household_a),
        household_b.name: _counts(inside=True, household=household_b),
    }

    report = seed(today=TODAY)
    demo = report.household

    assert demo.id not in {household_a.id, household_b.id}
    assert _counts(inside=True, household=household_a) == before[household_a.name]
    assert _counts(inside=True, household=household_b) == before[household_b.name]
    assert report.receipts == {"COMMITTED": 3 * STATEMENT_MONTHS}
    assert Receipt.objects.filter(
        household=demo, ingested_file__content_sha256=shared["content_sha256"]
    ).exists()
    # Every row outside the demo household is accounted for by A and B.
    outside = _counts(inside=False, household=demo)
    for table, count in outside.items():
        assert count == before[household_a.name][table] + before[household_b.name][table], table


# =============================================================================
# The helper ticket 02 imports, and the command
# =============================================================================


def test_demo_household_refuses_in_words_before_the_seed_has_run():
    assert find_demo_household() is None
    with pytest.raises(DemoHouseholdError, match="seed_demo_household"):
        demo_household()


def test_demo_household_finds_the_seeded_one():
    report = seed(today=TODAY)
    assert demo_household().id == report.household.id


def test_demo_household_refuses_an_ambiguous_name():
    seed(today=TODAY)
    Household.objects.create(name="Demo")
    with pytest.raises(DemoHouseholdError, match="More than one household"):
        demo_household()


def test_the_demo_household_name_is_a_setting(settings):
    settings.LEGION_DEMO_HOUSEHOLD_NAME = "Showcase"
    report = seed(today=TODAY)
    assert report.household.name == "Showcase"
    assert demo_household().id == report.household.id
    assert not Household.objects.filter(name="Demo").exists()


def test_the_command_runs_twice_and_says_what_the_gate_did(capsys):
    call_command("seed_demo_household")
    call_command("seed_demo_household", "--reset")
    out = capsys.readouterr().out
    assert "Created demo household Demo" in out
    assert "Refreshed demo household Demo" in out
    assert "The unbalanced receipt: QUARANTINED." in out
    assert "Reset kept (immutable, gated)" in out
