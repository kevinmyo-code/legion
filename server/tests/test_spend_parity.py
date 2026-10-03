"""`api/spend.py` replays the phone's own spend tests (web-revamp ticket 11,
spec D5: "parity is a test, not a hope").

Every case below is transcribed from one Kotlin test under
`app/src/test/java/com/kevin/legion/ledger/`, named in its `@kotlin(...)`
marker, with the same fixture rows and the same expected cents.
`test_every_transcribed_kotlin_test_exists` reads the phone's source to
check each named test is real.

**What is not transcribed, and why.** Assertions on the phone's sentences
(`uncategorizedExcludedSentence` and friends) are checked here through the
figure the sentence states, because the engine serves figures and the
client words them. Trust-tier (`TrustTier`), coverage-list and spend-trend
(`monthSpendFrom`) assertions have no counterpart on this endpoint and are
left to the phone.

Fixture ids are the phone's `nextId++`: pairing ties break on them, so they
are kept in the same order.
"""
from __future__ import annotations

import datetime as dt
from pathlib import Path

import pytest

from api.spend import (
    MATCHED_TRANSFER,
    OWN_ACCOUNT_MOVEMENT,
    SUSPECTED_TRANSFER,
    Month,
    Txn,
    account_month_results,
    analyze_transfers,
    budget_month_of,
    budget_month_rows,
    build_budget_vs_actual,
    counts_in_next_month,
    operating_expenses,
)

KOTLIN_DIR = Path(__file__).resolve().parents[2] / "app/src/test/java/com/kevin/legion/ledger"
TRANSCRIBED: list[tuple[str, str]] = []


def kotlin(file: str, name: str):
    """Marks a test as the transcription of `file`'s test `name`."""
    TRANSCRIBED.append((file, name))

    def mark(fn):
        return fn

    return mark


class Rows:
    """The phone's `txn(...)` builder: a fresh, increasing id per row."""

    def __init__(self, default_date: dt.date, default_category: str | None = None):
        self.next_id = 1
        self.default_date = default_date
        self.default_category = default_category

    def __call__(
        self,
        account: str,
        cents: int,
        *,
        date: dt.date | None = None,
        description: str = "GENERIC",
        category: str | None = ...,
        currency: str = "USD",
        unreconciled: bool = False,
    ) -> Txn:
        row = Txn(
            id=self.next_id,
            account_id=account,
            amount_cents=cents,
            txn_date=date or self.default_date,
            description=description,
            category=self.default_category if category is ... else category,
            currency=currency,
            unreconciled=unreconciled,
        )
        self.next_id += 1
        return row


def ids(rows) -> set[int]:
    return {row.id for row in rows}


# =============================================================================
# LedgerTransfersTest.kt
# =============================================================================

TRANSFERS = "LedgerTransfersTest.kt"
DAY_1 = dt.date(2025, 7, 31)  # DAY_1 = 1_753_920_000_000L


def days(n: int) -> dt.date:
    return DAY_1 + dt.timedelta(days=n)


@kotlin(TRANSFERS, "1 - two rows, different accounts, opposite equal amounts, two days apart, both excluded matched")  # noqa: E501
def test_transfers_1():
    t = Rows(DAY_1)
    a = t("checking", 1300_00, description="PAYMENT TO CARD")
    b = t("card", -1300_00, date=days(2), description="PAYMENT FROM CHK")
    result = analyze_transfers([a, b], [a, b])
    assert result.operating == []
    assert {(r.txn.id, r.reason, r.paired_with) for r in result.excluded} == {
        (a.id, MATCHED_TRANSFER, b.id),
        (b.id, MATCHED_TRANSFER, a.id),
    }


@kotlin(TRANSFERS, "2 - same amount, same account, is not a pair")
def test_transfers_2():
    t = Rows(DAY_1)
    a = t("checking", 500_00, description="MOVE")
    b = t("checking", -500_00, description="MOVE BACK")
    result = analyze_transfers([a, b], [a, b])
    assert len(result.operating) == 2 and result.excluded == []


@kotlin(TRANSFERS, "3 - same amount, opposite sign, 30 days apart, not paired at the 5-day default")
def test_transfers_3():
    t = Rows(DAY_1)
    a = t("checking", 200_00, description="GENERIC A")
    b = t("card", -200_00, date=days(30), description="GENERIC B")
    result = analyze_transfers([a, b], [a, b])
    assert len(result.operating) == 2 and result.excluded == []


@kotlin(TRANSFERS, "4 - three rows plus50 minus50 minus50, exactly one pair, one row survives")
def test_transfers_4():
    t = Rows(DAY_1)
    a, b, c = t("checking", 50_00), t("card", -50_00), t("savings", -50_00)
    result = analyze_transfers([a, b, c], [a, b, c])
    assert len(result.operating) == 1
    assert len(result.excluded) == 2
    assert all(r.reason == MATCHED_TRANSFER for r in result.excluded)


@kotlin(TRANSFERS, "5 - two candidate partners at 1 day and 4 days, pairs with the 1-day one")
def test_transfers_5():
    t = Rows(DAY_1)
    source = t("checking", 100_00)
    near = t("card", -100_00, date=days(1))
    far = t("savings", -100_00, date=days(4))
    result = analyze_transfers([source, near, far], [source, near, far])
    assert next(r for r in result.excluded if r.txn.id == source.id).paired_with == near.id
    assert [r.id for r in result.operating] == [far.id]


@kotlin(TRANSFERS, "6 - a partner outside the month but inside the pairing window - the in-period row excludes, the out-of-period row never appears")  # noqa: E501
def test_transfers_6():
    t = Rows(DAY_1)
    in_period = t("checking", 1300_00, description="TRANSFER TO SAV")
    partner = t("savings", -1300_00, date=days(3), description="TRANSFER FROM CHK")
    result = analyze_transfers([in_period], [in_period, partner])
    assert result.operating == []
    assert [(r.txn.id, r.reason, r.paired_with) for r in result.excluded] == [
        (in_period.id, MATCHED_TRANSFER, partner.id)
    ]


@kotlin(TRANSFERS, "7 - unpaired PAYMENT FROM SAV is flagged as a suspected transfer but still counts as spend")  # noqa: E501
def test_transfers_7():
    t = Rows(DAY_1)
    unpaired = t("card", 1300_00, description="PAYMENT FROM SAV 8267 CONF#v1ikbyqeg")
    result = analyze_transfers([unpaired], [unpaired])
    assert [r.id for r in result.operating] == [unpaired.id]
    assert [(r.reason, r.paired_with) for r in result.excluded] == [(SUSPECTED_TRANSFER, None)]


@kotlin(TRANSFERS, "8 - KROGER is not excluded - no keyword, no pair")
def test_transfers_8():
    t = Rows(DAY_1)
    groceries = t("checking", -84_37, description="KROGER #115")
    result = analyze_transfers([groceries], [groceries])
    assert len(result.operating) == 1 and result.excluded == []


@kotlin(TRANSFERS, "10 - an unpaired row naming an account Kevin actually holds is excluded as OWN_ACCOUNT_MOVEMENT")  # noqa: E501
def test_transfers_10():
    t = Rows(DAY_1)
    payment = t("checking", -1300_00, description="PAYMENT TO CRD 7823 Confirmation# 0649409616")
    result = analyze_transfers([payment], [payment], own_account_ids={"4111111111117823"})
    assert result.operating == []
    assert [(r.reason, r.paired_with) for r in result.excluded] == [(OWN_ACCOUNT_MOVEMENT, None)]


@kotlin(TRANSFERS, "11 - a Zelle payment to a person is never OWN_ACCOUNT_MOVEMENT and still counts as spend")  # noqa: E501
def test_transfers_11():
    t = Rows(DAY_1)
    zelle = t("checking", -40000, description="Zelle payment to  R Alan Cole US Conf# b4nb0qacg")
    result = analyze_transfers(
        [zelle], [zelle], own_account_ids={"4111111111117823", "4111111115042"}
    )
    assert [r.id for r in result.operating] == [zelle.id]
    assert [r.reason for r in result.excluded] == [SUSPECTED_TRANSFER]


@kotlin(TRANSFERS, "12 - a reference to an account NOT in ownAccountIds falls back to SUSPECTED_TRANSFER, still counted")  # noqa: E501
def test_transfers_12():
    t = Rows(DAY_1)
    row = t("checking", -50000, description="Online Banking transfer to SAV 8267 Confirmation# 1771219781")  # noqa: E501
    result = analyze_transfers([row], [row], own_account_ids={"4111111111117823", "4111111115042"})
    assert len(result.operating) == 1
    assert [r.reason for r in result.excluded] == [SUSPECTED_TRANSFER]


@kotlin(TRANSFERS, "13 - a matched pair is reported as MATCHED_TRANSFER, not OWN_ACCOUNT_MOVEMENT, even when both legs also name a known account")  # noqa: E501
def test_transfers_13():
    t = Rows(DAY_1)
    out = t("checking", -1300_00, description="PAYMENT TO CRD 7823")
    in_leg = t("card", 1300_00, date=days(2), description="PAYMENT FROM CHK 5042")
    result = analyze_transfers(
        [out, in_leg], [out, in_leg], own_account_ids={"4111111111117823", "4111111115042"}
    )
    assert result.operating == []
    assert all(r.reason == MATCHED_TRANSFER for r in result.excluded)


@kotlin(TRANSFERS, "14 - omitting ownAccountIds entirely reproduces the pre-2026-08-13 behaviour")
def test_transfers_14():
    t = Rows(DAY_1)
    payment = t("checking", -1300_00, description="PAYMENT TO CRD 7823 Confirmation# 0649409616")
    result = analyze_transfers([payment], [payment])
    assert len(result.operating) == 1
    assert [r.reason for r in result.excluded] == [SUSPECTED_TRANSFER]


@kotlin(TRANSFERS, "9 - reversing input list order changes nothing")
def test_transfers_9():
    t = Rows(DAY_1)
    a, b, c = t("checking", 50_00), t("card", -50_00), t("savings", -50_00)
    forward = analyze_transfers([a, b, c], [a, b, c])
    backward = analyze_transfers([c, b, a], [c, b, a])
    assert ids(forward.operating) == ids(backward.operating)
    assert {(r.txn.id, r.paired_with) for r in forward.excluded} == {
        (r.txn.id, r.paired_with) for r in backward.excluded
    }


# =============================================================================
# LedgerBudgetTest.kt
# =============================================================================

BUDGET = "LedgerBudgetTest.kt"
MONTH = Month(2026, 7)
MID_MONTH = dt.date(2025, 7, 16)  # MONTH_START (2025-07-01) + 15 days, as the phone has it


def budget(rows, targets=None, **kwargs):
    return build_budget_vs_actual("USD", MONTH, rows, rows, targets or {}, **kwargs)


def line(result, category):
    return next(bl for bl in result.lines if bl.category == category)


@kotlin(BUDGET, "spend is summed per category, remaining is target minus spent - D10 plain subtraction")  # noqa: E501
def test_budget_summed_per_category():
    t = Rows(MID_MONTH)
    rows = [
        t("checking", -80_00, description="KROGER", category="Groceries"),
        t("checking", -40_00, description="KROGER", category="Groceries"),
        t("checking", -25_00, description="DINER", category="Dining Out"),
    ]
    result = budget(rows, {"Groceries": 150_00, "Dining Out": 20_00})
    groceries, dining = line(result, "Groceries"), line(result, "Dining Out")
    assert (groceries.target_cents, groceries.actual_cents, groceries.gap_cents) == (
        150_00, 120_00, 30_00,
    )
    assert (dining.target_cents, dining.actual_cents, dining.gap_cents) == (20_00, 25_00, -5_00)


@kotlin(BUDGET, "income rows never enter any budget line - only expenses count against a budget")
def test_budget_income_never_counts():
    t = Rows(MID_MONTH)
    rows = [
        t("checking", 250_000, description="PAYROLL", category="Income"),
        t("checking", -80_00, description="KROGER", category="Groceries"),
    ]
    assert line(budget(rows, {"Groceries": 100_00, "Income": 0}), "Income").actual_cents == 0


@kotlin(BUDGET, "excluded transfers never inflate a category - reused analyzeTransfers exactly as the P&L used it")  # noqa: E501
def test_budget_transfers_never_inflate():
    t = Rows(MID_MONTH)
    rows = [
        t("checking", -80_00, description="KROGER", category="Groceries"),
        t("checking", -1300_00, description="PAYMENT TO CARD", category="Groceries"),
        t("card", 1300_00, date=MID_MONTH + dt.timedelta(days=2), description="PAYMENT FROM CHK"),
    ]
    assert line(budget(rows, {"Groceries": 100_00}), "Groceries").actual_cents == 80_00


@kotlin(BUDGET, "D11 - uncategorised spend gets its own bucket, never folded into any category")
def test_budget_uncategorised_bucket():
    t = Rows(MID_MONTH)
    rows = [
        t("checking", -80_00, description="KROGER", category="Groceries"),
        t("checking", -34_12, description="UNKNOWN MERCHANT", category=None),
    ]
    result = budget(rows, {"Groceries": 100_00})
    assert result.uncategorized_cents == 34_12
    assert line(result, "Groceries").actual_cents == 80_00
    assert result.spent_cents == 80_00
    assert result.all_operating_spend_cents == 114_12


@kotlin(BUDGET, "spend excludes the uncategorised bucket even when every row this month is uncategorised")  # noqa: E501
def test_budget_all_uncategorised():
    t = Rows(MID_MONTH)
    result = budget([t("checking", -55_00, description="UNKNOWN MERCHANT", category=None)])
    assert result.spent_cents == 0
    assert result.all_operating_spend_cents == 55_00
    # The sentence's figure: 55.00 not counted.
    assert result.uncategorized_cents == 55_00


@kotlin(BUDGET, "D11 - the uncategorised bucket is present even at zero, never conditionally omitted")  # noqa: E501
def test_budget_zero_bucket():
    t = Rows(MID_MONTH)
    result = budget([t("checking", -80_00, description="KROGER", category="Groceries")])
    assert result.uncategorized_cents == 0


@kotlin(BUDGET, "an unpaired transfer-looking leg still counts as spend - the other statement isn't on file yet")  # noqa: E501
def test_budget_unpaired_leg_counts():
    t = Rows(MID_MONTH)
    rows = [t("card", -1300_00, description="PAYMENT TO SAV 8267 CONF#v1ikbyqeg", category="Groceries")]  # noqa: E501
    assert line(budget(rows, {"Groceries": 100_00}), "Groceries").actual_cents == 1300_00


@kotlin(BUDGET, "a category with spend but no set budget gets its own zero-target line, not silence")  # noqa: E501
def test_budget_untargeted_line():
    t = Rows(MID_MONTH)
    result = budget([t("checking", -42_00, description="STORE", category="Shopping")])
    (only,) = result.lines
    assert (only.category, only.target_cents, only.actual_cents, only.gap_cents) == (
        "Shopping", 0, 42_00, -42_00,
    )


@kotlin(BUDGET, "D12 - a category with an UNRECONCILED row sets hasProvisionalRows and its actual still counts it")  # noqa: E501
def test_budget_provisional_counts_and_flags():
    t = Rows(MID_MONTH)
    reconciled = t("checking", -80_00, description="KROGER", category="Groceries")
    provisional = t(
        "card", -30_00, description="KROGER EXPRESS", category="Groceries", unreconciled=True
    )
    assert not budget([reconciled], {"Groceries": 100_00}).lines[0].has_provisional_rows
    with_provisional = budget([reconciled, provisional], {"Groceries": 100_00}).lines[0]
    assert with_provisional.has_provisional_rows
    assert with_provisional.actual_cents == 110_00


@kotlin(BUDGET, "only the entity's own currency contributes - an SGD row in range never reaches a US budget")  # noqa: E501
def test_budget_own_currency_only():
    t = Rows(MID_MONTH)
    rows = [
        t("bofa-checking", -100_00, category="Groceries"),
        t("dbs-checking", -999_99, currency="SGD", category="Groceries"),
    ]
    assert line(budget(rows, {"Groceries": 200_00}), "Groceries").actual_cents == 100_00


@kotlin(BUDGET, "a month with no transactions returns empty lines and zero uncategorised, isComplete false")  # noqa: E501
def test_budget_empty_month():
    result = budget([])
    assert result.lines == [] and result.uncategorized_cents == 0


@kotlin(BUDGET, "an own-account card payment is excluded from spend and shows up in the disclosure")
def test_budget_own_account_excluded_and_disclosed():
    t = Rows(MID_MONTH)
    groceries = t("checking", -80_00, description="KROGER", category="Groceries")
    payment = t("checking", -1300_00, description="PAYMENT TO CRD 7823", category="Groceries")
    result = budget(
        [groceries, payment], {"Groceries": 100_00}, own_account_ids={"4111111111117823"}
    )
    assert line(result, "Groceries").actual_cents == 80_00
    assert [r.id for r in result.own_account_rows] == [payment.id]
    assert result.own_account_cents == 1300_00


@kotlin(BUDGET, "a Zelle payment to a person still counts as spend even when ownAccountIds is non-empty")  # noqa: E501
def test_budget_zelle_still_counts():
    t = Rows(MID_MONTH)
    zelle = t("checking", -400_00, description="Zelle payment to  R Alan Cole US Conf# b4nb0qacg", category="Shopping")  # noqa: E501
    result = budget(
        [zelle], {"Shopping": 1000_00}, own_account_ids={"4111111111117823", "4111111115042"}
    )
    assert result.lines[0].actual_cents == 400_00
    assert result.own_account_rows == []


@kotlin(BUDGET, "a row filed under Transfers never counts in spend, and is disclosed with count and amount")  # noqa: E501
def test_budget_transfers_category_excluded():
    t = Rows(MID_MONTH)
    rows = [
        t("checking", -80_00, description="KROGER", category="Groceries"),
        t("checking", -400_00, description="ZELLE PAYMENT TO MIA", category="Transfers"),
        t("checking", -250_00, description="ONLINE BANKING TRANSFER TO SAV 1234", category="Transfers"),  # noqa: E501
    ]
    result = budget(rows, {"Groceries": 100_00}, not_spending={"Transfers"})
    assert result.spent_cents == 80_00
    assert all(bl.category != "Transfers" for bl in result.lines)
    assert result.uncategorized_cents == 0
    assert len(result.not_spending_rows) == 2
    assert result.not_spending_cents == 650_00
    assert result.not_spending_categories == ["Transfers"]


@kotlin(BUDGET, "without the flag the same Transfers row is ordinary spend - the flag is the definition, not the name")  # noqa: E501
def test_budget_transfers_without_flag():
    t = Rows(MID_MONTH)
    result = budget([t("checking", -400_00, description="ZELLE PAYMENT TO MIA", category="Transfers")])  # noqa: E501
    assert result.spent_cents == 400_00
    assert result.not_spending_rows == []


@kotlin(BUDGET, "a not-spending category does not disturb card-payment pairing")
def test_budget_not_spending_keeps_pairing():
    t = Rows(MID_MONTH)
    groceries = t("checking", -80_00, description="KROGER", category="Groceries")
    payment = t("checking", -1300_00, description="PAYMENT TO CRD 7823", category="Groceries")
    zelle = t("checking", -400_00, description="ZELLE PAYMENT TO MIA", category="Transfers")
    result = budget(
        [groceries, payment, zelle],
        {"Groceries": 100_00},
        own_account_ids={"4111111111117823"},
        not_spending={"Transfers"},
    )
    assert [r.id for r in result.own_account_rows] == [payment.id]
    assert [r.id for r in result.not_spending_rows] == [zelle.id]
    assert result.spent_cents == 80_00


@kotlin(BUDGET, "operatingExpenses drops not-spending rows, and nothing else")
def test_budget_operating_expenses_drops_not_spending():
    t = Rows(MID_MONTH)
    groceries = t("checking", -80_00, description="KROGER", category="Groceries")
    zelle = t("checking", -400_00, description="ZELLE PAYMENT TO MIA", category="Transfers")
    mystery = t("checking", -9_00, description="MYSTERY")
    rows = [groceries, zelle, mystery]
    kept = operating_expenses("USD", rows, rows, not_spending={"Transfers"})
    assert ids(kept) == {groceries.id, mystery.id}


@kotlin(BUDGET, "omitting ownAccountIds leaves the disclosure empty - the pre-2026-08-13 default")
def test_budget_no_own_ids_no_disclosure():
    t = Rows(MID_MONTH)
    payment = t("checking", -1300_00, description="PAYMENT TO CRD 7823", category="Groceries")
    result = budget([payment], {"Groceries": 100_00})
    assert result.own_account_rows == []
    assert result.lines[0].actual_cents == 1300_00


@kotlin(BUDGET, "totalCents is the exact Long sum of every category line's actual, uncategorised excluded")  # noqa: E501
def test_budget_total_is_exact():
    t = Rows(MID_MONTH)
    rows = [
        t("checking", -184_212, description="KROGER", category="Groceries"),
        t("checking", -55_00, description="UNKNOWN MERCHANT", category=None),
    ]
    assert budget(rows, {"Groceries": 200_000}).spent_cents == 184_212


@kotlin(BUDGET, "accountFilter narrows spend to one physical account, filtered lines sum to the ALL total")  # noqa: E501
def test_budget_account_filter_clusters_one_card():
    t = Rows(MID_MONTH)
    rows = [
        t("4400664229114146", -80_00, description="TRADER JOES", category="Groceries"),
        t("4146", -20_00, description="AMAZON", category="Shopping"),
        t("debit", -15_00, description="GAS STATION", category="Fuel"),
    ]
    everything = budget(rows)
    card = budget(rows, account_filter={"4400664229114146"})
    debit = budget(rows, account_filter={"debit"})
    via_short = budget(rows, account_filter={"4146"})
    assert everything.spent_cents == 115_00
    assert card.spent_cents == 100_00
    assert debit.spent_cents == 15_00
    assert everything.spent_cents == card.spent_cents + debit.spent_cents
    assert via_short.spent_cents == card.spent_cents


@kotlin(BUDGET, "accountFilter narrows the excluded-own-account disclosure to the selected cluster")
def test_budget_account_filter_narrows_disclosure():
    t = Rows(MID_MONTH)
    rows = [
        t("checking", -1300_00, description="PAYMENT TO CRD 7823", category="Groceries"),
        t("debit", -400_00, description="PAYMENT TO CRD 9999", category="Shopping"),
    ]
    result = budget(
        rows,
        {"Groceries": 100_00, "Shopping": 100_00},
        own_account_ids={"4111111111117823", "4222222222229999"},
        account_filter={"checking"},
    )
    assert len(result.own_account_rows) == 1
    assert result.own_account_cents == 1300_00


@kotlin(BUDGET, "a null accountFilter is unchanged behaviour - the untouched-install default")
def test_budget_null_filter_unchanged():
    t = Rows(MID_MONTH)
    rows = [t("checking", -42_00, description="STORE", category="Shopping")]
    assert budget(rows).spent_cents == budget(rows, account_filter=None).spent_cents == 42_00


# =============================================================================
# LedgerBudgetMonthTest.kt
# =============================================================================

BUDGET_MONTH = "LedgerBudgetMonthTest.kt"
RENT = 1180_63


def rent_rows():
    return Rows(dt.date(2026, 9, 1), default_category="Housing")


def budget_for(month: Month, rows, own=()):
    window, in_period = budget_month_rows(rows, "USD", month, 5)
    return build_budget_vs_actual("USD", month, in_period, window, {}, own_account_ids=own)


@kotlin(BUDGET_MONTH, "a Housing charge on the 29th, 30th or 31st of a 31-day month moves to the next month")  # noqa: E501
def test_month_last_three_days_move():
    t = rent_rows()
    for day in (29, 30, 31):
        row = t("card", -RENT, date=dt.date(2026, 8, day), description="RPS*RENT PORTAL")
        assert counts_in_next_month(row)
        assert budget_month_of(row) == Month(2026, 9)


@kotlin(BUDGET_MONTH, "a Housing charge on the 28th of a 31-day month stays in its own month")
def test_month_28th_stays():
    row = rent_rows()("card", -RENT, date=dt.date(2026, 8, 28))
    assert not counts_in_next_month(row)
    assert budget_month_of(row) == Month(2026, 8)


@kotlin(BUDGET_MONTH, "February's last three days are the 26th, 27th and 28th")
def test_month_february():
    t = rent_rows()
    for day in (26, 27, 28):
        assert budget_month_of(t("card", -RENT, date=dt.date(2026, 2, day))) == Month(2026, 3)
    assert budget_month_of(t("card", -RENT, date=dt.date(2026, 2, 25))) == Month(2026, 2)


@kotlin(BUDGET_MONTH, "a 30-day month moves the 28th but not the 27th")
def test_month_30_day_month():
    t = rent_rows()
    assert budget_month_of(t("card", -RENT, date=dt.date(2026, 9, 28))) == Month(2026, 10)
    assert budget_month_of(t("card", -RENT, date=dt.date(2026, 9, 27))) == Month(2026, 9)


@kotlin(BUDGET_MONTH, "a non-Housing charge on the 31st does not move")
def test_month_non_housing_stays():
    t = rent_rows()
    assert budget_month_of(t("card", -80_00, date=dt.date(2026, 8, 31), category="Groceries")) == Month(2026, 8)  # noqa: E501
    assert budget_month_of(t("card", -80_00, date=dt.date(2026, 8, 31), category=None)) == Month(2026, 8)  # noqa: E501


@kotlin(BUDGET_MONTH, "an inflow categorised Housing does not move")
def test_month_inflow_stays():
    refund = rent_rows()("card", RENT, date=dt.date(2026, 8, 31))
    assert not counts_in_next_month(refund)
    assert budget_month_of(refund) == Month(2026, 8)


@kotlin(BUDGET_MONTH, "December's late Housing charge moves into January of the next year")
def test_month_december_rolls_over():
    assert budget_month_of(rent_rows()("card", -RENT, date=dt.date(2026, 12, 31))) == Month(2027, 1)


@kotlin(BUDGET_MONTH, "September includes the August 31 rent and August excludes it")
def test_month_september_includes_august_rent():
    t = rent_rows()
    aug_rent = t("card", -RENT, date=dt.date(2026, 8, 31), description="RPS*RENT PORTAL")
    aug_groceries = t("card", -50_00, date=dt.date(2026, 8, 15), category="Groceries")
    sept_groceries = t("card", -60_00, date=dt.date(2026, 9, 10), category="Groceries")
    rows = [aug_rent, aug_groceries, sept_groceries]
    august, september = budget_for(Month(2026, 8), rows), budget_for(Month(2026, 9), rows)
    assert august.spent_cents == 50_00
    assert all(bl.category != "Housing" for bl in august.lines)
    assert september.spent_cents == RENT + 60_00
    assert line(september, "Housing").actual_cents == RENT
    assert [r.id for r in september.early_counted_here] == [aug_rent.id]
    assert september.early_counted_next_month == []
    assert [r.id for r in august.early_counted_next_month] == [aug_rent.id]
    assert august.early_counted_here == []


@kotlin(BUDGET_MONTH, "the September tile states the moved rent in words")
def test_month_september_tile_figures():
    t = rent_rows()
    aug_rent = t("card", -RENT, date=dt.date(2026, 8, 31))
    sept_rent = t("card", -RENT, date=dt.date(2026, 9, 30))
    september = budget_for(Month(2026, 9), [aug_rent, sept_rent])
    assert september.spent_cents == RENT
    # The two sentences' rows: counted here, and counting next month.
    assert [r.id for r in september.early_counted_here] == [aug_rent.id]
    assert [r.id for r in september.early_counted_next_month] == [sept_rent.id]


@kotlin(BUDGET_MONTH, "a month with nothing moved has no early-charge words")
def test_month_nothing_moved():
    september = budget_for(Month(2026, 9), [rent_rows()("card", -RENT, date=dt.date(2026, 9, 1))])
    assert september.early_counted_here == [] and september.early_counted_next_month == []


@kotlin(BUDGET_MONTH, "the pairing window stays on calendar dates")
def test_month_pairing_window_is_calendar():
    t = rent_rows()
    sept_rent = t("card", -RENT, date=dt.date(2026, 9, 30))
    aug_rent = t("card", -RENT, date=dt.date(2026, 8, 31))
    window, in_period = budget_month_rows([sept_rent, aug_rent], "USD", Month(2026, 9), 5)
    assert sept_rent in window and sept_rent not in in_period
    assert aug_rent in window and aug_rent in in_period


@kotlin(BUDGET_MONTH, "a card payment filed as Housing on the 31st is still paired and never becomes spend in either month")  # noqa: E501
def test_month_card_payment_as_housing():
    payment = rent_rows()(
        "checking", -1300_00, date=dt.date(2026, 8, 31), description="PAYMENT TO CRD 7823"
    )
    own = {"4111111111117823"}
    august = budget_for(Month(2026, 8), [payment], own)
    september = budget_for(Month(2026, 9), [payment], own)
    assert august.spent_cents == 0 and september.spent_cents == 0
    assert [r.id for r in september.own_account_rows] == [payment.id]
    assert august.early_counted_here == [] and august.early_counted_next_month == []
    assert september.early_counted_here == [] and september.early_counted_next_month == []


@kotlin(BUDGET_MONTH, "a caller that still passes calendar rows is never told its counted row is not here")  # noqa: E501
def test_month_calendar_rows_caller():
    aug_rent = rent_rows()("card", -RENT, date=dt.date(2026, 8, 31))
    result = build_budget_vs_actual("USD", Month(2026, 8), [aug_rent], [aug_rent], {})
    assert result.spent_cents == RENT
    assert result.early_counted_next_month == []


# =============================================================================
# MoneyMonthTest.kt - the per-account figure the phone's Money page shows
# =============================================================================

MONEY = "MoneyMonthTest.kt"
OCT = Month(2026, 10)
CHECKING = "BofA checking 5042"
CARD = "BofA card 7823"


def oct_rows():
    return Rows(dt.date(2026, 10, 1), default_category="Dining")


def d(day: int) -> dt.date:
    return dt.date(2026, 10, day)


def spend_of(results, name):
    return next(a for a in results if a.name == name)


@kotlin(MONEY, "one section per account ordered by name with totals and categories largest first")
def test_money_sections():
    t = oct_rows()
    rows = [
        t(CHECKING, -1_000, date=d(1), category="Groceries"),
        t(CHECKING, -5_000, date=d(2), category="Utilities"),
        t(CARD, -2_500, date=d(2), category="Dining"),
        t(CARD, -500, date=d(1), category="Dining"),
    ]
    results = account_month_results(rows, OCT, set())
    assert [a.name for a in results] == [CARD, CHECKING]
    checking = spend_of(results, CHECKING)
    assert checking.total_cents == 6_000
    assert [c for c, _ in checking.categories] == ["Utilities", "Groceries"]
    card = spend_of(results, CARD)
    assert card.total_cents == 3_000
    assert [cents for _, cents in card.categories] == [3_000]


@kotlin(MONEY, "a card payment from checking is spending on neither side")
def test_money_card_payment():
    t = oct_rows()
    rows = [
        t(CHECKING, -50_000, date=d(2), category="Payments", description="PAYMENT TO CRD 7823"),
        t(CARD, 50_000, date=d(2), category="Payments", description="PAYMENT FROM CHK 5042"),
        t(CARD, -1_200, date=d(2), category="Dining"),
        t(CHECKING, -700, date=d(1), category="Groceries"),
    ]
    results = account_month_results(rows, OCT, set())
    assert spend_of(results, CHECKING).total_cents == 700
    assert spend_of(results, CARD).total_cents == 1_200


@kotlin(MONEY, "a refund is excluded from spend, never netted")
def test_money_refund_not_netted():
    t = oct_rows()
    rows = [
        t(CARD, -4_000, date=d(2), category="Shopping"),
        t(CARD, 1_500, date=d(3), category="Shopping", description="REFUND"),
    ]
    assert spend_of(account_month_results(rows, OCT, set()), CARD).total_cents == 4_000


@kotlin(MONEY, "uncategorised is its own row, not in the total, and is said in words")
def test_money_uncategorised():
    t = oct_rows()
    rows = [t(CARD, -3_000, date=d(2), category="Dining"), t(CARD, -900, date=d(3), category=None)]
    card = spend_of(account_month_results(rows, OCT, set()), CARD)
    assert card.total_cents == 3_000
    assert card.budget.uncategorized_cents == 900


@kotlin(MONEY, "an unverified row is stated inside the total and on its category")
def test_money_unverified():
    t = oct_rows()
    rows = [
        t(CARD, -3_000, date=d(2), category="Dining", unreconciled=True),
        t(CARD, -2_000, date=d(3), category="Dining"),
        t(CARD, -800, date=d(3), category="Travel"),
    ]
    card = spend_of(account_month_results(rows, OCT, set()), CARD)
    assert card.total_cents == 5_800
    assert card.unverified_cents == 3_000
    assert line(card.budget, "Dining").unverified_cents == 3_000
    assert line(card.budget, "Travel").unverified_cents == 0


@kotlin(MONEY, "rent paid on the 30th of last month counts in this month and says so")
def test_money_rent_from_last_month():
    t = oct_rows()
    rent = t(CHECKING, -180_000, date=dt.date(2026, 9, 30), category="Housing", description="RENT")
    rows = [rent, t(CHECKING, -1_000, date=d(2), category="Groceries")]
    checking = spend_of(account_month_results(rows, OCT, set()), CHECKING)
    assert checking.total_cents == 181_000
    assert [r.id for r in checking.budget.early_counted_here] == [rent.id]


@kotlin(MONEY, "rent paid on the 30th of this month does not count here")
def test_money_rent_this_month():
    t = oct_rows()
    rows = [
        t(CHECKING, -180_000, date=d(30), category="Housing", description="RENT"),
        t(CHECKING, -1_000, date=d(2), category="Groceries"),
    ]
    assert spend_of(account_month_results(rows, OCT, set()), CHECKING).total_cents == 1_000


@kotlin(MONEY, "a not-spending category is excluded and disclosed")
def test_money_not_spending():
    t = oct_rows()
    rows = [
        t(CHECKING, -20_000, date=d(2), category="Transfers", description="ZELLE TO SOMEONE"),
        t(CHECKING, -1_000, date=d(2), category="Groceries"),
    ]
    checking = spend_of(account_month_results(rows, OCT, {"Transfers"}), CHECKING)
    assert checking.total_cents == 1_000
    assert checking.budget.not_spending_categories == ["Transfers"]
    assert checking.budget.not_spending_cents == 20_000


@kotlin(MONEY, "a bare last-4 and a full number for one card are one section")
def test_money_one_card_two_ids():
    t = oct_rows()
    rows = [
        t("5555555555557823", -1_000, date=d(2), category="Dining"),
        t("7823", -500, date=d(3), category="Dining"),
    ]
    results = account_month_results(rows, OCT, set())
    assert len(results) == 1
    assert results[0].name.endswith("7823")
    assert results[0].total_cents == 1_500


@kotlin(MONEY, "an account with rows only in other months is not shown, and no rows at all is empty")  # noqa: E501
def test_money_inactive_account_hidden():
    rows = [oct_rows()(CARD, -1_000, date=dt.date(2026, 8, 5), category="Dining")]
    assert account_month_results(rows, OCT, set()) == []
    assert account_month_results([], OCT, set()) == []


@kotlin(MONEY, "an active account with only credits is shown as having no spend, not hidden")
def test_money_credits_only_account_shown():
    rows = [oct_rows()(CHECKING, 9_000, date=d(2), category=None, description="PAYROLL")]
    (checking,) = account_month_results(rows, OCT, set())
    assert checking.name == CHECKING
    assert checking.total_cents == 0
    assert checking.categories == [] and checking.budget.uncategorized_cents == 0


# =============================================================================
# The transcription is checkable
# =============================================================================


def test_every_transcribed_kotlin_test_exists():
    if not KOTLIN_DIR.is_dir():
        pytest.skip("app/ is not checked out beside server/ here")
    for file, name in TRANSCRIBED:
        source = (KOTLIN_DIR / file).read_text(encoding="utf-8")
        assert f"fun `{name}`" in source, (file, name)
    assert len(TRANSCRIBED) == len(set(TRANSCRIBED))
