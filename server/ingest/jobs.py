"""The one wrapper every scheduled pipeline runs inside (backend-etl ticket 01).

Every pipeline in the backend-etl map is a management command, and every one of
those commands does its work through `run_job`, so that each run:

- **is recorded**, as an `ingest_runs` row written before the work starts and
  completed after it, which is what `GET /api/freshness` reads;
- **cannot overlap itself**, per source and per household, through a Postgres
  advisory lock rather than a lock file, because the two schedulers (Cloud
  Scheduler firing a Cloud Run Job, supercronic in the compose worker) share
  nothing but the database;
- **runs by hand exactly as it runs on schedule**: `python manage.py <task>`
  from a laptop takes the same lock and writes the same row.

A job whose upstream is down records `failed` and its command still exits 0.
The next scheduled run is the retry; a non-zero exit would only make Cloud Run
retry immediately against the same outage and mark the execution red for
something that is already written down, in words, where a surface can read it.
"""
from __future__ import annotations

import hashlib
import logging
import re
from collections.abc import Callable

from django.core.management.base import BaseCommand
from django.db import connection, transaction
from django.utils import timezone

from ingest.models import IngestRun, Outcome, Source

logger = logging.getLogger(__name__)

# A job returns None for `ok`, or one of these to say it ran and did something
# other than succeed without raising. `failed` is not here: a failure raises.
RETURNABLE_OUTCOMES = frozenset({Outcome.OK, Outcome.NEEDS_LOGIN, Outcome.SKIPPED})

MAX_ERROR_LENGTH = 2000


class NeedsLogin(Exception):
    """Raise from a job when the upstream refused the saved session.

    Recorded as `needs_login`, not `failed`, because the next scheduled run
    cannot fix it: map ruling 2 says a person logs in by hand in a real
    browser and hands the session over. The message is stored (scrubbed) like
    any other error.

    `refused_credential` (ticket 02) is the `SourceCredential` the upstream
    refused, when there is one; `ingest.vault.refuse_session` sets it. `run_job`
    stamps its `invalid_since` AFTER the job has unwound, because a stamp the
    job wrote itself dies with any savepoint or `atomic()` block the raise
    rolls back, and the next run would replay the dead session.
    """

    def __init__(self, message: str = "", *, refused_credential=None):
        super().__init__(message)
        self.refused_credential = refused_credential


def lock_key(source: str, household_id) -> int:
    """The advisory-lock key for one source in one household.

    `pg_try_advisory_lock` takes a signed 64-bit integer. Eight bytes of a
    SHA-256 over a namespaced string gives one that is stable across processes
    and machines (Python's own `hash()` is salted per process, so it would give
    two schedulers two different keys for the same job and no exclusion at
    all). The namespace keeps these keys clear of any other advisory lock this
    database ever takes.
    """
    material = f"legion.ingest_runs:{source}:{household_id}".encode()
    return int.from_bytes(hashlib.sha256(material).digest()[:8], "big", signed=True)


# What `scrub` removes. The error column is served to every member of the
# household by /api/freshness, so a message is treated as public the moment it
# is stored. These are the shapes a secret takes inside an exception message
# from an HTTP client or a database driver.
_CREDENTIALS_IN_URL = re.compile(r"(?P<scheme>[a-zA-Z][a-zA-Z0-9+.-]*://)[^/\s:@]+:[^/\s@]+@")
_SECRET_ASSIGNMENT = re.compile(
    r"(?P<name>\b[\w-]*(?:token|secret|password|passwd|pwd|api[_-]?key|session|cookie|"
    r"authorization|auth)[\w-]*)(?P<sep>\s*[=:]\s*)(?P<quote>['\"]?)(?:Bearer\s+|Token\s+)?"
    r"[^\s'\",;&]+",
    re.IGNORECASE,
)
_BEARER = re.compile(r"\b(Bearer|Token)\s+[A-Za-z0-9._~+/=-]{8,}", re.IGNORECASE)


def scrub(message: str) -> str:
    """The message with anything shaped like a credential replaced, capped.

    This is a net, not a guarantee: a job must still never put a secret into
    an exception message in the first place. It exists because the exceptions
    that reach this wrapper are mostly raised by libraries, which do not know
    that their message is about to be shown on a phone.
    """
    text = _CREDENTIALS_IN_URL.sub(r"\g<scheme>[redacted]@", message)
    text = _SECRET_ASSIGNMENT.sub(r"\g<name>\g<sep>\g<quote>[redacted]", text)
    text = _BEARER.sub(r"\1 [redacted]", text)
    if len(text) > MAX_ERROR_LENGTH:
        text = text[: MAX_ERROR_LENGTH - 1] + "…"
    return text


def _describe(exc: BaseException) -> str:
    message = str(exc).strip()
    name = type(exc).__name__
    return scrub(f"{name}: {message}" if message else name)


def _try_lock(key: int) -> bool:
    with connection.cursor() as cursor:
        cursor.execute("select pg_try_advisory_lock(%s)", [key])
        return bool(cursor.fetchone()[0])


def _unlock(key: int) -> None:
    with connection.cursor() as cursor:
        cursor.execute("select pg_advisory_unlock(%s)", [key])


def _call(fn: Callable[[IngestRun], str | None], run: IngestRun):
    """Runs the job so that a database error inside it cannot take the
    bookkeeping down with it.

    In production a command runs in autocommit, a failed statement poisons
    nothing, and this adds nothing. Inside a caller's own `atomic()` block a
    failed statement marks the whole transaction for rollback, and the
    `failed` row this wrapper is about to write would then fail too - so there,
    and only there, the job gets a savepoint of its own.
    """
    if connection.in_atomic_block:
        with transaction.atomic():
            return fn(run)
    return fn(run)


def _stamp_refused(credential) -> None:
    """`invalid_since` on a refused credential, keeping the FIRST refusal."""
    if credential is None:
        return
    from ingest.models import SourceCredential

    SourceCredential.objects.filter(pk=credential.pk, invalid_since__isnull=True).update(
        invalid_since=timezone.now()
    )


def run_job(source: str, household, fn: Callable[[IngestRun], str | None]) -> IngestRun:
    """Run `fn` once for `household`, recorded, and never overlapping itself.

    `fn` receives the in-progress `IngestRun` and may set `rows_written`,
    `rows_unchanged` and `watermark` on it; this wrapper saves them. It returns
    None for `ok`, or `needs_login` / `skipped`; it RAISES to fail, and
    `NeedsLogin` to say the saved session was refused.

    Never raises for anything `fn` does: every `Exception` is caught, recorded
    as `failed` with its (scrubbed) message, and returned as a row.
    `KeyboardInterrupt` and `SystemExit` are not `Exception`s and still stop
    the process, deliberately - a person pressing Ctrl-C meant it.
    """
    source = Source(source)
    key = lock_key(source, household.pk)
    if not _try_lock(key):
        now = timezone.now()
        return IngestRun.objects.create(
            household=household,
            source=source,
            started_at=now,
            finished_at=now,
            outcome=Outcome.SKIPPED_LOCKED,
        )
    try:
        run = IngestRun.objects.create(
            household=household, source=source, started_at=timezone.now()
        )
        error = None
        try:
            returned = _call(fn, run)
            outcome = Outcome(returned) if returned is not None else Outcome.OK
            if outcome not in RETURNABLE_OUTCOMES:
                raise ValueError(
                    f"A job returned {outcome.value!r}, which a job cannot report by "
                    f"returning. Raise to fail; skipped_locked is this wrapper's to record."
                )
        except NeedsLogin as exc:
            outcome, error = Outcome.NEEDS_LOGIN, _describe(exc)
            _stamp_refused(exc.refused_credential)
        except Exception as exc:  # noqa: BLE001 - catching everything is the contract
            logger.exception("%s for household %s failed", source, household.pk)
            outcome, error = Outcome.FAILED, _describe(exc)
        run.outcome = outcome
        run.error = error
        run.finished_at = timezone.now()
        run.save(
            update_fields=[
                "outcome",
                "error",
                "finished_at",
                "rows_written",
                "rows_unchanged",
                "watermark",
            ]
        )
        return run
    finally:
        _unlock(key)


def record_not_configured(source: str, household) -> IngestRun:
    """The `skipped` row for a household that has not set this source up.

    Written rather than left absent so `/api/freshness` can say "Canvas is not
    set up" instead of "Canvas has never synced", which would read as a fault
    to a household that never asked for Canvas.
    """
    now = timezone.now()
    return IngestRun.objects.create(
        household=household,
        source=Source(source),
        started_at=now,
        finished_at=now,
        outcome=Outcome.SKIPPED,
    )


def run_for_households(
    source: str,
    fn: Callable[[IngestRun], str | None],
    *,
    is_configured: Callable[[object], bool] = lambda household: True,
    write: Callable[[str], object] = print,
) -> list[IngestRun]:
    """`run_job` once for every household on this engine that has `source`
    configured, and a `skipped` row for every household that has not.

    No household at all is a no-op said in words and returns []. Nothing can be
    recorded, because a run row belongs to a household and there is none to
    belong to - and that is the one situation where no row is the honest one.
    """
    from household.models import Household

    households = list(Household.objects.order_by("created_at", "id"))
    if not households:
        write(f"{source}: this engine has no household yet, so there was nothing to run.")
        return []
    runs = []
    for household in households:
        if is_configured(household):
            run = run_job(source, household, fn)
        else:
            run = record_not_configured(source, household)
        line = f"{source} for household {household.pk}: {run.outcome}"
        if run.error:
            line += f" ({run.error})"
        write(line)
        runs.append(run)
    return runs


class JobCommand(BaseCommand):
    """Base class for every pipeline command in the backend-etl map.

    A subclass sets `source`, implements `job(run)`, and overrides
    `is_configured(household)` when the source needs something set up first
    (a saved session, a Drive folder). `handle` never raises for a job's
    failure, so the process exits 0 and the next scheduled run is the retry.

    A subclass whose upstream needs a login sets `session_source` (ticket 02)
    and gets the vault check for free: no stored session records `skipped`,
    and the job itself calls `ingest.vault.session_for` to get it.
    """

    source: str = ""
    session_source: str | None = None

    def is_configured(self, household) -> bool:
        if self.session_source is None:
            return True
        from ingest import vault

        return vault.is_configured(household, self.session_source)

    def job(self, run: IngestRun) -> str | None:
        raise NotImplementedError

    def handle(self, *args, **options):
        run_for_households(
            self.source,
            self.job,
            is_configured=self.is_configured,
            write=self.stdout.write,
        )
