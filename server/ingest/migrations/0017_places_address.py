"""`public.places.address` (Kevin, 2026-10-09: save a place by its address).

The SQL is `api/place_columns.py`. Guarded on the table existing, like
`0009`: a no-op on the pytest database at `migrate` time, where
`tests/conftest.py` calls the same function once it exists.

Lives in `ingest` because that is where every migration that alters a legacy
table lives; `legacy` has no migrations by design
(`MIGRATION_MODULES = {"legacy": None}`).
"""

from django.db import migrations


def add(apps, schema_editor):
    from api.place_columns import add_place_address

    with schema_editor.connection.cursor() as cursor:
        add_place_address(cursor)


def drop(apps, schema_editor):
    from api.place_columns import ADDRESS_DROP_SQL

    with schema_editor.connection.cursor() as cursor:
        cursor.execute(ADDRESS_DROP_SQL)


class Migration(migrations.Migration):
    dependencies = [
        ("ingest", "0016_merchant_aliases"),
    ]

    operations = [migrations.RunPython(add, drop)]
