"""`public.events.kind` gains `suggestion` (Kevin, 2026-10-09: weekend things
to do, separately coloured on the calendar), plus the CHECK that a suggestion
never reminds and is never done.

The SQL is `api/event_columns.py`. Guarded on the table existing, like
`0009`: a no-op on the pytest database at `migrate` time, where
`tests/conftest.py` calls the same function once it exists.
"""

from django.db import migrations


def add(apps, schema_editor):
    from api.event_columns import add_event_suggestions

    with schema_editor.connection.cursor() as cursor:
        add_event_suggestions(cursor)


def drop(apps, schema_editor):
    from api.event_columns import SUGGESTION_DROP_SQL, _table_exists

    with schema_editor.connection.cursor() as cursor:
        if _table_exists(cursor, "events"):
            cursor.execute(SUGGESTION_DROP_SQL)


class Migration(migrations.Migration):
    dependencies = [
        ("ingest", "0017_places_address"),
    ]

    operations = [migrations.RunPython(add, drop)]
