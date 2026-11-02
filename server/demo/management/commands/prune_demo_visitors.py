"""`manage.py prune_demo_visitors` (recruiter-demo ticket 02).

Deletes every recruiter visitor whose visit has expired: the `User`, and with
it (CASCADE) its demo-household membership, its device tokens and anything
else keyed on it. The `DemoVisit` row stays, with `user` and `token` nulled, so
"who came, from which ref, when" outlives the visitor; `AssistantCall` rows
keep their household and lose their user the same way (SET_NULL).

Only users who are `recruiter` members AND have a `DemoVisit` are touched, so
no real member can be deleted by this command whatever state their tokens are
in. Meant to run nightly beside ticket 03's demo reset. `--dry-run` says what
it would delete and deletes nothing.
"""

from __future__ import annotations

from django.core.management.base import BaseCommand
from django.db import transaction
from django.utils import timezone

from demo.models import DemoVisit
from household.models import HouseholdMember, User


class Command(BaseCommand):
    help = "Delete recruiter visitors (and their tokens) whose two-hour visit has ended."

    def add_arguments(self, parser):
        parser.add_argument("--dry-run", action="store_true")

    def handle(self, *args, **options):
        expired_user_ids = DemoVisit.objects.filter(
            expires_at__lte=timezone.now(), user__isnull=False
        ).values_list("user_id", flat=True)
        users = User.objects.filter(
            pk__in=list(expired_user_ids),
            household_member__role=HouseholdMember.RECRUITER,
            is_superuser=False,
            is_staff=False,
        )
        count = users.count()
        if options["dry_run"]:
            self.stdout.write(f"Would delete {count} expired demo visitor(s). Nothing was deleted.")
            return
        with transaction.atomic():
            users.delete()
        self.stdout.write(f"Deleted {count} expired demo visitor(s) and their tokens.")
