"""Additive columns on `public.places` (Kevin, 2026-10-09: "saving places
seems to be by coordinates now. lets change that to addresses").

`places` is a Supabase-era table (`managed = False`, `legacy/models/places.py`),
so its DDL is SQL run by a Django migration (ADR 0044), the same shape as
`api/event_columns.py`: the function is guarded on the table existing, so the
migration is a no-op on the pytest database at `migrate` time, and
`tests/conftest.py` calls the same function once the legacy tables exist.

**`address`** is the human address of a place: what is shown and spoken
wherever the place is. Coordinates stay, and stay required - geofences,
navigation and distance all need them - so the address is a label FOR the
coordinates, never a replacement. Nullable, because every place saved before
today has none, and because a place saved where the user stands keeps its
coordinates even when the address lookup fails (said in words on the phone,
never guessed). A blank string is refused by CHECK so "no address" has
exactly one spelling, NULL; the serializer folds blank to NULL before it gets
here.

Nothing in `private.*`: the live database role has no USAGE on that schema
(the 2026-10-03 deploy failure, commit 8e697bea).
"""

from __future__ import annotations

ADDRESS_CHECK_NAME = "places_address_not_blank"

ADDRESS_ADD_SQL = f"""
alter table public.places
    add column if not exists address text;
alter table public.places drop constraint if exists {ADDRESS_CHECK_NAME};
alter table public.places add constraint {ADDRESS_CHECK_NAME}
    check (address is null or length(trim(address)) > 0);
"""

ADDRESS_DROP_SQL = """
alter table if exists public.places drop column if exists address;
"""


def _table_exists(cursor, table: str) -> bool:
    cursor.execute("select to_regclass(%s)", [f"public.{table}"])
    return cursor.fetchone()[0] is not None


def add_place_address(cursor) -> str | None:
    """Adds `places.address` and its CHECK if `places` is there. None when
    it ran, or why not (the pytest database at `migrate` time)."""
    if not _table_exists(cursor, "places"):
        return "places: public.places does not exist here; nothing added."
    cursor.execute(ADDRESS_ADD_SQL)
    return None
