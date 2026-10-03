"""`public.events.remind_minutes_before` (web-revamp ticket 14, spec D7).

The SQL and the allowed set are `api/event_columns.py`. Guarded on the table
existing, like `0006` to `0008`: a no-op on the pytest database at `migrate`
time, where `tests/conftest.py` calls the same function once it exists.

Lives in `ingest` because that is where every migration that alters a legacy
table lives; `legacy` has no migrations by design
(`MIGRATION_MODULES = {"legacy": None}`).
"""

from django.db import migrations


def add(apps, schema_editor):
    from api.event_columns import add_remind_minutes_before

    with schema_editor.connection.cursor() as cursor:
        add_remind_minutes_before(cursor)


def drop(apps, schema_editor):
    from api.event_columns import REMIND_DROP_SQL

    with schema_editor.connection.cursor() as cursor:
        cursor.execute(REMIND_DROP_SQL)


class Migration(migrations.Migration):
    dependencies = [
        ("ingest", "0008_categories_excluded_from_spend"),
    ]

    operations = [migrations.RunPython(add, drop)]
