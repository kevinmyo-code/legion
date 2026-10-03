"""ADR 0052 (web-revamp ticket 06): `checklists.owner_user`, `created_by`,
`checklist_items.created_by`, and the owner guard on `checklists`.

The trigger function is `household/visibility_sql.py`'s, shared with
`events`; it refuses an owner who is not a member of the row's household.
Created here too (`create or replace`), so this migration does not depend on
the order `ingest/0010` runs in.
"""

import django.db.models.deletion
from django.db import migrations, models


def guard(apps, schema_editor):
    from household.visibility_sql import attach_guard, create_guard_function

    with schema_editor.connection.cursor() as cursor:
        create_guard_function(cursor)
        attach_guard(cursor, "checklists")


def unguard(apps, schema_editor):
    from household.visibility_sql import GUARD_TRIGGER

    with schema_editor.connection.cursor() as cursor:
        cursor.execute(f"drop trigger if exists {GUARD_TRIGGER} on public.checklists")


class Migration(migrations.Migration):
    dependencies = [
        ("checklists", "0002_household"),
        ("household", "0004_devicetoken_scope"),
    ]

    operations = [
        migrations.AddField(
            model_name="checklist",
            name="owner_user",
            field=models.ForeignKey(
                blank=True,
                null=True,
                on_delete=django.db.models.deletion.SET_NULL,
                related_name="+",
                to="household.user",
            ),
        ),
        migrations.AddField(
            model_name="checklist",
            name="created_by",
            field=models.ForeignKey(
                blank=True,
                null=True,
                on_delete=django.db.models.deletion.SET_NULL,
                related_name="+",
                to="household.user",
            ),
        ),
        migrations.AddField(
            model_name="checklistitem",
            name="created_by",
            field=models.ForeignKey(
                blank=True,
                null=True,
                on_delete=django.db.models.deletion.SET_NULL,
                related_name="+",
                to="household.user",
            ),
        ),
        migrations.RunPython(guard, unguard),
    ]
