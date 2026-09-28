"""`manage.py forget_quarantined <file name> [--household <uuid>]` - let
`drive_statements` try a quarantined statement again after a parser fix.

A file the watcher has seen is remembered by its content hash on
`ingested_files`, and a QUARANTINED record is never retried: that is what keeps
a bad file from being re-sent to Gemini every six hours. When the refusal was
the parser's fault and the parser has since been fixed, that memory has to go,
one file at a time and on purpose.

Only QUARANTINED records are forgotten. A committed file is never touched: its
rows and anchors stand, and forgetting it would let the same statement commit
twice. Nothing else is deleted. The file then needs a fresh copy dropped in the
Drive folder, because the watcher lists only files new or changed since its last
run.

Found 2026-09-28: Kevin's checking statement quarantined on a promotional
paragraph BofA printed mid-table, the parser was fixed, and the file had no way
back in.
"""
from __future__ import annotations

from django.core.management.base import BaseCommand, CommandError

from legacy.enums import IngestState
from legacy.models.ingest import IngestedFile


class Command(BaseCommand):
    help = "Forget one quarantined statement so the next drive_statements run can retry it."

    def add_arguments(self, parser):
        parser.add_argument("name", help="The file's name as it appears in the Drive folder.")
        parser.add_argument(
            "--household",
            help="The household's id. Optional when only one household holds that file name.",
        )

    def handle(self, *args, name: str, household: str | None = None, **options):
        files = IngestedFile.objects.filter(display_name=name)
        if household:
            files = files.filter(household_id=household)
        if not files.exists():
            raise CommandError(f"No statement named {name} has been seen. Nothing was changed.")

        quarantined = files.filter(state=IngestState.QUARANTINED)
        households = set(quarantined.values_list("household_id", flat=True))
        if len(households) > 1:
            raise CommandError(
                f"More than one household has a quarantined {name}. Pass --household. "
                "Nothing was changed."
            )
        if not households:
            raise CommandError(
                f"{name} is not quarantined, so there is nothing to retry. Nothing was changed."
            )

        forgotten = quarantined.count()
        quarantined.delete()
        self.stdout.write(
            f"Forgot {forgotten} quarantined record(s) for {name}. Drop a fresh copy of the file "
            "in the statements folder and the next drive_statements run will read it again."
        )
