"""`manage.py backup_nightly` - the database, dumped with `pg_dump -Fc` and
uploaded to the operator household's Google Drive under
`LEGION backups/<date>/`, 90 daily kept (backend-etl ticket 03).

`deploy/crontab` runs it at 03:00 UTC. Everything it decides lives in
`ingest/backup.py`; this is the wiring. Like every `JobCommand` it exits 0
for a failed backup: the failure is written down where `/api/freshness` reads
it, and the next night is the retry.
"""
from __future__ import annotations

import logging

from django.db import connection

from api.place_columns import converge_place_label_check
from ingest import backup
from ingest.jobs import JobCommand
from ingest.models import IngestRun, SessionSource, Source

logger = logging.getLogger(__name__)


class Command(JobCommand):
    help = (
        "Dump the database to a temp file and upload it to the operator household's "
        "Drive (LEGION backups/<date>/), keeping 90 daily. Media is not backed up yet."
    )

    source = Source.BACKUP
    session_source = SessionSource.DRIVE

    def is_configured(self, household) -> bool:
        operator = backup.backup_household()
        return operator is not None and operator.pk == household.pk

    def job(self, run: IngestRun) -> str | None:
        return backup.run_backup(run)

    def handle(self, *args, **options):
        # Not `run_for_households`: a backup is of the whole database, so it
        # runs ONCE, under the operator household, and every other household
        # gets a mirrored row (see ingest/backup.py's module docstring).
        backup.run_nightly(self.job, write=self.stdout.write)
        # After the dump, so a schema check can never cost a night's backup.
        # `ingest/migrations/0029` runs once; this is what validates the place
        # label CHECK later if that run found an overlong label.
        with connection.cursor() as cursor:
            unvalidated = converge_place_label_check(cursor)
        if unvalidated:
            logger.warning(unvalidated)
            self.stdout.write(unvalidated)
