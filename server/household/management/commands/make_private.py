"""`manage.py make_private` - make a set of existing shared events private to
one member (ADR 0052, web-revamp ticket 06, spec D3 "Backfill").

    manage.py make_private --household <id> --user-email <email> \\
        (--origin-prefix canvas: | --structured-meta-key course) [--dry-run]

Kevin runs it twice on live: once for `canvas:` (Canvas coursework, which
`origin_guid` names `canvas:<assignment id>`), once for `course` (the class
schedule, a Google-imported event whose `structured_meta` carries a `course`
key). From then on the poller inserts Canvas rows private by itself.

**Idempotent.** It only ever touches SHARED rows (`owner_user_id` null), so a
second run changes nothing and says so. A matching row already private to
someone is left exactly as it is, and counted in words. Tombstoned rows are
included on purpose: a tombstone in `/api/changes` is a full row, title and
all, and a shared one would keep handing that title to everyone else.

Each changed row's `updated_at` moves (the `touch_updated_at` trigger), so a
replica that held it while it was shared receives a redacted tombstone on its
next pull.
"""

from __future__ import annotations

import uuid

from django.core.management.base import BaseCommand, CommandError
from django.db import transaction


class Command(BaseCommand):
    help = (
        "Makes existing shared events private to one member of a household, selected by "
        "origin_guid prefix or by a structured_meta key. Idempotent; --dry-run counts only."
    )

    def add_arguments(self, parser):
        parser.add_argument("--household", required=True, help="The household's uuid.")
        parser.add_argument(
            "--user-email", required=True, help="The member the rows become private to."
        )
        which = parser.add_mutually_exclusive_group(required=True)
        which.add_argument(
            "--origin-prefix", help="Events whose origin_guid starts with this, e.g. canvas:"
        )
        which.add_argument(
            "--structured-meta-key",
            help="Events whose structured_meta has this top-level key, e.g. course",
        )
        parser.add_argument(
            "--dry-run", action="store_true", help="Count what would change; change nothing."
        )

    def handle(self, *args, **options):
        from household.models import Household, HouseholdMember, User
        from legacy.models.dates import Event

        try:
            household_id = uuid.UUID(options["household"])
        except ValueError as exc:
            raise CommandError(
                f"Nothing was changed. {options['household']!r} is not a household id (a uuid)."
            ) from exc
        household = Household.objects.filter(id=household_id).first()
        if household is None:
            raise CommandError(f"Nothing was changed. No household has id {household_id}.")
        email = options["user_email"]
        user = User.objects.filter(email__iexact=email).first()
        if user is None:
            raise CommandError(f"Nothing was changed. No account has the email {email}.")
        if not HouseholdMember.objects.filter(user=user, household=household).exists():
            raise CommandError(
                f"Nothing was changed. {user.email} is not a member of {household.name}, and a "
                f"row can only be private to a member of its own household."
            )

        matching = Event.objects.filter(household=household)
        if options["origin_prefix"]:
            prefix = options["origin_prefix"]
            matching = matching.filter(origin_guid__startswith=prefix)
            what = f"events whose origin_guid starts with {prefix!r}"
        else:
            key = options["structured_meta_key"]
            matching = matching.filter(structured_meta__has_key=key)
            what = f"events whose structured_meta has the key {key!r}"

        shared = matching.filter(owner_user__isnull=True)
        already_theirs = matching.filter(owner_user=user).count()
        someone_elses = matching.exclude(owner_user__isnull=True).exclude(owner_user=user).count()
        count = shared.count()

        if options["dry_run"]:
            self.stdout.write(
                f"Dry run, nothing was changed. {count} shared {what} would become private "
                f"to {user.email}."
            )
        else:
            with transaction.atomic():
                changed = shared.update(owner_user=user)
            self.stdout.write(f"Made {changed} shared {what} private to {user.email}.")
        if already_theirs:
            self.stdout.write(f"{already_theirs} were already private to {user.email}.")
        if someone_elses:
            self.stdout.write(
                f"{someone_elses} are private to another member and were left as they are."
            )
