"""Additive columns on `public.events` from the web revamp (spec
`.scratch/web-revamp/spec.md`), one function per ticket.

`events` is a Supabase-era table (`managed = False`, `legacy/models/dates.py`),
so its DDL is SQL run by a Django migration (ADR 0044), the same shape as
`ingest/category_flags.py`: each function is guarded on the table existing,
so the migration is a no-op on the pytest database at `migrate` time, and
`tests/conftest.py` calls the same function once the legacy tables exist.
Every column here is nullable or defaulted, so every existing row reads
exactly as it did.

**Ticket 14, `remind_minutes_before`.** How long before an event starts its
reminder fires, in minutes. Null is no reminder. The allowed set is a CHECK in
SQL as well as a refusal in the serializer, because an integrity rule that
must hold even if Django has a bug is SQL shipped by a migration (CLAUDE.md
section 7), and push dispatch (spec D7) will read the value without the
serializer in the way.
"""

from __future__ import annotations

# Spec D7: at start, 5, 10, 15, 30 minutes, 1 h, 2 h, 1 day. The serializer
# names this set in its 400, and the CHECK below is built from it, so the two
# cannot disagree.
REMIND_MINUTES_CHOICES: tuple[int, ...] = (0, 5, 10, 15, 30, 60, 120, 1440)

REMIND_CHECK_NAME = "events_remind_minutes_before_allowed"

REMIND_ADD_SQL = f"""
alter table public.events
    add column if not exists remind_minutes_before integer;
alter table public.events drop constraint if exists {REMIND_CHECK_NAME};
alter table public.events add constraint {REMIND_CHECK_NAME}
    check (remind_minutes_before is null
           or remind_minutes_before in ({", ".join(str(m) for m in REMIND_MINUTES_CHOICES)}));
"""

REMIND_DROP_SQL = """
alter table if exists public.events drop column if exists remind_minutes_before;
"""


def _table_exists(cursor, table: str) -> bool:
    cursor.execute("select to_regclass(%s)", [f"public.{table}"])
    return cursor.fetchone()[0] is not None


def add_remind_minutes_before(cursor) -> str | None:
    """Adds `events.remind_minutes_before` and its CHECK if `events` is there.
    None when it ran, or why not (the pytest database at `migrate` time)."""
    if not _table_exists(cursor, "events"):
        return "events: public.events does not exist here; nothing added."
    cursor.execute(REMIND_ADD_SQL)
    return None


# **Ticket 08, `event_skips.updated_at` and `deleted_at`.** A skip ("not this
# one") has to travel `/api/changes` like any other row, which needs a cursor
# and a tombstone. Existing skips get `updated_at = created_at`, so a client
# pulling from the beginning sees them in the order they were made. The touch
# trigger has its own function in `public` (see TOUCH_FUNCTION_SQL).
SKIPS_ADD_SQL = """
alter table public.event_skips
    add column updated_at timestamptz not null default now(),
    add column if not exists deleted_at timestamptz;
update public.event_skips set updated_at = created_at;
create index if not exists event_skips_household_updated_idx
    on public.event_skips (household_id, updated_at);
"""

# Its own function in `public`, not `private.touch_updated_at()`: the live database role has no
# USAGE on schema `private` (Supabase made it), so even probing that function raised "permission
# denied for schema private" and failed the 2026-10-03 deploy. Same fix as commit 8e697bea
# (category overrides). `create or replace` is idempotent, so there is no existence probe.
TOUCH_FUNCTION_SQL = """
create or replace function public.event_skips_touch_updated_at()
    returns trigger
    language plpgsql
    set search_path = ''
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;
"""

SKIPS_TRIGGER_SQL = """
drop trigger if exists touch_updated_at on public.event_skips;
create trigger touch_updated_at
    before update on public.event_skips
    for each row execute function public.event_skips_touch_updated_at();
"""

SKIPS_DROP_SQL = """
drop trigger if exists touch_updated_at on public.event_skips;
drop index if exists public.event_skips_household_updated_idx;
alter table if exists public.event_skips drop column if exists updated_at;
alter table if exists public.event_skips drop column if exists deleted_at;
drop function if exists public.event_skips_touch_updated_at();
"""


def add_event_skip_sync_columns(cursor) -> str | None:
    """`event_skips.updated_at`, `deleted_at`, an index and the touch trigger,
    if `event_skips` is there. None when it ran, or why not."""
    if not _table_exists(cursor, "event_skips"):
        return "event_skips: public.event_skips does not exist here; nothing added."
    # The backfill runs only in the same step that creates the column: run
    # again later, it would rewrite the `updated_at` of every skip changed
    # since, and the trigger would stamp them all with now().
    cursor.execute(
        "select 1 from information_schema.columns where table_schema = 'public' "
        "and table_name = 'event_skips' and column_name = 'updated_at'"
    )
    if cursor.fetchone() is None:
        cursor.execute(SKIPS_ADD_SQL)
    cursor.execute(TOUCH_FUNCTION_SQL)
    cursor.execute(SKIPS_TRIGGER_SQL)
    return None


# **Event suggestions (Kevin, 2026-10-09).** "Things to do on the weekend, as
# separately coloured events on our calendar." A suggestion is an event row
# with `kind = 'suggestion'`: somewhere the household COULD go, never a plan
# they made. Two CHECKs, both SQL because push dispatch and the phone's
# replica read these rows without the serializer in the way:
#
# - `events_kind_check` widens to the fourth value. Dropped by column rather
#   than by name, the same loop `supabase/migrations/
#   20260901000300_events_kind_completable_axis.sql` uses, so a constraint
#   Postgres auto-named differently on some database is still the one
#   replaced.
# - `events_suggestion_is_not_a_plan`: a suggestion never carries a reminder
#   and is never done. A reminder would ring for something nobody agreed to
#   go to, and a tick would say it happened. "Add to my plans" turns it into
#   `kind = 'event'` first; only then can either be set.
KIND_CHOICES: tuple[str, ...] = ("reminder", "event", "task", "suggestion")
KIND_SUGGESTION = "suggestion"
KIND_CHECK_NAME = "events_kind_check"
SUGGESTION_CHECK_NAME = "events_suggestion_is_not_a_plan"

_DROP_KIND_CHECKS_SQL = """
do $$
declare
    c record;
begin
    for c in
        select con.conname
        from pg_constraint con
        join pg_class rel on rel.oid = con.conrelid
        join pg_namespace nsp on nsp.oid = rel.relnamespace
        join pg_attribute att on att.attrelid = rel.oid and att.attname = 'kind'
        where nsp.nspname = 'public'
          and rel.relname = 'events'
          and con.contype = 'c'
          and con.conkey = array[att.attnum]
    loop
        execute format('alter table public.events drop constraint %I', c.conname);
    end loop;
end
$$;
"""


def _kind_check_sql(kinds: tuple[str, ...]) -> str:
    allowed = ", ".join(f"'{k}'" for k in kinds)
    return (
        _DROP_KIND_CHECKS_SQL + f"alter table public.events add constraint {KIND_CHECK_NAME} "
        f"check (kind in ({allowed}));\n"
    )


SUGGESTION_ADD_SQL = (
    f"alter table public.events drop constraint if exists {SUGGESTION_CHECK_NAME};\n"
    + _kind_check_sql(KIND_CHOICES)
    + f"""alter table public.events add constraint {SUGGESTION_CHECK_NAME}
    check (kind <> '{KIND_SUGGESTION}' or (remind_minutes_before is null and done = false));
"""
)

# Reversing refuses rather than corrupts: narrowing the CHECK fails on any
# suggestion still stored, which is the right answer - delete them, or turn
# them into events, before rolling back.
SUGGESTION_DROP_SQL = (
    f"alter table if exists public.events drop constraint if exists {SUGGESTION_CHECK_NAME};\n"
    + _kind_check_sql(("reminder", "event", "task"))
)


def add_event_suggestions(cursor) -> str | None:
    """Widens `events.kind` to `suggestion` and adds the not-a-plan CHECK, if
    `events` is there. None when it ran, or why not. Needs
    `remind_minutes_before` (`0009`) to exist first."""
    if not _table_exists(cursor, "events"):
        return "events: public.events does not exist here; nothing changed."
    cursor.execute(SUGGESTION_ADD_SQL)
    return None
