"""The bank's own transaction feed into `ledger_transactions` (ADR 0057).

Kevin, 2026-10-09: *"everything from plaid becomes truth, we retire manual
parsers and csvs and statements etc. no need to verify. automate both checking
and cards, all of it"*, and on screen: *"Plain, no tag at all"*.

So a row from here is `provenance = BANK_API`, has no statement header, passes
no gate, and is shown exactly like a verified row. CLAUDE.md section 4 (as
amended 2026-10-09) still binds every OTHER ingestion path; this module is the
one exception and it is narrow on purpose.

## One sync

1. `/item/get`: the consent expiry and any error on the Item are stored on
   `plaid_items`. An error the person fixes by signing in again
   (`plaid_client.SIGN_IN_ERROR_CODES`) stops the run as `needs_login`, said
   in words on `/api/freshness`.
2. `/transactions/sync` from the stored cursor, every page, restarting the
   whole loop from the starting cursor if Plaid says the data moved mid-way.
3. One transaction applies it all and only then saves the new cursor, so a
   failure re-reads the same changes next time instead of skipping them:
   - **added**: inserted (category from the household's rules at insert, the
     only write the immutability trigger allows);
   - **modified**: the old row is deleted and a new one inserted (the trigger
     refuses UPDATE on every row, and `/api/changes` keys this table on
     `created_at`, so a changed row must be a new row for a client to see it).
     A category someone set on the old row moves to the new one;
   - **pending to posted**: a posted row whose `pending_transaction_id` names
     a stored pending row replaces it, carrying its category;
   - **removed**: deleted.
4. **The feed replaces the file-derived rows it covers** (`replace_older_rows`).

## Money

Plaid's amount is a JSON number, positive when money leaves the account, the
same rule for checking and cards. LEGION's `amount_cents` is negative for money
out (the BofA exports print from the holder's side; `ingest/parsers/
bofa_activity.py`, "Signs"). So the sign flips, and the float is converted with
`Decimal(str(x))`, never float arithmetic (section 4 rule 3: money is integer
cents).
"""
from __future__ import annotations

import datetime
import re
import uuid
from collections import defaultdict
from dataclasses import dataclass, field
from decimal import ROUND_HALF_EVEN, Decimal, InvalidOperation
from typing import Any

from django.db import connection, transaction
from django.db.models import Max, Min
from django.db.models.functions import Now
from django.utils import timezone

from ingest import plaid_client, vault
from ingest.bank_api_sql import SUPERSEDE_SETTING
from ingest.category_rules import category_for_insert, household_rules
from ingest.models import IngestRun, PlaidItem
from legacy.enums import Provenance
from legacy.models.ledger import LedgerTransaction, LedgerTransactionCategory

CURRENCIES = frozenset({"USD", "SGD"})
# How far apart a bank row and an older file row may be dated and still be the
# same transaction (a card posts a day or two after the export dated it).
MATCH_WINDOW_DAYS = 2
# How many times one sync restarts after TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION.
MAX_SYNC_ATTEMPTS = 3
# Older rows the feed replaces. `USER` rows are a person's own entries and are
# never touched.
REPLACEABLE = (Provenance.DETERMINISTIC, Provenance.LLM_RECONCILED, Provenance.UNRECONCILED)

SIGN_IN_SENTENCE = "Bank connection needs you to sign in again."
BANK_SETTINGS_PATH = "/settings/bank"
SIGN_IN_WINDOW = datetime.timedelta(days=14)

_FOUR_DIGITS = re.compile(r"^[0-9]{4}$")


class PlaidDataError(ValueError):
    """A value from Plaid that cannot be stored as it is."""


# =============================================================================
# Money and identity
# =============================================================================


def to_cents(value: Any) -> int:
    """`value` (Plaid's JSON number, or a string or Decimal) as integer cents.

    A float goes through `Decimal(str(value))`: `str` gives the shortest
    decimal that round-trips, so 19.99 is `Decimal("19.99")`, never
    `19.989999999999998436805981327779591083526611328125`. A value with more
    than two places (Plaid sends none for USD) rounds half-even to the cent."""
    if value is None or isinstance(value, bool):
        raise PlaidDataError(f"{value!r} is not an amount.")
    try:
        if isinstance(value, float):
            amount = Decimal(str(value))
        else:
            amount = Decimal(value)
    except (InvalidOperation, TypeError, ValueError) as exc:
        raise PlaidDataError(f"{value!r} is not an amount.") from exc
    if not amount.is_finite():
        raise PlaidDataError(f"{value!r} is not an amount.")
    return int((amount * 100).quantize(Decimal(1), rounding=ROUND_HALF_EVEN))


def ledger_amount_cents(plaid_amount: Any) -> int:
    """Plaid's positive-is-money-out as LEGION's negative-is-money-out."""
    return -to_cents(plaid_amount)


def _date(value: Any) -> datetime.date:
    if isinstance(value, datetime.datetime):
        return value.date()
    if isinstance(value, datetime.date):
        return value
    if isinstance(value, str):
        try:
            return datetime.date.fromisoformat(value[:10])
        except ValueError as exc:
            raise PlaidDataError(f"{value!r} is not a date.") from exc
    raise PlaidDataError(f"{value!r} is not a date.")


def _datetime(value: Any) -> datetime.datetime | None:
    if value is None or value == "":
        return None
    if isinstance(value, datetime.datetime):
        parsed = value
    elif isinstance(value, str):
        parsed = datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))
    else:
        return None
    if timezone.is_naive(parsed):
        parsed = timezone.make_aware(parsed, datetime.UTC)
    return parsed


@dataclass(frozen=True)
class Account:
    account_id: str
    last4: str | None
    nickname: str


def account_record(raw: dict) -> dict:
    """What `plaid_items.accounts` keeps for one account. Never a balance."""
    return {
        "account_id": str(raw.get("account_id") or ""),
        "mask": raw.get("mask"),
        "name": raw.get("name"),
        "official_name": raw.get("official_name"),
        "type": raw.get("type"),
        "subtype": raw.get("subtype"),
    }


def account_map(records: list[dict]) -> dict[str, Account]:
    """account_id -> `account_last4` (Plaid's mask) and nickname (Plaid's
    name). A mask that is not four digits leaves `last4` None, and that
    account's rows are refused with a sentence rather than filed under a
    guessed number."""
    out = {}
    for raw in records:
        account_id = str(raw.get("account_id") or "")
        if not account_id:
            continue
        mask = str(raw.get("mask") or "")
        name = (raw.get("name") or raw.get("official_name") or "").strip() or "Bank account"
        out[account_id] = Account(
            account_id, mask if _FOUR_DIGITS.match(mask) else None, name
        )
    return out


def merge_accounts(stored: list[dict], fresh: list[dict]) -> list[dict]:
    by_id = {r.get("account_id"): r for r in stored if r.get("account_id")}
    for raw in fresh:
        record = account_record(raw)
        if record["account_id"]:
            by_id[record["account_id"]] = record
    return sorted(by_id.values(), key=lambda r: (r.get("name") or "", r["account_id"]))


# =============================================================================
# The report
# =============================================================================


@dataclass
class SyncReport:
    added: int = 0
    modified: int = 0
    removed: int = 0
    unchanged: int = 0
    pending_posted: int = 0
    # Older CSV- and statement-derived rows the feed replaced.
    replaced_older: int = 0
    # Categories carried from a replaced row to the row that replaced it.
    categories_moved: int = 0
    # Sentences: rows refused, categories kept or dropped, and why.
    notes: list[str] = field(default_factory=list)
    # Older rows kept because a category on them matched no bank row.
    kept_older: int = 0

    @property
    def inserted(self) -> int:
        return self.added + self.modified

    def summary(self) -> dict:
        return {
            "added": self.added,
            "modified": self.modified,
            "removed": self.removed,
            "unchanged": self.unchanged,
            "pending_posted": self.pending_posted,
            "replaced_older": self.replaced_older,
            "categories_moved": self.categories_moved,
            "kept_older": self.kept_older,
            "notes": list(self.notes),
        }

    def sentence(self) -> str:
        parts = [
            f"{self.added} new",
            f"{self.modified} changed",
            f"{self.removed} removed by the bank",
        ]
        if self.pending_posted:
            parts.append(f"{self.pending_posted} pending now posted")
        if self.replaced_older:
            parts.append(f"{self.replaced_older} older file rows replaced")
        text = "Bank sync: " + ", ".join(parts) + "."
        if self.kept_older:
            text += (
                f" {self.kept_older} older row(s) were kept because a category set on them "
                f"matched no bank row."
            )
        return text


# =============================================================================
# Categories that travel with a replaced row
# =============================================================================


@dataclass(frozen=True)
class _Override:
    category: str
    source: str
    origin_guid: str | None
    created_at: datetime.datetime | None


def _live_overrides(txn_ids) -> dict:
    rows = LedgerTransactionCategory.objects.filter(
        transaction_id__in=list(txn_ids), deleted_at__isnull=True
    )
    return {
        row.transaction_id: _Override(row.category, row.source, row.origin_guid, row.created_at)
        for row in rows
    }


MOVED, SAME, CONFLICT = "moved", "same", "conflict"


def _put_override(household, target: LedgerTransaction, snap: _Override) -> str:
    """Lay `snap` over `target`. `moved` when it now shows on `target`, `same`
    when `target` already showed that or a person's choice that outranks a
    rule, `conflict` when both are a person's choice and they differ (the
    target's, newer, stands)."""
    existing = LedgerTransactionCategory.objects.filter(transaction=target).first()
    now = timezone.now()
    if existing is None:
        LedgerTransactionCategory.objects.create(
            id=uuid.uuid4(),
            household=household,
            transaction=target,
            category=snap.category,
            source=snap.source,
            created_at=snap.created_at or now,
            updated_at=now,
            deleted_at=None,
            origin_guid=snap.origin_guid,
        )
        return MOVED
    if existing.deleted_at is not None:
        # A tombstone on the target: someone removed a category there. The
        # carried one is the only live choice, so it shows.
        existing.category, existing.source = snap.category, snap.source
        existing.deleted_at, existing.updated_at = None, now
        existing.save(update_fields=["category", "source", "deleted_at", "updated_at"])
        return MOVED
    if existing.category == snap.category:
        person = LedgerTransactionCategory.SOURCE_PERSON
        if snap.source == person and existing.source != snap.source:
            existing.source, existing.updated_at = snap.source, now
            existing.save(update_fields=["source", "updated_at"])
        return SAME
    if snap.source == LedgerTransactionCategory.SOURCE_RULE:
        return SAME
    if existing.source == LedgerTransactionCategory.SOURCE_RULE:
        existing.category, existing.source, existing.updated_at = snap.category, snap.source, now
        existing.save(update_fields=["category", "source", "updated_at"])
        return MOVED
    return CONFLICT


# =============================================================================
# Applying one batch of changes
# =============================================================================


def _build_row(household, txn: dict, accounts: dict[str, Account], rules) -> LedgerTransaction:
    account = accounts.get(str(txn.get("account_id") or ""))
    if account is None:
        raise PlaidDataError("it names an account Plaid did not list")
    if account.last4 is None:
        raise PlaidDataError(
            f"its account ({account.nickname}) has no four-digit number to file it under"
        )
    currency = txn.get("iso_currency_code")
    if currency not in CURRENCIES:
        raise PlaidDataError(f"its currency {currency!r} is not USD or SGD")
    # The bank's own text first, so rules written against the BofA exports
    # keep matching; Plaid's cleaned `name` when the bank's is absent.
    description = (txn.get("original_description") or txn.get("name") or "").strip()
    if not description:
        description = (txn.get("merchant_name") or "").strip() or "(no description from the bank)"
    category, pending_category = category_for_insert(None, description, rules)
    transaction_id = str(txn["transaction_id"])
    return LedgerTransaction(
        id=uuid.uuid4(),
        household=household,
        statement=None,
        account_last4=account.last4,
        account_nickname=account.nickname,
        currency=currency,
        txn_date=_date(txn.get("date")),
        description=description,
        amount_cents=ledger_amount_cents(txn.get("amount")),
        balance_cents=None,
        line_ref=f"plaid:{transaction_id}",
        category=category,
        category_pending=pending_category,
        provenance=Provenance.BANK_API,
        created_at=Now(),
        bank_transaction_id=transaction_id,
        bank_pending=bool(txn.get("pending")),
    )


_COMPARED = (
    "account_last4",
    "account_nickname",
    "currency",
    "txn_date",
    "description",
    "amount_cents",
    "bank_pending",
)


def _same(old: LedgerTransaction, new: LedgerTransaction) -> bool:
    return all(getattr(old, name) == getattr(new, name) for name in _COMPARED)


def apply_changes(
    household, *, added: list, modified: list, removed: list, accounts: list[dict]
) -> SyncReport:
    """Apply one `/transactions/sync` result. The caller holds the transaction."""
    report = SyncReport()
    rules = household_rules(household)
    by_account = account_map(accounts)

    upserts: dict[str, tuple[dict, bool]] = {}
    for txn in added:
        upserts[str(txn["transaction_id"])] = (txn, True)
    for txn in modified:
        upserts[str(txn["transaction_id"])] = (txn, False)
    removed_ids = {str(r["transaction_id"] if isinstance(r, dict) else r) for r in removed}
    predecessors = {
        str(txn["pending_transaction_id"])
        for txn, _ in upserts.values()
        if txn.get("pending_transaction_id")
    }
    wanted = set(upserts) | removed_ids | predecessors
    existing = {
        row.bank_transaction_id: row
        for row in LedgerTransaction.objects.filter(
            household=household, bank_transaction_id__in=list(wanted)
        )
    }

    new_rows: list[LedgerTransaction] = []
    to_delete: set = set()
    carry: list[tuple[Any, str]] = []  # (replaced row id, bank id of its replacement)
    for transaction_id, (txn, is_added) in upserts.items():
        try:
            row = _build_row(household, txn, by_account, rules)
        except (PlaidDataError, KeyError) as exc:
            report.notes.append(
                f"A bank transaction dated {txn.get('date')} was not stored: {exc}."
            )
            continue
        old = existing.get(transaction_id)
        if old is not None and _same(old, row):
            report.unchanged += 1
            continue
        if old is not None:
            to_delete.add(old.id)
            carry.append((old.id, transaction_id))
            report.modified += 1
        elif is_added:
            report.added += 1
        else:
            report.modified += 1
        pending_id = txn.get("pending_transaction_id")
        pending_row = existing.get(str(pending_id)) if pending_id else None
        if pending_row is not None and pending_row.id not in to_delete:
            to_delete.add(pending_row.id)
            carry.append((pending_row.id, transaction_id))
            report.pending_posted += 1
        new_rows.append(row)

    dropped_with_removal = []
    for transaction_id in removed_ids:
        row = existing.get(transaction_id)
        if row is not None and row.id not in to_delete:
            to_delete.add(row.id)
            dropped_with_removal.append(row)
            report.removed += 1

    snaps = _live_overrides(to_delete)
    if to_delete:
        LedgerTransaction.objects.filter(household=household, id__in=list(to_delete)).delete()
    LedgerTransaction.objects.bulk_create(new_rows)

    new_by_bank_id = {row.bank_transaction_id: row for row in new_rows}
    for replaced_id, bank_id in carry:
        snap = snaps.pop(replaced_id, None)
        if snap is None:
            continue
        outcome = _put_override(household, new_by_bank_id[bank_id], snap)
        if outcome == MOVED:
            report.categories_moved += 1
    for row in dropped_with_removal:
        snap = snaps.pop(row.id, None)
        if snap is not None:
            report.notes.append(
                f"The bank removed its transaction of {row.txn_date.isoformat()} "
                f"({row.description}, {row.amount_cents} cents); the category "
                f"{snap.category!r} set on it went with it."
            )
    return report


# =============================================================================
# The feed replaces the file-derived rows it covers
# =============================================================================


def _days_apart(a: datetime.date, b: datetime.date) -> int:
    return abs((a - b).days)


def replace_older_rows(household, report: SyncReport) -> None:
    """For each account and currency the feed covers, every older CSV- or
    statement-derived row dated on or after the EARLIEST bank row is replaced
    by the bank's rows. Rows before that date stay as history.

    Before a row goes, anything attached to it moves to the matching bank row:
    same account, same exact amount in cents, dated within two days, each bank
    row claimed once. Today that is the category override
    (`ledger_transaction_categories`, the only table keyed to a transaction
    besides `reversal_of`). A stored category on the old row (set at its insert)
    is carried as a `rule` override when the bank row has none.

    **A category with no matching bank row is kept, with its row**, and said in
    the report: never silently dropped. A row in a reversal chain is kept too
    (`reversal_of` is ON DELETE RESTRICT). When both rows carry a person's
    category and they differ, the bank row's (set later) stands and the report
    says what the older one was.
    """
    spans = (
        LedgerTransaction.objects.filter(household=household, provenance=Provenance.BANK_API)
        .values("account_last4", "currency")
        .annotate(earliest=Min("txn_date"), latest=Max("txn_date"))
    )
    for span in spans:
        _replace_span(household, report, span["account_last4"], span["currency"], span["earliest"])


def _replace_span(
    household, report: SyncReport, last4: str, currency: str, earliest: datetime.date
) -> None:
    """`replace_older_rows` for one account and currency."""
    older = list(
        LedgerTransaction.objects.filter(
            household=household,
            account_last4=last4,
            currency=currency,
            txn_date__gte=earliest,
            provenance__in=REPLACEABLE,
        ).order_by("txn_date", "created_at", "id")
    )
    if not older:
        return
    older_ids = [row.id for row in older]
    in_chain = set(
        LedgerTransaction.objects.filter(
            household=household, reversal_of__in=older_ids
        ).values_list("reversal_of", flat=True)
    ) | {row.id for row in older if row.reversal_of_id is not None}

    newest_older = max(row.txn_date for row in older)
    pool = list(
        LedgerTransaction.objects.filter(
            household=household,
            provenance=Provenance.BANK_API,
            account_last4=last4,
            currency=currency,
            txn_date__gte=earliest - datetime.timedelta(days=MATCH_WINDOW_DAYS),
            txn_date__lte=newest_older + datetime.timedelta(days=MATCH_WINDOW_DAYS),
        ).order_by("txn_date", "id")
    )
    pool_by_amount: dict[int, list[LedgerTransaction]] = defaultdict(list)
    for row in pool:
        pool_by_amount[row.amount_cents].append(row)
    pool_overrides = _live_overrides([row.id for row in pool])
    snaps = _live_overrides(older_ids)
    claimed: set = set()
    keep: set = set(in_chain)

    def match_for(row: LedgerTransaction) -> LedgerTransaction | None:
        candidates = [
            bank
            for bank in pool_by_amount.get(row.amount_cents, [])
            if bank.id not in claimed and _days_apart(bank.txn_date, row.txn_date)
            <= MATCH_WINDOW_DAYS
        ]
        if not candidates:
            return None
        candidates.sort(
            key=lambda bank: (
                _days_apart(bank.txn_date, row.txn_date),
                bank.bank_pending,
                bank.txn_date,
                str(bank.id),
            )
        )
        return candidates[0]

    # A person's choice first, then a rule's, then a stored category, so a
    # bank row goes to the most deliberate category that matches it.
    def rank(row: LedgerTransaction) -> int:
        snap = snaps.get(row.id)
        if snap is not None:
            return 0 if snap.source == LedgerTransactionCategory.SOURCE_PERSON else 1
        return 2 if row.category else 3

    for row in sorted(older, key=lambda r: (rank(r), r.txn_date, str(r.id))):
        if row.id in in_chain:
            continue
        snap = snaps.get(row.id)
        if snap is None and not row.category:
            continue
        bank = match_for(row)
        if snap is not None:
            if bank is None:
                keep.add(row.id)
                report.kept_older += 1
                report.notes.append(
                    f"Kept the older row of {row.txn_date.isoformat()} ({row.description}, "
                    f"{row.amount_cents} cents, account {last4}): it carries the category "
                    f"{snap.category!r} and no bank row matches it within "
                    f"{MATCH_WINDOW_DAYS} days at that exact amount."
                )
                continue
            claimed.add(bank.id)
            outcome = _put_override(household, bank, snap)
            pool_overrides[bank.id] = pool_overrides.get(bank.id) or snap
            if outcome == MOVED:
                report.categories_moved += 1
            elif outcome == CONFLICT:
                report.notes.append(
                    f"The older row of {row.txn_date.isoformat()} ({row.description}) had "
                    f"the category {snap.category!r}; the bank row that replaced it already "
                    f"had a different category set by a person, which stands."
                )
            continue
        if bank is None:
            continue
        claimed.add(bank.id)
        if bank.id not in pool_overrides and not bank.category:
            stored = _Override(row.category, LedgerTransactionCategory.SOURCE_RULE, None, None)
            if _put_override(household, bank, stored) == MOVED:
                pool_overrides[bank.id] = stored
                report.categories_moved += 1

    doomed = [row_id for row_id in older_ids if row_id not in keep]
    chained = len(in_chain)
    if chained:
        report.notes.append(
            f"Kept {chained} older row(s) on account {last4} that are part of a reversal "
            f"chain; a reversal cannot be deleted."
        )
    if doomed:
        with connection.cursor() as cursor:
            cursor.execute("select set_config(%s, 'on', true)", [SUPERSEDE_SETTING])
        try:
            deleted, _ = LedgerTransaction.objects.filter(
                household=household, id__in=doomed
            ).delete()
        finally:
            with connection.cursor() as cursor:
                cursor.execute("select set_config(%s, 'off', true)", [SUPERSEDE_SETTING])
        report.replaced_older += deleted


# =============================================================================
# One household's sync
# =============================================================================


def needs_sign_in(item: PlaidItem, now: datetime.datetime | None = None) -> bool:
    """The bank wants the person again: Plaid said so, or the consent ends
    within 14 days (BofA's consent lasts 12 months)."""
    now = now or timezone.now()
    if item.needs_sign_in_since is not None:
        return True
    if item.error_code in plaid_client.SIGN_IN_ERROR_CODES:
        return True
    return item.consent_expires_at is not None and item.consent_expires_at - now <= SIGN_IN_WINDOW


def sign_in_sentence(item: PlaidItem, now: datetime.datetime | None = None) -> str:
    now = now or timezone.now()
    text = SIGN_IN_SENTENCE
    expires = item.consent_expires_at
    if expires is not None:
        day = expires.date().isoformat()
        text += (
            f" The bank's consent ended on {day}."
            if expires <= now
            else f" The bank's consent ends on {day}."
        )
    return text + " Open Settings, then Bank connection, and choose Sign in again."


def _mark_sign_in(item: PlaidItem, code: str | None) -> None:
    item.error_code = code or item.error_code
    if item.needs_sign_in_since is None:
        item.needs_sign_in_since = timezone.now()
    item.save(update_fields=["error_code", "needs_sign_in_since"])


def _record_item(item: PlaidItem, info: dict) -> None:
    item.institution_id = info.get("institution_id") or item.institution_id
    item.institution_name = info.get("institution_name") or item.institution_name
    item.consent_expires_at = _datetime(info.get("consent_expiration_time"))
    item.error_code = info.get("error_code")
    item.save(
        update_fields=["institution_id", "institution_name", "consent_expires_at", "error_code"]
    )


def access_token_of(item: PlaidItem) -> str:
    secret = vault.unseal(item.access_token_ciphertext)
    token = secret.get("access_token") if isinstance(secret, dict) else None
    if not token:
        raise vault.VaultUnavailable("The stored bank connection holds no access token.")
    return token


@dataclass
class SyncOutcome:
    outcome: str | None  # None = ok, else needs_login / skipped
    sentence: str
    report: SyncReport | None = None


def sync_item(item: PlaidItem, gw: plaid_client.PlaidGateway) -> SyncOutcome:
    """One Item, end to end. Never raises for a sign-in problem: it is stored
    on the Item and returned as `needs_login`, so a caller's rollback cannot
    lose it. Other Plaid errors raise `PlaidError` (recorded `failed`)."""
    household = item.household
    token = access_token_of(item)
    try:
        info = gw.get_item(token)
    except plaid_client.PlaidError as exc:
        if exc.needs_sign_in:
            _mark_sign_in(item, exc.code)
            return SyncOutcome("needs_login", sign_in_sentence(item))
        raise
    _record_item(item, info)
    if item.error_code in plaid_client.SIGN_IN_ERROR_CODES:
        _mark_sign_in(item, item.error_code)
        return SyncOutcome("needs_login", sign_in_sentence(item))

    start = item.cursor
    for attempt in range(1, MAX_SYNC_ATTEMPTS + 1):
        added, modified, removed, accounts = [], [], [], []
        cursor = start
        try:
            while True:
                page = gw.sync_transactions(token, cursor)
                added += page.get("added") or []
                modified += page.get("modified") or []
                removed += page.get("removed") or []
                accounts = page.get("accounts") or accounts
                cursor = page.get("next_cursor") or cursor
                if not page.get("has_more"):
                    break
            break
        except plaid_client.PlaidError as exc:
            if exc.needs_sign_in:
                _mark_sign_in(item, exc.code)
                return SyncOutcome("needs_login", sign_in_sentence(item))
            if exc.code == plaid_client.MUTATION_DURING_PAGINATION and attempt < MAX_SYNC_ATTEMPTS:
                continue
            raise

    with transaction.atomic():
        merged = merge_accounts(item.accounts or [], accounts)
        report = apply_changes(
            household, added=added, modified=modified, removed=removed, accounts=merged
        )
        replace_older_rows(household, report)
        item.accounts = merged
        item.cursor = cursor
        item.last_synced_at = timezone.now()
        item.needs_sign_in_since = None
        item.save(update_fields=["accounts", "cursor", "last_synced_at", "needs_sign_in_since"])
    return SyncOutcome(None, report.sentence(), report)


def run_sync(run: IngestRun, *, gw: plaid_client.PlaidGateway | None = None, write=None):
    """`run_job`'s function for `Source.PLAID`. Returns the outcome."""
    item = PlaidItem.objects.select_related("household").filter(household=run.household).first()
    if item is None:
        run.error = "No bank is connected. Open Settings, then Bank connection."
        return "skipped"
    if gw is None:
        problem = plaid_client.configuration_problem()
        if problem:
            run.error = problem
            return "skipped"
        gw = plaid_client.gateway()
    result = sync_item(item, gw)
    if result.outcome is not None:
        run.error = result.sentence
        return result.outcome
    report = result.report
    run.rows_written = report.inserted
    run.rows_unchanged = report.unchanged
    if write is not None:
        write(f"  {result.sentence}")
        for note in report.notes:
            write(f"  note: {note}")
    run.last_report = report  # read by the "Sync now" route; never saved
    return None
