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

**WHICH household (ADR 0045).** `--household <uuid>` says so explicitly.
Without it, `household.tenancy.resolve_default_household` picks the bootstrap
household or the only one there is, and the command REFUSES in words when
this engine holds several and none is named - putting a person in the wrong
family and reporting success is the failure this whole ticket exists to make
impossible.
"""
from __future__ import annotations

import uuid

from django.core.management.base import BaseCommand, CommandError

from household.models import Household, HouseholdMember, User
from household.tenancy import BOOTSTRAP_ID_ENV, resolve_default_household


class Command(BaseCommand):
    help = (
        "Adds an existing user to a household (grants IsHouseholdMember). Idempotent. "
        "Pass --household to say which; without it, the bootstrap household or the only "
        "one there is."
    )

    def add_arguments(self, parser):
        parser.add_argument("email", type=str, help="Email of an existing User.")
        parser.add_argument(
            "--household",
            type=str,
            default=None,
            help=(
                "uuid of the household to add them to. Defaults to the one "
                f"{BOOTSTRAP_ID_ENV} names, or the only household if there is exactly one."
            ),
        )
        parser.add_argument(
            "--owner",
            action="store_true",
            help="Make them an owner (may invite and remove members), not a plain member.",
        )

    def handle(self, *args, **options):
        email = options["email"]
        try:
            user = User.objects.get(email=email)
        except User.DoesNotExist as exc:
            raise CommandError(
                f"No user with email {email!r}. Create one first with "
                f"'manage.py createsuperuser' or the admin."
            ) from exc

        existing = HouseholdMember.objects.filter(user=user).select_related("household").first()
        if existing is not None:
            # Said in words, and it names the household - "already a member"
            # was an unambiguous sentence when there was only one.
            self.stdout.write(
                f"{email} is already a member of {existing.household.name} "
                f"({existing.household_id}), as {existing.role}."
            )
            return

        household = self._household(options["household"])
        role = HouseholdMember.OWNER if options["owner"] else HouseholdMember.MEMBER
        HouseholdMember.objects.create(user=user, household=household, role=role)
        self.stdout.write(
            self.style.SUCCESS(
                f"Added {email} to {household.name} ({household.id}) as {role}."
            )
        )

    def _household(self, raw: str | None) -> Household:
        if raw:
            try:
                wanted = uuid.UUID(raw)
            except ValueError as exc:
                raise CommandError(f"--household {raw!r} is not a uuid. Nobody was added.") from exc
            household = Household.objects.filter(id=wanted).first()
            if household is None:
                raise CommandError(
                    f"No household has id {wanted}. Nobody was added. "
                    f"`manage.py shell -c \'from household.models import Household; "
                    f"print(list(Household.objects.values_list(\"id\", \"name\")))\'` "
                    f"lists them."
                )
            return household

        household = resolve_default_household()
        if household is None:
            raise CommandError(
                f"Nobody was added. This engine holds more than one household and none of "
                f"them is named by {BOOTSTRAP_ID_ENV}, so there is no household this "
                f"command may pick without guessing. Say which with --household <uuid>."
            )
        return household
