"""`public.vehicles.plate`: the car's licence plate (memory-and-vault ticket 06, Kevin 2026-11-05).

A plate is ordinary data, not an ID-vault secret (Kevin's ruling: "low-sensitivity identifiers
(plate, VIN, insurance policy number) are ordinary data"). Kevin's own value is set through the
API after deploy and is never written into code or tests.

`vehicles` is a Supabase-era table (`managed = False`), so its DDL is SQL run by a Django
migration (ADR 0044), the shape of `api/obd_arrival_index.py`: guarded on the table existing, so
`ingest/migrations/0025_vehicle_plate.py` is a no-op on the pytest database at `migrate` time and
`tests/conftest.py` calls it once the legacy tables exist. Additive and nullable: every existing
row reads null, and a client that never sends the field never changes it (`api/fleet.py`).
"""

from __future__ import annotations

PLATE_MAX_CHARS = 16

ADD_SQL = f"""
alter table public.vehicles add column if not exists plate text;
alter table public.vehicles drop constraint if exists vehicles_plate_length;
alter table public.vehicles add constraint vehicles_plate_length
    check (plate is null or char_length(plate) between 1 and {PLATE_MAX_CHARS});
"""

DROP_SQL = """
alter table public.vehicles drop constraint if exists vehicles_plate_length;
alter table public.vehicles drop column if exists plate;
"""


def add_plate_column(cursor) -> str | None:
    """Adds the column if `vehicles` is there. None when it ran, or why not."""
    cursor.execute("select to_regclass('public.vehicles')")
    if cursor.fetchone()[0] is None:
        return "vehicles: public.vehicles does not exist here; no plate column added."
    cursor.execute(ADD_SQL)
    return None
