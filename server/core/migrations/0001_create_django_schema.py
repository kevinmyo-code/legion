"""Creates the `django` schema before ANY app's first migration runs.

`legion/settings.py` points `search_path` at `django,public` for the
`default` connection so that Django's own bookkeeping never lands in
`public` (owned by `supabase/migrations/` - ADR 0044, execution-plan.md
Phase 0). A `search_path` entry naming a schema that does not exist yet is
not an error in Postgres - it is silently skipped, and object creation
falls through to the next schema in the list. Confirmed empirically against
the live database before writing this migration, not assumed from
documentation: an unqualified `CREATE TABLE` under `search_path =
zz_nonexistent_schema, public` landed in `public` with no error at all.

So the schema has to exist before `contenttypes.0001_initial` - the very
first migration Django itself ships - ever runs, in every environment this
settings module is used against (a fresh `docker compose` Postgres, a
pytest-django test database created and destroyed per run, and the live
server). `run_before` is Django's documented mechanism for forcing a
migration ahead of one it does not (and structurally cannot) depend on.

This lives in its own `core` app, deliberately not inside `household` -
see `core/apps.py`'s docstring for the swappable-dependency bug that
happens if this migration becomes `household`'s own first one instead.
"""
from __future__ import annotations

from django.db import migrations


class Migration(migrations.Migration):
    initial = True

    dependencies: list[tuple[str, str]] = []

    run_before = [
        ("contenttypes", "0001_initial"),
    ]

    operations = [
        migrations.RunSQL(
            sql="CREATE SCHEMA IF NOT EXISTS django;",
            reverse_sql="DROP SCHEMA IF EXISTS django CASCADE;",
        ),
    ]
