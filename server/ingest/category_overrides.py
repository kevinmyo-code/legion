"""`ledger_transaction_categories`: a category laid OVER a gated ledger row.

backend-etl ticket 14, option 2. Kevin, 2026-09-28: "yes 2".

**Why a second table.** `private.forbid_mutation_of_facts` refuses every
UPDATE on `ledger_transactions`, category included, and that rule stays: a
gated row is evidence of what the document said. So two kinds of category
could not reach a stored row:

- the rows stored before the server categorised at insert (about 850 BofA
  lines on 2026-09-28), which keep `category NULL` forever;
- a category a person sets by hand, on the phone or anywhere else.

Both live here instead, one row per transaction, and **every read of a
transaction's category reads the live row here IN PLACE OF the stored one**
(`with_effective_category`). The stored column is never touched.

**The precedence, in one place for the server** (the phone's half is
`app/.../backend/LedgerTransactionsMirror.kt`, `planLedgerMirror`):

1. a live `person` override;
2. a live `rule` override;
3. the row's own stored `category`;
4. nothing.

A `rule` never replaces a live `person` row. The API refuses it in words
(`api/ledger.LedgerTransactionCategorySerializer`), the backfill never
attempts it, and a trigger refuses it in SQL for any writer that forgets.

**This module holds three things, deliberately together:** the DDL (run by
`ingest/migrations/0007_ledger_transaction_categories.py` and, on the pytest
database, by `tests/conftest.py` - the same function both times, the
`household/tenancy_sql.py` precedent), the effective-category read, and the
`apply_category_rules` backfill. The backfill and the read must agree on what
"no category" means, and keeping them in one file is how they do.
"""
from __future__ import annotations

from dataclasses import dataclass

from django.db import connection, transaction
from django.db.models import (
    BooleanField,
    Case,
    Exists,
    F,
    OuterRef,
    Subquery,
    TextField,
    Value,
    When,
)
from django.db.models.functions import Coalesce

from ingest.category_rules import first_match, household_rules
from legacy.models.ledger import LedgerTransaction, LedgerTransactionCategory

TABLE = "ledger_transaction_categories"

# What `category_source` says on the wire when no override is live and the
# row's own column is set. `person` / `rule` come from the override itself.
STORED = "stored"

# The unique key the composite foreign key below needs. `(id)` is already the
# primary key, so `(id, household_id)` is unique by construction; the
# constraint exists only so Postgres will accept it as a foreign-key target.
# Adding it is DDL, not an UPDATE: `forbid_mutation_of_facts` is a row trigger
# and never sees it.
TXN_HOUSEHOLD_KEY = "ledger_transactions_id_household_key"


def create_sql(household_schema: str) -> str:
    """The table, its constraints and its two triggers. Idempotent.

    `household_schema` is where `household_household` lives (`django` on every
    real engine); found from the catalog by the caller, never assumed.

    **No foreign key from `category` to `categories`, deliberately.** The
    brief asked for one or for the reason there is none. There is none because:

    1. **A rule may name a category that no longer exists, and it still
       fires** - on the phone (`LedgerController.applyCategoryRules` does not
       check) and therefore on the server (`ingest/category_rules.py` says so
       and matches it). A foreign key would make the backfill refuse exactly
       the rows the phone categorises, and the two would disagree about the
       same transaction.
    2. **`categories` soft-deletes and renames.** A foreign key can say "a
       row with this name existed once", never "a live category has this
       name", and a rename (`PUT` of a new `name`) would be refused by it.
    3. **The phone sends a new category and an override through different
       outbox queues.** An override that drained first would be refused, and
       a refused outbox entry is retried to poison and then lost.
    4. **None of the category's other homes has one**:
       `ledger_transactions.category`, `category_rules.category` and
       `budget_targets.category` are all free text. An override stricter than
       the value it stands in for would check only the overlay.

    What IS checked in SQL: not blank; `source` is one of the two; one row per
    transaction; **the override and its transaction are in the same
    household** (a composite foreign key, so no bug in Django can lay one
    family's category over another family's row); `transaction_id` never
    moves; and a `rule` never replaces a live `person` row.

    **`on delete cascade` on the transaction.** The only row that can leave
    `ledger_transactions` is an UNRECONCILED one a rule-7 supersession
    deletes; its override has nothing left to describe and goes with it. The
    person's choice on a superseded provisional row is NOT carried to the
    verified row that replaces it - that row is a different transaction, and
    matching the two is its own ticket.
    """
    hh = household_schema
    return f"""
-- Every function here is this table's own and lives in `public`, never
-- `private`: the live engine role (`legion_engine`) has no USAGE on `private`,
-- whose functions `postgres` owns, and the first deploy of this migration
-- failed on exactly that ("permission denied for schema private",
-- 2026-09-29). Same precedent as checklists/migrations/0001_initial.py.
create or replace function public.ledger_category_touch_updated_at()
    returns trigger
    language plpgsql
    set search_path = ''
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

do $$
begin
    if not exists (select 1 from pg_constraint where conname = '{TXN_HOUSEHOLD_KEY}') then
        alter table public.ledger_transactions
            add constraint {TXN_HOUSEHOLD_KEY} unique (id, household_id);
    end if;
end $$;

create table if not exists public.{TABLE} (
    id             uuid        primary key default gen_random_uuid(),
    household_id   uuid        not null references {hh}.household_household (id),
    transaction_id uuid        not null,
    category       text        not null,
    source         text        not null default 'person',
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    deleted_at     timestamptz,
    origin_guid    text,
    constraint {TABLE}_category_not_blank check (length(trim(category)) > 0),
    constraint {TABLE}_source_valid check (source in ('rule', 'person')),
    constraint {TABLE}_one_per_transaction unique (transaction_id),
    constraint {TABLE}_same_household_as_transaction
        foreign key (transaction_id, household_id)
        references public.ledger_transactions (id, household_id)
        on delete cascade
);

create index if not exists {TABLE}_household_idx on public.{TABLE} (household_id);
create unique index if not exists {TABLE}_household_origin_guid_uniq
    on public.{TABLE} (household_id, origin_guid) where origin_guid is not null;

drop trigger if exists touch_updated_at on public.{TABLE};
create trigger touch_updated_at
    before update on public.{TABLE}
    for each row execute function public.ledger_category_touch_updated_at();

create or replace function public.ledger_category_person_outranks_rule()
    returns trigger
    language plpgsql
    set search_path = ''
as $$
begin
    if new.transaction_id <> old.transaction_id then
        raise exception 'ledger_transaction_categories: an override belongs to one transaction and never moves (%)',
            old.transaction_id using errcode = 'check_violation';
    end if;
    if old.source = 'person' and old.deleted_at is null and new.source = 'rule' then
        raise exception 'ledger_transaction_categories: a person set this category, and a rule never replaces it (transaction %)',
            old.transaction_id using errcode = 'check_violation';
    end if;
    return new;
end;
$$;

drop trigger if exists person_outranks_rule on public.{TABLE};
create trigger person_outranks_rule
    before update on public.{TABLE}
    for each row execute function public.ledger_category_person_outranks_rule();
"""


DROP_SQL = f"""
drop table if exists public.{TABLE};
drop function if exists public.ledger_category_person_outranks_rule();
drop function if exists public.ledger_category_touch_updated_at();
alter table if exists public.ledger_transactions drop constraint if exists {TXN_HOUSEHOLD_KEY};
"""


def create_table(cursor) -> str | None:
    """Creates the table if `ledger_transactions` is there to hang it on.

    Returns None when it ran, or a sentence saying why it did not. It does
    not run on the pytest database at `migrate` time, where the legacy tables
    do not exist yet (`tests/conftest.py` calls this again after it creates
    them). On a real engine they always exist, so the skip never happens
    there - and if it did, `tests/test_tenancy.py`'s catalog checks and the
    first read of the ledger would both say so.
    """
    from household.tenancy_sql import HOUSEHOLD_TABLE, _table_schema

    cursor.execute("select to_regclass('public.ledger_transactions')")
    if cursor.fetchone()[0] is None:
        return f"{TABLE}: public.ledger_transactions does not exist here; nothing created."
    household_schema = _table_schema(cursor, HOUSEHOLD_TABLE)
    if household_schema is None:
        raise RuntimeError(
            f"Nothing was created. {HOUSEHOLD_TABLE} does not exist, so {TABLE}.household_id "
            f"would have nothing to reference. Run household migration 0002 first."
        )
    cursor.execute(create_sql(household_schema))
    return None


# =============================================================================
# The read
# =============================================================================


def with_effective_category(queryset):
    """`queryset` of `LedgerTransaction`, annotated with the category every
    reader should show.

    - `effective_category`: the live override's category, else the stored one.
    - `effective_category_pending`: False under a live override (someone or
      something has categorised it), else the stored flag.
    - `category_source`: `person` / `rule` from a live override, `stored` when
      the row's own column is what shows, None when there is no category.

    A tombstoned override is ignored: deleting one puts the stored category
    back in view, which is what DELETE means on this table. The subquery is
    also narrowed to the transaction's own household, which the composite
    foreign key already guarantees; it is repeated so this read does not lean
    on the schema for ADR 0045.
    """
    live = LedgerTransactionCategory.objects.filter(
        transaction=OuterRef("pk"), household=OuterRef("household"), deleted_at__isnull=True
    )
    return queryset.annotate(
        override_category=Subquery(live.values("category")[:1]),
        override_source=Subquery(live.values("source")[:1]),
    ).annotate(
        effective_category=Coalesce(F("override_category"), F("category")),
        effective_category_pending=Case(
            When(override_category__isnull=False, then=Value(False)),
            default=F("category_pending"),
            output_field=BooleanField(),
        ),
        category_source=Case(
            When(override_category__isnull=False, then=F("override_source")),
            When(category__isnull=False, then=Value(STORED)),
            default=Value(None),
            output_field=TextField(),
        ),
    )


# =============================================================================
# The backfill (`manage.py apply_category_rules`)
# =============================================================================


@dataclass(frozen=True)
class BackfillReport:
    household_id: str
    # Stored `category IS NULL` and no live override: what the rules may fill.
    uncategorised: int
    matched: int
    written: int
    # Stored NULL, no live override, but a TOMBSTONED one: someone removed a
    # category on purpose, and the backfill does not put a rule's back.
    left_alone_removed: int
    unmatched: int

    def line(self, dry_run: bool) -> str:
        verb = "would write" if dry_run else "wrote"
        return (
            f"household {self.household_id}: {self.uncategorised} uncategorised, "
            f"{self.matched} matched a rule, {verb} {self.written if not dry_run else self.matched}, "
            f"{self.unmatched} matched no rule, {self.left_alone_removed} left alone "
            f"(an override there was deleted on purpose)."
        )


_INSERT_SQL = f"""
insert into public.{TABLE} (household_id, transaction_id, category, source)
select t.household_id, t.id, v.category, 'rule'
from unnest(%s::uuid[], %s::text[]) as v(transaction_id, category)
join public.ledger_transactions t
  on t.id = v.transaction_id and t.household_id = %s
on conflict (transaction_id) do nothing
"""


def backfill_household(household, *, dry_run: bool) -> BackfillReport:
    """Writes a `source='rule'` override for every transaction of `household`
    whose effective category is empty and which one of the household's rules
    matches. Same rules, same order, same match as the insert path
    (`ingest/category_rules.py`, which is the phone's semantics ported).

    **Idempotent.** A transaction that already has an override row of any
    kind is not a candidate, and the INSERT is `on conflict do nothing`, so a
    second run, or a person's override written between the read and the
    write, changes nothing. **A `person` row is never overwritten**: the only
    write here is an INSERT, and it yields to any row already there.
    """
    rules = household_rules(household)
    any_override = LedgerTransactionCategory.objects.filter(transaction=OuterRef("pk"))
    live_override = any_override.filter(deleted_at__isnull=True)
    rows = list(
        LedgerTransaction.objects.filter(household=household, category__isnull=True)
        .annotate(has_live=Exists(live_override), has_any=Exists(any_override))
        .filter(has_live=False)
        .order_by("created_at", "id")
        .values_list("id", "description", "has_any")
    )

    removed = sum(1 for _id, _desc, has_any in rows if has_any)
    candidates = [(txn_id, desc) for txn_id, desc, has_any in rows if not has_any]
    to_write = []
    for txn_id, description in candidates:
        category = first_match(description, rules)
        if category is not None:
            to_write.append((str(txn_id), category))

    written = 0
    if to_write and not dry_run:
        with transaction.atomic(), connection.cursor() as cursor:
            cursor.execute(
                _INSERT_SQL,
                [[t for t, _ in to_write], [c for _, c in to_write], str(household.id)],
            )
            written = cursor.rowcount

    return BackfillReport(
        household_id=str(household.id),
        uncategorised=len(rows),
        matched=len(to_write),
        written=written,
        left_alone_removed=removed,
        unmatched=len(candidates) - len(to_write),
    )
