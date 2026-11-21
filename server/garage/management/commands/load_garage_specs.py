"""`manage.py load_garage_specs <sheet.csv> [--dry-run]`: the checking sheet into the engine.

The sheet is private and lives outside the repo (`garage/example_specs.csv` shows its columns).
Only rows the owner ticked `verified`, with a figure, a unit, what it applies to, a source, a page
and a date, are stored (`garage.specs.read_sheet`); every other row is skipped, so an unchecked
figure never reaches the engine. Any problem on any row: nothing is loaded, and every problem is
named. A load REPLACES the model's whole table, so a row un-ticked on the sheet stops being
spoken. Nothing of the sheet is printed but counts and fastener ids.
"""

from __future__ import annotations

from pathlib import Path

from django.core.management.base import BaseCommand, CommandError
from django.db import transaction

from garage import specs
from garage import xj_front as m
from garage.models import FastenerSpec


class Command(BaseCommand):
    help = "Load the owner's checked fastener specs from his private checking sheet (CSV)."

    def add_arguments(self, parser):
        parser.add_argument("sheet", type=Path)
        parser.add_argument(
            "--dry-run", action="store_true", help="Check and count; store nothing."
        )

    def handle(self, *args, sheet: Path, dry_run: bool, **options):
        if not sheet.is_file():
            raise CommandError(f"{sheet} is not a file.")
        rows, skipped, problems = specs.read_sheet(sheet, m.FASTENERS_BY_ID)
        if problems:
            raise CommandError(
                "Nothing was loaded. Fix these rows on the sheet:\n- " + "\n- ".join(problems)
            )
        ids = ", ".join(r.fastener_id for r in rows) or "none"
        if dry_run:
            self.stdout.write(
                f"Dry run: {len(rows)} verified row(s) would load, {skipped} not verified "
                f"skipped. Verified: {ids}"
            )
            return
        with transaction.atomic():
            FastenerSpec.objects.filter(model=m.MODEL).delete()
            FastenerSpec.objects.bulk_create(
                FastenerSpec(
                    model=m.MODEL,
                    fastener_id=r.fastener_id,
                    torque_min=r.torque_min,
                    torque_max=r.torque_max,
                    unit=r.unit,
                    socket=r.socket,
                    thread=r.thread,
                    applies_to=r.applies_to,
                    source=r.source,
                    source_page=r.source_page,
                    verified_on=r.verified_on,
                )
                for r in rows
            )
        self.stdout.write(
            f"Loaded {len(rows)} verified row(s) for {m.MODEL}; {skipped} not verified, not "
            f"loaded. Verified: {ids}"
        )
