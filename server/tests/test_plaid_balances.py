"""Every sync refreshes every account's balance, and an as-of time is the
bank's own, never LEGION's clock.

Two bugs, one shape. `/transactions/sync` returns only the accounts that had
transactions in that response, so a quiet run refreshed nothing; and whatever
it did refresh was stamped with the moment this engine read it, though Plaid
documents those balances as cached. `net_cash` takes `min(as_of)` across the
accounts (ADR 0094), so one quiet account froze the figure's whole label.

The fix is `/accounts/get` (free, every account) stamped with `/item/get`'s
`status.transactions.last_successful_update` (the last time Plaid reached the
bank). The formula is untouched: `min` stays `min`, and a sync that learns
nothing new still never moves a stamp.

The fakes are `tests/test_plaid.py`'s; nothing here reaches the network.
"""
from __future__ import annotations

import datetime

import pytest
from cryptography.fernet import Fernet
from django.utils import timezone
from django.utils.dateparse import parse_datetime

from ingest import plaid_client, vault
from ingest.models import PlaidItem
from tests.test_plaid import (
    ACCESS,
    CARD,
    CARD_WITH_BALANCE,
    CHECKING,
    CHECKING_WITH_BALANCE,
    LAST_BANK_READ,
    LAST_BANK_READ_ISO,
    FakePlaid,
    bank_rows,
    sync,
    txn,
)

pytestmark = pytest.mark.django_db

LATER_BANK_READ = LAST_BANK_READ + datetime.timedelta(hours=3)


# The gateway and the linked Item, as `tests/test_plaid.py` builds them. Its
# fixtures are not importable by name without pytest treating the import as a
# redefinition, and this is the second copy, not the third.
@pytest.fixture
def fake(settings, monkeypatch):
    monkeypatch.setenv(vault.VAULT_KEY_ENV, Fernet.generate_key().decode())
    settings.PLAID_CLIENT_ID = "client-id-test"
    settings.PLAID_SANDBOX_SECRET = "sandbox-secret-test-never-served"
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


def balances(item):
    item.refresh_from_db()
    return {a["account_id"]: a for a in item.accounts}


def test_a_sync_with_no_new_rows_still_refreshes_every_balance(fake, item, household_a):
    """The frozen-figure bug. The page carries no accounts at all, which is
    what Plaid sends when nothing happened, and the balances still arrive."""
    fake.page(None, "c1", accounts=[])
    fake.accounts = [CHECKING_WITH_BALANCE, CARD_WITH_BALANCE]

    assert sync(household_a).outcome == "ok"

    stored = balances(item)
    assert stored["acc-chk"]["balance_current_cents"] == 123_456
    assert stored["acc-card"]["balance_current_cents"] == 41_007
    assert len(fake.accounts_get_calls) == 1


def test_a_quiet_sync_never_restamps_an_as_of_to_now(fake, item, household_a):
    """A no-op sync moves no stamp. The old code wrote `now()` on every run
    that saw the account, which made a stale figure look fresh."""
    fake.page(None, "c1", added=[txn("t1", account="acc-chk", date="2026-11-20")], accounts=[])
    fake.accounts = [CHECKING_WITH_BALANCE]
    assert sync(household_a).outcome == "ok"
    first = balances(item)["acc-chk"]["balance_as_of"]

    fake.page("c1", "c2", accounts=[])
    assert sync(household_a).outcome == "ok"

    after = balances(item)["acc-chk"]
    assert after["balance_as_of"] == first == LAST_BANK_READ_ISO
    # The engine's own read time is minutes ago; the bank's is days ago.
    assert after["balance_as_of"] != item.last_synced_at.isoformat()
    assert timezone.now() - LAST_BANK_READ > datetime.timedelta(hours=1)


def test_the_as_of_moves_when_plaid_reaches_the_bank_again(fake, item, household_a):
    """And it moves for EVERY account, not only one with a new transaction."""
    fake.page(None, "c1", accounts=[])
    fake.accounts = [CHECKING_WITH_BALANCE, CARD_WITH_BALANCE]
    assert sync(household_a).outcome == "ok"
    assert balances(item)["acc-chk"]["balance_as_of"] == LAST_BANK_READ_ISO

    fake.item["transactions_last_successful_update"] = LATER_BANK_READ
    fake.page("c1", "c2", accounts=[])
    assert sync(household_a).outcome == "ok"

    assert balances(item)["acc-chk"]["balance_as_of"] == LATER_BANK_READ.isoformat()


def test_an_accounts_own_stated_time_outranks_the_items(fake, item, household_a):
    """Plaid sends `last_updated_datetime` for Capital One and Apple Card only.
    Where it is sent it is the better answer, so it still wins."""
    fake.page(None, "c1", accounts=[])
    fake.accounts = [CHECKING_WITH_BALANCE, CARD_WITH_BALANCE]

    assert sync(household_a).outcome == "ok"

    stored = balances(item)
    assert stored["acc-card"]["balance_as_of"] == "2026-11-03T14:14:00+00:00"
    assert stored["acc-chk"]["balance_as_of"] == LAST_BANK_READ_ISO


def test_with_no_time_from_plaid_a_stored_balance_keeps_its_own(fake, item, household_a):
    """`status` is nullable. An undated balance never overwrites a dated one,
    and never borrows the earlier read's time for a newer figure."""
    fake.page(None, "c1", accounts=[])
    fake.accounts = [CHECKING_WITH_BALANCE]
    assert sync(household_a).outcome == "ok"

    fake.item["transactions_last_successful_update"] = None
    moved = {
        **CHECKING_WITH_BALANCE,
        "balances": {**CHECKING_WITH_BALANCE["balances"], "current": 999.99},
    }
    fake.accounts = [moved]
    fake.page("c1", "c2", accounts=[])
    run = sync(household_a)
    assert run.outcome == "ok"

    after = balances(item)["acc-chk"]
    assert after["balance_as_of"] == LAST_BANK_READ_ISO
    assert after["balance_current_cents"] == 123_456
    assert any("did not say when it last read the bank" in note for note in run.last_report.notes)


def test_a_first_link_with_no_status_keeps_an_undated_balance(
    fake, item, household_a, auth_client
):
    """Nothing stored yet AND no time from Plaid: the figure is kept and left
    undated, and net cash says in words that it has no as-of rather than
    inventing one. Plaid's own update time arrives on a later sync."""
    fake.item["transactions_last_successful_update"] = None
    fake.page(None, "c1", accounts=[])
    fake.accounts = [CHECKING_WITH_BALANCE]

    assert sync(household_a).outcome == "ok"

    stored = balances(item)["acc-chk"]
    assert stored["balance_current_cents"] == 123_456
    assert stored["balance_as_of"] is None
    body = auth_client.get("/api/finance/net-cash").data
    assert body["available"] is False
    assert "no as-of time was stored with its balance" in body["reason"]


def test_a_fresh_account_record_still_updates_its_name(fake, item, household_a):
    """Keeping a dated balance keeps the BALANCE, not the whole record."""
    fake.page(None, "c1", accounts=[])
    fake.accounts = [CHECKING_WITH_BALANCE]
    assert sync(household_a).outcome == "ok"

    fake.item["transactions_last_successful_update"] = None
    fake.accounts = [{**CHECKING, "name": "Renamed Checking"}]
    fake.page("c1", "c2", accounts=[])
    assert sync(household_a).outcome == "ok"

    after = balances(item)["acc-chk"]
    assert after["name"] == "Renamed Checking"
    assert after["balance_current_cents"] == 123_456
    assert after["balance_as_of"] == LAST_BANK_READ_ISO


def test_an_account_the_feed_no_longer_lists_keeps_its_record(fake, item, household_a):
    """Plaid returns ACTIVE accounts only, so an absent account is not news."""
    fake.page(None, "c1", accounts=[])
    fake.accounts = [CHECKING_WITH_BALANCE, CARD_WITH_BALANCE]
    assert sync(household_a).outcome == "ok"

    fake.accounts = [CHECKING_WITH_BALANCE]
    fake.page("c1", "c2", accounts=[])
    assert sync(household_a).outcome == "ok"

    assert balances(item)["acc-card"]["balance_current_cents"] == 41_007


def test_a_sign_in_error_from_the_balance_call_asks_for_a_sign_in(fake, item, household_a):
    fake.page(None, "c1", accounts=[])
    fake.accounts_get_errors.append(plaid_client.PlaidError("ITEM_LOGIN_REQUIRED", "sign in"))

    run = sync(household_a)

    assert run.outcome == "needs_login"
    item.refresh_from_db()
    assert item.needs_sign_in_since is not None


def test_a_balance_error_never_stops_the_transactions(fake, item, household_a):
    """The balances are a label on a figure; the transactions are the money.
    So a failed balance refresh degrades: the page's own accounts stand in,
    every stored balance keeps its figure and its stamp, the report says so in
    words, and the rows commit (review of 2026-11-23)."""
    fake.page(None, "c1", added=[txn("t1")])
    fake.accounts = [CHECKING_WITH_BALANCE, CARD_WITH_BALANCE]
    assert sync(household_a).outcome == "ok"
    before = balances(item)["acc-card"]

    fake.page("c1", "c2", added=[txn("t2")])
    fake.accounts_get_errors.append(plaid_client.PlaidError("INTERNAL_SERVER_ERROR", "later"))

    run = sync(household_a)

    assert run.outcome == "ok"
    assert run.rows_written == 1
    item.refresh_from_db()
    assert item.cursor == "c2"
    assert bank_rows(household_a)[-1].bank_transaction_id == "t2"
    after = balances(item)["acc-card"]
    assert after["balance_current_cents"] == before["balance_current_cents"]
    assert after["balance_as_of"] == before["balance_as_of"]
    assert any("INTERNAL_SERVER_ERROR" in note for note in run.last_report.notes)


def test_a_balance_call_that_fails_outside_plaids_own_errors_also_degrades(
    fake, item, household_a
):
    """`plaid_client._call` wraps `plaid.ApiException` only, so a socket or
    deserialisation error from this one call arrives as itself. It must not be
    the one shape that still kills the sync."""
    fake.page(None, "c1", added=[txn("t1")])
    fake.accounts_get_errors.append(OSError("connection reset"))

    run = sync(household_a)

    assert run.outcome == "ok"
    assert run.rows_written == 1
    item.refresh_from_db()
    assert item.cursor == "c1"
    assert any("OSError" in note for note in run.last_report.notes)


def test_net_cash_as_of_moves_on_a_quiet_sync(fake, item, household_a, auth_client):
    """What Kevin sees. `min(as_of)` is unchanged (ADR 0094, F3); it stopped
    being frozen because both parts are refreshed now."""
    fake.page(None, "c1", accounts=[])
    fake.accounts = [CHECKING_WITH_BALANCE, {**CARD, "balances": {"current": 10.0,
                                                                 "iso_currency_code": "USD",
                                                                 "last_updated_datetime": None}}]
    assert sync(household_a).outcome == "ok"
    first = auth_client.get("/api/finance/net-cash").data

    fake.item["transactions_last_successful_update"] = LATER_BANK_READ
    fake.page("c1", "c2", accounts=[])
    assert sync(household_a).outcome == "ok"

    second = auth_client.get("/api/finance/net-cash").data
    assert _as_of(first) == LAST_BANK_READ
    assert _as_of(second) == LATER_BANK_READ


def _as_of(body) -> datetime.datetime | None:
    value = body["as_of"]
    return value if isinstance(value, datetime.datetime) else parse_datetime(value)
