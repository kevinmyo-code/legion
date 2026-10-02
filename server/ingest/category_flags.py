"""`categories.excluded_from_spend`: the ONE definition of which categories
are not spending.

Kevin, 2026-09-29: "ignore zelle for spending. its just transfer between here
and there." Money moving between the household's own places is not spend, and
a category whose rows are all such movements says so here, once. Every
surface that totals spend reads this flag rather than a name: the phone's
budget (`ledger/LedgerBudget.kt`, from the Room mirror of this column), and
any server figure that is ever written (there is none today; the server
serves rows, not totals).

**A flag, not a hard-coded name.** A name compared in two languages drifts
the day someone renames the category, and a household that files the same
movements under "Moving money" would get no exclusion at all. The flag
travels with the category through the same sync every other category field
uses.

**Excluded is disclosed, never hidden.** A surface that leaves these rows out
of a total says so in words, with the count and the amount - the same posture
`excludedOwnAccountMovementsSentence` takes for card payments.

`categories` is a Supabase-era table (`managed = False`); the column is added
by a Django migration (ADR 0044), `ingest/migrations/0008_categories_excluded_from_spend.py`,
through `add_column` below, which `tests/conftest.py` also calls once the
legacy tables exist. Additive, defaulted false, so every existing category
keeps counting exactly as it did.
"""
from __future__ import annotations

ADD_COLUMN_SQL = """
alter table public.categories
    add column if not exists excluded_from_spend boolean not null default false;
"""

DROP_COLUMN_SQL = """
alter table if exists public.categories drop column if exists excluded_from_spend;
"""


def add_column(cursor) -> str | None:
    """Adds the column if `categories` is there. None when it ran, or why not
    (the pytest database at `migrate` time, where the legacy tables do not
    exist yet)."""
    cursor.execute("select to_regclass('public.categories')")
    if cursor.fetchone()[0] is None:
        return "categories: public.categories does not exist here; nothing added."
    cursor.execute(ADD_COLUMN_SQL)
    return None
