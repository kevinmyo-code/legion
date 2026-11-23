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
- by the build it was made with (`seed_version`: a hash of every server `.py` and `.yaml`
  file, taken once per process, so any deploy retires every older spare; the service and the
  `legion-worker` job run one image, so they agree);
- younger than `MAX_AGE`, so no job keyed on wall time has touched it that a fresh seed would
  have escaped (`prune_ticked_items` drops Groceries lines 24 h after their tick, and a tick's
  time is the seed's).

Anything else is deleted whole (`demo.visit_cleanup.delete_visit_household`), never re-dated:
by the next refill and by the hourly `prune_demo_visitors`.

Refill: only the hourly `prune_demo_visitors` job, never the request process (review 1122: a
thread Cloud Run starves after the response would sit in an open transaction holding locks a
ship's migration needs). A second visitor inside the hour seeds inline. Each refill transaction
sets its own `lock_timeout` and `idle_in_transaction_session_timeout`, so it can never hold a
migration up for long. One refill at a time across the engine (an advisory lock). Pool size:
`LEGION_DEMO_SPARE_POOL` (default 2; 0 turns the pool off and the next refill deletes every
spare), and 0 while no demo code is live.
"""

from __future__ import annotations

import datetime as dt
import hashlib
import os
import uuid
import zoneinfo
from pathlib import Path

from django.conf import settings
from django.db import connection, transaction
from django.utils import timezone

from demo.briefing import seed_visit_procedures
from demo.models import DemoCode, SpareVisitHousehold
from demo.visit_cleanup import delete_visit_household
from household.demo_seed import DEMO_TIMEZONE, seed_household
from household.models import Household
from ingest.models import IngestRun

DEFAULT_POOL = 2
# Under `TICKED_ITEM_TTL` (24 h) less a whole visit (2 h), with room to spare.
MAX_AGE = dt.timedelta(hours=20)
# Two-int advisory lock key, in its own space beside `assistant.turn_slots`'s.
_LOCK = (0x5350, 1)  # "SP"
# Each refill transaction's own limits (SET LOCAL): a lock it cannot get in time fails the
# refill, never a migration waiting behind it, and a transaction left idle is ended by Postgres.
_TXN_LIMITS = (
    "SET LOCAL lock_timeout = '5s'",
    "SET LOCAL idle_in_transaction_session_timeout = '30s'",
)
_SERVER_ROOT = Path(__file__).resolve().parent.parent
_NOT_BUILD = {"frontend", "node_modules", "tests", "static", "media", "__pycache__"}


def pool_size() -> int:
    """`LEGION_DEMO_SPARE_POOL`, and 0 while the demo's door is closed (no live code): an engine
    nobody can enter keeps no spare households."""
    if not DemoCode.live().exists():
        return 0
    try:
        return max(0, int(str(getattr(settings, "LEGION_DEMO_SPARE_POOL", DEFAULT_POOL))))
    except ValueError:
        return DEFAULT_POOL


def _build_key() -> str:
    """A hash of every `.py` and `.yaml` file of the server this process runs (tests, the web
    build and caches left out). Any deploy changes it; the service and the job, one image,
    agree on it. Not `K_REVISION`: the job has no service revision."""
    digest = hashlib.sha256()
    for directory, subdirs, files in os.walk(_SERVER_ROOT):
        subdirs[:] = sorted(d for d in subdirs if d not in _NOT_BUILD and not d.startswith("."))
        for name in sorted(files):
            if name.endswith((".py", ".yaml")):
                path = Path(directory) / name
                digest.update(path.relative_to(_SERVER_ROOT).as_posix().encode())
                digest.update(path.read_bytes())
    return digest.hexdigest()


_BUILD_KEY = _build_key()


def seed_version() -> str:
    """The build a spare was seeded by. A spare from any other build is never handed out."""
    return _BUILD_KEY


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
    one (`skip_locked`). What the wait left behind goes: the household's own `created_at` becomes
    the visit's, as a seed now would have stamped it, and the job rows the scheduled commands
    wrote while it waited (a heartbeat every half hour, a skipped push check every five
    minutes) are deleted, since a fresh seed has none."""
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
    IngestRun.objects.filter(household=household, started_at__gte=spare.created_at).delete()
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
            _limit_this_transaction()
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
    if size is None:
        size = pool_size()
    elif not DemoCode.live().exists():
        size = 0
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
                _limit_this_transaction()
                household = make_visit_household(today=day)
                SpareVisitHousehold.objects.create(
                    household=household, seeded_for=day, seed_version=seed_version()
                )
            seeded += 1
        return {"discarded": discarded, "seeded": seeded, "busy": 0}
    finally:
        with connection.cursor() as cursor:
            cursor.execute("select pg_advisory_unlock(%s, %s)", list(_LOCK))


def _limit_this_transaction() -> None:
    with connection.cursor() as cursor:
        for statement in _TXN_LIMITS:
            cursor.execute(statement)
