"""`public.merchant_aliases` (Kevin, 2026-10-07): a merchant name a person
chose, shown in place of a gated row's bank text. Display only.

The SQL, and why each constraint is there, is `ingest/merchant_aliases.create_sql`.
It runs through `create_table`, which `tests/conftest.py` also calls: on the
pytest database the legacy tables do not exist yet when `migrate` runs, so
there this migration records a no-op and the fixture creates the table once
they do. The same guard and the same reason as `0007`.
"""

from django.db import migrations


def create(apps, schema_editor):
    from ingest.merchant_aliases import create_table

    with schema_editor.connection.cursor() as cursor:
        create_table(cursor)


def drop(apps, schema_editor):
    from ingest.merchant_aliases import DROP_SQL

    with schema_editor.connection.cursor() as cursor:
        cursor.execute(DROP_SQL)


class Migration(migrations.Migration):
    dependencies = [
        ("household", "0003_invite"),
        ("ingest", "0015_push_source"),
    ]

    operations = [migrations.RunPython(create, drop)]
