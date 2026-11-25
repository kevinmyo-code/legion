"""Net cash (Kevin, 2026-11-16, ADR 0094): checking minus everything owed on
every linked card, pending counted as spent, from the last bank sync.

Invented accounts and figures only (the repo is public). The pure `compute`
is tested on plain dicts; the route, the engine tool's lead and tenancy are
tested through the API.
"""

from __future__ import annotations

import datetime as dt
import uuid

import pytest
from django.db.models.functions import Now

from finance.net_cash import compute

UTC = dt.UTC
AS_OF = "2026-11-16T14:05:00+00:00"
TODAY = dt.date(2026, 11, 16)


def checking(**over) -> dict:
    return {
        "account_id": "acc-chk",
        "mask": "1000",
        "name": "Everyday Checking",
        "type": "depository",
        "subtype": "checking",
        "balance_current_cents": 200_000,
        "balance_available_cents": None,
        "balance_currency": "USD",
        "balance_as_of": AS_OF,
        **over,
    }


def card(**over) -> dict:
    return {
        "account_id": "acc-card",
        "mask": "1199",
        "name": "Rewards Card",
        "type": "credit",
        "subtype": "credit card",
        "balance_current_cents": 40_000,
        "balance_available_cents": 460_000,
        "balance_currency": "USD",
        "balance_as_of": AS_OF,
        **over,
    }


def row(last4: str, day: dt.date, cents: int, pending: bool = False) -> dict:
    return {
        "account_last4": last4,
        "currency": "USD",
        "txn_date": day,
        "amount_cents": cents,
        "bank_pending": pending,
    }


def days_ago(n: int) -> dt.date:
    return TODAY - dt.timedelta(days=n)


# =============================================================================
# The formula
# =============================================================================


def test_checking_minus_card_owed_with_pending_counted_as_spent():
    rows = [
        row("1000", TODAY, -1_200, pending=True),
        row("1199", TODAY, -3_500, pending=True),
        row("1199", TODAY, -500, pending=True),
    ]
    body = compute([checking(), card()], rows, UTC)
    assert body["available"] is True and body["reason"] is None
    # 200000 - 1200 (checking pending) - 40000 owed - 3500 - 500 (card pending)
    assert body["net_cents"] == 200_000 - 1_200 - 40_000 - 3_500 - 500
    assert body["currency"] == "USD"
    assert body["pending_cents"] == -1_200 - 3_500 - 500
    chk, crd = body["parts"]
    assert (chk["role"], chk["basis"], chk["balance_cents"]) == (
        "checking",
        "current_plus_pending",
        198_800,
    )
    assert (crd["role"], crd["basis"], crd["balance_cents"]) == (
        "card",
        "current_plus_pending",
        -44_000,
    )
    assert crd["mask"] == "1199" and crd["pending_count"] == 2


def test_checking_uses_the_banks_available_and_does_not_count_pending_twice():
    """Plaid: depository `available` is current less pending outflows plus
    pending inflows. The bank already took the pending charge off."""
    rows = [row("1000", TODAY, -1_200, pending=True)]
    body = compute([checking(balance_available_cents=198_800), card()], rows, UTC)
    chk = body["parts"][0]
    assert chk["basis"] == "bank_available" and chk["balance_cents"] == 198_800
    assert chk["pending_cents"] == -1_200  # said beside it, not added to it
    assert body["net_cents"] == 198_800 - 40_000


def test_a_pending_credit_on_the_card_takes_from_what_is_owed():
    body = compute([checking(), card()], [row("1199", TODAY, 2_000, pending=True)], UTC)
    assert body["parts"][1]["balance_cents"] == -40_000 + 2_000


def test_every_card_is_owed_and_other_accounts_are_left_out():
    savings = checking(account_id="acc-sav", mask="2000", name="Savings", subtype="savings")
    second = card(
        account_id="acc-card-2", mask="4242", name="Travel Card", balance_current_cents=10_000
    )
    body = compute([checking(), card(), second, savings], [], UTC)
    # Checking first, then each card by its name.
    assert [p["mask"] for p in body["parts"]] == ["1000", "1199", "4242"]
    assert body["net_cents"] == 200_000 - 40_000 - 10_000


def test_a_posted_row_is_already_inside_the_banks_figure():
    """A posted feed row is in the bank's `current`: never added again. (Rows a
    person logged as pending never reach `compute`: `net_cash` reads
    `BANK_API` rows only; see the route test below.)"""
    body = compute([checking(), card()], [row("1000", TODAY, -9_999)], UTC)
    assert body["net_cents"] == 200_000 - 40_000


@pytest.mark.parametrize(
    "broken, reason",
    [
        (card(balance_current_cents=None), "the bank sent no balance for this card"),
        (card(balance_as_of=None), "no as-of time was stored with its balance"),
        (card(balance_currency=None), "the bank sent no currency with its balance"),
        (card(mask="78"), "no four-digit number"),
    ],
)
def test_a_missing_part_gives_no_net_figure_and_names_the_account(broken, reason):
    body = compute([checking(), broken], [], UTC)
    assert body["available"] is False
    assert body["net_cents"] is None and body["history"] is None
    (missing,) = body["missing"]
    assert missing["role"] == "card" and reason in missing["reason"]
    assert body["reason"].startswith("No net cash figure: Rewards Card")
    assert reason in body["reason"]


def test_no_checking_account_gives_no_figure():
    body = compute([card()], [], UTC)
    assert body["available"] is False and body["net_cents"] is None
    assert "no checking account is linked" in body["reason"]


def test_no_card_is_checking_alone():
    body = compute([checking()], [], UTC)
    assert body["net_cents"] == 200_000 and len(body["parts"]) == 1


def test_two_currencies_are_never_added():
    body = compute([checking(), card(balance_currency="SGD")], [], UTC)
    assert body["available"] is False and body["net_cents"] is None
    assert "never added" in body["reason"]


def test_as_of_is_the_older_part():
    older = "2026-11-15T09:00:00+00:00"
    body = compute([checking(), card(balance_as_of=older)], [], UTC)
    assert body["as_of"] == dt.datetime(2026, 11, 15, 9, 0, tzinfo=UTC)


def test_nothing_linked_is_no_figure_with_nothing_to_name():
    body = compute([], [], UTC)
    assert body["available"] is False and body["parts"] == [] and body["missing"] == []


# =============================================================================
# The 30-day line (posted basis)
# =============================================================================


def test_the_line_walks_back_through_posted_rows_and_ends_at_the_headline():
    rows = [
        row("1000", days_ago(40), 0),  # the feed reaches back further than the window
        row("1199", days_ago(40), 0),
        row("1000", days_ago(2), -10_000),  # spent on checking two days ago
        row("1199", days_ago(1), -2_000),  # a card charge yesterday
        row("1199", TODAY, -700, pending=True),  # pending: today's point only
    ]
    body = compute([checking(), card()], rows, UTC)
    history = body["history"]
    assert history["basis"] == "posted" and history["short"] is False
    points = {p["on"]: p["net_cents"] for p in history["points"]}
    assert len(points) == 30 and history["end"] == TODAY
    assert points[TODAY] == body["net_cents"] == 200_000 - 40_000 - 700
    # End of yesterday: posted figures, before nothing dated today.
    assert points[days_ago(1)] == 200_000 - 40_000
    # End of two days ago: before yesterday's card charge.
    assert points[days_ago(2)] == 200_000 - 40_000 + 2_000
    # End of three days ago: before the checking spend too.
    assert points[days_ago(3)] == 200_000 + 10_000 - 40_000 + 2_000


def test_a_short_feed_draws_what_exists_and_says_from_when():
    rows = [row("1000", days_ago(5), -100), row("1199", days_ago(3), -100)]
    history = compute([checking(), card()], rows, UTC)["history"]
    # The later of the accounts' earliest rows, less one day.
    assert history["start"] == days_ago(4) and history["short"] is True
    assert len(history["points"]) == 5


def test_an_account_with_no_posted_row_gives_only_todays_point():
    history = compute([checking(), card()], [row("1000", days_ago(5), -100)], UTC)["history"]
    assert [p["on"] for p in history["points"]] == [TODAY]


def test_checking_with_available_but_no_current_has_a_figure_and_only_todays_point():
    rows = [row("1000", days_ago(3), -100), row("1199", days_ago(3), -100)]
    body = compute(
        [checking(balance_current_cents=None, balance_available_cents=150_000), card()], rows, UTC
    )
    assert body["net_cents"] == 150_000 - 40_000
    assert [p["on"] for p in body["history"]["points"]] == [TODAY]


def test_a_card_payment_does_not_move_net_cash_when_both_legs_post_the_same_day():
    """Rule 4: paying the card from checking moves both sides by the same
    amount. Today's figures are after the payment."""
    rows = [
        row("1000", days_ago(40), 0),
        row("1199", days_ago(40), 0),
        row("1000", days_ago(3), -30_000),  # payment out of checking
        row("1199", days_ago(3), 30_000),  # payment onto the card
    ]
    body = compute([checking(), card()], rows, UTC)
    points = {p["on"]: p["net_cents"] for p in body["history"]["points"]}
    assert points[days_ago(4)] == points[days_ago(3)] == points[days_ago(1)] == body["net_cents"]


def test_a_card_payment_whose_legs_post_on_different_days_dips_for_the_days_between():
    """Documented in ADR 0094: the line is posted basis, so it dips while only
    the checking leg has posted, and comes back when the card leg posts."""
    rows = [
        row("1000", days_ago(40), 0),
        row("1199", days_ago(40), 0),
        row("1000", days_ago(4), -30_000),
        row("1199", days_ago(2), 30_000),
    ]
    body = compute([checking(), card()], rows, UTC)
    points = {p["on"]: p["net_cents"] for p in body["history"]["points"]}
    assert points[days_ago(5)] == body["net_cents"]
    assert points[days_ago(4)] == points[days_ago(3)] == body["net_cents"] - 30_000
    assert points[days_ago(2)] == body["net_cents"]


def test_a_pending_card_payment_with_both_legs_pending_does_not_move_the_headline():
    before = compute([checking(), card()], [], UTC)["net_cents"]
    rows = [row("1000", TODAY, -30_000, pending=True), row("1199", TODAY, 30_000, pending=True)]
    assert compute([checking(), card()], rows, UTC)["net_cents"] == before


# =============================================================================
# The route, the engine tool, tenancy
# =============================================================================


def _link(household, *records):
    from ingest.models import PlaidItem

    return PlaidItem.objects.create(
        household=household,
        item_id=f"item-{uuid.uuid4().hex[:8]}",
        access_token_ciphertext=b"not-a-real-token",
        accounts=list(records),
    )


def _feed(household, last4: str, day: dt.date, cents: int, pending: bool = False):
    from legacy.models.ledger import LedgerTransaction

    tid = uuid.uuid4().hex
    LedgerTransaction.objects.create(
        id=uuid.uuid4(),
        household=household,
        account_last4=last4,
        account_nickname="Feed",
        currency="USD",
        txn_date=day,
        description="FEED ROW",
        amount_cents=cents,
        line_ref=f"plaid:{tid}",
        category_pending=False,
        provenance="BANK_API",
        bank_transaction_id=tid,
        bank_pending=pending,
        created_at=Now(),
    )


@pytest.mark.django_db
def test_the_route_answers_net_cash_with_its_working(auth_client, household_a):
    _link(household_a, checking(), card())
    _feed(household_a, "1199", TODAY, -3_500, pending=True)
    _feed(household_a, "1199", days_ago(2), -1_000)
    body = auth_client.get("/api/finance/net-cash").json()
    assert body["available"] is True
    assert body["net_cents"] == 200_000 - 40_000 - 3_500
    assert body["currency"] == "USD" and body["as_of"].startswith("2026-11-16T14:05")
    assert [p["mask"] for p in body["parts"]] == ["1000", "1199"]
    assert body["history"]["points"][-1]["net_cents"] == body["net_cents"]
    assert body["history"]["start"] is not None


@pytest.mark.django_db
def test_a_charge_logged_by_hand_as_pending_is_not_in_net_cash(auth_client, household_a):
    from legacy.models.ledger import LedgerTransaction

    _link(household_a, checking(), card())
    LedgerTransaction.objects.create(
        id=uuid.uuid4(),
        household=household_a,
        account_last4="1199",
        account_nickname="Rewards Card",
        currency="USD",
        txn_date=TODAY,
        description="LOGGED BY VOICE",
        amount_cents=-9_900,
        line_ref=f"voice:{uuid.uuid4()}",
        category_pending=False,
        provenance="UNRECONCILED",
        pending_logged_at=Now(),
        created_at=Now(),
    )
    assert auth_client.get("/api/finance/net-cash").json()["net_cents"] == 200_000 - 40_000


@pytest.mark.django_db
def test_the_route_says_which_account_has_no_balance(auth_client, household_a):
    _link(household_a, checking(), card(balance_current_cents=None))
    body = auth_client.get("/api/finance/net-cash").json()
    assert body["available"] is False and body["net_cents"] is None
    assert body["missing"][0]["mask"] == "1199"
    assert "Rewards Card ending 1199" in body["reason"]


@pytest.mark.django_db
def test_account_balances_leads_with_net_cash(household_a, household_user, settings):
    from tests.test_engine_mcp import call, client_with

    settings.LEGION_MCP = True
    _link(household_a, checking(), card())
    _feed(household_a, "1199", TODAY, -3_500, pending=True)
    is_error, text, structured = call(client_with(household_user), "account_balances", {})
    assert not is_error, text
    assert text.startswith("Net cash now: $1,565.00 (USD), as of ")
    assert "Of which pending at the bank: -$35.00." in text
    assert "Balances per account" in text
    assert structured["net_cash"]["net_cents"] == 156_500
    # Asked about one account, there is no net cash lead.
    _, one, _ = call(client_with(household_user), "account_balances", {"account": "1199"})
    assert "Net cash" not in one


@pytest.mark.django_db
def test_account_balances_with_a_missing_card_names_it_and_adds_nothing(
    household_a, household_user, settings
):
    from tests.test_engine_mcp import call, client_with

    settings.LEGION_MCP = True
    _link(household_a, checking(), card(balance_current_cents=None))
    _, text, _ = call(client_with(household_user), "account_balances", {})
    assert text.startswith("No net cash figure: Rewards Card ending 1199")
    assert "Do not add the accounts below up yourself" in text


@pytest.mark.django_db
def test_net_cash_never_reads_another_households_bank(auth_client, household_a, household_b):
    _link(household_b, checking(name="Household B checking"), card())
    _feed(household_b, "1199", TODAY, -3_500, pending=True)
    body = auth_client.get("/api/finance/net-cash").json()
    assert body["available"] is False and body["parts"] == [] and body["net_cents"] is None
    assert "Household B" not in str(body)
