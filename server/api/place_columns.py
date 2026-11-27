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


# The label cap (django-engine ticket 14): `api/places.LABEL_MAX_LENGTH` in
# SQL, so a writer that is not `PlaceSerializer` is held to it too. The number
# is written out rather than imported so this migration's SQL cannot change
# under an applied database; `tests/test_places_label_check.py` fails if the
# two ever disagree. `length(label)`, not `trim`: the serializer measures the
# stored string, and so does this.
#
# NOT VALID first, VALIDATE only when no row breaks it. Every writer has
# capped labels at 30 since the column existed (the phone's `normalizeLabel`,
# then the serializer), so live data is expected clean, but nobody can read
# prod from here and a CHECK that fails on one stray row fails the deploy.
# Such a row is not renamed or cut short here: the label is the place's
# identity (URL key, `events.trigger_place_label`, the geofence requestId),
# and truncating it could collide with another place's label. It stays,
# unvalidated, and the next run validates once it is gone. Postgres still
# checks a NOT VALID constraint on UPDATE, so until then that one row cannot
# be tombstoned or renamed by the API.
LABEL_CHECK_NAME = "places_label_length"

LABEL_ADD_SQL = f"""
alter table public.places drop constraint if exists {LABEL_CHECK_NAME};
alter table public.places add constraint {LABEL_CHECK_NAME}
    check (length(label) <= 30) not valid;
"""

LABEL_DROP_SQL = f"""
alter table if exists public.places drop constraint if exists {LABEL_CHECK_NAME};
"""


def add_place_label_check(cursor) -> str | None:
    """Adds `places_label_length`, validated when every row passes. None
    when it is on and validated, or why not."""
    if not _table_exists(cursor, "places"):
        return "places: public.places does not exist here; nothing added."
    cursor.execute(LABEL_ADD_SQL)
    cursor.execute("select count(*) from public.places where length(label) > 30")
    overlong = cursor.fetchone()[0]
    if overlong:
        return (
            f"places: {overlong} place label(s) over 30 characters, so "
            f"{LABEL_CHECK_NAME} holds new writes but is not validated."
        )
    cursor.execute(f"alter table public.places validate constraint {LABEL_CHECK_NAME}")
    return None
