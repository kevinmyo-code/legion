"""`public.ledger_transaction_categories` (backend-etl ticket 14, option 2).

A category laid over a gated `ledger_transactions` row, because the row itself
may never be updated. The SQL, and why each constraint is there (and why there
is no foreign key to `categories`), is `ingest/category_overrides.create_sql`.
It runs through `create_table`, which `tests/conftest.py` also calls: on the
pytest database the legacy tables do not exist yet when `migrate` runs, so
there this migration records a no-op and the fixture creates the table once
they do. The same guard and the same reason as `0006` and
`household/migrations/0002`.

Lives in `ingest` because `ingest` owns the categorisation rules and the
backfill that writes most of these rows; `legacy` has no migrations by design
(`MIGRATION_MODULES = {"legacy": None}`).
"""

from django.db import migrations


def create(apps, schema_editor):
    from ingest.category_overrides import create_table

    with schema_editor.connection.cursor() as cursor:
        create_table(cursor)


def drop(apps, schema_editor):
    from ingest.category_overrides import DROP_SQL

    with schema_editor.connection.cursor() as cursor:
        cursor.execute(DROP_SQL)


class Migration(migrations.Migration):
    dependencies = [
        ("household", "0003_invite"),
        ("ingest", "0006_tidy_canvas_title_whitespace"),
    ]

    operations = [migrations.RunPython(create, drop)]
