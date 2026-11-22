"""Spare recruiter visit households, seeded ahead of time (demo-enter-slow, 2026-11-22).

`POST /api/demo/enter` took about 16 s on Cloud Run, nearly all of it seeding the visit's own
household (ADR 0069): about 400 serial queries to a database in another region. So a few
households are seeded before anyone asks, and `/enter` claims one inside the visit's own
transaction, falling back to seeding inline when none is ready. A visit never fails because the
pool is empty.

**A claimed spare is the household a fresh seed would have made.** It is made by the same
function (`make_visit_household`, the one place the `is_demo_visit` flag is set), seeded with
the same `today`, holds no member until the claim adds the visitor, and its marker row goes in
the claim's own transaction. So a spare is handed out only while it is still what a seed made
now would be:

- on the day it was seeded for, in the demo's own zone (every seeded row is relative to that
  day), and on the same UTC day;
- by the seed it was made with (`seed_version`: a deploy that changes the seed retires it);
- younger than `MAX_AGE`, so no job keyed on wall time has touched it that a fresh seed would
  have escaped (`prune_ticked_items` drops Groceries lines 24 h after their tick, and a tick's
  time is the seed's).

Anything else is deleted whole (`demo.visit_cleanup.delete_visit_household`), never re-dated:
by the next refill and by the hourly `prune_demo_visitors`.

Refill: after each visit commits, on a thread (`refill_soon`), so it is never in the response's
path. Best effort, because Cloud Run may throttle the CPU once the response is sent; the hourly
`prune_demo_visitors` refills too. One refill at a time across every instance (an advisory
lock), so two never overshoot the pool. Pool size: `LEGION_DEMO_SPARE_POOL` (default 2; 0 turns
the pool off and the next refill deletes every spare).
"""

from __future__ import annotations

import datetime as dt
import hashlib
import logging
import threading
import uuid
import zoneinfo
from functools import cache
from pathlib import Path

from django.conf import settings
from django.db import connection, transaction
from django.utils import timezone

from demo.briefing import DEMO_PROCEDURES, seed_visit_procedures
from demo.models import SpareVisitHousehold
from demo.visit_cleanup import delete_visit_household
from household.demo_seed import DEMO_TIMEZONE, seed_household
from household.models import Household

logger = logging.getLogger(__name__)

DEFAULT_POOL = 2
# Under `TICKED_ITEM_TTL` (24 h) less a whole visit (2 h), with room to spare.
MAX_AGE = dt.timedelta(hours=20)
# Two-int advisory lock key, in its own space beside `assistant.turn_slots`'s.
_LOCK = (0x5350, 1)  # "SP"
_SEED_SOURCES = (
    Path(__file__).resolve().parent.parent / "household" / "demo_seed.py",
    Path(__file__).resolve().parent / "briefing.py",
)


def pool_size() -> int:
    try:
        return max(0, int(str(getattr(settings, "LEGION_DEMO_SPARE_POOL", DEFAULT_POOL))))
    except ValueError:
        return DEFAULT_POOL


@cache
def seed_version() -> str:
    """A hash of the seed's own source and the visit procedures it writes. A spare made by an
    older seed than the running one is never handed out."""
    digest = hashlib.sha256()
    for path in (*_SEED_SOURCES, *sorted(DEMO_PROCEDURES.glob("*.yaml"))):
        digest.update(path.name.encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


def seed_day(now: dt.datetime | None = None) -> dt.date:
    """The `today` a seed made at `now` uses: the date in the demo's own zone."""
    return (now or timezone.now()).astimezone(zoneinfo.ZoneInfo(DEMO_TIMEZONE)).date()


def make_visit_household(today: dt.date | None = None) -> Household:
    """A visit's own household, flagged and seeded. The flag is set here and nowhere else in the
    engine (ADR 0069), for a spare and for an inline seed alike."""
    tag = uuid.uuid4().hex[:8]
    household = Household.objects.create(
        name=f"Demo visit {tag}", timezone=DEMO_TIMEZONE, is_demo_visit=True
    )
    seed_household(household, created=True, today=today)
    # The visit's own morning briefing, built only from the visit's tools (`demo/briefing.py`).
    seed_visit_procedures(household)
    return household


def _fresh(now: dt.datetime):
    midnight_utc = now.astimezone(dt.UTC).replace(hour=0, minute=0, second=0, microsecond=0)
    return SpareVisitHousehold.objects.filter(
        seeded_for=seed_day(now),
        seed_version=seed_version(),
        created_at__gte=max(now - MAX_AGE, midnight_utc),
    )


def claim(now: dt.datetime | None = None) -> Household | None:
    """One ready spare, made the caller's, or None. Call inside the visit's transaction: the
    marker row goes with it, and a rollback puts the spare back. Two callers never get the same
    one (`skip_locked`). The household's own `created_at` becomes the visit's, as a seed now
    would have stamped it."""
    if pool_size() == 0:
        return None
    now = now or timezone.now()
    spare = (
        _fresh(now)
        .select_for_update(skip_locked=True, of=("self",))
        .select_related("household")
        .order_by("created_at", "id")
        .first()
    )
    if spare is None:
        return None
    household = spare.household
    spare.delete()
    Household.objects.filter(pk=household.pk).update(created_at=now)
    household.created_at = now
    return household


def discard(now: dt.datetime | None = None, *, keep: int | None = None) -> int:
    """Deletes every spare that may no longer be handed out, and fresh ones beyond `keep` (the
    pool size), each whole. A spare being claimed right now is skipped. Returns how many."""
    now = now or timezone.now()
    keep = pool_size() if keep is None else keep
    fresh = list(_fresh(now).order_by("created_at", "id").values_list("pk", flat=True))
    unwanted = fresh[keep:]
    stale = SpareVisitHousehold.objects.exclude(pk__in=fresh).values_list("pk", flat=True)
    gone = 0
    for pk in [*stale, *unwanted]:
        with transaction.atomic():
            spare = (
                SpareVisitHousehold.objects.select_for_update(skip_locked=True, of=("self",))
                .select_related("household")
                .filter(pk=pk)
                .first()
            )
            if spare is None:
                continue
            household = spare.household
            spare.delete()
            delete_visit_household(household)
            gone += 1
    return gone


def stale_count(now: dt.datetime | None = None) -> int:
    """How many spares `discard` would delete now (the dry run's number)."""
    now = now or timezone.now()
    fresh = _fresh(now).count()
    return SpareVisitHousehold.objects.count() - min(fresh, pool_size())


def refill(size: int | None = None) -> dict[str, int]:
    """Discards what may not be handed out, then seeds spares until `size` (the pool size) are
    ready. Each spare is seeded and marked in one transaction, so a half-seeded one is never
    claimable. Another refill already running anywhere means this one does nothing."""
    size = pool_size() if size is None else size
    with connection.cursor() as cursor:
        cursor.execute("select pg_try_advisory_lock(%s, %s)", list(_LOCK))
        if not cursor.fetchone()[0]:
            return {"discarded": 0, "seeded": 0, "busy": 1}
    try:
        discarded = discard(keep=size)
        seeded = 0
        while seeded < size and _fresh(timezone.now()).count() < size:
            day = seed_day()
            with transaction.atomic():
                household = make_visit_household(today=day)
                SpareVisitHousehold.objects.create(
                    household=household, seeded_for=day, seed_version=seed_version()
                )
            seeded += 1
        return {"discarded": discarded, "seeded": seeded, "busy": 0}
    finally:
        with connection.cursor() as cursor:
            cursor.execute("select pg_advisory_unlock(%s, %s)", list(_LOCK))


def _spawn(work) -> None:
    threading.Thread(target=work, name="legion-demo-spares", daemon=True).start()


def _refill_on_thread() -> None:
    try:
        refill()
    except Exception:  # noqa: BLE001 - a failed refill only means the next visit seeds inline
        logger.exception("demo spares: the refill failed; the next visit seeds inline")
    finally:
        connection.close()


def refill_soon() -> None:
    """Tops the pool up on a thread once the caller's transaction commits: never in the
    response's path, and never for a request that created nothing."""
    if pool_size() == 0:
        return
    transaction.on_commit(lambda: _spawn(_refill_on_thread))
