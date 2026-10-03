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


# =============================================================================
# The session vault (backend-etl ticket 02)
# =============================================================================


class SessionSource(models.TextChoices):
    """The upstreams a person hands a session to, one row each per household.

    Not `Source`: that enum names PIPELINES, and two pipelines (`backup`,
    `drive_statements`) share the one `drive` credential (map ruling 4).

    **`bofa` is deliberately absent and must stay absent** (map ruling 7,
    ticket 09): a BofA session never leaves Kevin's laptop. The PUT route
    refuses it by name, and the check constraint below refuses it again in
    SQL, so a bug in the view still cannot store one.
    """

    CANVAS = "canvas", "Canvas"
    WEBASSIGN = "webassign", "WebAssign"
    DRIVE = "drive", "Google Drive"


class CredentialKind(models.TextChoices):
    # A browser's cookies for one site, captured after a person logged in by
    # hand (Canvas, WebAssign). Never a password: none is ever asked for.
    COOKIE_JAR = "cookie_jar", "Cookie jar"
    # A Google installed-app OAuth refresh token plus the household's own
    # client id and secret, which the server needs to redeem it.
    OAUTH_REFRESH = "oauth_refresh", "OAuth refresh token"


# The script a person runs on their laptop to hand a session over. Named once:
# the freshness sentence and every vault message point at it. The sentence
# carries no trailing full stop after it, so a copied command is runnable.
LOGIN_SCRIPT = "tools/connect_session.py"

# Which saved session each pipeline runs on. A pipeline absent here needs no
# login (`heartbeat`, `obd_rollup`), so its freshness sentence never tells a
# person to run the login script.
SESSION_FOR_SOURCE: dict[str, str] = {
    Source.CANVAS: SessionSource.CANVAS,
    Source.WEBASSIGN: SessionSource.WEBASSIGN,
    Source.DRIVE_STATEMENTS: SessionSource.DRIVE,
    Source.BACKUP: SessionSource.DRIVE,
}


# The kind is decided by the source, never by the caller: a client cannot
# store a cookie jar under `drive` and have a job misread it.
KIND_FOR_SOURCE: dict[str, str] = {
    SessionSource.CANVAS: CredentialKind.COOKIE_JAR,
    SessionSource.WEBASSIGN: CredentialKind.COOKIE_JAR,
    SessionSource.DRIVE: CredentialKind.OAUTH_REFRESH,
}


class SourceCredential(models.Model):
    """One saved session for one upstream, for one household.

    `ciphertext` is a Fernet token over the JSON secret, under
    `LEGION_VAULT_KEY` (`ingest/vault.py`). Nothing else on this row is
    secret, and nothing that is secret is anywhere else on it: `config` holds
    what a job needs to know that is safe to show a member (the Canvas base
    URL, the Drive folder id), and it is served by `GET` as it is.

    **In `public`, like `ingest_runs`**, for the same reason and by the same
    migration pattern: it belongs to a household, so it is in
    `household.tenancy.TENANT_TABLES` and the tenancy tests hold it to that.
    A row here readable by anything with `public` access (the read-only
    role, a backup dump) exposes only ciphertext; the key lives in the
    environment, never in the database.
    """

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    household = models.ForeignKey(
        "household.Household", on_delete=models.PROTECT, related_name="+"
    )
    source = models.TextField(choices=SessionSource.choices)
    kind = models.TextField(choices=CredentialKind.choices)
    ciphertext = models.BinaryField()
    captured_at = models.DateTimeField()
    # The earliest expiry the captured cookies declared, if any did. A hint,
    # not a promise: servers end sessions early all the time.
    expires_hint = models.DateTimeField(null=True, blank=True)
    # Set when an upstream refused this session (401/403 or a login
    # redirect); cleared when a person hands over a fresh one.
    invalid_since = models.DateTimeField(null=True, blank=True)
    # ADR 0052 (web-revamp ticket 06): the member who handed this session
    # over. Canvas rows the poller inserts are private to them. Backfilled to
    # the household's one owner by `0011_source_credentials_user`; null when
    # that was ambiguous, and then the poller inserts shared rows. Never
    # served (`ingest/sessions.py` builds its body by hand).
    user = models.ForeignKey(
        "household.User", null=True, blank=True, on_delete=models.SET_NULL, related_name="+"
    )
    config = models.JSONField(default=dict, blank=True)

    class Meta:
        db_table = "source_credentials"
        constraints = [
            models.UniqueConstraint(
                fields=["household", "source"], name="source_credentials_hh_source_uniq"
            ),
            models.CheckConstraint(
                condition=models.Q(source__in=SessionSource.values),
                name="source_credentials_source_valid",
            ),
            models.CheckConstraint(
                condition=models.Q(kind__in=CredentialKind.values),
                name="source_credentials_kind_valid",
            ),
            # KIND_FOR_SOURCE, again in SQL (section 7).
            models.CheckConstraint(
                condition=(
                    models.Q(
                        source__in=[SessionSource.CANVAS, SessionSource.WEBASSIGN],
                        kind=CredentialKind.COOKIE_JAR,
                    )
                    | models.Q(source=SessionSource.DRIVE, kind=CredentialKind.OAUTH_REFRESH)
                ),
                name="source_credentials_kind_matches_source",
            ),
        ]

    def __str__(self) -> str:
        return f"{self.source} session ({self.kind})"
