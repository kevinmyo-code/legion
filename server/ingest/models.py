"""`ingest_runs`: one row per attempt of one scheduled pipeline for one household
(backend-etl ticket 01).

Every pipeline in the backend-etl map is a management command wrapped by
`ingest.jobs.run_job`, and this is what that wrapper writes. It is the
evidence behind `GET /api/freshness`: a surface that says "Canvas last synced 3
hours ago" is reading the newest `ok` row here, never a timestamp a job chose
to report about itself.

**Physically in `public`, like `checklists`, and for the same reason.** It is
household data (ADR 0045: every data row belongs to one household, and this one
says what happened to a household's feeds), it is in
`household.tenancy.TENANT_TABLES`, and `tests/test_tenancy.py` asserts that
every `public` table carrying `household_id` is on that list and vice versa.
`migrations/0001_initial.py` uses the same temporary `search_path` swap
`checklists/migrations/0001_initial.py` documents.

**`outcome` is NULL while a run is in progress.** `run_job` writes the row
before calling the job and fills `outcome` and `finished_at` after, so a run
that is killed mid-flight (container OOM, Cloud Run timeout) stays visible as a
row that started and never finished, rather than leaving no trace at all.
"""
from __future__ import annotations

import uuid

from django.db import models
from django.db.models.functions import Now


class Source(models.TextChoices):
    """Every pipeline the backend-etl map names. `heartbeat` is not a data
    feed: it only proves the scheduler fires (ticket 01's own crontab line)."""

    CANVAS = "canvas", "Canvas"
    WEBASSIGN = "webassign", "WebAssign"
    DRIVE_STATEMENTS = "drive_statements", "Drive statements"
    BACKUP = "backup", "Backup"
    OBD_ROLLUP = "obd_rollup", "OBD roll-up"
    HEARTBEAT = "heartbeat", "Heartbeat"


class Outcome(models.TextChoices):
    OK = "ok", "OK"
    FAILED = "failed", "Failed"
    # The upstream refused the saved session. Distinct from `failed` because
    # the fix is a person logging in again (map ruling 2), not the next run.
    NEEDS_LOGIN = "needs_login", "Needs login"
    # Another run of the same source for the same household held the lock.
    SKIPPED_LOCKED = "skipped_locked", "Skipped, already running"
    # The household has not configured this source. A stranger's clone without
    # Canvas records this, quietly, rather than crashing or recording `failed`.
    SKIPPED = "skipped", "Skipped, not set up"


class IngestRun(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey(
        "household.Household", on_delete=models.PROTECT, related_name="+"
    )
    source = models.TextField(choices=Source.choices)
    started_at = models.DateTimeField(db_default=Now())
    finished_at = models.DateTimeField(null=True, blank=True)
    outcome = models.TextField(choices=Outcome.choices, null=True, blank=True)
    rows_written = models.PositiveIntegerField(default=0)
    rows_unchanged = models.PositiveIntegerField(default=0)
    # Source-defined: a Canvas `updated_since`, a Drive page token, the last
    # file id seen. Opaque to everything but the job that wrote it.
    watermark = models.TextField(null=True, blank=True)
    # Never a secret. `ingest.jobs.scrub` runs over every message before it is
    # stored, because this column is served to every member by /api/freshness.
    error = models.TextField(null=True, blank=True)

    class Meta:
        db_table = "ingest_runs"
        indexes = [
            models.Index(
                fields=["household", "source", "-started_at"],
                name="ingest_runs_hh_src_start_idx",
            ),
        ]
        constraints = [
            # Section 7: an integrity rule that must hold even if Django has a
            # bug is SQL. `choices=` alone is validation Django only runs in a
            # form or serializer, never on `save()`.
            models.CheckConstraint(
                condition=models.Q(source__in=Source.values),
                name="ingest_runs_source_valid",
            ),
            models.CheckConstraint(
                condition=models.Q(outcome__isnull=True) | models.Q(outcome__in=Outcome.values),
                name="ingest_runs_outcome_valid",
            ),
        ]

    def __str__(self) -> str:
        return f"{self.source} {self.outcome or 'running'} @ {self.started_at}"
