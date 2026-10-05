"""Past Groceries ticks become bought entries (purchase-log ticket 03, Kevin
2026-10-04: "import").

For each household, its Groceries list is found by the same rule the live hook
uses (`purchases/groceries.py`): the checklist named "Groceries"
(case-insensitive, trimmed), shared, not deleted, the oldest if there are
several. Every live (not deleted) tick on any of its items becomes one entry:

- `source = GROCERIES_BACKFILL`, `bought_on` = the tick's day,
  `logged_at` = the tick's `ticked_at` (the instant the line came off the list);
- `created_by` = null. Ticks never recorded who made them, so every surface
  says "logged by: not recorded" rather than guessing;
- shared, and linked to the tick.

**Idempotent, keyed on the tick.** A tick that already has an entry (the
unique key on `purchases.tick_id`) is skipped, so a second run, or a run after
the hook has already logged some ticks, adds nothing twice.

**Caveat carried from ADR 0049, and not fixable here:** a tick records no
text, only the item it was on. An item whose text was edited after it was
ticked imports under its CURRENT text, not the text it had on the day it was
bought. Telling the two apart would need text-on-tick history, which has never
been kept.

Ticks on items since deleted are included: deleting a line from the list does
not mean the purchase did not happen. Ticks on a list renamed away from
"Groceries" before this ran are not, because that list is no longer the
household's Groceries list.

Reversing it deletes only the rows it made (`GROCERIES_BACKFILL`).
"""

from django.db import migrations
from django.db.models.functions import Lower, Trim


def backfill(apps, schema_editor):
    Checklist = apps.get_model("checklists", "Checklist")
    ChecklistTick = apps.get_model("checklists", "ChecklistTick")
    Purchase = apps.get_model("purchases", "Purchase")

    groceries_by_household = {}
    lists = (
        Checklist.objects.filter(owner_user__isnull=True, deleted_at__isnull=True)
        .annotate(_name=Lower(Trim("name")))
        .filter(_name="groceries")
        .order_by("created_at", "id")
    )
    for checklist in lists:
        groceries_by_household.setdefault(checklist.household_id, checklist.id)

    for household_id, checklist_id in groceries_by_household.items():
        ticks = (
            ChecklistTick.objects.filter(
                item__checklist_id=checklist_id,
                deleted_at__isnull=True,
                purchase__isnull=True,
            )
            # A blank line is not an item anybody bought (and the table's
            # own check constraint refuses blank item text).
            .exclude(item__text__regex=r"^\s*$")
            .select_related("item")
            .order_by("day", "ticked_at")
        )
        Purchase.objects.bulk_create(
            [
                Purchase(
                    household_id=household_id,
                    item=tick.item.text,
                    bought_on=tick.day,
                    logged_at=tick.ticked_at,
                    created_by=None,
                    owner_user=None,
                    source="GROCERIES_BACKFILL",
                    tick=tick,
                )
                for tick in ticks
            ]
        )


def unbackfill(apps, schema_editor):
    Purchase = apps.get_model("purchases", "Purchase")
    Purchase.objects.filter(source="GROCERIES_BACKFILL").delete()


class Migration(migrations.Migration):
    dependencies = [
        ("purchases", "0001_initial"),
        ("checklists", "0004_owner_restrict"),
    ]

    operations = [migrations.RunPython(backfill, unbackfill)]
