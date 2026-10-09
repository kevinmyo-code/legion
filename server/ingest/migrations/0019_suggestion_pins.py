"""`public.suggestion_pins` (Kevin, 2026-10-09): one member's "I want to go"
on one suggestion, seen by the whole household.

The SQL, and why each constraint is there, is `api/suggestion_pins.create_sql`.
It runs through `create_table`, which `tests/conftest.py` also calls: on the
pytest database the legacy tables do not exist yet when `migrate` runs, so
there this migration records a no-op and the fixture creates the table once
they do. The same guard and the same reason as `0007` and `0016`.

Nothing here touches `private.*`: the live engine role has no USAGE on it.
"""

from django.db import migrations


def create(apps, schema_editor):
    from api.suggestion_pins import create_table

    with schema_editor.connection.cursor() as cursor:
        create_table(cursor)


def drop(apps, schema_editor):
    from api.suggestion_pins import DROP_SQL

    with schema_editor.connection.cursor() as cursor:
        cursor.execute(DROP_SQL)


class Migration(migrations.Migration):
    dependencies = [
        ("household", "0005_household_timezone"),
        ("ingest", "0018_event_suggestions"),
    ]

    operations = [migrations.RunPython(create, drop)]
