"""`public.events.organizer` (engine brain, calendar taint; relay report 12.4):
who organized an event that came in from outside, a nullable column.

The SQL is `api/event_columns.py`. Guarded on the table existing, like
`0018`: a no-op on the pytest database at `migrate` time, where
`tests/conftest.py` calls the same function once it exists.
"""

from django.db import migrations


def add(apps, schema_editor):
    from api.event_columns import add_event_organizer

    with schema_editor.connection.cursor() as cursor:
        add_event_organizer(cursor)


def drop(apps, schema_editor):
    from api.event_columns import ORGANIZER_DROP_SQL

    with schema_editor.connection.cursor() as cursor:
        cursor.execute(ORGANIZER_DROP_SQL)


class Migration(migrations.Migration):
    dependencies = [
        ("ingest", "0026_memory_source"),
    ]

    operations = [migrations.RunPython(add, drop)]
