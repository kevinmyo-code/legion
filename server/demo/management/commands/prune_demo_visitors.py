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
"""

from __future__ import annotations

from datetime import timedelta

from django.core.management.base import BaseCommand
from django.db import transaction
from django.utils import timezone

from demo.models import DemoVisit
from demo.visit_cleanup import delete_visit_household
from devicelogs.models import VISIT_RETENTION_DAYS, DeviceLog
from devicelogs.web_conversation import prune_visit_records
from household.models import Household, HouseholdMember, User


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
                f"than {VISIT_RETENTION_DAYS} days. Nothing was deleted."
            )
            return
        rows = 0
        for household in households:
            rows += sum(delete_visit_household(household).values())
        with transaction.atomic():
            legacy_users.delete()
        records = prune_visit_records()
        self.stdout.write(
            f"Deleted {n_households} expired demo visit household(s) ({rows} rows, visitors "
            f"included) and {n_legacy} older visitor(s) with their tokens, and {records} visit "
            f"conversation record(s) older than {VISIT_RETENTION_DAYS} days."
        )
