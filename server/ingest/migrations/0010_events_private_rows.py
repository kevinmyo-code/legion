"""`public.events.owner_user_id` and `created_by_id`, and the owner guard
(ADR 0052, web-revamp ticket 06).

The SQL, and why the guard is a trigger rather than a composite foreign key,
is `household/visibility_sql.py`. Guarded on the table existing, like `0006`
to `0009`: a no-op on the pytest database at `migrate` time, where
`tests/conftest.py` calls the same function once it exists.
"""

from django.db import migrations


def add(apps, schema_editor):
    from household.visibility_sql import add_event_columns

    with schema_editor.connection.cursor() as cursor:
        add_event_columns(cursor)


def drop(apps, schema_editor):
    from household.visibility_sql import EVENT_COLUMNS_DROP_SQL

    with schema_editor.connection.cursor() as cursor:
        cursor.execute(EVENT_COLUMNS_DROP_SQL)


class Migration(migrations.Migration):
    dependencies = [
        ("household", "0004_devicetoken_scope"),
        ("ingest", "0009_events_remind_minutes_before"),
    ]

    operations = [migrations.RunPython(add, drop)]
