"""ADR 0055: a tick on the household's Groceries list is a purchase.

The business rule, once, in Django (ADR 0044). `checklists/views.py` calls the
two hooks below from the only two places a tick is written:
`ChecklistItemTickView.post` (tick, and revive-on-retick) and
`ChecklistItemUntickView.delete`. The phone, the web and `/mcp`'s
`tick_checklist_item` (which dispatches to that same view) all reach those
two, so none of them can disagree about what a Groceries tick means.

Both hooks run inside the tick's own transaction (`save_or_400`'s savepoint),
so a tick and its purchase commit together or not at all.

## The rules (purchase-log ticket 01)

- **Which list.** The household's checklist named "Groceries"
  (case-insensitive, trimmed), shared, not deleted. Several: the oldest. A
  private list called Groceries is somebody's own list, not the household's
  shopping list, and does not count. Renaming the list ends the hook.
- **Tick.** A live tick created (or revived) on that list makes one
  `GROCERIES_TICK` entry: the item's text at that moment, `bought_on` = the
  tick's `day`, `created_by` = the member whose request made the tick, shared.
  A double-tap that changes nothing makes nothing.
- **Untick on the same local day** soft-deletes the entry that tick made: a
  mistaken tick is not a purchase.
- **Untick on a later day leaves it**: clearing an old tick is not evidence the
  purchase did not happen.
- **Re-tick** revives the one entry linked to that tick rather than making a
  second (one entry per tick, by a unique key).

## What "the same local day" means here

The server keeps no household timezone (`TIME_ZONE = "UTC"`, and CLAUDE.md
section 1 keeps zone ids away from anything that guesses with them). So the
untick route takes the caller's own local day as `?today=<epoch day>`; the web
and the phone know it. **When a caller does not send it** (an installed phone
that predates this), the server falls back to today's UTC date. For a household
west of UTC, like Kevin's, that fallback can only err one way: an untick late
in the evening, local time, reads as a later day and the entry is kept. It can
never remove a purchase on a later local day. East of UTC the error would run
the other way, which is why clients should send `today`.
"""

from __future__ import annotations

from datetime import UTC, datetime

from django.db.models.functions import Lower, Now, Trim

from purchases.matching import date_to_day
from purchases.models import SOURCE_GROCERIES_TICK, Purchase

GROCERIES_NAME = "groceries"


def groceries_list_id(household_id):
    """The id of the household's Groceries list, or None."""
    from checklists.models import Checklist

    return (
        Checklist.objects.filter(
            household_id=household_id, owner_user__isnull=True, deleted_at__isnull=True
        )
        .annotate(_name=Lower(Trim("name")))
        .filter(_name=GROCERIES_NAME)
        .order_by("created_at", "id")
        .values_list("id", flat=True)
        .first()
    )


def is_groceries_list(checklist) -> bool:
    if checklist.owner_user_id is not None or checklist.deleted_at is not None:
        return False
    return groceries_list_id(checklist.household_id) == checklist.id


def utc_today() -> int:
    return date_to_day(datetime.now(UTC).date())


def on_tick(tick, item, user) -> Purchase | None:
    """A live tick was just created or revived on `item`. Returns the entry
    made or revived, or None when the list is not the Groceries list or the
    tick's entry is already live."""
    if not is_groceries_list(item.checklist):
        return None
    existing = Purchase.objects.filter(tick=tick).first()
    if existing is None:
        return Purchase.objects.create(
            household_id=tick.household_id,
            item=item.text,
            bought_on=tick.day,
            created_by=user,
            source=SOURCE_GROCERIES_TICK,
            tick=tick,
        )
    if existing.deleted_at is None:
        return None
    existing.deleted_at = None
    existing.item = item.text
    existing.bought_on = tick.day
    existing.created_by = user
    existing.source = SOURCE_GROCERIES_TICK
    existing.logged_at = Now()
    existing.save(
        update_fields=["deleted_at", "item", "bought_on", "created_by", "source", "logged_at"]
    )
    return existing


def on_untick(tick, today: int | None) -> bool:
    """`tick` was just soft-deleted. Soft-deletes the entry it made when the
    untick is on the tick's own local day. True when an entry was removed."""
    current = today if today is not None else utc_today()
    if current > tick.day:
        return False
    return (
        Purchase.objects.filter(
            tick=tick, source=SOURCE_GROCERIES_TICK, deleted_at__isnull=True
        ).update(deleted_at=Now())
        > 0
    )
