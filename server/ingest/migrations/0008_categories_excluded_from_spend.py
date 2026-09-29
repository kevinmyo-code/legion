"""`public.categories.excluded_from_spend` (Kevin, 2026-09-29: "ignore zelle
for spending. its just transfer between here and there.").

The reason and the SQL are `ingest/category_flags.py`. Guarded on the table
existing, like `0006` and `0007`: a no-op on the pytest database at `migrate`
time, where `tests/conftest.py` calls the same function once it exists.

**No data migration creates a `Transfers` category here.** Categories are
seeded by the phone (`data/local/CategorySeed.kt`, and a Room migration for
an existing install) and reach the server through the ordinary category
upsert, flag included. A server-side insert would race that upsert on
`(household_id, name)`.
"""

from django.db import migrations


def add(apps, schema_editor):
    from ingest.category_flags import add_column

    with schema_editor.connection.cursor() as cursor:
        add_column(cursor)


def drop(apps, schema_editor):
    from ingest.category_flags import DROP_COLUMN_SQL

    with schema_editor.connection.cursor() as cursor:
        cursor.execute(DROP_COLUMN_SQL)


class Migration(migrations.Migration):
    dependencies = [
        ("ingest", "0007_ledger_transaction_categories"),
    ]

    operations = [migrations.RunPython(add, drop)]
