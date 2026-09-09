"""Tells Django's model state about the `household_id` column that
`household/migrations/0002_households_are_tenants.py` has already created.

**State-only, and that is the whole point.** The three `checklists` tables
live in `public` alongside the forty legacy ones (see `0001_initial.py`'s
own comment for the search_path swap that put them there), and ADR 0045's
column is added to all forty-three by ONE code path -
`household/tenancy_sql.apply_all` - so there is one set of behaviours to
reason about and one set to test. What Django needs on top of that is not
more DDL, it is knowledge: without these state operations `makemigrations`
would see a `household` field on the model, no migration declaring it, and
propose adding the column a second time.

`SeparateDatabaseAndState` with an EMPTY `database_operations` list is
Django's documented way to say "this already happened, record it".

**One piece of cruft this leaves behind, named rather than left to be
found.** Django creates a companion `varchar_pattern_ops` index
(`checklists_sync_id_<hash>_like`) alongside a unique `CharField`. Dropping
`unique=True` in the model state does not drop that index from the
database, and `household/tenancy_sql.py` does not touch it either - its
catalog query selects `indisunique` indexes only, and the `_like` companion
is not unique. So a redundant, non-unique pattern index survives on
`sync_id` for each of the three tables. It costs a little write time and
nothing else; it is left because removing it means guessing at a hashed
name, and a wrong guess would drop the wrong index.
"""

import django.db.models.deletion
from django.db import migrations, models

from household.tenancy_sql import household_unique_name


class Migration(migrations.Migration):

    dependencies = [
        ("checklists", "0001_initial"),
        # The column, the household table it references, and the re-keyed
        # unique constraints all arrive there.
        ("household", "0002_households_are_tenants"),
    ]

    operations = [
        migrations.SeparateDatabaseAndState(
            database_operations=[],
            state_operations=[
                migrations.AddField(
                    model_name="checklist",
                    name="household",
                    field=models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name="+",
                        to="household.household",
                    ),
                ),
                migrations.AddField(
                    model_name="checklistitem",
                    name="household",
                    field=models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name="+",
                        to="household.household",
                    ),
                ),
                migrations.AddField(
                    model_name="checklisttick",
                    name="household",
                    field=models.ForeignKey(
                        on_delete=django.db.models.deletion.PROTECT,
                        related_name="+",
                        to="household.household",
                    ),
                ),
                migrations.AlterField(
                    model_name="checklist",
                    name="sync_id",
                    field=models.CharField(blank=True, max_length=64, null=True),
                ),
                migrations.AlterField(
                    model_name="checklistitem",
                    name="sync_id",
                    field=models.CharField(blank=True, max_length=64, null=True),
                ),
                migrations.AlterField(
                    model_name="checklisttick",
                    name="sync_id",
                    field=models.CharField(blank=True, max_length=64, null=True),
                ),
                migrations.AddConstraint(
                    model_name="checklist",
                    constraint=models.UniqueConstraint(
                        fields=("household", "sync_id"),
                        name=household_unique_name("checklists", ["sync_id"]),
                    ),
                ),
                migrations.AddConstraint(
                    model_name="checklistitem",
                    constraint=models.UniqueConstraint(
                        fields=("household", "sync_id"),
                        name=household_unique_name("checklist_items", ["sync_id"]),
                    ),
                ),
                migrations.AddConstraint(
                    model_name="checklisttick",
                    constraint=models.UniqueConstraint(
                        fields=("household", "sync_id"),
                        name=household_unique_name("checklist_ticks", ["sync_id"]),
                    ),
                ),
            ],
        ),
    ]
