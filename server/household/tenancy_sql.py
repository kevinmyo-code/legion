"""The DDL half of ADR 0045, written once and run from three places.

`household/migrations/0002_households_are_tenants.py` runs it against
whatever database `manage.py migrate` is pointed at. `tests/conftest.py`
runs it against the pytest test database, right after
`tests/legacy_test_schema.py` has created the forty legacy tables in their
PRE-tenancy shape - so the suite exercises this module rather than a
hand-written imitation of it. `manage.py tenancy_sql` PRINTS what it would
run and executes nothing, which is how the live database's plan gets
audited before anybody applies it.

**Nothing here is hardcoded from a document.** Index names, unique-key
column lists, partial-index predicates and the foreign keys that decide
which unique keys are ALREADY household-scoped are all read from the
Postgres catalogs at run time. The ticket's own words for why: "the header
comments in `supabase/migrations/` have been wrong about applied state
before". The only inputs are `household.tenancy.TENANT_TABLES` and the
bootstrap household id.

## What it does to one table

1. `ALTER TABLE public.<t> ADD COLUMN household_id uuid NOT NULL DEFAULT
   '<bootstrap>' REFERENCES <schema>.household_household(id)`, then
   `ALTER COLUMN household_id DROP DEFAULT`. The default exists for the
   length of one statement so that existing rows are backfilled by
   Postgres itself in a single pass; dropping it afterwards is what stops
   a later INSERT that forgot the column from silently landing in the
   bootstrap household. That distinction is the whole point: a backfill is
   a one-time fact about rows that predate tenancy, and a default is a
   standing promise to guess.
2. `CREATE INDEX <t>_household_idx ON public.<t>(household_id)`.
3. Re-keys every unique index and unique constraint on the table that is
   not already scoped (see below), so it becomes unique PER HOUSEHOLD
   rather than per server.

## Which unique keys get re-keyed, and how that is decided

ADR 0045 decision 1: "Unique keys that were per-server (`origin_guid`,
`places.label`) become per-household." The ADR names two examples; this
module applies the rule they are examples OF, because the same argument
holds verbatim for `drives.sync_id`, `categories.name`,
`grocery_staples.name`, `ingested_files.content_sha256`,
`events.google_event_id` and the rest: a second household that cannot
create a category called "Groceries" because the first one did is not
isolated from the first one, it is colliding with it.

**A unique key is left alone when one of its columns is a foreign key to
another tenant table.** `obd_samples_natural_key_idx (vehicle_id, pid,
recorded_at)` and `maintenance_schedules_unique_per_vehicle (vehicle_id,
service_name)` are the ticket's own two examples, and the catalog knows the
same thing about `event_skips (event_id, skip_date)`,
`statements (ingested_file_id, account_last4)` and
`checklist_ticks (item_id, day)`. Such a key is already per-household
transitively - the vehicle, the event, the file is - and prepending
`household_id` would add a column that cannot change the key's meaning.

**A unique key another table's foreign key points at is never touched
either**, and that is one more real gap: `list_items.list_origin_guid`
references `item_lists.origin_guid` rather than that table's primary key, so
`item_lists.origin_guid` has to stay unique per SERVER. Dropping it is
refused by Postgres and by Django's `fields.E311` alike. `legacy/models/
notes.py` carries the same note beside the column.

**Primary keys are never touched, and one of them is a real gap.**
`chassis_quirks` is keyed on a human-assigned `quirk_id` text primary key,
so two households cannot both record the quirk `s55-crank-hub`. Re-keying a
primary key is a different and much larger operation (every referencing
row, every ORM assumption about `pk`), and nothing in this ticket asked for
it. It is named here, and in this ticket's report, rather than left to be
discovered: `chassis_quirks` is per-household for READS and per-server for
its identity.
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass, field

from household.tenancy import TENANT_TABLES

HOUSEHOLD_COLUMN = "household_id"
HOUSEHOLD_TABLE = "household_household"


def household_unique_name(table: str, columns) -> str:
    """The name a re-keyed unique index gets.

    A NEW, deterministic name rather than the old one, for two reasons. An
    index called `categories_name_unique` that is actually unique on
    `(household_id, name)` is a stale claim of exactly the kind CLAUDE.md's
    "rulings, not observations" test exists to catch - and the same function
    is called by `legacy/models/*.py`'s `Meta.constraints`, so the model file
    and the database cannot disagree about what the constraint is called.

    Postgres truncates an identifier at 63 bytes SILENTLY, which would turn
    two long names into one collision, so the length is handled here instead
    of being left to the server.
    """
    columns = list(columns)
    base = f"{table}_household_{'_'.join(columns)}_uniq"
    if len(base) <= 63:
        return base
    short = f"{table}_hh_{'_'.join(c[:8] for c in columns)}_uniq"
    if len(short) <= 63:
        return short
    digest = hashlib.sha256(base.encode("utf-8")).hexdigest()[:8]
    return f"{table[:40]}_hh_{digest}_uniq"


@dataclass
class TablePlan:
    table: str
    statements: list = field(default_factory=list)
    notes: list = field(default_factory=list)


@dataclass
class UniqueKey:
    """One unique index on a table, as the catalog describes it."""

    index_name: str
    constraint_name: str | None
    columns: list
    predicate: str | None
    is_primary: bool


def _table_schema(cursor, table: str):
    cursor.execute(
        "select n.nspname from pg_class c join pg_namespace n on n.oid = c.relnamespace "
        "where c.relname = %s and c.relkind = 'r' and n.nspname in ('public', 'django')",
        [table],
    )
    row = cursor.fetchone()
    return row[0] if row else None


def _has_column(cursor, schema: str, table: str, column: str) -> bool:
    cursor.execute(
        "select 1 from information_schema.columns "
        "where table_schema = %s and table_name = %s and column_name = %s",
        [schema, table, column],
    )
    return cursor.fetchone() is not None


def _tenant_fk_columns(cursor, table: str):
    """Columns on `table` that are foreign keys to ANOTHER tenant table.

    A unique key containing one of these is already scoped to a household by
    way of the row it points at - see this module's own doc comment.
    """
    cursor.execute(
        """
        select a.attname, rt.relname
        from pg_constraint c
        join pg_class t on t.oid = c.conrelid
        join pg_namespace n on n.oid = t.relnamespace
        join pg_class rt on rt.oid = c.confrelid
        join unnest(c.conkey) with ordinality as k(attnum, ord) on true
        join pg_attribute a on a.attrelid = c.conrelid and a.attnum = k.attnum
        where c.contype = 'f' and n.nspname = 'public' and t.relname = %s
        """,
        [table],
    )
    return {
        column
        for column, referenced in cursor.fetchall()
        if referenced in TENANT_TABLES and referenced != table
    }


def _fk_target_columns(cursor, table: str):
    """Column sets on `table` that some OTHER table's foreign key points at.

    A unique key over such a set cannot be dropped: Postgres refuses ("cannot
    drop constraint ... because other objects depend on it") and Django's own
    `fields.E311` refuses the model before that. `legacy.ListItem` is the one
    case in this schema - it is a foreign key to `item_lists.origin_guid`
    rather than to that table's primary key - and it is discovered here rather
    than hardcoded, because the next one will not announce itself either.
    """
    cursor.execute(
        """
        select array_agg(a.attname order by k.ord)
        from pg_constraint c
        join pg_class rt on rt.oid = c.confrelid
        join pg_namespace rn on rn.oid = rt.relnamespace
        join unnest(c.confkey) with ordinality as k(attnum, ord) on true
        join pg_attribute a on a.attrelid = c.confrelid and a.attnum = k.attnum
        where c.contype = 'f' and rn.nspname = 'public' and rt.relname = %s
        group by c.oid
        """,
        [table],
    )
    return {tuple(row[0]) for row in cursor.fetchall() if row[0]}


def _unique_keys(cursor, table: str):
    cursor.execute(
        """
        select i.relname,
               con.conname,
               ix.indisprimary,
               pg_get_expr(ix.indpred, ix.indrelid),
               (select array_agg(pg_get_indexdef(ix.indexrelid, k + 1, true) order by k)
                  from generate_series(0, ix.indnkeyatts - 1) as k)
        from pg_index ix
        join pg_class i on i.oid = ix.indexrelid
        join pg_class t on t.oid = ix.indrelid
        join pg_namespace n on n.oid = t.relnamespace
        left join pg_constraint con
               on con.conindid = ix.indexrelid and con.contype in ('u', 'p')
        where n.nspname = 'public' and t.relname = %s and ix.indisunique
        order by i.relname
        """,
        [table],
    )
    return [
        UniqueKey(
            index_name=index_name,
            constraint_name=constraint_name,
            columns=list(columns or []),
            predicate=predicate,
            is_primary=is_primary,
        )
        for index_name, constraint_name, is_primary, predicate, columns in cursor.fetchall()
    ]


def _index_exists(cursor, name: str) -> bool:
    cursor.execute(
        "select 1 from pg_class c join pg_namespace n on n.oid = c.relnamespace "
        "where c.relkind = 'i' and n.nspname = 'public' and c.relname = %s",
        [name],
    )
    return cursor.fetchone() is not None


def plan_table(cursor, table: str, bootstrap_id, household_schema: str) -> TablePlan:
    """Every statement one table needs, in the order it needs them."""
    plan = TablePlan(table=table)
    schema = _table_schema(cursor, table)
    if schema is None:
        # Honest skip, recorded rather than silent. This happens on the pytest
        # test database, where the forty legacy tables do not exist yet when
        # `migrate` runs (they are `managed = False` with no migrations, so
        # `tests/legacy_test_schema.py` creates them AFTERWARDS and then calls
        # this module itself). It must never happen on the live database, and
        # `tests/test_tenancy.py`'s information_schema check is what proves the
        # final state either way.
        plan.notes.append(f"{table}: no such table in public or django; nothing planned.")
        return plan
    if schema != "public":
        plan.notes.append(f"{table}: found in schema {schema!r}, not public; nothing planned.")
        return plan

    if _has_column(cursor, schema, table, HOUSEHOLD_COLUMN):
        plan.notes.append(f"{table}: already has {HOUSEHOLD_COLUMN}; column step skipped.")
    else:
        plan.statements.append(
            f"ALTER TABLE public.{table} "
            f"ADD COLUMN {HOUSEHOLD_COLUMN} uuid NOT NULL DEFAULT '{bootstrap_id}' "
            f"REFERENCES {household_schema}.{HOUSEHOLD_TABLE} (id);"
        )
        plan.statements.append(
            f"ALTER TABLE public.{table} ALTER COLUMN {HOUSEHOLD_COLUMN} DROP DEFAULT;"
        )
        plan.statements.append(
            f"CREATE INDEX {table}_household_idx ON public.{table} ({HOUSEHOLD_COLUMN});"
        )

    scoped_by = _tenant_fk_columns(cursor, table)
    fk_targets = _fk_target_columns(cursor, table)
    for key in _unique_keys(cursor, table):
        if key.is_primary:
            plan.notes.append(
                f"{table}: primary key {key.index_name} left as it is - a primary key is the "
                f"row's identity and re-keying one is not this ticket."
            )
            continue
        if HOUSEHOLD_COLUMN in key.columns:
            plan.notes.append(f"{table}: {key.index_name} is already household-scoped.")
            continue
        if tuple(key.columns) in fk_targets:
            plan.notes.append(
                f"{table}: {key.index_name} left as it is - another table's foreign key "
                f"points at ({', '.join(key.columns)}), so this key cannot be dropped and "
                f"stays unique per SERVER. See legacy/models/notes.py for what that costs."
            )
            continue
        already = sorted(set(key.columns) & scoped_by)
        if already:
            plan.notes.append(
                f"{table}: {key.index_name} left as it is - {', '.join(already)} is a foreign "
                f"key to another tenant table, so the key is already per-household."
            )
            continue
        if any(not column.isidentifier() for column in key.columns):
            raise RuntimeError(
                f"Nothing was changed. {table}.{key.index_name} is a unique index over an "
                f"EXPRESSION ({', '.join(key.columns)}), which this planner does not know how "
                f"to rebuild with household_id prepended. Re-key it by hand and re-run."
            )

        new_name = household_unique_name(table, key.columns)
        columns = ", ".join([HOUSEHOLD_COLUMN, *key.columns])
        if _index_exists(cursor, new_name):
            # The re-keyed index is already there and the old one is beside it.
            # This is what a RE-RUN looks like: a first pass that got as far as
            # creating the new index and then failed, or - the case that found
            # it - a pytest `--reuse-db` run, where `tests/legacy_test_schema.py`
            # re-executes its `create ... if not exists` DDL and puts the
            # pre-tenancy index back on a database that has already been
            # migrated. Dropping the old one and NOT re-creating the new one
            # makes `apply_all` idempotent, which a migration that touches
            # forty-three tables in one transaction has every reason to be.
            plan.statements.append(f"DROP INDEX public.{key.index_name};")
            plan.notes.append(
                f"{table}: {new_name} already exists, so {key.index_name} was dropped and "
                f"nothing was created. This is a re-run."
            )
            continue
        if key.constraint_name:
            # A unique CONSTRAINT owns its index; dropping the index directly is
            # refused by Postgres ("cannot drop index ... because constraint ...
            # requires it"), so the constraint is what gets dropped. A constraint
            # cannot carry a WHERE clause either, so there is no predicate to
            # preserve on this branch.
            plan.statements.append(
                f"ALTER TABLE public.{table} DROP CONSTRAINT {key.constraint_name};"
            )
            plan.statements.append(
                f"ALTER TABLE public.{table} ADD CONSTRAINT {new_name} UNIQUE ({columns});"
            )
        else:
            where = f" WHERE {key.predicate}" if key.predicate else ""
            plan.statements.append(f"DROP INDEX public.{key.index_name};")
            plan.statements.append(
                f"CREATE UNIQUE INDEX {new_name} ON public.{table} ({columns}){where};"
            )
    return plan


# Where Django puts its own tables (`legion/settings.py` points the default
# connection's `search_path` at `django,public` so its bookkeeping never lands
# in `public`, which `supabase/migrations/` owns). Used only by `plan_all` when
# it is asked to plan AHEAD of the migration that creates the table.
DJANGO_SCHEMA = "django"


def plan_all(cursor, tables, bootstrap_id, *, before_the_household_table_exists: bool = False):
    """One `TablePlan` per table.

    `before_the_household_table_exists` is for `manage.py tenancy_sql` and for
    nothing else. That command's whole job is to print the plan for a database
    the migration has NOT run against yet - which is the only moment the plan
    is worth auditing - and on such a database `household_household` is not
    there to be found. The migration itself always passes False, because by
    the time its `RunPython` step runs it has already created the table three
    operations earlier; if it could not find it, something is wrong and
    stopping is the right answer.
    """
    household_schema = _table_schema(cursor, HOUSEHOLD_TABLE)
    if household_schema is None:
        if not before_the_household_table_exists:
            raise RuntimeError(
                f"Nothing was changed. {HOUSEHOLD_TABLE} does not exist yet, so there is "
                f"nothing for household_id to reference. Run household migration 0002 first."
            )
        household_schema = DJANGO_SCHEMA
    return [plan_table(cursor, table, bootstrap_id, household_schema) for table in tables]


def apply_all(cursor, tables, bootstrap_id):
    plans = plan_all(cursor, tables, bootstrap_id)
    for plan in plans:
        for statement in plan.statements:
            cursor.execute(statement)
    return plans
