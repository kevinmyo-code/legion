"""Import EVERY past Groceries tick, including the ones 0002 missed (Kevin,
2026-10-05: "import all 59 past Groceries ticks", deleted and archived lists
too, "logged by: not recorded").

**Why 0002 imported nothing on the live engine.** It found the household's
Groceries list by the live hook's old rule: the oldest SHARED, NON-DELETED
list named "Groceries". Six such lists had existed since Sep 6 (four deleted,
two archived) and Kevin deleted the latest on Oct 3. The rule chose an
ARCHIVED list (archiving does not delete) that held 0 ticks, and the 59 live
ticks sat on the other five. Picking ONE list was the mistake: Groceries was
hand-made, so history spread across whichever copies existed.

**What this imports.** Every live tick on any shared checklist whose trimmed,
lower-cased name is "groceries" (deleted and archived lists included, since a
list being put away does not mean the purchase did not happen) or that is the
built-in list (`system_key`, from checklists 0006). As in 0002: source
`GROCERIES_BACKFILL`, `created_by` null (ticks never recorded who), `bought_on`
= the tick's day, `logged_at` = its `ticked_at`, item = the item's CURRENT
text, linked to the tick, blank item text skipped.

**Idempotent on the tick** (`purchase__isnull=True`): anything 0002 or the live
hook already made is left alone, so running twice adds nothing.

**Caveat carried from ADR 0049:** a tick records no text, only its item, so an
item edited after it was ticked imports under its current text. Private lists
named Groceries are somebody's own and stay out.

Reversing deletes only `GROCERIES_BACKFILL` rows (0002's included, as 0002's
own reverse does).
"""

from django.db import migrations
from django.db.models.functions import Lower, Trim


def backfill(apps, schema_editor):
    Checklist = apps.get_model("checklists", "Checklist")
    ChecklistTick = apps.get_model("checklists", "ChecklistTick")
    Purchase = apps.get_model("purchases", "Purchase")

    from django.db.models import Q

    list_ids = list(
        Checklist.objects.filter(owner_user__isnull=True)
        .annotate(_name=Lower(Trim("name")))
        .filter(Q(_name="groceries") | Q(system_key="groceries"))
        .values_list("id", flat=True)
    )
    ticks = (
        ChecklistTick.objects.filter(
            item__checklist_id__in=list_ids,
            deleted_at__isnull=True,
            purchase__isnull=True,
        )
        # A blank line is not an item anybody bought (and the table's own
        # check constraint refuses blank item text).
        .exclude(item__text__regex=r"^\s*$")
        .select_related("item")
        .order_by("day", "ticked_at")
    )
    Purchase.objects.bulk_create(
        [
            Purchase(
                household_id=tick.household_id,
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
        ("purchases", "0002_backfill_groceries_ticks"),
        ("checklists", "0006_create_builtin_groceries"),
    ]

    operations = [migrations.RunPython(backfill, unbackfill)]
