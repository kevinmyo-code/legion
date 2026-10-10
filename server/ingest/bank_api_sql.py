"""The SQL that lets a bank's own feed write `public.ledger_transactions`
(ADR 0057, Kevin 2026-10-09: "everything from plaid becomes truth").

Three changes, each shipped by a Django migration (ADR 0044) and each called
again by `tests/conftest.py` once the legacy tables exist, the same pattern as
`ingest/category_flags.py`:

1. **`BANK_API` joins `public.provenance`** (`ensure_provenance_value`,
   migration 0020). The enum type is owned by Supabase's `postgres` role, NOT
   by the live engine role, so on the live database `ALTER TYPE` is refused.
   The migration therefore adds the value only when the role running it may,
   and otherwise refuses in words naming the one statement an operator runs
   first (`supabase/migrations/20261009000100_provenance_bank_api.sql`). It
   refuses BEFORE anything else of this change is applied: it is the first of
   the three migrations, and `migrate` stops at it.

2. **`ledger_transactions` learns bank rows** (`apply_ledger_bank_rows`,
   migration 0021):
   - `ledger_txn_header_matches_provenance` now lets a `BANK_API` row stand
     with no statement header, like `UNRECONCILED`: a bank feed has no printed
     document to hang a row on.
   - `bank_transaction_id` (the feed's own id, unique per household) and
     `bank_pending` (the bank has not posted it yet). Both are only ever set
     on a `BANK_API` row, by check constraint.
   - The immutability trigger is replaced by `public.ledger_transactions_guard()`.
     **Nothing in `private`**: the live role has no USAGE on that schema, so a
     migration that names `private.*` passes the suite and dies on deploy
     (it happened twice). UPDATE is still refused on every row. DELETE is
     allowed on `UNRECONCILED` (rule 7, unchanged) and on `BANK_API` (the
     bank removed or reworded its own transaction, and the sync replaces it),
     and on any row ONLY inside a transaction that has set
     `legion.bank_supersede = 'on'` locally - the one place that does is
     `ingest.plaid_sync.replace_older_rows`, where the bank's feed replaces
     the CSV- and statement-derived rows it now covers (ADR 0057).
"""
from __future__ import annotations

BANK_API = "BANK_API"

# The operator's file for a database where the engine role does not own the
# enum (Supabase: the `postgres` role does).
PROVENANCE_SQL_FILE = "supabase/migrations/20261009000100_provenance_bank_api.sql"

# Read by the trigger. Set with `set_config(name, 'on', true)`: transaction-local,
# so it cannot leak past the one replacement that needed it.
SUPERSEDE_SETTING = "legion.bank_supersede"


def _has_type(cursor) -> bool:
    cursor.execute("select to_regtype('public.provenance')")
    return cursor.fetchone()[0] is not None


def has_bank_api_value(cursor) -> bool:
    cursor.execute(
        "select 1 from pg_enum e join pg_type t on t.oid = e.enumtypid "
        "join pg_namespace n on n.oid = t.typnamespace "
        "where n.nspname = 'public' and t.typname = 'provenance' and e.enumlabel = %s",
        [BANK_API],
    )
    return cursor.fetchone() is not None


def ensure_provenance_value(cursor) -> str | None:
    """Adds `BANK_API` to `public.provenance` when it is missing and this role
    may. None when the value is there afterwards (or was already), a sentence
    when there is no such type here (the pytest database at `migrate` time).
    Raises `RuntimeError`, in words, when the value is missing and this role
    cannot add it.

    `ALTER TYPE ... ADD VALUE` must commit before the value can be used, so the
    migration that calls this is `atomic = False` and the constraint that names
    the value lives in the NEXT migration.
    """
    if not _has_type(cursor):
        return "public.provenance does not exist here; nothing added."
    if has_bank_api_value(cursor):
        return None
    cursor.execute(
        "select pg_has_role(current_user, t.typowner, 'MEMBER') "
        "from pg_type t where t.oid = 'public.provenance'::regtype"
    )
    may_alter = bool(cursor.fetchone()[0])
    if not may_alter:
        raise RuntimeError(
            "Nothing was changed. public.provenance has no BANK_API value, and this database "
            "role does not own the type, so it cannot add one. Run "
            f"{PROVENANCE_SQL_FILE} as the type's owner (Supabase: the postgres role, in the "
            "SQL editor), then run migrate again."
        )
    cursor.execute(f"alter type public.provenance add value if not exists '{BANK_API}'")
    return None


LEDGER_BANK_ROWS_SQL = f"""
alter table public.ledger_transactions
    drop constraint if exists ledger_txn_header_matches_provenance;
alter table public.ledger_transactions
    add constraint ledger_txn_header_matches_provenance check (
        (provenance in ('UNRECONCILED', '{BANK_API}') and statement_id is null)
        or (provenance not in ('UNRECONCILED', '{BANK_API}') and statement_id is not null)
    );

alter table public.ledger_transactions add column if not exists bank_transaction_id text;
alter table public.ledger_transactions
    add column if not exists bank_pending boolean not null default false;

alter table public.ledger_transactions
    drop constraint if exists ledger_txn_bank_fields_only_on_bank_rows;
alter table public.ledger_transactions
    add constraint ledger_txn_bank_fields_only_on_bank_rows check (
        provenance = '{BANK_API}' or (bank_transaction_id is null and not bank_pending)
    );
alter table public.ledger_transactions
    drop constraint if exists ledger_txn_bank_row_has_id;
alter table public.ledger_transactions
    add constraint ledger_txn_bank_row_has_id check (
        provenance <> '{BANK_API}' or bank_transaction_id is not null
    );

create unique index if not exists ledger_transactions_household_bank_txn_uniq
    on public.ledger_transactions (household_id, bank_transaction_id)
    where bank_transaction_id is not null;

create or replace function public.ledger_transactions_guard()
    returns trigger
    language plpgsql
    set search_path = ''
as $$
begin
    if tg_op = 'UPDATE' then
        raise exception
            'Row % in % is immutable. Post a reversal (a new row with reversal_of set) and a '
            'replacement instead of editing it.',
            old.id, tg_table_name
            using errcode = 'restrict_violation';
    end if;

    if tg_op = 'DELETE' then
        -- Rule 7 supersession: a provisional row was never asserted as fact.
        if old.provenance::text = 'UNRECONCILED' then
            return old;
        end if;
        -- ADR 0057: the bank's feed removed or reworded its own transaction.
        if old.provenance::text = '{BANK_API}' then
            return old;
        end if;
        -- ADR 0057: the bank's feed replaces the file-derived rows it covers,
        -- inside the one transaction that asked for it.
        if coalesce(current_setting('{SUPERSEDE_SETTING}', true), '') = 'on' then
            return old;
        end if;
        raise exception
            'Row % in % is immutable and is not provisional. Post a reversal instead of '
            'deleting it.',
            old.id, tg_table_name
            using errcode = 'restrict_violation';
    end if;

    return null;
end;
$$;

drop trigger if exists forbid_mutation on public.ledger_transactions;
create trigger forbid_mutation
    before update or delete on public.ledger_transactions
    for each row execute function public.ledger_transactions_guard();
"""

# Reverse only. Names `private.*`, so it can only run where that schema is
# usable; a forward migrate never reaches it.
LEDGER_BANK_ROWS_DROP_SQL = """
drop trigger if exists forbid_mutation on public.ledger_transactions;
create trigger forbid_mutation
    before update or delete on public.ledger_transactions
    for each row execute function private.forbid_mutation_of_facts();
drop function if exists public.ledger_transactions_guard();
drop index if exists public.ledger_transactions_household_bank_txn_uniq;
alter table public.ledger_transactions drop constraint if exists ledger_txn_bank_row_has_id;
alter table public.ledger_transactions
    drop constraint if exists ledger_txn_bank_fields_only_on_bank_rows;
alter table public.ledger_transactions drop column if exists bank_pending;
alter table public.ledger_transactions drop column if exists bank_transaction_id;
alter table public.ledger_transactions
    drop constraint if exists ledger_txn_header_matches_provenance;
alter table public.ledger_transactions
    add constraint ledger_txn_header_matches_provenance check (
        (provenance = 'UNRECONCILED' and statement_id is null)
        or (provenance <> 'UNRECONCILED' and statement_id is not null)
    );
"""


def apply_ledger_bank_rows(cursor) -> str | None:
    """Applies `LEDGER_BANK_ROWS_SQL` when `ledger_transactions` exists and
    already carries `household_id` (ADR 0045). None when it ran, else why not.
    Raises when the table is there but `BANK_API` is not: the constraint names
    the value, and migration 0020 is what adds it."""
    cursor.execute("select to_regclass('public.ledger_transactions')")
    if cursor.fetchone()[0] is None:
        return "public.ledger_transactions does not exist here; nothing changed."
    if not has_bank_api_value(cursor):
        raise RuntimeError(
            "Nothing was changed. public.provenance has no BANK_API value yet. "
            f"Run {PROVENANCE_SQL_FILE} as the type's owner, then migrate again."
        )
    cursor.execute(LEDGER_BANK_ROWS_SQL)
    return None
