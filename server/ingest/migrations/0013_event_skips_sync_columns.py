"""`public.event_skips.updated_at`, `deleted_at` and the touch trigger
(web-revamp ticket 08, spec D4), so a skip can travel `/api/changes` and a
DELETE can tombstone it.

The SQL is `api/event_columns.py`. Guarded on the table existing, like
`0006` onwards: a no-op on the pytest database at `migrate` time, where
`tests/conftest.py` calls the same function once it exists.
"""

from django.db import migrations


def add(apps, schema_editor):
    from api.event_columns import add_event_skip_sync_columns

    with schema_editor.connection.cursor() as cursor:
        add_event_skip_sync_columns(cursor)


def drop(apps, schema_editor):
    from api.event_columns import SKIPS_DROP_SQL

    with schema_editor.connection.cursor() as cursor:
        cursor.execute(SKIPS_DROP_SQL)


class Migration(migrations.Migration):
    dependencies = [
        ("ingest", "0012_canvas_rows_are_private"),
    ]

    operations = [migrations.RunPython(add, drop)]
