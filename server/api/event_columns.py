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
