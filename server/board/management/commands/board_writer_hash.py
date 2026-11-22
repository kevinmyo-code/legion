"""`manage.py board_writer_hash --email <member> --sha256 <hash>`: register the board writer.

The laptop makes the token itself (`push.py --new-token`) and prints only its SHA-256; the member
runs this with that hash. The raw token never leaves the laptop and never reaches the engine's logs:
only the hash is stored, exactly what `POST /api/board/writer` would store. Any earlier writer of
that member dies now. A recruiter or a non-member is refused. Prints ids only, never the email.
"""

from __future__ import annotations

import re

from django.core.management.base import BaseCommand, CommandError
from django.db import transaction
from django.utils import timezone

from board.models import BoardSnapshot
from household.models import HouseholdMember, User

_HASH = re.compile(r"^[0-9a-f]{64}$")


class Command(BaseCommand):
    help = "Register the laptop's board writer token by its SHA-256 (the token never leaves it)."

    def add_arguments(self, parser):
        parser.add_argument("--email", required=True)
        parser.add_argument("--sha256", required=True)

    def handle(self, *args, email, sha256, **options):
        digest = sha256.strip().lower()
        if not _HASH.match(digest):
            raise CommandError("Nothing was set: --sha256 must be 64 hex characters.")
        user = User.objects.filter(email__iexact=email.strip(), is_active=True).first()
        member = HouseholdMember.objects.filter(user=user).first() if user else None
        if member is None or member.role == HouseholdMember.RECRUITER:
            raise CommandError("Nothing was set: no active household member has that email.")
        with transaction.atomic():
            row, _ = BoardSnapshot.objects.get_or_create(
                owner_user=user, defaults={"household_id": member.household_id}
            )
            row.household_id = member.household_id
            row.writer_key_hash = digest
            row.writer_created_at = timezone.now()
            row.writer_last_used_at = None
            row.save(
                update_fields=[
                    "household",
                    "writer_key_hash",
                    "writer_created_at",
                    "writer_last_used_at",
                ]
            )
        self.stdout.write(f"Board writer set for board {row.pk}; any earlier writer is dead.")
