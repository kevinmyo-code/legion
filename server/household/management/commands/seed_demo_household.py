"""`manage.py seed_demo_household [--reset]` - build the FAKE demo household
recruiters read (recruiter-demo ticket 03).

Idempotent: a second run writes nothing new. `--reset` first deletes every row
of the demo household the engine permits deleting, then reseeds; the gated
rows (statements, their transactions, receipts, their line items) are immutable
by trigger and are kept, and the reseed finds them already committed. Run
nightly with `--reset` so dates stay relative to today.

Creates no users and no members. `household/demo_seed.py` has the data and
says which engine path writes each aspect.
"""
from __future__ import annotations

import time

from django.core.management.base import BaseCommand, CommandError
from rest_framework.exceptions import ValidationError

from household.demo import DemoHouseholdError
from household.demo_seed import seed
from ingest.gate import GateInputError


class Command(BaseCommand):
    help = (
        "Builds the fake demo household (LEGION_DEMO_HOUSEHOLD_NAME, default 'Demo'). "
        "Idempotent. --reset deletes what the engine allows deleting, then reseeds."
    )

    def add_arguments(self, parser):
        parser.add_argument(
            "--reset",
            action="store_true",
            help=(
                "Delete the demo household's deletable rows first. Gated rows are immutable "
                "and are kept; the reseed finds them already committed."
            ),
        )

    def handle(self, *args, **options):
        started = time.perf_counter()
        try:
            report = seed(reset_first=options["reset"])
        except (DemoHouseholdError, GateInputError) as exc:
            raise CommandError(f"{exc} The demo household was not seeded.") from exc
        except ValidationError as exc:
            raise CommandError(
                f"A seeded row was refused by the API's own validation: {exc.detail}. "
                f"The demo household was not fully seeded."
            ) from exc

        household = report.household
        verb = "Created" if report.created_household else "Refreshed"
        self.stdout.write(
            self.style.SUCCESS(f"{verb} demo household {household.name} ({household.id}).")
        )
        if report.reset_deleted:
            deleted = ", ".join(f"{n} {name}" for name, n in report.reset_deleted.items())
            kept = ", ".join(f"{n} {name}" for name, n in report.reset_kept.items())
            self.stdout.write(f"Reset deleted: {deleted}.")
            self.stdout.write(f"Reset kept (immutable, gated): {kept}.")
        self.stdout.write(f"Statements: {report.statements}.")
        self.stdout.write(f"Receipts: {report.receipts}.")
        self.stdout.write(f"Card (provisional, unverified): {report.card}.")
        self.stdout.write(f"Upserted: {report.upserts}.")
        bad = report.bad_receipt
        self.stdout.write(
            f"The unbalanced receipt: {bad.get('outcome')}. {bad.get('reason', '')}".rstrip()
        )
        # Ticket 13: every recruiter visit runs this same seed inside
        # `POST /api/demo/enter`, so this is roughly what a visitor waits for.
        self.stdout.write(f"Seeded in {time.perf_counter() - started:.2f} s.")
