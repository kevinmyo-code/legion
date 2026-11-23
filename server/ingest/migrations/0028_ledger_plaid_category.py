"""A bank row keeps the category evidence Plaid sent with it (ADR 0057,
CLAUDE.md section 4 rule 8): `plaid_category`, the raw
`personal_finance_category` object, and `category_origin`, which source wrote
the row's category. Additive; nothing existing changes.

The SQL is `ingest/bank_api_sql.py`. Guarded on the table existing, like `0021`:
a no-op on the pytest database at `migrate` time, where `tests/conftest.py`
calls the same function once the table exists.

Numbered 0028 and not 0027: `feat/engine-calendar-organizer` already holds
`0027_event_organizer` in this app.
"""

from django.db import migrations


def add(apps, schema_editor):
    from ingest.bank_api_sql import apply_plaid_category

    with schema_editor.connection.cursor() as cursor:
        apply_plaid_category(cursor)


def drop(apps, schema_editor):
    from ingest.bank_api_sql import PLAID_CATEGORY_DROP_SQL

    with schema_editor.connection.cursor() as cursor:
        cursor.execute("select to_regclass('public.ledger_transactions')")
        if cursor.fetchone()[0] is not None:
            cursor.execute(PLAID_CATEGORY_DROP_SQL)


class Migration(migrations.Migration):
    dependencies = [
        ("ingest", "0026_memory_source"),
    ]

    operations = [migrations.RunPython(add, drop)]
