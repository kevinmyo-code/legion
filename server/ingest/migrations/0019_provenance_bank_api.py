"""`BANK_API` joins `public.provenance` (ADR 0057, Kevin 2026-10-09).

The SQL and the refusal are `ingest/bank_api_sql.py`. **`atomic = False`**:
`ALTER TYPE ... ADD VALUE` must commit before anything may use the value, and
the constraint that names it is in `0020`.

**On the live database this does not add the value; it checks for it.** The
enum is owned by Supabase's `postgres` role, so the operator runs
`supabase/migrations/20261009000100_provenance_bank_api.sql` first. Missing
there and not addable here, this raises in words and `migrate` stops before
`0020` and `0021`, so nothing of the Plaid change is half-applied.

On the pytest database at `migrate` time the type does not exist yet; this is
a no-op there and `tests/conftest.py` calls the same function once it does.
"""

from django.db import migrations


def add(apps, schema_editor):
    from ingest.bank_api_sql import ensure_provenance_value

    with schema_editor.connection.cursor() as cursor:
        ensure_provenance_value(cursor)


class Migration(migrations.Migration):
    atomic = False

    dependencies = [
        ("ingest", "0018_event_suggestions"),
    ]

    # Postgres cannot drop an enum value, so there is nothing to reverse.
    operations = [migrations.RunPython(add, migrations.RunPython.noop)]
