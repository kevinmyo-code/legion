"""`manage.py prune_demo_visitors` (recruiter-demo tickets 02 and 13).

Deletes every recruiter visit that has expired. Since ticket 13 (ADR 0069) a
visit has its own household, and that household goes whole: every row in
every tenant table, the gated money rows included, then the visitor (its
membership and tokens with it), then the household itself
(`demo.visit_cleanup.delete_visit_household`). The `DemoVisit` row stays,
with `user`, `token` and `household` nulled, so "who came, from which ref,
when" outlives the visit.

Only a household flagged `is_demo_visit` AND named by an expired `DemoVisit`
is touched, and the cleanup refuses any household without the flag, so no
real household can be reached by this command. A visit made before ticket 13
(no household; it joined the shared demo household) loses only its visitor,
as before. Runs hourly (`deploy/crontab`). `--dry-run` says what it would
delete and deletes nothing.

**Also deletes recruiter-visit conversation records older than 30 days**
(`devicelogs.web_conversation.prune_visit_records`, Kevin 2026-11-03: recorded
"to improve" the assistant). Those rows have no household, so the visit's
household going does not take them; this step is what does, 30 days after each
arrived, whether or not its visit has ended.

**And procedure run traces past retention** (`procedures.retention.prune`,
procedures ticket 13): a visit's runs go with its household above; this is
the 30-day backstop for recruiter runs and the 60-day sweep for members'
(also run inline at each new run).

**And the spare visit households** (`demo.spares`, demo-enter-slow 2026-11-22): every spare
that may no longer be handed out (seeded for an earlier day, by an older seed, or too long ago)
is deleted whole, like an expired visit's household, and the pool is topped back up to
`LEGION_DEMO_SPARE_POOL`. This hourly run is the refill's backstop: `/enter` also starts one
after each visit, on a thread Cloud Run may starve.

**And agent turn traces past retention** (`agents.legion.retention.prune`, engine-brain ticket
02, ADR 0100): checkpoints at 7 days, turn content at 30, model-call numbers at 90. Also run
inline at each new turn; this hourly run is what prunes while the turn endpoint is off.
"""

from __future__ import annotations

import logging
from datetime import timedelta

from django.core.management.base import BaseCommand
from django.db import transaction
from django.utils import timezone

from agents.legion.retention import prune as prune_agent_traces
from demo import spares
from demo.models import CallbackRequest, DemoVisit, ShopOrder
from demo.visit_cleanup import delete_visit_household
from devicelogs.models import VISIT_RETENTION_DAYS, DeviceLog
from devicelogs.web_conversation import prune_visit_records
from household.models import Household, HouseholdMember, User
from procedures.retention import prune as prune_runs

logger = logging.getLogger(__name__)


class Command(BaseCommand):
    help = (
        "Delete expired recruiter visits: each visit's own demo household with every row "
        "in it, and the visitor with its tokens."
    )

    def add_arguments(self, parser):
        parser.add_argument("--dry-run", action="store_true")

    def handle(self, *args, **options):
        expired = DemoVisit.objects.filter(expires_at__lte=timezone.now())
        households = Household.objects.filter(
            is_demo_visit=True,
            pk__in=list(
                expired.filter(household__isnull=False).values_list("household_id", flat=True)
            ),
        )
        # Pre-ticket-13 visitors: no household of their own.
        legacy_users = User.objects.filter(
            pk__in=list(
                expired.filter(household__isnull=True, user__isnull=False).values_list(
                    "user_id", flat=True
                )
            ),
            household_member__role=HouseholdMember.RECRUITER,
            is_superuser=False,
            is_staff=False,
        )
        n_households, n_legacy = households.count(), legacy_users.count()
        if options["dry_run"]:
            stale = DeviceLog.objects.filter(
                demo_visitor=True,
                household__isnull=True,
                received_at__lt=timezone.now() - timedelta(days=VISIT_RETENTION_DAYS),
            ).count()
            self.stdout.write(
                f"Would delete {n_households} expired demo visit household(s) and "
                f"{n_legacy} older visitor(s), and {stale} visit conversation record(s) older "
                f"than {VISIT_RETENTION_DAYS} days. Nothing was deleted. Would discard "
                f"{spares.stale_count()} spare visit household(s) that may no longer be handed "
                f"out, and seed none."
            )
            return
        rows = 0
        for household in households:
            rows += sum(delete_visit_household(household).values())
        with transaction.atomic():
            legacy_users.delete()
        records = prune_visit_records()
        runs = prune_runs()
        traces = sum(prune_agent_traces().values())
        from demo import funnel

        counts = funnel.prune(VISIT_RETENTION_DAYS)
        # The store-rep demo (`demo/shop/`): orders and callback requests, a visitor's name and
        # contact included, go after the same 30 days as the visit records.
        cutoff = timezone.now() - timedelta(days=VISIT_RETENTION_DAYS)
        shop = (
            ShopOrder.objects.filter(created_at__lt=cutoff).delete()[0]
            + CallbackRequest.objects.filter(created_at__lt=cutoff).delete()[0]
        )
        # Last, and never in the way of the deletes above: a refill that fails is said in words
        # and the next visit seeds inline.
        try:
            pool = spares.refill()
            spare_line = (
                "Spare visit households: another refill was running, so none were touched."
                if pool["busy"]
                else f"Spare visit households: {pool['discarded']} discarded, {pool['seeded']} "
                f"seeded (pool {spares.pool_size()})."
            )
        except Exception as exc:  # noqa: BLE001 - reported in the output line
            logger.exception("prune_demo_visitors: the spare refill failed")
            spare_line = (
                f"Spare visit households: the refill failed ({type(exc).__name__}); the next "
                f"visit seeds inline."
            )
        self.stdout.write(
            f"Deleted {n_households} expired demo visit household(s) ({rows} rows, visitors "
            f"included) and {n_legacy} older visitor(s) with their tokens, and {records} visit "
            f"conversation record(s) older than {VISIT_RETENTION_DAYS} days, and {runs} procedure "
            f"run(s) past retention, and {traces} agent trace row(s) past retention, and "
            f"{counts} funnel count row(s) older than {VISIT_RETENTION_DAYS} days, and {shop} "
            f"store-rep order or callback row(s) older than {VISIT_RETENTION_DAYS} days. "
            + spare_line
        )
