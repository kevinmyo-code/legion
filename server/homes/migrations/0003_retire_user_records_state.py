"""State only: Django forgets the four user-aspect record models (the generic user tracker).

Kevin ruled the tracker engine cut on 2026-11-24, so `homes/records.py`, `homes/records_tools.py`
and the four models in `homes/models.py` are gone. The TABLES stay: dropping them deletes rows and
needs his word, so the drop is its own migration. `0002_user_records.py` is unchanged and still
creates them (never edit an existing migration), which is why this is `SeparateDatabaseAndState`
with no `database_operations`: the schema is untouched and only Django's model state changes.

While this is the head, `user_aspects`, `user_record_types`, `user_field_defs` and `user_records`
exist in Postgres with their `household` column, unreachable by any route, tool or model, and still
listed in `household.tenancy.TENANT_TABLES` so the tenancy tests' two directions stay true.

Children before parents, the order `DeleteModel` needs for the FK chain aspect -> record type ->
field / record.
"""

from django.db import migrations


class Migration(migrations.Migration):

    dependencies = [
        ("homes", "0002_user_records"),
    ]

    operations = [
        migrations.SeparateDatabaseAndState(
            database_operations=[],
            state_operations=[
                migrations.DeleteModel(name="UserRecord"),
                migrations.DeleteModel(name="UserFieldDef"),
                migrations.DeleteModel(name="UserRecordType"),
                migrations.DeleteModel(name="UserAspect"),
            ],
        ),
    ]
