"""The gated-row triggers allow DELETE inside a demo-visit household (ADR 0069).

The SQL is `household/demo_visit_sql.py`. Guarded on the tables existing,
like `ingest/migrations/0021`: a no-op on the pytest database at `migrate`
time, where `tests/conftest.py` calls the same function once the tables exist.
Names nothing in `private` (the live role has no USAGE on it).
"""

from django.db import migrations


def add(apps, schema_editor):
    from household.demo_visit_sql import apply_demo_visit_exemption

    with schema_editor.connection.cursor() as cursor:
        apply_demo_visit_exemption(cursor)


def drop(apps, schema_editor):
    from household.demo_visit_sql import drop_demo_visit_exemption

    with schema_editor.connection.cursor() as cursor:
        drop_demo_visit_exemption(cursor)


class Migration(migrations.Migration):
    dependencies = [
        ("household", "0007_household_is_demo_visit"),
        ("ingest", "0021_ledger_bank_rows"),
    ]

    operations = [migrations.RunPython(add, drop)]
