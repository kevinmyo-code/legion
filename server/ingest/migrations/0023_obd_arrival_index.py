"""`obd_samples (vehicle_id, created_at, id)` for the arrival-order feed.

The SQL is `api/obd_arrival_index.py`. Guarded on the table existing, like
`0017`: a no-op on the pytest database at `migrate` time, where
`tests/conftest.py` calls the same function once it exists. Lives in `ingest`
because `legacy` has no migrations by design.
"""

from django.db import migrations


def create(apps, schema_editor):
    from api.obd_arrival_index import create_arrival_index

    with schema_editor.connection.cursor() as cursor:
        create_arrival_index(cursor)


def drop(apps, schema_editor):
    from api.obd_arrival_index import DROP_SQL

    with schema_editor.connection.cursor() as cursor:
        cursor.execute(DROP_SQL)


class Migration(migrations.Migration):
    dependencies = [
        ("ingest", "0022_plaid_items"),
    ]

    operations = [migrations.RunPython(create, drop)]
