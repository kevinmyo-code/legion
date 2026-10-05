"""Every household gets its ONE built-in Groceries list (Kevin, 2026-10-05).

Shared (no owner), plain (no schedule), live, `system_key = "groceries"`, and
EMPTY: Kevin ruled "start empty" rather than reviving the list he deleted on
Oct 3. The old hand-made lists called Groceries are not touched, revived or
renamed; their ticks are imported into the purchase log by
`purchases/migrations/0003_backfill_all_groceries_ticks.py`.

Idempotent: a household that already has a live list with the key is skipped.
A household created after this runs gets its list from
`checklists/signals.py` instead. Reversing deletes only the empty built-in
lists this made; one that has gained items is left, since removing it would
delete someone's data on a rollback.
"""

from django.db import migrations


def create(apps, schema_editor):
    Household = apps.get_model("household", "Household")
    Checklist = apps.get_model("checklists", "Checklist")
    have = set(
        Checklist.objects.filter(system_key="groceries", deleted_at__isnull=True).values_list(
            "household_id", flat=True
        )
    )
    Checklist.objects.bulk_create(
        [
            Checklist(
                household_id=household_id,
                name="Groceries",
                owner_user=None,
                system_key="groceries",
            )
            for household_id in Household.objects.values_list("id", flat=True)
            if household_id not in have
        ]
    )


def remove(apps, schema_editor):
    Checklist = apps.get_model("checklists", "Checklist")
    Checklist.objects.filter(system_key="groceries", items__isnull=True).delete()


class Migration(migrations.Migration):
    dependencies = [
        ("checklists", "0005_builtin_system_key"),
        ("household", "0004_devicetoken_scope"),
    ]

    operations = [migrations.RunPython(create, remove)]
