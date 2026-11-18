"""The demo's funnel counts (`DemoFunnelDay`): one conditional UPDATE per step, a row made on the
first count of a day. Counts only: the ref tag is cleaned to a short slug, nothing else is kept."""

from __future__ import annotations

import re
from datetime import UTC, datetime, timedelta

from django.db import IntegrityError, transaction
from django.db.models import F

from demo.models import DemoFunnelDay

_REF = re.compile(r"[^a-z0-9_-]")


# A ref is counted only when it is a short slug that is known (round 2 finding 13: free text made
# unbounded rows, and a per-person link could identify a visitor): one Kevin listed in
# `LEGION_DEMO_REFS` (comma separated), or one a visit has already entered with. Anything else is
# counted under OTHER_REF, and at most MAX_REFS_A_DAY refs get rows of their own on a day.
_SLUG = re.compile(r"^[a-z0-9][a-z0-9-]{0,23}$")
OTHER_REF = "other"
MAX_REFS_A_DAY = 30


def clean_ref(ref: str | None) -> str:
    return _REF.sub("", (ref or "").strip().lower())[:32]


def _known(ref: str) -> bool:
    import os

    from demo.models import DemoVisit

    listed = {r.strip().lower() for r in os.environ.get("LEGION_DEMO_REFS", "").split(",")}
    return ref in listed or DemoVisit.objects.filter(ref=ref).exists()


def counted_ref(ref: str | None, day) -> str:
    """The ref a step is counted under: "" for none, the ref itself when it is a known short slug
    and the day has room, else OTHER_REF."""
    ref = clean_ref(ref)
    if not ref:
        return ""
    if not _SLUG.match(ref) or not _known(ref):
        return OTHER_REF
    seen = set(DemoFunnelDay.objects.filter(day=day).values_list("ref", flat=True))
    if ref not in seen and len(seen - {"", OTHER_REF}) >= MAX_REFS_A_DAY:
        return OTHER_REF
    return ref


def count(event: str, ref: str | None, now: datetime | None = None) -> bool:
    """Adds one to today's count of `event` for `ref`. False for an event not in the list."""
    if event not in DemoFunnelDay.EVENTS:
        return False
    day = (now or datetime.now(UTC)).astimezone(UTC).date()
    ref = counted_ref(ref, day)
    if DemoFunnelDay.objects.filter(day=day, ref=ref, event=event).update(count=F("count") + 1):
        return True
    try:
        with transaction.atomic():
            DemoFunnelDay.objects.create(day=day, ref=ref, event=event, count=1)
    except IntegrityError:
        DemoFunnelDay.objects.filter(day=day, ref=ref, event=event).update(count=F("count") + 1)
    return True


def prune(days: int, now: datetime | None = None) -> int:
    cutoff = (now or datetime.now(UTC)).date() - timedelta(days=days)
    deleted, _ = DemoFunnelDay.objects.filter(day__lt=cutoff).delete()
    return deleted
