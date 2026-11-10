"""Ticked grocery lines leave the list a day after they were ticked (Kevin, 2026-11-10:
"ticked items shouldn't linger" - 74 ticked lines had piled up on Groceries).

`TICKED_ITEM_TTL` is the one place the window lives. Only the household's built-in
Groceries list (plain, no schedule) is pruned: a routine's ticks reset by day and its
lines ARE the routine, and a hand-made list was not part of the ask.

A line is soft-deleted (`deleted_at`), exactly as `DELETE .../items/<id>` does, and its
ticks are kept: the bought log (ADR 0055) and tick history read them. The touch trigger
bumps `updated_at`, so every client's next pull carries the tombstone.
"""

from __future__ import annotations

import datetime

from django.db.models import Exists, Max, OuterRef, Q
from django.db.models.functions import Now
from django.utils import timezone

from checklists.models import SYSTEM_KEY_GROCERIES, ChecklistItem, ChecklistTick

# Kevin's default; change it here and nowhere else.
TICKED_ITEM_TTL = datetime.timedelta(hours=24)


def prune_ticked(now: datetime.datetime | None = None) -> int:
    """Soft-deletes every live Groceries line whose latest live tick is older than
    `TICKED_ITEM_TTL`. Returns how many lines were removed."""
    cutoff = (now or timezone.now()) - TICKED_ITEM_TTL
    live_ticks = ChecklistTick.objects.filter(item=OuterRef("pk"), deleted_at__isnull=True)
    stale = (
        ChecklistItem.objects.filter(
            deleted_at__isnull=True,
            checklist__deleted_at__isnull=True,
            checklist__system_key=SYSTEM_KEY_GROCERIES,
            checklist__schedule_kind__isnull=True,
        )
        .filter(Exists(live_ticks))
        .annotate(last_tick=Max("ticks__ticked_at", filter=Q(ticks__deleted_at__isnull=True)))
        .filter(last_tick__lte=cutoff)
    )
    ids = list(stale.values_list("pk", flat=True))
    if not ids:
        return 0
    return ChecklistItem.objects.filter(pk__in=ids, deleted_at__isnull=True).update(deleted_at=Now())
