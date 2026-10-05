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

- **Which list.** The household's BUILT-IN Groceries list: the checklist with
  `system_key = "groceries"` (`checklists/builtin.py`), created with the
  household and unable to be deleted, archived, renamed or made private
  (Kevin, 2026-10-05). The hook used to key on a list NAMED Groceries, which
  failed live: six hand-made ones had come and gone, none was live, and the
  hook fired on nothing. A user list that happens to be called Groceries is
  now just a list.
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

**Kevin, 2026-10-05: the household has a timezone, and the server uses it.**
"Today" for the same-day check is decided in this order:

1. **The household's timezone** (`Household.timezone`, set once by the owner):
   the server's own clock at the moment the untick ARRIVES, read in that
   zone. It wins over anything a client sends, so a device with a wrong clock
   cannot move the day. One consequence, accepted with it: a phone untick
   queued offline before midnight and delivered after it is judged a later
   day and keeps the entry. That is the safe direction - a purchase is kept,
   never removed - and the entry can still be deleted by hand.
2. **The caller's `?today=<epoch day>`**, when the household has no zone set.
   Kept for compatibility and as the fallback; the web and the phone send it.
3. **Today's UTC date**, when neither is known (an installed phone that
   predates `today`, in a household that never set a zone). West of UTC, like
   Kevin's household, that can only err one way: a late-evening untick reads
   as a later day and the entry is kept. It never removes a purchase on a
   later local day.

The zone is server-side date math only. CLAUDE.md section 1 keeps zone ids
away from anything that talks to a model; nothing here does.
"""

from __future__ import annotations

from datetime import UTC, datetime

from django.db.models.functions import Now

from household.timezones import household_zone_name, local_date, zone_named
from purchases.matching import date_to_day
from purchases.models import SOURCE_GROCERIES_TICK, Purchase


def groceries_list_id(household_id):
    """The id of the household's built-in Groceries list, or None."""
    from checklists.models import SYSTEM_KEY_GROCERIES, Checklist

    return (
        Checklist.objects.filter(
            household_id=household_id,
            system_key=SYSTEM_KEY_GROCERIES,
            deleted_at__isnull=True,
        )
        .values_list("id", flat=True)
        .first()
    )


def is_groceries_list(checklist) -> bool:
    from checklists.models import SYSTEM_KEY_GROCERIES

    return checklist.system_key == SYSTEM_KEY_GROCERIES and checklist.deleted_at is None


def now_utc() -> datetime:
    """The server's clock. One function so a test can stand at 23:30 in
    Chicago without touching the real clock."""
    return datetime.now(UTC)


def utc_today() -> int:
    return date_to_day(now_utc().date())


def household_today(household_id) -> int | None:
    """Today's epoch day in the household's own timezone, or None when the
    household has not set one."""
    zone = zone_named(household_zone_name(household_id))
    if zone is None:
        return None
    return date_to_day(local_date(zone, now_utc()))


def untick_today(household_id, client_today: int | None) -> int:
    """Which day the same-day rule treats as today. Precedence, as the module
    docstring sets out: household timezone, then the client's `today`, then
    UTC."""
    from_household = household_today(household_id)
    if from_household is not None:
        return from_household
    if client_today is not None:
        return client_today
    return utc_today()


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
    untick is on the tick's own local day. True when an entry was removed.

    `today` is the caller's own local epoch day, or None when it sent none; it
    counts only when the household has no timezone (`untick_today`)."""
    live = Purchase.objects.filter(
        tick=tick, source=SOURCE_GROCERIES_TICK, deleted_at__isnull=True
    )
    # Most unticks are on lists that are not Groceries: skip the household
    # read when there is no entry to take back.
    if not live.exists():
        return False
    if untick_today(tick.household_id, today) > tick.day:
        return False
    return live.update(deleted_at=Now()) > 0
