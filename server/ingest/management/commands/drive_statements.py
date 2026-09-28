"""`manage.py drive_statements` - raw bank statements from the household's Drive
folder, through the section 4 gate (backend-etl ticket 06). `deploy/crontab`
runs it every six hours.

Everything it decides lives in `ingest/statements.py`, and the commit is
`ingest.views.commit_statement`, the same function `POST /api/ingest/statement`
calls. Like every `JobCommand` it exits 0 for a failed run: the failure is
written down where `/api/freshness` reads it, and the next run is the retry.
"""
from __future__ import annotations

from ingest import statements
from ingest.jobs import JobCommand
from ingest.models import IngestRun, SessionSource, Source


class Command(JobCommand):
    help = (
        "Read new bank PDFs/CSVs from the Drive statements folder, extract them (a parser "
        "where one exists, else Gemini on LEGION_GEMINI_KEY) and commit them through the "
        "reconciliation gate. A file that does not reconcile is quarantined with its reason."
    )

    source = Source.DRIVE_STATEMENTS
    session_source = SessionSource.DRIVE

    def is_configured(self, household) -> bool:
        return statements.is_configured(household)

    def job(self, run: IngestRun) -> str | None:
        outcome, report = statements.process(run, write=self.stdout.write)
        for note in report.notes:
            self.stdout.write(f"  note: {note}")
        return outcome
