"""ADR 0057: the bank's own transaction feed (Plaid) as the ledger's source of
truth, and Bank of America retired from the Drive watcher.

Plaid is a fake (`FakePlaid`, installed with `plaid_client.set_gateway_factory`):
nothing here reaches the network. Drive and Gemini are the fakes from ticket
06's tests.
"""
from __future__ import annotations

import datetime
import uuid
from decimal import Decimal
from pathlib import Path

import pytest
from cryptography.fernet import Fernet
from django.db import IntegrityError, connection, transaction
from django.utils import timezone
from rest_framework.test import APIClient

from ingest import plaid_client, plaid_sync, statements, vault
from ingest.bank_api_sql import SUPERSEDE_SETTING
from ingest.jobs import run_job
from ingest.models import IngestRun, Outcome, PlaidItem, Source
from ingest.plaid_sync import ledger_amount_cents, to_cents
from ingest.views import commit_statement
from legacy.enums import Provenance
from legacy.models.ledger import LedgerTransaction, LedgerTransactionCategory, Statement
from tests.test_drive_statements import (
    DRIVE_SECRET,
    FOLDER,
    KEY,
    FakeDrive,
    FakeGemini,
    a_reading,
)
from tests.test_drive_statements import run as run_watcher
from tests.test_ingest_api import a_statement

pytestmark = pytest.mark.django_db

ACCESS = "access-sandbox-0000-never-served"
FIXTURES = Path(__file__).parent / "bofa_fixtures"

CHECKING = {
    "account_id": "acc-chk",
    "mask": "1000",
    "name": "Adv Plus Banking",
    "official_name": None,
    "type": "depository",
    "subtype": "checking",
}
CARD = {
    "account_id": "acc-card",
    "mask": "7823",
    "name": "Customized Cash Rewards",
    "official_name": None,
    "type": "credit",
    "subtype": "credit card",
}


def txn(tid, *, account="acc-card", amount=12.5, date="2026-09-10", name="COFFEE", **extra):
    return {
        "transaction_id": tid,
        "account_id": account,
        "amount": amount,
        "iso_currency_code": "USD",
        "date": date,
        "name": name,
        "original_description": extra.pop("original_description", None),
        "merchant_name": None,
        "pending": extra.pop("pending", False),
        "pending_transaction_id": extra.pop("pending_transaction_id", None),
        **extra,
    }


class FakePlaid:
    """`PlaidGateway`, in memory. `pages[cursor]` is what a sync from `cursor`
    returns; `None` is the first call's cursor."""

    def __init__(self):
        self.pages: dict = {}
        self.item = {
            "item_id": "item-1",
            "institution_id": "ins_127989",
            "institution_name": "Bank of America",
            "consent_expiration_time": None,
            "error_code": None,
        }
        self.link_tokens: list = []
        self.sync_cursors: list = []
        self.sync_errors: list = []

    def create_link_token(self, *, client_user_id, access_token=None):
        self.link_tokens.append(access_token)
        return {"link_token": "link-sandbox-abc", "expiration": "2026-10-09T20:00:00Z"}

    def exchange_public_token(self, public_token):
        assert public_token == "public-sandbox-xyz"
        return {"access_token": ACCESS, "item_id": "item-1"}

    def get_item(self, access_token):
        assert access_token == ACCESS
        return dict(self.item)

    def sync_transactions(self, access_token, cursor):
        assert access_token == ACCESS
        self.sync_cursors.append(cursor)
        if self.sync_errors:
            raise self.sync_errors.pop(0)
        return self.pages[cursor]

    def page(self, cursor, next_cursor, *, added=(), modified=(), removed=(), has_more=False):
        self.pages[cursor] = {
            "added": list(added),
            "modified": list(modified),
            "removed": [{"transaction_id": t} for t in removed],
            "accounts": [CHECKING, CARD],
            "next_cursor": next_cursor,
            "has_more": has_more,
        }


@pytest.fixture
def fake(settings, monkeypatch):
    monkeypatch.setenv(vault.VAULT_KEY_ENV, Fernet.generate_key().decode())
    settings.PLAID_CLIENT_ID = "client-id-test"
    settings.PLAID_SECRET = "secret-test-never-served"
    settings.PLAID_ENV = "sandbox"
    gateway = FakePlaid()
    plaid_client.set_gateway_factory(lambda: gateway)
    yield gateway
    plaid_client.set_gateway_factory(None)


@pytest.fixture
def item(fake, household_a):
    return PlaidItem.objects.create(
        household=household_a,
        item_id="item-1",
        access_token_ciphertext=vault.seal({"access_token": ACCESS}),
    )


def sync(household) -> IngestRun:
    return run_job(Source.PLAID, household, plaid_sync.run_sync)


def bank_rows(household):
    return list(
        LedgerTransaction.objects.filter(
            household=household, provenance=Provenance.BANK_API
        ).order_by("txn_date", "bank_transaction_id")
    )


def person_override(row, category):
    now = timezone.now()
    return LedgerTransactionCategory.objects.create(
        id=uuid.uuid4(),
        household=row.household,
        transaction=row,
        category=category,
        source=LedgerTransactionCategory.SOURCE_PERSON,
        created_at=now,
        updated_at=now,
    )


def live_category(row_id):
    override = LedgerTransactionCategory.objects.filter(
        transaction_id=row_id, deleted_at__isnull=True
    ).first()
    return (override.category, override.source) if override else None


# =============================================================================
# Money
# =============================================================================


@pytest.mark.parametrize(
    ("value", "cents"),
    [
        (19.99, 1999),
        (0.1 + 0.2, 30),  # 0.30000000000000004 as a float
        (0.3, 30),
        (1234.56, 123456),
        (0.07, 7),
        (-500.0, -50000),
        ("12.34", 1234),
        (Decimal("8.10"), 810),
        (5, 500),
    ],
)
def test_a_plaid_amount_becomes_exact_cents(value, cents):
    assert to_cents(value) == cents


def test_cents_add_up_where_floats_do_not():
    assert 0.1 + 0.2 != 0.3
    assert to_cents(0.1) + to_cents(0.2) == to_cents(0.3) == 30


@pytest.mark.parametrize("bad", [None, True, "abc", float("nan"), float("inf")])
def test_a_value_that_is_not_an_amount_is_refused(bad):
    with pytest.raises(plaid_sync.PlaidDataError):
        to_cents(bad)


def test_the_sign_flips_from_plaids_money_out_positive_to_legions_money_out_negative():
    assert ledger_amount_cents(19.99) == -1999  # a purchase
    assert ledger_amount_cents(-2500.0) == 250000  # a payment or deposit in


# =============================================================================
# The sync
# =============================================================================


def test_added_modified_and_removed_are_applied_and_the_cursor_kept(fake, item, household_a):
    fake.page(
        None,
        "c1",
        added=[
            txn("t-a", account="acc-chk", amount=12.5, date="2026-09-10", name="COFFEE"),
            txn("t-b", amount=40.0, date="2026-09-11", name="GROCER",
                original_description="GROCER #12 HOUSTON TX"),
            txn("t-pay", amount=-500.0, date="2026-09-12", name="PAYMENT THANK YOU"),
        ],
    )
    first = sync(household_a)
    assert first.outcome == Outcome.OK, first.error
    rows = {r.bank_transaction_id: r for r in bank_rows(household_a)}
    assert set(rows) == {"t-a", "t-b", "t-pay"}
    assert rows["t-a"].amount_cents == -1250 and rows["t-a"].account_last4 == "1000"
    assert rows["t-a"].account_nickname == "Adv Plus Banking"
    assert rows["t-pay"].amount_cents == 50000
    # The bank's raw text when Plaid gives it, so category rules keep matching.
    assert rows["t-b"].description == "GROCER #12 HOUSTON TX"
    assert all(r.statement_id is None for r in rows.values())
    assert all(r.line_ref == f"plaid:{r.bank_transaction_id}" for r in rows.values())
    item.refresh_from_db()
    assert item.cursor == "c1"
    assert item.institution_name == "Bank of America"
    assert {a["mask"] for a in item.accounts} == {"1000", "7823"}
    old_b = rows["t-b"].id

    fake.page(
        "c1",
        "c2",
        modified=[txn("t-b", amount=41.0, date="2026-09-11", name="GROCER",
                      original_description="GROCER #12 HOUSTON TX")],
        removed=["t-a"],
    )
    second = sync(household_a)
    assert second.outcome == Outcome.OK, second.error
    assert fake.sync_cursors == [None, "c1"]
    rows = {r.bank_transaction_id: r for r in bank_rows(household_a)}
    assert set(rows) == {"t-b", "t-pay"}
    # A changed row is a NEW row (the trigger refuses UPDATE; the change feed
    # keys on created_at), carrying the bank's new amount.
    assert rows["t-b"].amount_cents == -4100 and rows["t-b"].id != old_b
    item.refresh_from_db()
    assert item.cursor == "c2" and item.last_synced_at is not None


def test_a_redelivered_identical_row_changes_nothing(fake, item, household_a):
    fake.page(None, "c1", added=[txn("t-a")])
    sync(household_a)
    before = bank_rows(household_a)[0].id
    fake.page("c1", "c2", modified=[txn("t-a")])
    sync(household_a)
    assert [r.id for r in bank_rows(household_a)] == [before]


def test_pending_to_posted_replaces_the_pending_row_and_carries_its_category(
    fake, item, household_a
):
    fake.page(None, "c1", added=[txn("t-pend", amount=18.0, pending=True, name="DINER")])
    sync(household_a)
    pending = bank_rows(household_a)[0]
    assert pending.bank_pending is True
    person_override(pending, "Dining")

    fake.page(
        "c1",
        "c2",
        added=[txn("t-post", amount=21.6, date="2026-09-12", name="DINER",
                   pending_transaction_id="t-pend")],
        removed=["t-pend"],
    )
    result = sync(household_a)
    assert result.outcome == Outcome.OK, result.error
    rows = bank_rows(household_a)
    assert [r.bank_transaction_id for r in rows] == ["t-post"]
    assert rows[0].bank_pending is False and rows[0].amount_cents == -2160
    assert live_category(rows[0].id) == ("Dining", "person")


def test_a_failed_sync_keeps_the_cursor_where_it_was(fake, item, household_a):
    fake.page(None, "c1", added=[txn("t-a")])
    sync(household_a)
    fake.sync_errors.append(plaid_client.PlaidError("INTERNAL_SERVER_ERROR", "try later"))
    failed = sync(household_a)
    assert failed.outcome == Outcome.FAILED
    item.refresh_from_db()
    assert item.cursor == "c1"


def test_a_mutation_mid_pagination_restarts_from_the_starting_cursor(fake, item, household_a):
    fake.page(None, "p2", added=[txn("t-a")], has_more=True)
    fake.page("p2", "c-end", added=[txn("t-b", date="2026-09-12")])
    fake.sync_errors.append(
        plaid_client.PlaidError(plaid_client.MUTATION_DURING_PAGINATION, "moved")
    )
    result = sync(household_a)
    assert result.outcome == Outcome.OK, result.error
    # The failed first call, then the whole loop again from None.
    assert fake.sync_cursors == [None, None, "p2"]
    assert {r.bank_transaction_id for r in bank_rows(household_a)} == {"t-a", "t-b"}
    item.refresh_from_db()
    assert item.cursor == "c-end"


def test_a_row_on_an_account_with_no_four_digit_number_is_refused_in_words(
    fake, item, household_a
):
    fake.page(None, "c1", added=[txn("t-x", account="acc-unknown")])
    fake.pages[None]["accounts"].append({"account_id": "acc-unknown", "mask": None, "name": "?"})
    run = IngestRun.objects.create(household=household_a, source=Source.PLAID)
    assert plaid_sync.run_sync(run) is None
    assert bank_rows(household_a) == []
    assert "no four-digit number" in " ".join(run.last_report.notes)


# =============================================================================
# The feed replaces the file-derived rows it covers
# =============================================================================


def _provisional(household, *, last4, date, amount, description):
    return LedgerTransaction.objects.create(
        id=uuid.uuid4(),
        household=household,
        statement=None,
        account_last4=last4,
        account_nickname="BofA card",
        currency="USD",
        txn_date=datetime.date.fromisoformat(date),
        description=description,
        amount_cents=amount,
        line_ref=f"csv:{description}",
        category=None,
        category_pending=True,
        provenance=Provenance.UNRECONCILED,
        created_at=timezone.now(),
    )


def test_the_feed_replaces_older_rows_from_its_earliest_date_and_moves_their_categories(
    fake, item, household_a
):
    # Checking: a gated statement (DETERMINISTIC) with rows 07-03, 07-11, 07-20.
    committed = commit_statement(a_statement(account_last4="1000"), household_a)
    assert committed.body["outcome"] == "COMMITTED", committed.body
    statement_id = committed.body["statement_id"]
    # Card: provisional CSV rows, one with a person's category that a bank row
    # matches (one day apart, same cents), one with a category nothing matches,
    # and one before the feed's window.
    matched = _provisional(household_a, last4="7823", date="2026-09-10", amount=-4000,
                           description="SHELL OIL 123")
    person_override(matched, "Fuel")
    orphan = _provisional(household_a, last4="7823", date="2026-09-15", amount=-9999,
                          description="GIFT SHOP")
    person_override(orphan, "Gifts")
    before = _provisional(household_a, last4="7823", date="2026-08-01", amount=-100,
                          description="OLD")
    plain = _provisional(household_a, last4="7823", date="2026-09-14", amount=-700,
                         description="PARKING")

    fake.page(
        None,
        "c1",
        added=[
            txn("k1", account="acc-chk", amount=-3000.0, date="2026-07-10", name="SALARY"),
            txn("k2", account="acc-chk", amount=1500.0, date="2026-07-20", name="RENT"),
            txn("c1", amount=40.0, date="2026-09-11", name="SHELL OIL"),
            txn("c2", amount=7.0, date="2026-09-14", name="PARKING"),
            txn("c0", amount=1.0, date="2026-09-09", name="EARLIEST"),
        ],
    )
    result = sync(household_a)
    assert result.outcome == Outcome.OK, result.error

    remaining = set(
        LedgerTransaction.objects.filter(household=household_a)
        .exclude(provenance=Provenance.BANK_API)
        .values_list("description", flat=True)
    )
    # Checking: 07-03 is before the feed's earliest checking row (07-10) and
    # stays; 07-11 and 07-20 are replaced, statement-derived as they are.
    assert "COFFEE" in remaining and "SALARY" not in remaining and "RENT" not in remaining
    assert Statement.objects.filter(id=statement_id).exists()  # the header and its anchors stay
    # Card: the matched row is gone and its category is on the bank row; the
    # unmatched categorised row is KEPT and said; the old row before the
    # window stays; the plain row in the window goes.
    assert "SHELL OIL 123" not in remaining and "PARKING" not in remaining
    assert "GIFT SHOP" in remaining and "OLD" in remaining
    shell = LedgerTransaction.objects.get(household=household_a, bank_transaction_id="c1")
    assert live_category(shell.id) == ("Fuel", "person")
    assert not LedgerTransaction.objects.filter(id__in=[matched.id, plain.id]).exists()
    assert LedgerTransaction.objects.filter(id__in=[orphan.id, before.id]).count() == 2


def test_an_unmatched_category_is_reported_in_the_sync_result(fake, item, household_a):
    orphan = _provisional(household_a, last4="7823", date="2026-09-15", amount=-9999,
                          description="GIFT SHOP")
    person_override(orphan, "Gifts")
    fake.page(None, "c1", added=[txn("c0", amount=1.0, date="2026-09-01")])
    run = IngestRun.objects.create(household=household_a, source=Source.PLAID)
    assert plaid_sync.run_sync(run) is None
    report = run.last_report
    assert report.kept_older == 1
    assert any("GIFT SHOP" in note and "Gifts" in note for note in report.notes)
    assert live_category(orphan.id) == ("Gifts", "person")


# =============================================================================
# The database still guards gated rows
# =============================================================================


def test_a_bank_row_can_be_deleted_but_never_updated(fake, item, household_a):
    fake.page(None, "c1", added=[txn("t-a")])
    sync(household_a)
    row = bank_rows(household_a)[0]
    with pytest.raises(Exception, match="immutable"), transaction.atomic():
        LedgerTransaction.objects.filter(id=row.id).update(description="EDITED")
    LedgerTransaction.objects.filter(id=row.id).delete()
    assert bank_rows(household_a) == []


def test_a_gated_row_is_deleted_only_inside_the_feeds_replacement(household_a):
    committed = commit_statement(a_statement(), household_a)
    row = LedgerTransaction.objects.filter(statement_id=committed.body["statement_id"]).first()
    with pytest.raises(Exception, match="immutable"), transaction.atomic():
        LedgerTransaction.objects.filter(id=row.id).delete()
    with transaction.atomic():
        with connection.cursor() as cursor:
            cursor.execute("select set_config(%s, 'on', true)", [SUPERSEDE_SETTING])
        LedgerTransaction.objects.filter(id=row.id).delete()
    assert not LedgerTransaction.objects.filter(id=row.id).exists()


def test_a_bank_row_has_no_statement_and_a_file_row_never_carries_a_bank_id(household_a):
    with pytest.raises(IntegrityError), transaction.atomic():
        LedgerTransaction.objects.create(
            id=uuid.uuid4(), household=household_a, statement=None, account_last4="1000",
            account_nickname="x", currency="USD", txn_date=datetime.date(2026, 9, 1),
            description="x", amount_cents=-1, line_ref="x", category_pending=True,
            provenance=Provenance.BANK_API, created_at=timezone.now(),
        )  # no bank_transaction_id
    with pytest.raises(IntegrityError), transaction.atomic():
        LedgerTransaction.objects.create(
            id=uuid.uuid4(), household=household_a, statement=None, account_last4="1000",
            account_nickname="x", currency="USD", txn_date=datetime.date(2026, 9, 1),
            description="x", amount_cents=-1, line_ref="x", category_pending=True,
            provenance=Provenance.UNRECONCILED, created_at=timezone.now(),
            bank_transaction_id="t-z",
        )


def test_a_document_cannot_claim_to_be_the_bank_feed(household_a):
    from ingest import gate

    with pytest.raises(gate.GateInputError, match="BANK_API"):
        commit_statement(a_statement(provenance=Provenance.BANK_API), household_a)


# =============================================================================
# Plain on every surface
# =============================================================================


def test_a_bank_row_is_served_plain_with_no_unverified_words(fake, item, household_a, auth_client):
    fake.page(None, "c1", added=[txn("t-a", pending=True)])
    sync(household_a)
    body = auth_client.get("/api/ledger/transactions/").data
    rows = body["results"] if isinstance(body, dict) and "results" in body else body
    row = next(r for r in rows if r["provenance"] == "BANK_API")
    assert row["verification_note"] is None
    assert row["pending"] is True
    assert "nverified" not in str(row)

    from engine_mcp.tools import _say

    said = _say("ledger_transactions", {"provenance": "BANK_API"})
    assert "UNVERIFIED" not in said.upper().replace("FACT", "")
    assert "fact" in said


# =============================================================================
# Freshness: the consent, in words
# =============================================================================


def _plaid_entry(client):
    sources = client.get("/api/freshness").data["sources"]
    return next(s for s in sources if s["source"] == "plaid")


def test_consent_ending_within_14_days_says_sign_in_again_with_the_page(
    fake, item, household_a, auth_client
):
    fake.page(None, "c1")
    fake.item["consent_expiration_time"] = timezone.now() + datetime.timedelta(days=10)
    assert sync(household_a).outcome == Outcome.OK
    entry = _plaid_entry(auth_client)
    assert entry["sentence"].startswith("Bank connection needs you to sign in again.")
    assert "consent ends on" in entry["sentence"]
    assert entry["action_url"] == "/settings/bank"


def test_consent_far_off_reads_as_a_normal_sync(fake, item, household_a, auth_client):
    fake.page(None, "c1")
    fake.item["consent_expiration_time"] = timezone.now() + datetime.timedelta(days=200)
    assert sync(household_a).outcome == Outcome.OK
    entry = _plaid_entry(auth_client)
    assert entry["sentence"].startswith("The bank connection last synced")
    assert entry["action_url"] is None


def test_a_bank_that_wants_a_login_records_needs_login_and_says_so(
    fake, item, household_a, auth_client
):
    fake.item["error_code"] = "ITEM_LOGIN_REQUIRED"
    run = sync(household_a)
    assert run.outcome == Outcome.NEEDS_LOGIN
    item.refresh_from_db()
    assert item.needs_sign_in_since is not None
    entry = _plaid_entry(auth_client)
    assert entry["sentence"].startswith("Bank connection needs you to sign in again.")
    assert entry["action_url"] == "/settings/bank"
    assert "connect_session" not in entry["sentence"]


def test_no_bank_connected_is_not_set_up(household_a, auth_client):
    from django.core.management import call_command

    call_command("plaid_sync")
    entry = _plaid_entry(auth_client)
    assert entry["sentence"] == "The bank connection is not set up."
    assert entry["stale"] is False


# =============================================================================
# The routes
# =============================================================================


@pytest.fixture
def member_client(household_a):
    from household.models import DeviceToken, HouseholdMember, User

    user = User.objects.create_user(email="member@example.com", password="correct horse battery")
    HouseholdMember.objects.create(user=user, household=household_a, role=HouseholdMember.MEMBER)
    _token, raw = DeviceToken.issue(user, "Member phone")
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw}")
    return client


@pytest.mark.parametrize(
    "path", ["/api/ingest/plaid/link-token", "/api/ingest/plaid/exchange",
             "/api/ingest/plaid/update-link-token"]
)
def test_link_routes_are_owner_only(fake, member_client, path):
    response = member_client.post(path, {"public_token": "public-sandbox-xyz"}, format="json")
    assert response.status_code == 403
    assert "Only an owner" in str(response.data)
    assert not PlaidItem.objects.exists()


def test_the_first_link_stores_a_sealed_token_and_never_returns_it(
    fake, household_a, auth_client
):
    token = auth_client.post("/api/ingest/plaid/link-token")
    assert token.status_code == 200, token.data
    assert token.data["mode"] == "create" and fake.link_tokens == [None]

    linked = auth_client.post(
        "/api/ingest/plaid/exchange", {"public_token": "public-sandbox-xyz"}, format="json"
    )
    assert linked.status_code == 201, linked.data
    assert linked.data["connected"] is True
    assert linked.data["environment"] == "sandbox"
    assert linked.data["environment_sentence"] == "Test mode (Plaid sandbox): no real bank data."
    stored = PlaidItem.objects.get(household=household_a)
    assert ACCESS.encode() not in bytes(stored.access_token_ciphertext)
    assert vault.unseal(stored.access_token_ciphertext) == {"access_token": ACCESS}

    for path in ("/api/ingest/plaid", "/api/ingest/sessions", "/api/freshness"):
        body = auth_client.get(path)
        assert ACCESS not in str(body.data) and "item-1" not in str(body.data), path
    assert ACCESS not in str(linked.data) and "secret-test" not in str(linked.data)


def test_a_second_link_is_refused_so_no_lifetime_slot_is_spent(fake, item, auth_client):
    again = auth_client.post("/api/ingest/plaid/link-token")
    assert again.status_code == 409 and "10 lifetime" in again.data["detail"]
    exchange = auth_client.post(
        "/api/ingest/plaid/exchange", {"public_token": "public-sandbox-xyz"}, format="json"
    )
    assert exchange.status_code == 409
    assert fake.link_tokens == []


def test_sign_in_again_uses_update_mode_on_the_same_token(fake, item, auth_client):
    response = auth_client.post("/api/ingest/plaid/update-link-token")
    assert response.status_code == 200, response.data
    assert response.data["mode"] == "update"
    assert fake.link_tokens == [ACCESS]
    assert ACCESS not in str(response.data)


def test_without_keys_nothing_links_and_it_says_why(settings, household_a, auth_client):
    settings.PLAID_CLIENT_ID = ""
    response = auth_client.post("/api/ingest/plaid/link-token")
    assert response.status_code == 503
    assert "PLAID_CLIENT_ID" in response.data["detail"]
    status_body = auth_client.get("/api/ingest/plaid").data
    assert status_body["configured"] is False and status_body["connected"] is False


def test_sync_now_runs_and_reports_in_words(fake, item, member_client):
    fake.page(None, "c1", added=[txn("t-a")])
    response = member_client.post("/api/ingest/plaid/sync")
    assert response.status_code == 200
    assert response.data["outcome"] == "ok"
    assert response.data["sentence"].startswith("Bank sync: 1 new")
    assert response.data["report"]["added"] == 1


# =============================================================================
# Bank of America retired from the Drive watcher; DBS unchanged
# =============================================================================


@pytest.fixture
def drive_connected(monkeypatch, household_a):
    monkeypatch.setenv(vault.VAULT_KEY_ENV, Fernet.generate_key().decode())
    monkeypatch.setenv(statements.GEMINI_KEY_ENV, KEY)
    return vault.store(
        household_a, "drive", DRIVE_SECRET, config={statements.FOLDER_CONFIG_KEY: FOLDER}
    )


class NoGemini(FakeGemini):
    def __call__(self, content, *, key):
        pytest.fail("Gemini was asked about a Bank of America file")


def test_bofa_is_retired_by_default():
    assert statements.BOFA_RETIRED is True


@pytest.mark.parametrize(
    ("name", "fixture", "mime"),
    [
        ("bofa_7823_activity_2026-09-27.csv", "currentTransaction_7823.csv", "text/csv"),
        ("currentTransaction_7823.csv", "currentTransaction_7823.csv", "text/csv"),
        ("statement.pdf", "bofa_card_happy_path.pdf", "application/pdf"),
    ],
)
def test_a_bofa_file_is_skipped_and_the_run_log_says_so(
    drive_connected, household_a, name, fixture, mime
):
    drive = FakeDrive()
    drive.add(name, content=(FIXTURES / fixture).read_bytes(), mime=mime)
    result = run_watcher(household_a, drive, NoGemini())
    assert result.outcome == Outcome.OK, result.error
    assert "retired" in result.output and "no longer read" in result.output
    assert not LedgerTransaction.objects.filter(household=household_a).exists()
    from legacy.models.ingest import IngestedFile

    assert not IngestedFile.objects.filter(household=household_a).exists()


def test_a_dbs_statement_still_ingests_through_the_gate(drive_connected, household_a):
    drive = FakeDrive()
    drive.add("dbs_2026-07.pdf")
    reading = a_reading(account_last4="5678", account_nickname="DBS savings", currency="SGD")
    result = run_watcher(household_a, drive, FakeGemini(reading))
    assert result.outcome == Outcome.OK, result.error
    rows = LedgerTransaction.objects.filter(household=household_a, account_last4="5678")
    assert rows.count() == 3
    assert {r.provenance for r in rows} == {Provenance.LLM_RECONCILED}
    assert {r.currency for r in rows} == {"SGD"}


# =============================================================================
# Deploy wiring (no network, no secret values)
# =============================================================================


def test_the_crontab_runs_plaid_sync_every_six_hours():
    crontab = Path(__file__).resolve().parents[2] / "deploy" / "crontab"
    assert "30 */6 * * * python manage.py plaid_sync" in crontab.read_text().splitlines()


def test_the_plaid_keys_are_cloud_run_secrets_and_the_environment_defaults_to_sandbox():
    import importlib.util

    path = Path(__file__).resolve().parents[2] / "deploy" / "cloudrun" / "_common.py"
    spec = importlib.util.spec_from_file_location("cloudrun_common_plaid", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    names = module.secret_names_for({})
    assert "PLAID_CLIENT_ID" in names and "PLAID_SECRET" in names
    assert "PLAID_CLIENT_ID=PLAID_CLIENT_ID:latest" in module.secrets_flag_value(names)
    assert module.plain_env_from({})["PLAID_ENV"] == "sandbox"
    assert module.plain_env_from({"PLAID_ENV": "production"})["PLAID_ENV"] == "production"
