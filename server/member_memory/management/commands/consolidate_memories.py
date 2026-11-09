"""`manage.py consolidate_memories`: finished conversation records become memories.

Hourly from `deploy/crontab` (memory-and-vault ticket 03). See `member_memory/consolidate.py`
for what the model is shown and what it may write. Prints counts only, never a memory or a
transcript.

Runs once per household through `ingest.jobs.run_for_households` (source `memory`, 2026-11-09),
so each run leaves an `ingest_runs` row and `/api/freshness` says when memory was last updated.
"""

from __future__ import annotations

from django.core.management.base import BaseCommand

from ingest.jobs import run_for_households
from ingest.models import Outcome, Source
from ingest.statements import gemini_key
from member_memory import consolidate


class Command(BaseCommand):
    help = "Distil finished conversation records into members' private memories."

    def add_arguments(self, parser):
        parser.add_argument(
            "--limit",
            type=int,
            default=consolidate.BATCH_LIMIT,
            help=(
                f"At most this many records per household this run "
                f"(default {consolidate.BATCH_LIMIT})."
            ),
        )

    def handle(self, *args, limit, **options):
        key = gemini_key() or ""
        limit = max(1, limit)

        def job(run):
            report = consolidate.run(limit=limit, key=key, household=run.household)
            self.stdout.write(report.sentence())
            run.rows_written = report.written
            run.rows_unchanged = report.skipped
            if report.no_key:
                # Freshness says "is not being read" and this reason, like the statements job.
                run.error = report.sentence()
                return Outcome.SKIPPED
            if report.all_failed:
                # Not one record could be read: the model is down or refusing. Recorded as a
                # failed run so freshness says so and goes stale, not as an ok run that wrote 0.
                raise RuntimeError(report.sentence())
            return None

        run_for_households(Source.MEMORY, job, write=self.stdout.write)
