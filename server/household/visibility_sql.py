"""The SQL half of ADR 0052, "A row may be private to one member".

`events` and `checklists` carry a nullable `owner_user_id`: null is shared,
set is private to that user. Both also carry `created_by_id`, as does
`checklist_items`, set from the request on create and never accepted from a
body. `household/tenancy.visible()` is the Python half: the read filter every
path for those tables goes through.

**What the database itself guarantees.** A private row's owner is a member of
the row's household. That is an integrity rule that must hold even if Django
has a bug (CLAUDE.md section 7), so it is a trigger shipped by a migration:
`public.legion_owner_is_a_member()`, attached BEFORE INSERT OR UPDATE to both
tables.

**Why a trigger and not the composite foreign key the ticket names.** A
foreign key `(owner_user_id, household_id) -> household_householdmember
(user_id, household_id)` would hold at every instant, including after a
member is removed - and removal is exactly when spec D3 needs it NOT to
hold. Removal tombstones that member's private rows and keeps them private:
a tombstone in `/api/changes` is a full row, so an owner nulled by the
foreign key's `ON DELETE` would turn their tombstoned private rows into
shared ones and hand every title to the rest of the household. The trigger
checks membership at the moment an owner is SET (insert, or an update that
changes the owner or the household), which is the moment the rule is about,
and leaves a removed member's tombstones alone.

`owner_user_id` and `created_by_id` reference `household_user (id)` with
`ON DELETE SET NULL`, as spec D3 rules. On `events` that is SQL; on the
Django-managed `checklists` tables it is Django's `on_delete=SET_NULL`.

`events` is a Supabase-era table (`managed = False`), so its columns are
added here, guarded on the table existing, the same shape as
`ingest/category_flags.py`; `tests/conftest.py` calls `add_event_columns`
once the legacy tables exist. The checklists columns are ordinary Django
`AddField`s (`checklists/migrations/0003_private_rows.py`).
"""

from __future__ import annotations

GUARD_FUNCTION = "public.legion_owner_is_a_member"
GUARD_TRIGGER = "owner_is_a_member"
MEMBER_TABLE = "household_householdmember"
USER_TABLE = "household_user"


def _schema_of(cursor, table: str) -> str:
    cursor.execute(
        "select n.nspname from pg_class c join pg_namespace n on n.oid = c.relnamespace "
        "where c.relname = %s and c.relkind in ('r', 'p') order by n.nspname = 'django' desc "
        "limit 1",
        [table],
    )
    row = cursor.fetchone()
    if row is None:
        raise RuntimeError(
            f"Nothing was changed. There is no {table} table, so a private row's owner "
            f"cannot be checked against anything. household's own migrations create it; "
            f"run them first."
        )
    return row[0]


def guard_function_sql(member_schema: str) -> str:
    return f"""
create or replace function {GUARD_FUNCTION}()
    returns trigger
    language plpgsql
    set search_path = ''
as $$
begin
    if new.owner_user_id is null then
        return new;
    end if;
    if tg_op = 'UPDATE'
       and new.owner_user_id is not distinct from old.owner_user_id
       and new.household_id is not distinct from old.household_id then
        return new;
    end if;
    if not exists (
        select 1 from {member_schema}.{MEMBER_TABLE} m
         where m.user_id = new.owner_user_id and m.household_id = new.household_id
    ) then
        raise exception
            'Nothing was saved. A private row belongs to one member of its household, '
            'and user % is not a member of household %.',
            new.owner_user_id, new.household_id
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
"""


def create_guard_function(cursor) -> None:
    cursor.execute(guard_function_sql(_schema_of(cursor, MEMBER_TABLE)))


def attach_guard(cursor, table: str) -> None:
    cursor.execute(
        f"drop trigger if exists {GUARD_TRIGGER} on public.{table};"
        f"create trigger {GUARD_TRIGGER} before insert or update on public.{table} "
        f"for each row execute function {GUARD_FUNCTION}();"
    )


def event_columns_sql(user_schema: str) -> str:
    return f"""
alter table public.events
    add column if not exists owner_user_id uuid
        references {user_schema}.{USER_TABLE} (id) on delete set null,
    add column if not exists created_by_id uuid
        references {user_schema}.{USER_TABLE} (id) on delete set null;
create index if not exists events_owner_user_idx on public.events (owner_user_id)
    where owner_user_id is not null;
"""


EVENT_COLUMNS_DROP_SQL = f"""
drop trigger if exists {GUARD_TRIGGER} on public.events;
alter table if exists public.events drop column if exists owner_user_id;
alter table if exists public.events drop column if exists created_by_id;
"""


def add_event_columns(cursor) -> str | None:
    """`events.owner_user_id`, `events.created_by_id` and the owner guard, if
    `events` is there. None when it ran, or why not (the pytest database at
    `migrate` time, where the legacy tables do not exist yet)."""
    cursor.execute("select to_regclass('public.events')")
    if cursor.fetchone()[0] is None:
        return "events: public.events does not exist here; nothing added."
    cursor.execute(event_columns_sql(_schema_of(cursor, USER_TABLE)))
    create_guard_function(cursor)
    attach_guard(cursor, "events")
    return None
