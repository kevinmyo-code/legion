"""`manage.py canvas_poll` - Canvas coursework kept current in `events`
(backend-etl ticket 04). `deploy/crontab` runs it every thirty minutes.

Everything it decides lives in `ingest/canvas.py` and in
`public.upsert_canvas_task` (migration 0003); this is the wiring. Like every
`JobCommand` it exits 0 for a failed run: the failure is written down where
`/api/freshness` reads it, and the next half hour is the retry.

`--dry-run` reads Canvas and runs every write inside a transaction that is
then rolled back, printing what each row WOULD become. It records no
`ingest_runs` row and leaves the database exactly as it found it, which makes
the first live run's diff against the hand-seeded tasks one command.
"""

from __future__ import annotations

from django.db import transaction

from ingest import canvas, vault
from ingest.jobs import JobCommand, NeedsLogin
from ingest.models import IngestRun, SessionSource, Source


class _RollBack(Exception):
    pass


class Command(JobCommand):
    help = (
        "Read every active Canvas course's assignments with submission state and "
        "write them as task rows in events. --dry-run prints the plan and writes nothing."
    )

    source = Source.CANVAS
    session_source = SessionSource.CANVAS

    def add_arguments(self, parser):
        parser.add_argument(
            "--dry-run",
            action="store_true",
            help="Print the planned inserts, updates and tombstones; write nothing.",
        )

    def job(self, run: IngestRun) -> str | None:
        outcome, results, plan = canvas.poll(run)
        for note in plan.notes:
            self.stdout.write(f"  note: {note}")
        return outcome

    def handle(self, *args, **options):
        if not options.get("dry_run"):
            return super().handle(*args, **options)
        from household.models import Household

        for household in Household.objects.order_by("created_at", "id"):
            if not vault.is_configured(household, SessionSource.CANVAS):
                self.stdout.write(f"canvas for household {household.pk}: not set up, skipped")
                continue
            self._dry_run(household)

    def _dry_run(self, household):
        self.stdout.write(f"canvas DRY RUN for household {household.pk}. Nothing will be kept.")
        run = IngestRun(household=household, source=Source.CANVAS)
        try:
            with transaction.atomic():
                _outcome, results, plan = canvas.poll(run)
                raise _RollBack
        except _RollBack:
            pass
        except (NeedsLogin, canvas.CanvasError) as exc:
            self.stdout.write(f"  would record {type(exc).__name__}: {exc}")
            return
        counts: dict[str, int] = {}
        for result in results:
            counts[result.get("action", "?")] = counts.get(result.get("action", "?"), 0) + 1
            if result.get("action") != "unchanged":
                self.stdout.write("  " + canvas.describe(result))
        for note in plan.notes:
            self.stdout.write(f"  note: {note}")
        summary = ", ".join(f"{k} {v}" for k, v in sorted(counts.items())) or "nothing"
        self.stdout.write(f"  planned: {summary}. Rolled back; nothing was written.")
