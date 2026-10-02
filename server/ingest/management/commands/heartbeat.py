"""`manage.py heartbeat` - records that the scheduler fired, and nothing else
(backend-etl ticket 01).

The first line in `deploy/crontab`, every thirty minutes. It reads no upstream
and writes no data; its `ok` rows are the proof that Cloud Scheduler (or the
compose worker's supercronic) is actually running the lines in that file, so a
feed that goes stale can be told apart from a scheduler that stopped.
"""
from __future__ import annotations

from ingest.jobs import JobCommand
from ingest.models import IngestRun, Source


class Command(JobCommand):
    help = "Records one ok heartbeat run per household: proof the scheduler fires."

    source = Source.HEARTBEAT

    def job(self, run: IngestRun) -> str | None:
        return None
