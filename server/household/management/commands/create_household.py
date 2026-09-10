"""`manage.py create_household --name "The Wins" --owner-email kevin@example.com`
- found a household from the command line and give it its first owner.

The bootstrap path (django-engine ticket 11, ADR 0045's "a fresh compose
stack has one household, made by `manage.py create_household`"). Everything
the web app does through invite codes, this does with a shell - which is
what a brand-new engine needs, because on a brand-new engine there is nobody
to mint the first invite.

**One user belongs to exactly one household** (ADR 0045, and
`HouseholdMember` is a `OneToOneField` on the user because of it), so this
refuses in words rather than moving somebody: an email that is already a
member of some household is not silently re-homed here.

`--id` exists for the one case where the uuid is not this command's to
choose: `LEGION_BOOTSTRAP_HOUSEHOLD_ID` names the household every
pre-tenancy row was backfilled into, in every environment the database is
ever restored into (`household/tenancy.py:bootstrap_household_id`), so an
operator rebuilding that household by hand has to be able to pin it.
"""
from __future__ import annotations

import uuid

from django.core.exceptions import ValidationError as DjangoValidationError
from django.core.management.base import BaseCommand, CommandError
from django.db import transaction

from household.models import Household, HouseholdMember, User
from household.tenancy import BOOTSTRAP_ID_ENV


class Command(BaseCommand):
    help = (
        "Creates a household and makes one user its owner. Pass --password to create "
        "that user too if they do not exist yet. Pass --id to pin the household's uuid."
    )

    def add_arguments(self, parser):
        parser.add_argument("--name", required=True, help="What the household is called.")
        parser.add_argument(
            "--owner-email",
            required=True,
            dest="owner_email",
            help="Email of the user who becomes its owner (may invite and remove members).",
        )
        parser.add_argument(
            "--password",
            default=None,
            help=(
                "Creates the owner with this password if no user with that email exists "
                "yet. Ignored (with a warning) when they already exist - this command "
                "never resets a live password."
            ),
        )
        parser.add_argument(
            "--id",
            dest="household_id",
            default=None,
            help=(
                "uuid to give the household instead of a random one. Use it to rebuild "
                f"the household {BOOTSTRAP_ID_ENV} names."
            ),
        )

    @transaction.atomic
    def handle(self, *args, **options):
        name = (options["name"] or "").strip()
        if not name:
            raise CommandError("--name is empty. No household was created.")

        email = options["owner_email"].strip()
        user = User.objects.filter(email__iexact=email).first()
        if user is None:
            user = self._create_user(email, options["password"])
        elif options["password"]:
            self.stdout.write(
                self.style.WARNING(
                    f"{email} already exists; --password was given but is ignored. "
                    f"Nobody's password was changed."
                )
            )

        existing = HouseholdMember.objects.filter(user=user).select_related("household").first()
        if existing is not None:
            raise CommandError(
                f"No household was created. {email} is already a member of "
                f"{existing.household.name} ({existing.household_id}), and a user belongs "
                f"to exactly one household (ADR 0045). Remove them from it first, or use a "
                f"different email."
            )

        household = Household(name=name, created_by=user)
        if options["household_id"]:
            household.id = self._uuid(options["household_id"])
            if Household.objects.filter(id=household.id).exists():
                raise CommandError(
                    f"No household was created: {household.id} is already the id of a "
                    f"household on this engine."
                )
        household.save()
        HouseholdMember.objects.create(
            user=user, household=household, role=HouseholdMember.OWNER
        )

        self.stdout.write(
            self.style.SUCCESS(
                f"Created household {household.name} ({household.id}) with {email} as owner."
            )
        )
        if Household.objects.count() > 1:
            # Said every time it becomes true, because it is the moment two
            # OTHER commands stop being able to guess: `add_household_member`
            # with no --household and the superuser signal both fall back to
            # "the only household there is", and there is no longer only one.
            self.stdout.write(
                self.style.WARNING(
                    f"This engine now holds more than one household. Set {BOOTSTRAP_ID_ENV} "
                    f"in deploy/.env, or pass --household to `add_household_member`: "
                    f"neither that command nor `createsuperuser` will guess which family "
                    f"you meant."
                )
            )

    def _uuid(self, raw: str) -> uuid.UUID:
        try:
            return uuid.UUID(raw)
        except ValueError as exc:
            raise CommandError(
                f"--id {raw!r} is not a uuid. No household was created."
            ) from exc

    def _create_user(self, email: str, password: str | None) -> User:
        if not password:
            raise CommandError(
                f"No user with email {email!r}, and no --password to create one. No "
                f"household was created. Either create the user first "
                f"('manage.py createsuperuser' or the admin) or pass --password."
            )
        try:
            from django.contrib.auth.password_validation import validate_password

            validate_password(password)
        except DjangoValidationError as exc:
            raise CommandError(
                f"Password for {email!r} was rejected: {'; '.join(exc.messages)}. No "
                f"household and no user were created."
            ) from exc
        user = User.objects.create_user(email=email, password=password)
        # The password itself is never echoed, here or anywhere else - same
        # posture as `DeviceToken.issue` never persisting a raw key.
        self.stdout.write(self.style.SUCCESS(f"Created user {email}."))
        return user
