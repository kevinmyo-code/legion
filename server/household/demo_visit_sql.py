"""The one exemption the gated-row triggers make: a demo visit's own rows may be
deleted (recruiter-demo ticket 13, Kevin 2026-11-02, ADR 0069).

`POST /api/demo/enter` gives each recruiter visit its own copy of the demo
household, seeded through the gate like any other (`household/demo_seed.py`),
and `manage.py prune_demo_visitors` deletes the whole copy when the visit ends.
The four gated tables refuse DELETE on a non-provisional row (CLAUDE.md
section 4: post a reversal, never delete), so without an exemption no visit
could ever be cleaned up.

**The exemption is scoped by household, never by caller.** A DELETE is allowed
only when the row's `household_id` names a household whose `is_demo_visit` is
true. That column is set by `/enter` at creation and by nothing else (no
serializer, no admin form, no view writes it: `Household.is_demo_visit`).
UPDATE stays refused everywhere, the flag's households included. Every other
rule is unchanged and checked FIRST, so Kevin's paths never reach the
household lookup.

Two functions, both in `public`:

- `public.gated_row_guard()`, new, for `statements`, `receipts` and
  `receipt_line_items`. It is `private.forbid_mutation_of_facts` (UPDATE
  refused; DELETE only on `UNRECONCILED`) plus the exemption. It replaces that
  function on those three tables rather than editing it: the live role has no
  USAGE on `private`, so a migration that names `private.*` passes the suite
  and dies on deploy (`ingest/bank_api_sql.py` says it happened twice).
- `public.ledger_transactions_guard()`, redefined: ADR 0057's ladder
  (`UNRECONCILED`, `BANK_API`, `legion.bank_supersede`) exactly as
  `ingest/bank_api_sql.py` wrote it, plus the exemption last.

`private.forbid_mutation_of_facts` itself is left alone; after this nothing
calls it. Both functions are `set search_path = ''`, so the household table is
named with its schema, read from the catalog at migration time (Django's own
tables live in `django` on the live database, `household/tenancy_sql.py`).

Shipped by `household/migrations/0008_demo_visit_delete_exemption.py` and
called again by `tests/conftest.py` once the legacy tables exist, AFTER
`apply_ledger_bank_rows` (which re-creates the ledger guard without the
exemption).
"""
from __future__ import annotations

from ingest.bank_api_sql import BANK_API, SUPERSEDE_SETTING

GATED_TABLES = ("statements", "receipts", "receipt_line_items")
HOUSEHOLD_TABLE = "household_household"


def _household_schema(cursor) -> str | None:
    cursor.execute(
        "select n.nspname from pg_class c join pg_namespace n on n.oid = c.relnamespace "
        "where c.relname = %s and c.relkind = 'r' and n.nspname in ('public', 'django')",
        [HOUSEHOLD_TABLE],
    )
    row = cursor.fetchone()
    return row[0] if row else None


def _demo_visit_clause(schema: str) -> str:
    return f"""
        -- ADR 0069: a demo visit's copy is deleted whole when the visit ends.
        -- Checked last, so no other path ever pays for this lookup.
        if exists (
            select 1 from "{schema}"."{HOUSEHOLD_TABLE}" h
            where h.id = old.household_id and h.is_demo_visit
        ) then
            return old;
        end if;"""


def exemption_sql(schema: str) -> str:
    demo_visit = _demo_visit_clause(schema)
    triggers = "\n".join(
        f"""drop trigger if exists forbid_mutation on public.{table};
create trigger forbid_mutation
    before update or delete on public.{table}
    for each row execute function public.gated_row_guard();"""
        for table in GATED_TABLES
    )
    return f"""
create or replace function public.gated_row_guard()
    returns trigger
    language plpgsql
    set search_path = ''
as $$
begin
    if tg_op = 'UPDATE' then
        raise exception
            'Row % in % is immutable: it came through the reconciliation gate. Post a reversal '
            '(a new row with reversal_of set) and a replacement instead of editing it.',
            old.id, tg_table_name
            using errcode = 'restrict_violation';
    end if;

    if tg_op = 'DELETE' then
        -- Rule 7: a provisional row was never asserted as fact.
        if old.provenance::text = 'UNRECONCILED' then
            return old;
        end if;{demo_visit}
        raise exception
            'Row % in % is immutable: it came through the reconciliation gate and is not '
            'provisional. Post a reversal instead of deleting it.',
            old.id, tg_table_name
            using errcode = 'restrict_violation';
    end if;

    return null;
end;
$$;

{triggers}

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
        end if;{demo_visit}
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


def apply_demo_visit_exemption(cursor) -> str | None:
    """Applies `exemption_sql` when all four gated tables exist and carry
    `household_id`. None when it ran, else why not (the pytest database at
    `migrate` time, where `tests/conftest.py` calls this again later)."""
    for table in (*GATED_TABLES, "ledger_transactions"):
        cursor.execute("select to_regclass(%s)", [f"public.{table}"])
        if cursor.fetchone()[0] is None:
            return f"public.{table} does not exist here; nothing changed."
    cursor.execute(
        "select count(*) from information_schema.columns where table_schema = 'public' "
        "and table_name = any(%s) and column_name = 'household_id'",
        [list((*GATED_TABLES, "ledger_transactions"))],
    )
    if cursor.fetchone()[0] != len(GATED_TABLES) + 1:
        return "The gated tables carry no household_id yet; nothing changed."
    schema = _household_schema(cursor)
    if schema is None:
        raise RuntimeError(
            f"Nothing was changed. {HOUSEHOLD_TABLE} does not exist, so the demo-visit "
            f"exemption has no household to read."
        )
    cursor.execute(exemption_sql(schema))
    return None


def _drop_sql() -> str:
    """Reverse: the three tables back on `private.forbid_mutation_of_facts`, and
    the ledger guard as `ingest/bank_api_sql.py` left it."""
    from ingest.bank_api_sql import LEDGER_BANK_ROWS_SQL

    start = LEDGER_BANK_ROWS_SQL.index("create or replace function public.ledger_transactions_guard")
    ledger_guard = LEDGER_BANK_ROWS_SQL[start:]
    triggers = "\n".join(
        f"""drop trigger if exists forbid_mutation on public.{table};
create trigger forbid_mutation
    before update or delete on public.{table}
    for each row execute function private.forbid_mutation_of_facts();"""
        for table in GATED_TABLES
    )
    return f"{triggers}\ndrop function if exists public.gated_row_guard();\n{ledger_guard}"


def drop_demo_visit_exemption(cursor) -> None:
    cursor.execute("select to_regclass('public.ledger_transactions')")
    if cursor.fetchone()[0] is not None:
        cursor.execute(_drop_sql())
