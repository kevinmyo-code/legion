"""`GET /api/ledger/spend?month=YYYY-MM&tz=<IANA>` - what the household spent
this month, computed once, on the engine (web-revamp ticket 11, spec D5).

**A port, not a new definition.** CLAUDE.md section 7: a business rule lives
in Django once. The phone computes spend in
`app/src/main/java/com/kevin/legion/ledger/`, and every function below names
the Kotlin it ports. `tests/test_spend_parity.py` replays the phone's own
unit tests for those classes and must produce the same cents; until the phone
reads this endpoint, that test is what keeps the two equal.

The rules, kept exactly:

- **Transfers never count** (`LedgerTransfers.analyzeTransfers`): pass 1
  pairs two rows on different accounts with equal and opposite amounts within
  5 days (closest date first, then lowest id; greedy, each row used once);
  pass 2 pulls a row whose description names an account the household holds
  (`CRD 7823`); pass 3 only FLAGS wording ("payment to") and leaves the row in
  spend, so a Zelle to a person still counts.
- **Only outflows count**, and a refund is not netted against them.
- **A not-spending category (`categories.excluded_from_spend`) never counts**,
  removed after pairing so pairing is unchanged by it.
- **Spend is the categorised lines only.** Uncategorised money is its own
  figure beside it, never folded in (Kevin, 2026-08-15).
- **The budget month** (`BudgetMonth.budgetMonthOf`): a Housing outflow in
  the last 3 days of a month counts in the next month. Pairing still reads
  calendar dates.
- **Per account** (`MoneyMonth.buildAccountMonthResults`): every figure is
  computed over the whole household's rows and only then narrowed to one
  account, so a card payment whose other leg is on another account still
  pairs.

**The account identity is `account_last4`** (spec D5: `account_nickname`
holds a label for server-ingested rows and the phone's raw id for uploaded
ones, so keying on it splits one card). The phone's algorithm takes an
account id string and compares cards by their last four characters
(`sameCard`); here that string IS the last four, so one card is one account
however its rows were named.

Money is `int` cents throughout (CLAUDE.md section 4 rule 3).
"""
from __future__ import annotations

import calendar
import datetime as dt
import re
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

# `BudgetMonth.kt`.
EARLY_CHARGE_CATEGORY = "Housing"
EARLY_CHARGE_TRAILING_DAYS = 3
# `LedgerController.PAIRING_WINDOW_DAYS` and `analyzeTransfers`' default.
PAIRING_WINDOW_DAYS = 5
MAX_DAYS_APART = 5

# `LedgerTransfers.TRANSFER_KEYWORDS`, lower-cased.
TRANSFER_KEYWORDS = (
    "payment from",
    "payment to",
    "online banking transfer",
    "transfer from",
    "transfer to",
    "conf#",
)
# `LedgerAccountIdentity.ACCOUNT_REFERENCE`.
_ACCOUNT_REFERENCE = re.compile(r"(?i)\b(?:CRD|CHK|SAV|ACCT)\s*#?\s*(\d{4,})\b")

MATCHED_TRANSFER = "MATCHED_TRANSFER"
OWN_ACCOUNT_MOVEMENT = "OWN_ACCOUNT_MOVEMENT"
SUSPECTED_TRANSFER = "SUSPECTED_TRANSFER"


@dataclass(frozen=True)
class Txn:
    """One ledger row as the algorithm sees it. `id` is the ordering key
    pairing ties break on (the phone's Room id; here the row's position by
    `(created_at, id)`, the order the phone's mirror inserts them in)."""

    id: int
    account_id: str
    amount_cents: int
    txn_date: dt.date
    description: str = "GENERIC"
    category: str | None = None
    currency: str = "USD"
    unreconciled: bool = False


@dataclass(frozen=True)
class ExcludedRow:
    txn: Txn
    reason: str
    paired_with: int | None


@dataclass(frozen=True)
class TransferAnalysis:
    operating: list[Txn]
    excluded: list[ExcludedRow]


# -----------------------------------------------------------------------------
# LedgerAccountIdentity.kt
# -----------------------------------------------------------------------------


def same_card(a: str, b: str) -> bool:
    return a == b or (len(a) >= 4 and len(b) >= 4 and a[-4:] == b[-4:])


def matches_account_filter(account_id: str, account_filter: Iterable[str] | None) -> bool:
    return account_filter is None or any(same_card(it, account_id) for it in account_filter)


def references_own_account(description: str, own_account_ids: Iterable[str]) -> bool:
    own = list(own_account_ids)
    if not own:
        return False
    return any(
        same_card(it, match.group(1))
        for match in _ACCOUNT_REFERENCE.finditer(description)
        for it in own
    )


# -----------------------------------------------------------------------------
# LedgerTransfers.kt
# -----------------------------------------------------------------------------


def _looks_like_transfer(description: str) -> bool:
    lower = description.lower()
    return any(keyword in lower for keyword in TRANSFER_KEYWORDS)


def analyze_transfers(
    in_period: Sequence[Txn],
    pairing_window: Sequence[Txn],
    max_days_apart: int = MAX_DAYS_APART,
    own_account_ids: Iterable[str] = (),
) -> TransferAnalysis:
    own = set(own_account_ids)
    candidates = sorted(pairing_window, key=lambda t: t.id)
    consumed: set[int] = set()
    partner_of: dict[int, int] = {}
    for row in candidates:
        if row.id in consumed:
            continue
        best = None
        for other in candidates:
            if (
                other.id != row.id
                and other.id not in consumed
                and other.account_id != row.account_id
                and other.amount_cents == -row.amount_cents
                and abs((row.txn_date - other.txn_date).days) <= max_days_apart
            ):
                key = (abs((row.txn_date - other.txn_date).days), other.id)
                if best is None or key < best[0]:
                    best = (key, other)
        if best is None:
            continue
        partner = best[1]
        consumed.update((row.id, partner.id))
        partner_of[row.id] = partner.id
        partner_of[partner.id] = row.id

    operating: list[Txn] = []
    excluded: list[ExcludedRow] = []
    for txn in in_period:
        if txn.id in consumed:
            excluded.append(ExcludedRow(txn, MATCHED_TRANSFER, partner_of[txn.id]))
        elif references_own_account(txn.description, own):
            excluded.append(ExcludedRow(txn, OWN_ACCOUNT_MOVEMENT, None))
        elif _looks_like_transfer(txn.description):
            excluded.append(ExcludedRow(txn, SUSPECTED_TRANSFER, None))
            operating.append(txn)
        else:
            operating.append(txn)
    return TransferAnalysis(operating, excluded)


# -----------------------------------------------------------------------------
# BudgetMonth.kt
# -----------------------------------------------------------------------------


@dataclass(frozen=True, order=True)
class Month:
    year: int
    month: int

    @classmethod
    def parse(cls, raw: str) -> Month:
        year, _, month = raw.partition("-")
        value = cls(int(year), int(month))
        if not 1 <= value.month <= 12 or len(raw) != 7:
            raise ValueError(raw)
        return value

    @classmethod
    def of(cls, day: dt.date) -> Month:
        return cls(day.year, day.month)

    def plus(self, months: int) -> Month:
        total = self.year * 12 + self.month - 1 + months
        return Month(total // 12, total % 12 + 1)

    @property
    def first(self) -> dt.date:
        return dt.date(self.year, self.month, 1)

    @property
    def last(self) -> dt.date:
        return dt.date(self.year, self.month, calendar.monthrange(self.year, self.month)[1])

    def __str__(self) -> str:
        return f"{self.year:04d}-{self.month:02d}"


def counts_in_next_month(txn: Txn) -> bool:
    if txn.amount_cents >= 0 or txn.category != EARLY_CHARGE_CATEGORY:
        return False
    length = calendar.monthrange(txn.txn_date.year, txn.txn_date.month)[1]
    return txn.txn_date.day >= length - (EARLY_CHARGE_TRAILING_DAYS - 1)


def budget_month_of(txn: Txn) -> Month:
    month = Month.of(txn.txn_date)
    return month.plus(1) if counts_in_next_month(txn) else month


def budget_month_rows(
    rows: Sequence[Txn], currency: str, month: Month, pairing_days: int = PAIRING_WINDOW_DAYS
) -> tuple[list[Txn], list[Txn]]:
    """`(pairing_window, in_period)`: the calendar month padded by
    `pairing_days` each side, and the rows whose budget month is `month`."""
    pad = dt.timedelta(days=pairing_days)
    own = [t for t in rows if t.currency == currency]
    window = [t for t in own if month.first - pad <= t.txn_date <= month.last + pad]
    return window, [t for t in own if budget_month_of(t) == month]


# -----------------------------------------------------------------------------
# LedgerBudget.kt
# -----------------------------------------------------------------------------


def _before_category_exclusion(currency, in_period, pairing_window, max_days, own, account_filter):
    analysis = analyze_transfers(
        [t for t in in_period if t.currency == currency],
        [t for t in pairing_window if t.currency == currency],
        max_days,
        own,
    )
    return [
        t
        for t in analysis.operating
        if t.amount_cents < 0 and matches_account_filter(t.account_id, account_filter)
    ]


def operating_expenses(
    currency: str,
    in_period: Sequence[Txn],
    pairing_window: Sequence[Txn],
    max_days_apart: int = MAX_DAYS_APART,
    own_account_ids: Iterable[str] = (),
    account_filter: Iterable[str] | None = None,
    not_spending: Iterable[str] = (),
) -> list[Txn]:
    excluded = set(not_spending)
    return [
        t
        for t in _before_category_exclusion(
            currency, in_period, pairing_window, max_days_apart, set(own_account_ids),
            account_filter,
        )
        if not (t.category is not None and t.category in excluded)
    ]


def not_spending_expenses(
    currency, in_period, pairing_window, max_days_apart=MAX_DAYS_APART, own_account_ids=(),
    account_filter=None, not_spending=(),
) -> list[Txn]:
    excluded = set(not_spending)
    return [
        t
        for t in _before_category_exclusion(
            currency, in_period, pairing_window, max_days_apart, set(own_account_ids),
            account_filter,
        )
        if t.category is not None and t.category in excluded
    ]


def _own_account_movements(currency, in_period, pairing_window, max_days, own, account_filter):
    analysis = analyze_transfers(
        [t for t in in_period if t.currency == currency],
        [t for t in pairing_window if t.currency == currency],
        max_days,
        own,
    )
    return [
        row.txn
        for row in analysis.excluded
        if row.reason == OWN_ACCOUNT_MOVEMENT
        and row.txn.amount_cents < 0
        and matches_account_filter(row.txn.account_id, account_filter)
    ]


@dataclass
class BudgetLine:
    category: str
    target_cents: int
    actual_cents: int
    has_provisional_rows: bool
    unverified_cents: int

    @property
    def gap_cents(self) -> int:
        return self.target_cents - self.actual_cents


@dataclass
class BudgetVsActual:
    month: Month
    lines: list[BudgetLine]
    uncategorized_cents: int
    uncategorized_has_provisional_rows: bool
    own_account_rows: list[Txn]
    not_spending_rows: list[Txn]
    early_counted_here: list[Txn]
    early_counted_next_month: list[Txn]
    expenses: list[Txn] = field(default_factory=list)

    @property
    def spent_cents(self) -> int:
        """THE definition: the category lines' actuals, nothing else."""
        return sum(line.actual_cents for line in self.lines)

    @property
    def all_operating_spend_cents(self) -> int:
        return self.spent_cents + self.uncategorized_cents

    @property
    def own_account_cents(self) -> int:
        return -sum(t.amount_cents for t in self.own_account_rows)

    @property
    def not_spending_cents(self) -> int:
        return -sum(t.amount_cents for t in self.not_spending_rows)

    @property
    def not_spending_categories(self) -> list[str]:
        return sorted({t.category for t in self.not_spending_rows if t.category})


def build_budget_vs_actual(
    currency: str,
    month: Month,
    in_period: Sequence[Txn],
    pairing_window: Sequence[Txn],
    targets: dict[str, int],
    max_days_apart: int = MAX_DAYS_APART,
    own_account_ids: Iterable[str] = (),
    account_filter: Iterable[str] | None = None,
    not_spending: Iterable[str] = (),
) -> BudgetVsActual:
    """`LedgerBudget.buildBudgetVsActual`, minus the coverage list (the
    response states completeness separately, `_complete`)."""
    own = set(own_account_ids)
    not_spending = set(not_spending)
    account_filter = None if account_filter is None else set(account_filter)
    expenses = operating_expenses(
        currency, in_period, pairing_window, max_days_apart, own, account_filter, not_spending
    )
    not_spending_rows = (
        not_spending_expenses(
            currency, in_period, pairing_window, max_days_apart, own, account_filter, not_spending
        )
        if not_spending
        else []
    )
    by_category: dict[str, list[Txn]] = {}
    for t in expenses:
        if t.category is not None:
            by_category.setdefault(t.category, []).append(t)
    uncategorized = [t for t in expenses if t.category is None]

    def line(category: str, target: int) -> BudgetLine:
        rows = by_category.get(category, [])
        return BudgetLine(
            category=category,
            target_cents=target,
            actual_cents=-sum(t.amount_cents for t in rows),
            has_provisional_rows=any(t.unreconciled for t in rows),
            unverified_cents=-sum(t.amount_cents for t in rows if t.unreconciled),
        )

    lines = [line(c, target) for c, target in targets.items()]
    lines += [line(c, 0) for c in by_category if c not in targets]
    lines.sort(key=lambda bl: bl.category)

    counted_here = [t for t in expenses if Month.of(t.txn_date) != month]
    in_period_ids = {t.id for t in in_period}
    moved_out = [
        t
        for t in pairing_window
        if Month.of(t.txn_date) == month
        and budget_month_of(t) != month
        and t.id not in in_period_ids
    ]
    counted_next = (
        operating_expenses(
            currency, moved_out, pairing_window, max_days_apart, own, account_filter, not_spending
        )
        if moved_out
        else []
    )
    return BudgetVsActual(
        month=month,
        lines=lines,
        uncategorized_cents=-sum(t.amount_cents for t in uncategorized),
        uncategorized_has_provisional_rows=any(t.unreconciled for t in uncategorized),
        own_account_rows=_own_account_movements(
            currency, in_period, pairing_window, max_days_apart, own, account_filter
        ),
        not_spending_rows=not_spending_rows,
        early_counted_here=counted_here,
        early_counted_next_month=counted_next,
        expenses=expenses,
    )


# -----------------------------------------------------------------------------
# MoneyMonth.kt
# -----------------------------------------------------------------------------


@dataclass
class AccountSpend:
    """`MoneyMonth.AccountMonthSpend` for one physical account."""

    account_ids: frozenset[str]
    budget: BudgetVsActual

    @property
    def name(self) -> str:
        return max(self.account_ids, key=lambda i: (len(i), i))

    @property
    def total_cents(self) -> int:
        return self.budget.spent_cents

    @property
    def unverified_cents(self) -> int:
        return sum(line.unverified_cents for line in self.budget.lines if line.actual_cents)

    @property
    def categories(self) -> list[tuple[str, int]]:
        spent = [(bl.category, bl.actual_cents) for bl in self.budget.lines if bl.actual_cents]
        return sorted(spent, key=lambda c: (-c[1], c[0]))



def cluster_accounts(rows: Sequence[Txn]) -> list[tuple[str, frozenset[str]]]:
    """`MoneyMonth.clusterAccounts`: one cluster per currency per card."""
    clusters: list[tuple[str, set[str]]] = []
    for currency, account in sorted({(t.currency, t.account_id) for t in rows}, key=lambda p: p[1]):
        existing = next(
            (c for c in clusters if c[0] == currency and any(same_card(i, account) for i in c[1])),
            None,
        )
        if existing is not None:
            existing[1].add(account)
        else:
            clusters.append((currency, {account}))
    return [(currency, frozenset(ids)) for currency, ids in clusters]


def account_month_results(
    rows: Sequence[Txn], month: Month, not_spending: Iterable[str]
) -> list[AccountSpend]:
    """`MoneyMonth.buildAccountMonthResults`: one entry per account active in
    `month`, sorted by name."""
    not_spending = set(not_spending)
    out = []
    for currency, ids in cluster_accounts(rows):
        pairing_window, in_period = budget_month_rows(rows, currency, month)
        if not any(any(same_card(i, t.account_id) for i in ids) for t in in_period):
            continue
        own = {t.account_id for t in rows if t.currency == currency}
        budget = build_budget_vs_actual(
            currency, month, in_period, pairing_window, {}, MAX_DAYS_APART, own, ids, not_spending
        )
        out.append(AccountSpend(ids, budget))
    return sorted(out, key=lambda a: a.name.lower())


# -----------------------------------------------------------------------------
# LedgerCoverage.coversMonthWithoutGaps
# -----------------------------------------------------------------------------


def covers_month_without_gaps(windows: Sequence[tuple[dt.date, dt.date]], month: Month) -> bool:
    """Day-granular port: windows are inclusive date ranges; adjacent ones may
    touch within one day; an empty list never covers anything."""
    if not windows:
        return False
    ordered = sorted(windows)
    if ordered[0][0] > month.first:
        return False
    reach = ordered[0][1]
    for start, end in ordered[1:]:
        if (start - reach).days > 1:
            return False
        reach = max(reach, end)
    return reach >= month.last


# -----------------------------------------------------------------------------
# The endpoint's read
# -----------------------------------------------------------------------------


class BadRequest(ValueError):
    pass


def resolve_month(raw_month: str | None, raw_tz: str | None, today_utc: dt.datetime) -> Month:
    """`month` if given, else the current month in `tz` (falling back to UTC
    when `tz` is absent or not a zone this server knows)."""
    if raw_month:
        try:
            return Month.parse(raw_month)
        except ValueError as exc:
            raise BadRequest(
                f"{raw_month!r} is not a month. Use YYYY-MM, e.g. 2026-10. Nothing was computed."
            ) from exc
    zone = dt.UTC
    if raw_tz:
        try:
            zone = ZoneInfo(raw_tz)
        except (ZoneInfoNotFoundError, ValueError):
            zone = dt.UTC
    return Month.of(today_utc.astimezone(zone).date())


def household_spend(queryset_rows, categories, targets, statements, month: Month, currency: str):
    """The response body from already-scoped rows. Pure over its inputs so
    the endpoint test and the parity test exercise the same code.

    - `queryset_rows`: dicts with `id`, `created_at`, `account_last4`,
      `account_nickname`, `currency`, `txn_date`, `description`,
      `amount_cents`, `effective_category`, `provenance`.
    - `categories`: dicts with `name`, `excluded_from_spend`.
    - `targets`: dicts with `category`, `currency`, `amount_cents`,
      `effective_from_month` (a date).
    - `statements`: dicts with `account_last4`, `currency`, `period_start`,
      `period_end`.
    """
    ordered = sorted(queryset_rows, key=lambda r: (r["created_at"], str(r["id"])))
    txns = [
        Txn(
            id=index,
            account_id=row["account_last4"],
            amount_cents=row["amount_cents"],
            txn_date=row["txn_date"],
            description=row["description"],
            category=row["effective_category"],
            currency=row["currency"],
            unreconciled=row["provenance"] == "UNRECONCILED",
        )
        for index, row in enumerate(ordered)
    ]
    not_spending = {c["name"] for c in categories if c["excluded_from_spend"]}

    # One currency's figure: every function below already narrows to it, so
    # filtering first changes no cent and keeps another currency's cards out.
    txns = [t for t in txns if t.currency == currency]
    accounts = []
    for account in account_month_results(txns, month, not_spending):
        last4 = account.name
        own_rows = [r for r in ordered if r["account_last4"] == last4 and r["currency"] == currency]
        accounts.append(
            {
                "account_last4": last4,
                "label": _label(own_rows, last4),
                "spend_cents": account.total_cents,
                "unverified": any(
                    line.has_provisional_rows for line in account.budget.lines if line.actual_cents
                ),
                "unverified_cents": account.unverified_cents,
                "latest_row_at": max((r["created_at"] for r in own_rows), default=None),
            }
        )

    pairing_window, in_period = budget_month_rows(txns, currency, month)
    own = {t.account_id for t in txns if t.currency == currency}
    month_targets = _targets_for(targets, currency, month)
    whole = build_budget_vs_actual(
        currency, month, in_period, pairing_window, month_targets, MAX_DAYS_APART, own, None,
        not_spending,
    )
    active = {a["account_last4"] for a in accounts}
    windows: dict[str, list[tuple[dt.date, dt.date]]] = {}
    for statement in statements:
        if statement["currency"] == currency:
            windows.setdefault(statement["account_last4"], []).append(
                (statement["period_start"], statement["period_end"])
            )
    complete = bool(active) and all(
        covers_month_without_gaps(windows.get(last4, []), month) for last4 in active
    )
    return {
        "month": str(month),
        "currency": currency,
        "accounts": accounts,
        "categories": [
            {
                "category": line.category,
                "spend_cents": line.actual_cents,
                "target_cents": month_targets.get(line.category),
                "unverified": line.has_provisional_rows,
            }
            for line in whole.lines
        ],
        "uncategorised_cents": whole.uncategorized_cents,
        "uncategorised_unverified": whole.uncategorized_has_provisional_rows,
        "excluded": {
            "not_spending_cents": whole.not_spending_cents,
            "not_spending_categories": whole.not_spending_categories,
            "own_account_moves_cents": whole.own_account_cents,
            "early_charges_moved_cents": -sum(
                t.amount_cents for t in whole.early_counted_here + whole.early_counted_next_month
            ),
            "early_charges_counted_here_cents": -sum(
                t.amount_cents for t in whole.early_counted_here
            ),
            "early_charges_counted_next_month_cents": -sum(
                t.amount_cents for t in whole.early_counted_next_month
            ),
        },
        "complete": complete,
    }


def _label(rows, last4: str) -> str:
    """Spec D5: the most recent server-ingested `account_nickname` for the
    card, else "Card ending 7823". A phone-uploaded row's nickname is the
    phone's raw account id (digits, `LedgerReconcile`), never a label, so a
    nickname with no letter in it is not offered as one."""
    named = [r for r in rows if any(ch.isalpha() for ch in (r.get("account_nickname") or ""))]
    if not named:
        return f"Card ending {last4}"
    return max(named, key=lambda r: (r["created_at"], str(r["id"])))["account_nickname"]


def _targets_for(targets, currency: str, month: Month) -> dict[str, int]:
    """`BudgetTargetDao.currentTargets`: per category, the latest target
    effective on or before the month's first day, for this currency."""
    latest: dict[str, tuple[dt.date, int]] = {}
    for target in targets:
        if target["currency"] != currency or target["effective_from_month"] > month.first:
            continue
        seen = latest.get(target["category"])
        if seen is None or target["effective_from_month"] >= seen[0]:
            latest[target["category"]] = (target["effective_from_month"], target["amount_cents"])
    return {category: cents for category, (_, cents) in latest.items()}
