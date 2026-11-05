"""`manage.py import_legacy_memories --member <email>`: the one-time import, engine side.

Reads the phone's memories from the engine's legacy replica (`companion_memories` and
`memories`) and writes them into memory v2 as the named member's private memories
(`member_memory/importer.py`). Idempotent: a second run imports nothing new. `--dry-run` counts
without writing. Prints counts only, never a memory's text.
"""

from __future__ import annotations

from django.core.management.base import BaseCommand, CommandError
from django.db import transaction

from household.models import HouseholdMember, User
from member_memory import importer


class Command(BaseCommand):
    help = "Import the phone's legacy memories into memory v2, private to one member."

    def add_arguments(self, parser):
        parser.add_argument("--member", required=True, help="The member's sign-in email.")
        parser.add_argument("--dry-run", action="store_true", help="Count, write nothing.")

    def handle(self, *args, member, dry_run, **options):
        user = User.objects.filter(email__iexact=member.strip()).first()
        if user is None:
            raise CommandError(f"Nothing was imported. No account signs in as {member!r}.")
        membership = HouseholdMember.objects.filter(user=user).select_related("household").first()
        if membership is None:
            raise CommandError("Nothing was imported. That account belongs to no household.")
        if membership.role == HouseholdMember.RECRUITER:
            raise CommandError("Nothing was imported. A recruiter visitor has no memory.")
        rows = importer.legacy_rows(membership.household)
        with transaction.atomic():
            counts = importer.import_for(membership.household, user, rows)
            if dry_run:
                transaction.set_rollback(True)
        verb = "Would import" if dry_run else "Imported"
        self.stdout.write(
            f"{verb} {counts['imported']} of {len(rows)} legacy memories; "
            f"{counts['already_here']} already here, {counts['refused_sensitive']} refused as "
            f"vault identifiers, {counts['refused_invalid']} unreadable."
        )
