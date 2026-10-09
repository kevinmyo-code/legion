"""`ledger_transactions` learns bank-feed rows (ADR 0057): a `BANK_API` row
stands with no statement header, carries the feed's own id and pending flag,
and the immutability trigger moves to `public.ledger_transactions_guard()`
(nothing in `private`; the live role has no USAGE on it).

The SQL is `ingest/bank_api_sql.py`. Guarded on the table existing, like
`0008`: a no-op on the pytest database at `migrate` time, where
`tests/conftest.py` calls the same function once the table exists.
"""

from django.db import migrations


def add(apps, schema_editor):
    from ingest.bank_api_sql import apply_ledger_bank_rows

    with schema_editor.connection.cursor() as cursor:
        apply_ledger_bank_rows(cursor)


def drop(apps, schema_editor):
    from ingest.bank_api_sql import LEDGER_BANK_ROWS_DROP_SQL

    with schema_editor.connection.cursor() as cursor:
        cursor.execute("select to_regclass('public.ledger_transactions')")
        if cursor.fetchone()[0] is not None:
            cursor.execute(LEDGER_BANK_ROWS_DROP_SQL)


class Migration(migrations.Migration):
    dependencies = [
        ("ingest", "0019_provenance_bank_api"),
    ]

    operations = [migrations.RunPython(add, drop)]
