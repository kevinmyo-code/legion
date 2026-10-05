"""`manage.py set_household_timezone <zone> [--household <uuid>]` - set a
household's IANA timezone from the command line (Kevin, 2026-10-05).

The same write the owner makes in Settings > Household
(`PATCH /api/households/me`), for an operator at a shell. No migration sets
a value for any household, deliberately; this or the settings row is how a
household gets one.

Which household: `--household <uuid>`, or without it the bootstrap household
or the only one there is (`household.tenancy.resolve_default_household`),
refusing in words when that would be a guess - the same rule
`add_household_member` follows.
"""

from __future__ import annotations

import uuid

from django.core.management.base import BaseCommand, CommandError

from household.models import Household
from household.tenancy import BOOTSTRAP_ID_ENV, resolve_default_household
from household.timezones import is_known_zone


class Command(BaseCommand):
    help = (
        "Sets a household's IANA timezone (for example America/Chicago). Pass --household "
        "to say which; without it, the bootstrap household or the only one there is."
    )

    def add_arguments(self, parser):
        parser.add_argument("zone", type=str, help="An IANA zone name, e.g. America/Chicago.")
        parser.add_argument(
            "--household",
            type=str,
            default=None,
            help=(
                "uuid of the household. Defaults to the one "
                f"{BOOTSTRAP_ID_ENV} names, or the only household if there is exactly one."
            ),
        )

    def handle(self, *args, **options):
        zone = options["zone"].strip()
        if not is_known_zone(zone):
            raise CommandError(
                f"Nothing was changed. {zone!r} is not a timezone this server knows. Use an "
                f"IANA name such as America/Chicago."
            )
        household = self._household(options["household"])
        before = household.timezone
        household.timezone = zone
        household.save(update_fields=["timezone"])
        was = f"was {before}" if before else "was unset"
        self.stdout.write(
            self.style.SUCCESS(
                f"{household.name} ({household.id}) now keeps time in {zone} ({was})."
            )
        )

    def _household(self, raw: str | None) -> Household:
        if raw:
            try:
                wanted = uuid.UUID(raw)
            except ValueError as exc:
                raise CommandError(
                    f"Nothing was changed. --household {raw!r} is not a uuid."
                ) from exc
            household = Household.objects.filter(id=wanted).first()
            if household is None:
                raise CommandError(f"Nothing was changed. No household has id {wanted}.")
            return household
        household = resolve_default_household()
        if household is None:
            raise CommandError(
                f"Nothing was changed. This engine holds more than one household and none of "
                f"them is named by {BOOTSTRAP_ID_ENV}, so there is no household this command "
                f"may pick without guessing. Say which with --household <uuid>."
            )
        return household
