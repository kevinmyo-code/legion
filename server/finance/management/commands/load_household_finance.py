"""`manage.py load_household_finance <file>` (household-finance ticket 12).

Reads the household's PRIVATE finance file (outside the public repo) and
upserts its members, accounts, income plan, floors, top-up trigger and budgets.
Idempotent. Prints counts and keys only, never a figure (`finance/loader.py`).

Two ways in, because the deployed engine runs commands as a Cloud Run Job that
cannot see a laptop's disk:

- a path, for a compose engine or a laptop pointed at its own database:
  `python manage.py load_household_finance ~/Documents/<file>.md`
- `--content-b64`, the file's bytes base64-encoded into one argument, for
  `gcloud run jobs execute ... --args=manage.py,load_household_finance,--content-b64,<b64>`
  (base64 has no commas, which gcloud splits `--args` on).

`--dry-run` parses and reports the counts without writing anything.
"""

from __future__ import annotations

import base64
import binascii
from pathlib import Path

from django.conf import settings
from django.core.management.base import BaseCommand, CommandError

from finance import loader
from household.models import Household
from household.tenancy import resolve_default_household


class Command(BaseCommand):
    help = "Load the household's private finance file into the engine (counts only printed)."

    def add_arguments(self, parser):
        parser.add_argument("path", nargs="?", help="the private file, outside the repo")
        parser.add_argument("--content-b64", default=None, help="the file's bytes, base64")
        parser.add_argument("--household", default=None, help="household uuid (default: the one)")
        parser.add_argument("--dry-run", action="store_true", help="parse and count, write nothing")

    def handle(self, *args, **options):
        try:
            text = self._text(options)
            plan = loader.parse(text)
        except loader.LoaderRefused as exc:
            raise CommandError(str(exc)) from exc
        if options["dry_run"]:
            self.stdout.write(
                f"Dry run, nothing written. The file reads as {len(plan.members)} members, "
                f"{len(plan.accounts)} accounts, {len(plan.income)} income plan lines, "
                f"{len(plan.budgets)} budget caps, and "
                + ("a policy (floors and top-up trigger)." if plan.policy else "no policy.")
            )
            return
        household = self._household(options["household"])
        try:
            summary = loader.apply(plan, household)
        except loader.LoaderRefused as exc:
            raise CommandError(str(exc)) from exc
        self.stdout.write("Loaded the household's finance file.")
        for line in summary.lines():
            self.stdout.write(line)

    def _text(self, options) -> str:
        if options["content_b64"]:
            if options["path"]:
                raise loader.LoaderRefused("Give a path or --content-b64, not both.")
            try:
                return base64.b64decode(options["content_b64"], validate=True).decode("utf-8")
            except (binascii.Error, UnicodeDecodeError) as exc:
                raise loader.LoaderRefused(
                    "--content-b64 is not base64 of a UTF-8 file. Nothing was loaded."
                ) from exc
        if not options["path"]:
            raise loader.LoaderRefused("Give the private file's path. Nothing was loaded.")
        server = Path(settings.BASE_DIR).resolve()
        # A checkout's root holds `.git`; inside the image `server/` is `/app`.
        repo_root = server.parent if (server.parent / ".git").exists() else server
        return loader.read_private_file(Path(options["path"]).expanduser(), repo_root)

    def _household(self, raw):
        if raw:
            household = Household.objects.filter(id=raw).first()
            if household is None:
                raise CommandError(f"No household has the id {raw}. Nothing was loaded.")
            return household
        household = resolve_default_household()
        if household is None:
            raise CommandError(
                "This engine holds more than one household and none was named. Pass "
                "--household <uuid>. Nothing was loaded."
            )
        return household
