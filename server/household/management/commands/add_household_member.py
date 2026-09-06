"""`manage.py add_household_member <email>` - the missing piece between
`createsuperuser`/the admin and `IsHouseholdMember`. A `User` row alone does
not pass that permission (`household.permissions.IsHouseholdMember` checks
for a `HouseholdMember` row, not merely an authenticated user) - found the
hard way, 2026-09-05: a freshly `createsuperuser`-made account got a bare
403 from every authenticated endpoint until a `HouseholdMember` row was
added by hand. `household/signals.py` closes this gap automatically for a
SUPERUSER from here on; this command is the explicit path for the SECOND
adult, who is a household member but not necessarily a superuser, and
remains the general-purpose way to fix an account that predates the signal.

Idempotent, and says which happened in words - a fresh clone's first-run
script can call this unconditionally without checking first.
"""
from __future__ import annotations

from django.core.management.base import BaseCommand, CommandError

from household.models import HouseholdMember, User


class Command(BaseCommand):
    help = "Adds an existing user to the household (grants IsHouseholdMember). Idempotent."

    def add_arguments(self, parser):
        parser.add_argument("email", type=str, help="Email of an existing User.")

    def handle(self, *args, **options):
        email = options["email"]
        try:
            user = User.objects.get(email=email)
        except User.DoesNotExist as exc:
            raise CommandError(
                f"No user with email {email!r}. Create one first with "
                f"'manage.py createsuperuser' or the admin."
            ) from exc

        _member, created = HouseholdMember.objects.get_or_create(user=user)
        if created:
            self.stdout.write(self.style.SUCCESS(f"Added {email} to the household."))
        else:
            self.stdout.write(f"{email} is already a household member.")
