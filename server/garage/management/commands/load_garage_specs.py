"""`manage.py load_garage_specs <sheet.csv> --profile <truck.csv> [--dry-run | --confirm N]`.

The owner's checking sheet and truck profile into the engine. Both are private and live outside
the repo (`garage/example_specs.csv` and `garage/example_truck.csv` show the columns, made up).
Only rows the owner ticked `verified`, complete and fitting his truck, are stored
(`garage.specs.read_sheet`); any problem on any row: nothing is loaded and every problem is named.

**Writing needs `--confirm N`, N being the number of verified rows the dry run reported**, so a
load is never one typed command: the owner's private load script shows the dry run and asks a
person at a terminal to type the count. A load REPLACES the table and the
profile, so a row un-ticked on the sheet stops being spoken. Nothing of the sheet is printed but
counts and fastener ids.
"""

from __future__ import annotations

from pathlib import Path

from django.core.management.base import BaseCommand, CommandError
from django.db import transaction

from garage import specs
from garage import xj_front as m
from garage.models import FastenerSpec, GarageTruck


class Command(BaseCommand):
    help = "Load the owner's checked fastener specs and truck profile from his private sheets."

    def add_arguments(self, parser):
        parser.add_argument("sheet", type=Path)
        parser.add_argument("--profile", type=Path, required=True)
        group = parser.add_mutually_exclusive_group(required=True)
        group.add_argument("--dry-run", action="store_true", help="Check and count; store nothing.")
        group.add_argument(
            "--confirm", type=int, help="The verified-row count the dry run reported."
        )

    def handle(self, *args, sheet: Path, profile: Path, dry_run: bool, confirm, **options):
        for path in (sheet, profile):
            if not path.is_file():
                raise CommandError(f"Nothing was loaded. {path} is not a file.")
        truck, problems = specs.read_profile(profile)
        if truck is None:
            raise CommandError(
                "Nothing was loaded. Fix the truck profile:\n- " + "\n- ".join(problems)
            )
        rows, skipped, problems, kit_ids = specs.read_sheet(sheet, m.FASTENERS_BY_ID, truck)
        if problems:
            raise CommandError(
                "Nothing was loaded. Fix these rows on the sheet:\n- " + "\n- ".join(problems)
            )
        ids = ", ".join(r.fastener_id for r in rows) or "none"
        summary = (
            f"{len(rows)} verified row(s), {skipped} not verified; {len(kit_ids)} fastener(s) "
            f"with kit hardware on the truck. Verified: {ids}"
        )
        if dry_run:
            self.stdout.write(f"Dry run, nothing stored: {summary}")
            self.stdout.write(f"VERIFIED_ROWS={len(rows)}")
            return
        if confirm != len(rows):
            raise CommandError(
                f"Nothing was loaded. --confirm {confirm} does not match the {len(rows)} verified "
                "row(s) on the sheet. Run the dry run and check it first."
            )
        with transaction.atomic():
            GarageTruck.objects.update_or_create(
                model=m.MODEL,
                defaults={
                    "year": truck.year,
                    "engine": truck.engine,
                    "axle": truck.axle,
                    "lift_in": truck.lift_in,
                    "kit_fasteners": sorted(kit_ids),
                },
            )
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
                    year_from=r.year_from,
                    year_to=r.year_to,
                    engine=r.engine,
                    axle=r.axle,
                    hardware=r.hardware,
                    kit_name=r.kit_name,
                    source_kind=r.source_kind,
                    source=r.source,
                    source_page=r.source_page,
                    verified_on=r.verified_on,
                )
                for r in rows
            )
        self.stdout.write(f"Loaded for {m.MODEL}: {summary}")
