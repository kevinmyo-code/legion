"""`public.vehicles.plate` (memory-and-vault ticket 06).

The SQL is `api/vehicle_plate.py`. Guarded on the table existing, like `0023`: a no-op on the
pytest database at `migrate` time, where `tests/conftest.py` calls the same function once it
exists. Lives in `ingest` because `legacy` has no migrations by design. ADDITIVE: one nullable
column.
"""

from django.db import migrations


def add(apps, schema_editor):
    from api.vehicle_plate import add_plate_column

    with schema_editor.connection.cursor() as cursor:
        add_plate_column(cursor)


def drop(apps, schema_editor):
    from api.vehicle_plate import DROP_SQL

    with schema_editor.connection.cursor() as cursor:
        cursor.execute(DROP_SQL)


class Migration(migrations.Migration):
    dependencies = [
        ("ingest", "0024_brief_source"),
    ]

    operations = [migrations.RunPython(add, drop)]
