"""`manage.py morning_brief` - the daily brief, built at 05:00 household-local
time (ai-upgrade ticket 02, ruling 1).

`deploy/crontab` runs this EVERY HOUR. Cloud Scheduler and supercronic both
run that file in UTC (`install_schedule.py` passes no `--time-zone`), so the
05:00 is decided here, per household, by `brief.build.is_due`: local time at
or after 05:00 and no scheduled brief yet for that local date. That holds
through DST and for every household on the engine (ADR 0045), where a fixed
UTC hour would be right for one zone in one season.

A household that is not due gets no `ingest_runs` row (a row every hour
saying "skipped" would make freshness read "not set up"). A due household runs
through `ingest.jobs.run_job` like every other pipeline: recorded, and never
overlapping itself or a Refresh.

`--dry-run` composes every member's brief inside a READ ONLY transaction that
is then rolled back, prints it, and writes nothing: no `briefs` row, no
`ingest_runs` row, no advisory lock. It ignores the 05:00 gate. It still makes
the one News call when `LEGION_GEMINI_KEY` is set, unless `--no-news`.
"""

from __future__ import annotations

from django.db import connection, transaction
from django.utils import timezone

from brief.build import build_for_household, describe, is_due
from brief.models import Trigger
from brief.news import failed
from ingest.jobs import JobCommand, run_job
from ingest.models import IngestRun, Source


class _RollBack(Exception):
    pass


class Command(JobCommand):
    help = (
        "Build the daily brief for every household whose local time has reached 05:00 and "
        "that has no brief yet today. --dry-run prints every member's brief and writes nothing."
    )

    source = Source.BRIEF

    def add_arguments(self, parser):
        parser.add_argument(
            "--dry-run",
            action="store_true",
            help="Compose and print every brief in a read-only transaction; write nothing.",
        )
        parser.add_argument(
            "--no-news",
            action="store_true",
            help="With --dry-run: skip the Gemini call and print News as not built.",
        )
        parser.add_argument(
            "--force",
            action="store_true",
            help="Build now for every household, ignoring the 05:00 gate (still recorded).",
        )

    def handle(self, *args, **options):
        from household.models import Household

        households = list(Household.objects.order_by("created_at", "id"))
        if not households:
            self.stdout.write(
                "brief: this engine has no household yet, so there was nothing to build."
            )
            return
        if options.get("dry_run"):
            for household in households:
                self._dry_run(household, no_news=options.get("no_news", False))
            return
        now = timezone.now()
        for household in households:
            if not options.get("force") and not is_due(household, now):
                self.stdout.write(f"brief for household {household.pk}: not due")
                continue
            run = run_job(Source.BRIEF, household, self._job_for(household))
            line = f"brief for household {household.pk}: {run.outcome}"
            if run.error:
                line += f" ({run.error})"
            self.stdout.write(line)

    def _job_for(self, household):
        def job(run: IngestRun) -> None:
            briefs = build_for_household(household, Trigger.SCHEDULED)
            run.rows_written = len(briefs)
            if briefs and not briefs[0].news_ok:
                news = next(s for s in briefs[0].sections if s["key"] == "news")
                self.stdout.write(f"  note: {news['note']}")

        return job

    def _dry_run(self, household, *, no_news: bool):
        self.stdout.write(f"brief DRY RUN for household {household.pk}. Nothing will be kept.")
        news_builder = (
            (lambda today: failed("News was not built: --no-news was given.")) if no_news else None
        )
        # READ ONLY can only open a transaction, never join one. From a shell
        # this block IS the transaction, so Postgres itself refuses any write;
        # inside a test's own transaction the rollback alone is the guarantee.
        outermost = not connection.in_atomic_block
        try:
            with transaction.atomic():
                if outermost:
                    with connection.cursor() as cursor:
                        cursor.execute("SET TRANSACTION READ ONLY")
                kwargs = {"save": False}
                if news_builder is not None:
                    kwargs["news_builder"] = news_builder
                briefs = build_for_household(household, Trigger.SCHEDULED, **kwargs)
                for brief in briefs:
                    self.stdout.write(describe(brief))
                if not briefs:
                    self.stdout.write("  this household has no active member, so no brief.")
                raise _RollBack
        except _RollBack:
            pass
