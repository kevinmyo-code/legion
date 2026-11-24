"""Drops the four retired user-aspect record tables. THIS DELETES DATA.

`0003_retire_user_records_state.py` took the models out of Django's state and left the tables in
Postgres. This is the other half, and it is a separate migration on purpose: dropping a table
deletes rows, which needs Kevin's word, so everything before this commit ships without it.

**All four tables were empty when this was written**, confirmed by a read-only row count before the
change (kept out of this public repo; the counts are in the slice's report under `.legion/reports/`).
So nothing is lost. A household that did hold trackers would lose the engine's copy only: the
phone's own Room rows are the live ones and are not touched by any of this.

Children before parents (the FK chain is aspect -> record type -> field / record) and no CASCADE,
so a foreign key nobody expected fails the migration instead of silently pulling another table's
rows down with it. `public.`-qualified because `0002_user_records.py` created them there through
its search_path swap.

`reverse_sql` is a no-op rather than a `CREATE TABLE`, because recreating empty tables is not an
undo. Django will therefore let a backward migrate SUCCEED without restoring anything, so this is
not irreversible in the sense Django enforces: it is unrecoverable in fact. Reversing it leaves
Django's state expecting no models and the database holding no tables, which agree; restoring rows
needs a database backup, not a migration.
"""

from django.db import migrations

_DROPS = (
    "user_records",
    "user_field_defs",
    "user_record_types",
    "user_aspects",
)


class Migration(migrations.Migration):

    dependencies = [
        ("homes", "0003_retire_user_records_state"),
    ]

    operations = [
        migrations.RunSQL(
            sql=f"DROP TABLE IF EXISTS public.{table};",
            reverse_sql=migrations.RunSQL.noop,
        )
        for table in _DROPS
    ]
