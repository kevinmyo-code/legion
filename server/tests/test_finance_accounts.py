"""Household-finance ticket 01: accounts and dated balance snapshots.

Tenancy leak, the Plaid snapshot's sign and day, pending kept beside the
balance, the unique (account, day, source) key, and the estimate flag carried
to the wire. Synthetic household only (`tests/finance_synthetic.py`).
"""

from __future__ import annotations

import datetime as dt
from zoneinfo import ZoneInfo

import pytest
from django.core.management import call_command
from django.db import IntegrityError, transaction

from finance.models import BalanceSnapshot
from finance.snapshots import snapshot_plaid
from tests.finance_synthetic import make_household, snapshot
from tests.test_plaid import CARD, CHECKING, fake, item, sync, txn  # noqa: F401 - fixtures

pytestmark = pytest.mark.django_db

ACCOUNTS = "/api/finance/accounts"
SNAPSHOTS = "/api/finance/snapshots"
UTC = ZoneInfo("UTC")


def _with_balance(record, current, as_of=None):
    return {
        **record,
        "balances": {
            "current": current,
            "available": None,
            "iso_currency_code": "USD",
            "last_updated_datetime": as_of,
        },
    }


def test_accounts_and_snapshots_never_leak_to_another_household(
    token_a, token_b, household_a, household_b
):
    _, accounts = make_household(household_a)
    snapshot(household_a, accounts["sg-savings"], dt.date(2026, 10, 31), 500_000)
    assert {a["key"] for a in token_a.get(ACCOUNTS).data} >= {"us-checking", "sg-savings"}
    assert token_b.get(ACCOUNTS).data == []
    assert token_b.get(SNAPSHOTS).data == []
    assert len(token_a.get(SNAPSHOTS).data) == 1


def test_a_debt_account_says_so_and_a_joint_account_has_no_owner(token_a, household_a):
    make_household(household_a)
    by = {a["key"]: a for a in token_a.get(ACCOUNTS).data}
    assert by["us-card"]["is_debt"] is True and by["us-checking"]["is_debt"] is False
    assert by["flat-loan"]["is_debt"] is True
    assert by["flat"]["owner"] is None and by["us-checking"]["owner"] == "alex"
    assert "household" not in str(token_a.get(ACCOUNTS).data)


def test_a_plaid_snapshot_is_signed_debt_negative_on_the_day_the_bank_stated(
    fake, item, household_a  # noqa: F811
):
    _, accounts = make_household(household_a)
    fake.page(
        None,
        "c1",
        added=[txn("p1", account="acc-card", amount=20.0, date="2026-11-03", pending=True)],
        accounts=[
            _with_balance(CHECKING, 1000.0, "2026-11-03T22:00:00+00:00"),
            _with_balance(CARD, 500.0, "2026-11-03T22:00:00+00:00"),
        ],
    )
    assert sync(household_a).outcome == "ok"
    report = snapshot_plaid(household_a, dt.date(2026, 11, 5), UTC)
    assert report.written == 2 and report.unmatched == [] and report.no_balance == []
    checking = BalanceSnapshot.objects.get(account=accounts["us-checking"])
    card = BalanceSnapshot.objects.get(account=accounts["us-card"])
    assert (checking.amount_cents, checking.as_of) == (100_000, dt.date(2026, 11, 3))
    assert card.amount_cents == -50_000
    assert checking.is_estimate is False and card.source == "plaid"
    # Pending stays beside the balance, in the ledger's sign, never inside it.
    assert (card.pending_cents, card.pending_count) == (-2_000, 1)
    assert (checking.pending_cents, checking.pending_count) == (0, 0)


def test_a_second_run_the_same_day_updates_that_days_row(fake, item, household_a):  # noqa: F811
    _, accounts = make_household(household_a)
    stated = "2026-11-03T12:00:00+00:00"
    fake.page(None, "c1", accounts=[_with_balance(CHECKING, 1000.0, stated), CARD])
    assert sync(household_a).outcome == "ok"
    snapshot_plaid(household_a, dt.date(2026, 11, 3), UTC)
    fake.page("c1", "c2", accounts=[_with_balance(CHECKING, 2000.0, stated), CARD])
    assert sync(household_a).outcome == "ok"
    report = snapshot_plaid(household_a, dt.date(2026, 11, 3), UTC)
    rows = BalanceSnapshot.objects.filter(account=accounts["us-checking"])
    assert [r.amount_cents for r in rows] == [200_000]
    # The card's record carries no balance: said, and nothing written.
    assert report.no_balance == ["us-card"]


def test_the_unique_key_is_account_day_source(household_a):
    _, accounts = make_household(household_a)
    day = dt.date(2026, 10, 31)
    snapshot(household_a, accounts["sg-savings"], day, 100_000)
    snapshot(household_a, accounts["sg-savings"], day, 100_000, source="manual")
    with pytest.raises(IntegrityError), transaction.atomic():
        snapshot(household_a, accounts["sg-savings"], day, 200_000)


def test_the_estimate_flag_reaches_the_wire(token_a, household_a):
    _, accounts = make_household(household_a)
    snapshot(household_a, accounts["flat"], dt.date(2026, 10, 1), 50_000_000, estimate=True)
    snapshot(household_a, accounts["sg-savings"], dt.date(2026, 10, 1), 500_000)
    body = token_a.get(SNAPSHOTS, {"from": "2026-10-01", "to": "2026-10-31"}).data
    by = {row["account"]: row for row in body}
    assert by["flat"]["is_estimate"] is True and by["sg-savings"]["is_estimate"] is False
    assert token_a.get(SNAPSHOTS, {"account": "flat"}).data[0]["amount_cents"] == 50_000_000
    bad = token_a.get(SNAPSHOTS, {"from": "October"})
    assert bad.status_code == 400 and "Nothing was read" in bad.data["detail"]


def test_the_daily_command_says_what_it_wrote(fake, item, household_a, capsys):  # noqa: F811
    make_household(household_a)
    fake.page(None, "c1", accounts=[_with_balance(CHECKING, 1000.0), _with_balance(CARD, 0.0)])
    assert sync(household_a).outcome == "ok"
    call_command("finance_snapshots")
    out = capsys.readouterr().out
    assert "2 snapshot(s) written" in out


def test_the_crontab_snapshots_once_a_day_after_a_plaid_sync():
    from pathlib import Path

    crontab = Path(__file__).resolve().parents[2] / "deploy" / "crontab"
    assert "45 21 * * * python manage.py finance_snapshots" in crontab.read_text().splitlines()
