"""The demo's funnel counts (`DemoFunnelDay`): one conditional UPDATE per step, a row made on the
first count of a day. Counts only: the ref tag is cleaned to a short slug, nothing else is kept."""

from __future__ import annotations

import re
from datetime import UTC, datetime, timedelta

from django.db import IntegrityError, transaction
from django.db.models import F

from demo.models import DemoFunnelDay

_REF = re.compile(r"[^a-z0-9_-]")


def clean_ref(ref: str | None) -> str:
    return _REF.sub("", (ref or "").strip().lower())[:32]


def count(event: str, ref: str | None, now: datetime | None = None) -> bool:
    """Adds one to today's count of `event` for `ref`. False for an event not in the list."""
    if event not in DemoFunnelDay.EVENTS:
        return False
    day = (now or datetime.now(UTC)).astimezone(UTC).date()
    ref = clean_ref(ref)
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
