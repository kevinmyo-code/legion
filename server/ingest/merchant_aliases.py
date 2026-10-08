"""`merchant_aliases`: a name a person chose for a merchant, shown in place of
the bank's text. Display only.

Kevin, 2026-10-07: the card rows reading "JOHN NAUS MD PA COLLEYVILLE TX"
should show as "Walmart". A gated `ledger_transactions` row is never updated
(`private.forbid_mutation_of_facts`), so the bank's `description` is NEVER
rewritten. Instead a household-scoped rule - substring to display name -
changes what every surface SHOWS. The raw bank text stays stored and stays on
the wire as `description`.

**An alias affects display and nothing else.** Category matching
(`ingest/category_rules.py`), dedup, transfer detection, the section 4 gate
and every ingest path read `description` and never this table. A rule written
against "JOHN NAUS" keeps matching after the row is shown as "Walmart".

**The match is the category rule's** (`ingest/category_rules.first_match`):
the household's live aliases (`deleted_at IS NULL`), oldest first by
`created_at_client` then `id`; the first whose `substring`, uppercased, is
contained in the uppercased `description` wins. Done in SQL with
`strpos(upper(description), upper(substring)) > 0`, which is a literal match:
a `%` or `_` in a substring means itself, with no LIKE escaping to get wrong.
One known edge, stated rather than hidden: Postgres's `upper()` maps one
character to one character, so `ß` stays `ß` here where Python's and
Kotlin's `uppercase()` give `SS`. Bank text is effectively ASCII; a German
street name in a merchant line is where the two could disagree.

**This module holds the DDL and the read**, the `ingest/category_overrides.py`
precedent: `create_table` is run by `ingest/migrations/0016_merchant_aliases.py`
and, on the pytest database, by `tests/conftest.py` - the same function both
times.
"""
from __future__ import annotations

from django.db.models import OuterRef, Subquery
from django.db.models.functions import StrIndex, Upper

from legacy.models.ledger import MerchantAlias

TABLE = "merchant_aliases"


def create_sql(household_schema: str) -> str:
    """The table, its constraints and its `updated_at` trigger. Idempotent.

    `household_schema` is where `household_household` lives (`django` on
    every real engine); found from the catalog by the caller, never assumed.

    Born tenanted (ADR 0045): `household_id` is NOT NULL from the first row,
    and `origin_guid` is unique per HOUSEHOLD under the name
    `household_unique_name` gives it, so `legacy/models/ledger.MerchantAlias`'s
    `Meta.constraints` and this index cannot call one key two things.
    """
    from household.tenancy_sql import household_unique_name

    hh = household_schema
    origin_key = household_unique_name(TABLE, ["origin_guid"])
    return f"""
-- In `public`, never `private`: the live engine role has no USAGE on
-- `private` (the 2026-09-29 deploy failure `ingest/category_overrides.py`
-- records). A function of this table's own, named for it.
create or replace function public.merchant_alias_touch_updated_at()
    returns trigger
    language plpgsql
    set search_path = ''
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

create table if not exists public.{TABLE} (
    id                uuid        primary key default gen_random_uuid(),
    household_id      uuid        not null references {hh}.household_household (id),
    substring         text        not null,
    display_name      text        not null,
    created_at_client timestamptz not null default now(),
    provenance        text        not null default 'USER',
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    deleted_at        timestamptz,
    origin_guid       uuid        not null,
    -- A blank substring is contained in every description, so one alias would
    -- rename the whole ledger.
    constraint {TABLE}_substring_not_blank check (btrim(substring) <> ''),
    constraint {TABLE}_display_name_not_blank check (btrim(display_name) <> '')
);

create index if not exists {TABLE}_household_idx on public.{TABLE} (household_id);
create unique index if not exists {origin_key}
    on public.{TABLE} (household_id, origin_guid);

drop trigger if exists touch_updated_at on public.{TABLE};
create trigger touch_updated_at
    before update on public.{TABLE}
    for each row execute function public.merchant_alias_touch_updated_at();
"""


DROP_SQL = f"""
drop table if exists public.{TABLE};
drop function if exists public.merchant_alias_touch_updated_at();
"""


def create_table(cursor) -> str | None:
    """Creates the table if the ledger is there to show it over.

    Returns None when it ran, or a sentence saying why it did not. It does not
    run on the pytest database at `migrate` time, where the legacy ledger
    tables do not exist yet (`tests/conftest.py` calls this again after it
    creates them, in the same order as `ledger_transaction_categories`). On a
    real engine they always exist, so the skip never happens there - and if
    it did, `tests/test_tenancy.py`'s catalog checks and the first read of the
    ledger would both say so.
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


def with_display_description(queryset):
    """`queryset` of `LedgerTransaction`, annotated with `display_description`:
    the `display_name` of the oldest live alias in the row's own household
    whose substring the row's `description` contains, case-insensitively.
    None when no alias applies. `description` itself is untouched.

    Narrowed to the transaction's household (ADR 0045): another household's
    alias is not a fact about this household's merchants.
    """
    matching = (
        MerchantAlias.objects.filter(household=OuterRef("household"), deleted_at__isnull=True)
        .alias(_at=StrIndex(Upper(OuterRef("description")), Upper("substring")))
        .filter(_at__gt=0)
        .order_by("created_at_client", "id")
    )
    return queryset.annotate(display_description=Subquery(matching.values("display_name")[:1]))
