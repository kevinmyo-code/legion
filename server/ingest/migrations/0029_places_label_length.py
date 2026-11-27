"""`places_label_length`: the 30-character place label cap as a CHECK
(django-engine ticket 14).

The SQL is `api/place_columns.py`. Guarded on the table existing, like
`0017`: a no-op on the pytest database at `migrate` time, where
`tests/conftest.py` calls the same function once it exists. Never fails on
an existing overlong row; it leaves the CHECK NOT VALID and says so.
"""

from django.db import migrations


def add(apps, schema_editor):
    from api.place_columns import add_place_label_check

    with schema_editor.connection.cursor() as cursor:
        message = add_place_label_check(cursor)
    if message:
        print(f"\n  {message}")


def drop(apps, schema_editor):
    from api.place_columns import LABEL_DROP_SQL

    with schema_editor.connection.cursor() as cursor:
        cursor.execute(LABEL_DROP_SQL)


class Migration(migrations.Migration):
    dependencies = [
        ("ingest", "0028_ledger_plaid_category"),
    ]

    operations = [migrations.RunPython(add, drop)]
