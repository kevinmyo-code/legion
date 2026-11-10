"""Postgres row-level security as a backstop under the Python choke point (ADR 0045's second
layer; architecture review 2026-11-10, upgrade #11).

ADR 0045 scopes every query to the request's household in Python (`household.tenancy`). That is
one filter, and the same database now holds two families and public recruiter visitors. This
module adds the second layer the ADR named: Postgres itself refuses a row from another
household, even when a view forgets its filter.

**How it works.**

- A Postgres role, `legion_app` (`RLS_ROLE`), with NOBYPASSRLS, that does not own any table.
  Creating it is the operator's job, once (`deploy/rls/README.md`); nothing in this repo creates
  a role on a live database.
- Policies on the tables in `RLS_TABLES`, created by `household/migrations/0009_rls_backstop.py`
  through `apply_rls()`: a row is visible and writable only when its `household_id` equals the
  transaction's `app.household_id` setting. Unset means no household, which matches nothing:
  it fails closed.
- `RlsMiddleware` (`household/rls_middleware.py`), when `LEGION_RLS=on`, wraps each request in a
  transaction and runs `SET LOCAL ROLE legion_app` in it. The household is bound as soon as the
  request's user is known: the middleware binds a session user, `DeviceTokenAuthentication`
  binds a token user, and the demo's `/enter` binds the visit household it just made.
- Everything that is not a request (management commands, Cloud Run Job executions, migrations,
  the shell) keeps the connecting role, which owns the tables, so the policies do not apply to
  it. That is the "job/bypass path": jobs legitimately read every household (the nightly
  backup, consolidation, pruning demo visits).

**Why `SET LOCAL ROLE` and not a second login.** One `DATABASE_URL`, one connection pool, and the
switch is a flag: `LEGION_RLS=off` is exactly today's behaviour. The cost is that it guards
against a Python bug (a missing filter), not against SQL injection, since injected SQL could
`RESET ROLE`. The stronger form, the service logging in as `legion_app` directly with jobs on a
separate owner `DATABASE_URL`, is the follow-up step in `deploy/rls/README.md`.

**Policies are `TO PUBLIC`, deliberately.** They then exist without the role (a fresh clone, the
test database before its fixture runs) and need no role name in a migration. Who they bind:
any role that is not the owner and has no BYPASSRLS. On the live database that is `legion_app`,
and Supabase's own `anon` / `authenticated` roles. For those two this only narrows: the legacy
tables already had RLS on with Supabase-era policies for `authenticated` (permissive policies
OR together; ours is false for a Supabase client, which cannot set `app.household_id`), and a
Django-managed table that had RLS off now refuses them entirely.
"""
from __future__ import annotations

from contextlib import contextmanager

from household.tenancy import TENANT_TABLES

RLS_ROLE = "legion_app"
HOUSEHOLD_SETTING = "app.household_id"
USER_SETTING = "app.user_id"
POLICY = "legion_household"
SHARED_READ_POLICY = "legion_shared_read"
NULL_OWN_POLICY = "legion_null_own"

# Phase 1: every tenant table the recruiter demo can reach (`household.recruiter_routes`
# RECRUITER_ALLOWED and RECRUITER_READ_ONLY, the `allows_recruiter` assistant doors, the changes
# feed minus the body and memory aspects it refuses a visitor, and what `/api/demo/enter` seeds).
# Phase 2 is the rest of TENANT_TABLES (body, memory, vault, Claude, watches, open loops, push,
# books, briefs, ingest); it adds names here and a migration that calls apply_rls() again.
RLS_TABLES: tuple[str, ...] = (
    # dates
    "events",
    "event_skips",
    "suggestion_pins",
    # lists and the bought log
    "checklists",
    "checklist_items",
    "checklist_ticks",
    "purchases",
    # places, notes
    "places",
    "voice_notes",
    "item_lists",
    "list_items",
    # ledger
    "categories",
    "category_rules",
    "budget_targets",
    "statements",
    "ledger_transactions",
    "ledger_transaction_categories",
    "merchant_aliases",
    # household finance (GET only for a visitor)
    "finance_members",
    "finance_accounts",
    "finance_balance_snapshots",
    "finance_income_plans",
    "finance_policies",
    # pantry
    "grocery_staples",
    "receipts",
    "receipt_line_items",
    # fleet
    "vehicles",
    "vehicle_specs",
    "drives",
    "drive_reassignments",
    "code_events",
    "code_clear_events",
    "obd_samples",
    "oil_analyses",
    "service_history",
    "maintenance_schedules",
    "chassis_quirks",
    "build_entries",
    # procedures (the run inspector; `/enter` seeds the visit's own)
    "procedures",
    "procedure_versions",
    "procedure_runs",
    "procedure_steps",
    # the assistant doors a visitor may use
    "assistant_companions",
    "assistant_calls",
    "device_logs",
)

# A built-in procedure (and its versions) has NO household and is visible to every household
# (`procedures.models.visible_to`). Reads only: writing one is a management command's job.
SHARED_READ_TABLES: frozenset[str] = frozenset({"procedures", "procedure_versions"})

# A recruiter visitor's conversation record is stored with NO household (`device_logs`,
# `demo_visitor=True`, `devicelogs/web_conversation.py`): the visit household is deleted when
# the visit ends and the record must outlive it. A request may write and read back such a row
# only when it is its OWN user's (`user_id` = the request's user); the prune job reads all of
# them as the owner. (INSERT ... RETURNING checks the SELECT policy too, so insert-only would
# refuse Django's own create().)
NULL_OWN_TABLES: frozenset[str] = frozenset({"device_logs"})

_CURRENT = f"nullif(current_setting('{HOUSEHOLD_SETTING}', true), '')::uuid"
_CURRENT_USER = f"nullif(current_setting('{USER_SETTING}', true), '')::uuid"


def _schema_of(cursor, table: str) -> str | None:
    cursor.execute(
        "select n.nspname from pg_class c join pg_namespace n on n.oid = c.relnamespace "
        "where c.relname = %s and c.relkind in ('r', 'p') and n.nspname in ('public', 'django') "
        "order by n.nspname = 'public' desc limit 1",
        [table],
    )
    row = cursor.fetchone()
    return row[0] if row else None


def policy_statements(qualified: str, table: str) -> list[str]:
    """The DDL for one table, idempotent (drop-if-exists, then create)."""
    stmts = [
        f"alter table {qualified} enable row level security",
        f"drop policy if exists {POLICY} on {qualified}",
        f"create policy {POLICY} on {qualified} for all to public "
        f"using (household_id = {_CURRENT}) with check (household_id = {_CURRENT})",
        f"drop policy if exists {SHARED_READ_POLICY} on {qualified}",
        f"drop policy if exists {NULL_OWN_POLICY} on {qualified}",
    ]
    if table in SHARED_READ_TABLES:
        stmts.append(
            f"create policy {SHARED_READ_POLICY} on {qualified} for select to public "
            f"using (household_id is null)"
        )
    if table in NULL_OWN_TABLES:
        own = f"(household_id is null and user_id = {_CURRENT_USER})"
        stmts.append(
            f"create policy {NULL_OWN_POLICY} on {qualified} for all to public "
            f"using {own} with check {own}"
        )
    return stmts


def apply_rls(cursor, tables=RLS_TABLES) -> list[str]:
    """Enable RLS and (re)create the policies on every table in `tables` that exists.

    Returns the names that do not exist yet. On the live database that is none; on the pytest
    database at `migrate` time it is the legacy tables, which `tests/conftest.py` creates
    afterwards and then calls this again (the same pattern as `demo_visit_sql`)."""
    missing = []
    for table in tables:
        if table not in TENANT_TABLES:
            raise ValueError(f"{table} is not in household.tenancy.TENANT_TABLES")
        schema = _schema_of(cursor, table)
        if schema is None:
            missing.append(table)
            continue
        for stmt in policy_statements(f'"{schema}"."{table}"', table):
            cursor.execute(stmt)
    return missing


def drop_rls(cursor, tables=RLS_TABLES) -> None:
    """Undo apply_rls: drop our policies, and turn RLS off only on a table left with no policy
    at all. A legacy table keeps RLS on with its Supabase-era `household_all` policy, as it was
    before this existed."""
    for table in tables:
        schema = _schema_of(cursor, table)
        if schema is None:
            continue
        q = f'"{schema}"."{table}"'
        for name in (POLICY, SHARED_READ_POLICY, NULL_OWN_POLICY, "legion_null_insert"):
            cursor.execute(f"drop policy if exists {name} on {q}")
        cursor.execute(
            "select count(*) from pg_policies where schemaname = %s and tablename = %s",
            [schema, table],
        )
        if cursor.fetchone()[0] == 0:
            cursor.execute(f"alter table {q} disable row level security")


# ---------------------------------------------------------------- per-request binding


def _connection():
    from django.db import connection

    return connection


def is_active() -> bool:
    """True inside a request that RlsMiddleware has put under the RLS role."""
    return bool(getattr(_connection(), "legion_rls_active", False))


def bind_household(household_id, user_id=None) -> None:
    """Point this request's transaction at one household (and, for the `NULL_OWN_TABLES`, one
    user). A no-op outside RlsMiddleware (jobs, tests that call views directly, LEGION_RLS=off),
    so callers need no flag of their own."""
    if not is_active():
        return
    with _connection().cursor() as cursor:
        cursor.execute(
            "select set_config(%s, %s, true), set_config(%s, %s, true)",
            [
                HOUSEHOLD_SETTING, str(household_id) if household_id else "",
                USER_SETTING, str(user_id) if user_id else "",
            ],
        )


def bind_user(user) -> None:
    """bind_household for a user's own household (none for an anonymous user)."""
    if not is_active():
        return
    if not getattr(user, "is_authenticated", False):
        bind_household(None)
        return
    household = getattr(user, "household", None)
    bind_household(getattr(household, "id", None), getattr(user, "pk", None))


@contextmanager
def as_household(household_id):
    """Run a block bound to `household_id`, then put the request's own binding back.

    For server code that writes into a household the request has just CREATED (the built-in
    lists a new household gets, a demo visit's seed), never for reading someone else's: the id
    comes from the row the code made, not from the request."""
    if not is_active():
        yield
        return
    with _connection().cursor() as cursor:
        cursor.execute(
            "select current_setting(%s, true), current_setting(%s, true)",
            [HOUSEHOLD_SETTING, USER_SETTING],
        )
        prev_household, prev_user = cursor.fetchone()
    with _connection().cursor() as cursor:
        cursor.execute("select set_config(%s, %s, true)", [HOUSEHOLD_SETTING, str(household_id)])
    try:
        yield
    finally:
        if not _connection().needs_rollback:
            with _connection().cursor() as cursor:
                cursor.execute(
                    "select set_config(%s, %s, true), set_config(%s, %s, true)",
                    [HOUSEHOLD_SETTING, prev_household or "", USER_SETTING, prev_user or ""],
                )
