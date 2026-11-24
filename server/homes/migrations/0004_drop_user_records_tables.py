"""Drops the four retired user-aspect record tables. THIS DELETES DATA.

`0003_retire_user_records_state.py` took the models out of Django's state and left the tables in
Postgres. This is the other half, and it is a separate migration on purpose: dropping a table
deletes rows, which needs Kevin's word, so everything before this commit ships without it.

**What is in them on live, read read-only this session through the `legion-worker` job (execution
`legion-worker-pnllk`, a READ ONLY transaction in `manage.py shell`):** `user_aspects` 0,
`user_record_types` 0, `user_field_defs` 0, `user_records` 0. All four are empty, so nothing of
his is lost. A household that did hold trackers would lose the engine's copy only: the phone's own
Room rows are the live ones and are not touched by any of this.

Children before parents (the FK chain is aspect -> record type -> field / record) and no CASCADE,
so a foreign key nobody expected fails the migration instead of silently pulling another table's
rows down with it. `public.`-qualified because `0002_user_records.py` created them there through
its search_path swap.

Irreversible by design: `reverse_sql` would recreate empty tables and call that an undo.
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
