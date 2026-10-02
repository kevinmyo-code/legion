"""`manage.py apply_category_rules [--dry-run] [--household <uuid>]`.

Backfill for backend-etl ticket 14, option 2. For every ledger transaction
whose effective category is empty (stored `category IS NULL` and no live
override), apply the household's `category_rules` - the same rules, order and
match as the insert path and the phone - and write a `source='rule'` row to
`ledger_transaction_categories`. The gated row itself is never touched;
`forbid_mutation_of_facts` would refuse it anyway.

- **Idempotent.** A second run writes nothing; an INSERT that meets any
  existing override yields to it (`on conflict do nothing`).
- **Never overwrites a `person` override.** It only inserts.
- **Leaves a deliberately removed override alone.** A tombstoned override
  means someone cleared that category on purpose; it is counted, not refilled.
- **`--dry-run` writes nothing** and prints the same counts.

Every household by default, each in its own transaction. `ingest/category_overrides.py`
holds the logic; this is the door.
"""
from __future__ import annotations

import uuid

from django.core.management.base import BaseCommand, CommandError

from household.models import Household
from ingest.category_overrides import backfill_household


class Command(BaseCommand):
    help = (
        "Write rule-sourced category overrides for ledger transactions that have no "
        "category. Idempotent; never overwrites a person's category. --dry-run writes nothing."
    )

    def add_arguments(self, parser):
        parser.add_argument(
            "--dry-run", action="store_true", help="Print the counts and write nothing."
        )
        parser.add_argument(
            "--household", help="One household's uuid. Default: every household."
        )

    def handle(self, *args, dry_run: bool = False, household: str | None = None, **options):
        households = Household.objects.order_by("id")
        if household:
            try:
                wanted = uuid.UUID(household)
            except ValueError as exc:
                raise CommandError(
                    f"Nothing was written. {household!r} is not a household uuid."
                ) from exc
            households = households.filter(id=wanted)
            if not households.exists():
                raise CommandError(f"Nothing was written. No household has id {wanted}.")

        prefix = "DRY RUN, nothing written. " if dry_run else ""
        totals = {"uncategorised": 0, "matched": 0, "written": 0, "unmatched": 0, "removed": 0}
        for row in households:
            report = backfill_household(row, dry_run=dry_run)
            self.stdout.write(prefix + report.line(dry_run))
            totals["uncategorised"] += report.uncategorised
            totals["matched"] += report.matched
            totals["written"] += report.written
            totals["unmatched"] += report.unmatched
            totals["removed"] += report.left_alone_removed

        written = totals["matched"] if dry_run else totals["written"]
        verb = "would write" if dry_run else "wrote"
        self.stdout.write(
            f"{prefix}Total: {totals['uncategorised']} uncategorised, {totals['matched']} matched "
            f"a rule, {verb} {written}, {totals['unmatched']} matched no rule, "
            f"{totals['removed']} left alone (override deleted on purpose)."
        )
