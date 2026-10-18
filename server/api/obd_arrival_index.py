"""Arrival-order index on `public.obd_samples` (Kevin, 2026-10-17).

`GET /api/fleet/obd_samples/?received_after=&after_id=` walks one vehicle's
samples in `(created_at, id)` order, so a head unit that uploads late is still
found. This index serves exactly that predicate and order.

`obd_samples` is a Supabase-era table (`managed = False`), so its DDL is SQL run
by a Django migration (ADR 0044), the same shape as `api/place_columns.py`: the
function is guarded on the table existing, so the migration is a no-op on the
pytest database at `migrate` time and `tests/conftest.py` calls it once the
legacy tables exist.

Plain `create index`, not CONCURRENTLY: nothing in this repo uses CONCURRENTLY
(it cannot run inside a migration transaction) and the table is ~20k rows, so
the lock is brief. Touches `public` only: the live database role has no USAGE on
`private` (the 2026-10-03 deploy failure, commit 8e697bea).
"""

from __future__ import annotations

INDEX_NAME = "obd_samples_vehicle_arrival_idx"

CREATE_SQL = f"""
create index if not exists {INDEX_NAME}
    on public.obd_samples (vehicle_id, created_at, id);
"""

DROP_SQL = f"""
drop index if exists public.{INDEX_NAME};
"""


def create_arrival_index(cursor) -> str | None:
    """Creates the index if `obd_samples` is there. None when it ran, or why not."""
    cursor.execute("select to_regclass('public.obd_samples')")
    if cursor.fetchone()[0] is None:
        return "obd_samples: public.obd_samples does not exist here; no index created."
    cursor.execute(CREATE_SQL)
    return None
