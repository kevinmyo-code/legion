"""backend-etl ticket 01: the job runner, `ingest_runs`, `GET /api/freshness`
and `manage.py heartbeat`.

The household-isolation half of freshness lives in `tests/test_tenancy.py`
with every other leak test (`test_freshness_never_shows_another_households_runs`).
"""
from __future__ import annotations

import datetime
import threading

import pytest
from django.core.management import call_command
from django.db import connection
from django.utils import timezone

from ingest import jobs
from ingest.freshness import ago, sentence
from ingest.jobs import NeedsLogin, run_for_households, run_job, scrub
from ingest.models import IngestRun, Outcome, Source

# =============================================================================
# run_job
# =============================================================================


@pytest.mark.django_db(transaction=True)
def test_two_concurrent_runs_of_one_source_the_second_records_skipped_locked(household_a):
    """A real second connection, not a nested call: a session-level advisory
    lock is re-entrant inside ONE session, so a nested `run_job` on the same
    connection would take it again and prove nothing. The holder runs on its
    own thread, which Django gives its own connection."""
    holding = threading.Event()
    release = threading.Event()
    first: list[IngestRun] = []

    def hold(run):
        holding.set()
        assert release.wait(10), "the test never released the first run"

    def first_run():
        try:
            first.append(run_job(Source.CANVAS, household_a, hold))
        finally:
            connection.close()

    thread = threading.Thread(target=first_run)
    thread.start()
    try:
        assert holding.wait(10), "the first run never started"

        second = run_job(Source.CANVAS, household_a, lambda run: None)
        assert second.outcome == Outcome.SKIPPED_LOCKED
        assert second.finished_at is not None

        # The lock is per SOURCE: another source in the same household runs.
        other_source = run_job(Source.WEBASSIGN, household_a, lambda run: None)
        assert other_source.outcome == Outcome.OK

        # And the first run is visible as in progress while it runs.
        in_progress = IngestRun.objects.get(
            source=Source.CANVAS, household=household_a, outcome__isnull=True
        )
        assert in_progress.finished_at is None
    finally:
        release.set()
        thread.join(10)

    assert first and first[0].outcome == Outcome.OK
    # Released: the next run takes the lock and succeeds.
    assert run_job(Source.CANVAS, household_a, lambda run: None).outcome == Outcome.OK


@pytest.mark.django_db
def test_a_raising_job_records_failed_with_its_message(household_a):
    def boom(run):
        raise RuntimeError("Canvas answered 503 Service Unavailable")

    run = run_job(Source.CANVAS, household_a, boom)

    run.refresh_from_db()
    assert run.outcome == Outcome.FAILED
    assert run.error == "RuntimeError: Canvas answered 503 Service Unavailable"
    assert run.finished_at is not None


@pytest.mark.django_db
def test_a_database_error_inside_a_job_still_records_failed(household_a):
    """Inside the test's own transaction, a failed statement would poison the
    connection and the `failed` row could not be written - which is exactly
    the case `jobs._call`'s savepoint exists for."""

    def bad_sql(run):
        with connection.cursor() as cursor:
            cursor.execute("select * from no_such_table_anywhere")

    run = run_job(Source.BACKUP, household_a, bad_sql)

    run.refresh_from_db()
    assert run.outcome == Outcome.FAILED
    assert "no_such_table_anywhere" in run.error


@pytest.mark.django_db
def test_a_job_records_its_counts_and_watermark(household_a):
    def work(run):
        run.rows_written = 4
        run.rows_unchanged = 11
        run.watermark = "2026-09-27T10:00:00Z"

    run = run_job(Source.CANVAS, household_a, work)

    run.refresh_from_db()
    assert (run.outcome, run.rows_written, run.rows_unchanged, run.watermark) == (
        Outcome.OK,
        4,
        11,
        "2026-09-27T10:00:00Z",
    )


@pytest.mark.django_db
def test_needs_login_is_its_own_outcome(household_a):
    def refused(run):
        raise NeedsLogin("Canvas refused the saved session")

    run = run_job(Source.CANVAS, household_a, refused)
    assert run.outcome == Outcome.NEEDS_LOGIN
    assert "refused the saved session" in run.error


@pytest.mark.django_db
def test_a_job_cannot_report_skipped_locked_by_returning_it(household_a):
    run = run_job(Source.CANVAS, household_a, lambda run: Outcome.SKIPPED_LOCKED)
    assert run.outcome == Outcome.FAILED
    assert "skipped_locked" in run.error


def test_the_lock_key_is_stable_and_distinct():
    assert jobs.lock_key("canvas", "a") == jobs.lock_key("canvas", "a")
    assert jobs.lock_key("canvas", "a") != jobs.lock_key("canvas", "b")
    assert jobs.lock_key("canvas", "a") != jobs.lock_key("webassign", "a")
    assert -(2**63) <= jobs.lock_key("canvas", "a") < 2**63


@pytest.mark.parametrize(
    ("raw", "leaked"),
    [
        ("could not connect to postgres://legion:hunter2@db:5432/x", "hunter2"),
        ("401 for https://canvas.example?access_token=abc123def456", "abc123def456"),
        ("header Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.x.y", "eyJhbGciOiJIUzI1NiJ9"),
        ("password='s3cret-value' rejected", "s3cret-value"),
        ('session_cookie="c00kie-value"', "c00kie-value"),
    ],
)
def test_scrub_removes_credential_shapes(raw, leaked):
    cleaned = scrub(raw)
    assert leaked not in cleaned
    assert "[redacted]" in cleaned


def test_scrub_caps_the_length():
    assert len(scrub("x" * 10_000)) == jobs.MAX_ERROR_LENGTH


# =============================================================================
# The household loop and the command
# =============================================================================


@pytest.mark.django_db
def test_a_household_without_the_source_configured_records_skipped(household_a, household_b):
    calls = []

    runs = run_for_households(
        Source.CANVAS,
        lambda run: calls.append(run.household_id),
        is_configured=lambda household: household.pk == household_a.pk,
        write=lambda line: None,
    )

    assert calls == [household_a.pk]
    by_household = {run.household_id: run.outcome for run in runs}
    assert by_household == {household_a.pk: Outcome.OK, household_b.pk: Outcome.SKIPPED}


@pytest.mark.django_db
def test_no_household_at_all_is_a_quiet_no_op():
    from checklists.models import Checklist
    from household.models import Household

    # Each household owns its built-in Groceries list, and a checklist PROTECTs its household.
    Checklist.objects.all().delete()
    Household.objects.all().delete()
    lines = []

    assert run_for_households(Source.CANVAS, lambda run: None, write=lines.append) == []
    assert IngestRun.objects.count() == 0
    assert "no household" in lines[0]


@pytest.mark.django_db
def test_heartbeat_records_ok_for_every_household(household_a, household_b):
    call_command("heartbeat", stdout=_Sink())

    outcomes = set(
        IngestRun.objects.filter(source=Source.HEARTBEAT).values_list("household_id", "outcome")
    )
    assert outcomes == {(household_a.pk, Outcome.OK), (household_b.pk, Outcome.OK)}


@pytest.mark.django_db
def test_a_failing_job_command_records_failed_and_exits_zero(household_a, monkeypatch):
    """`call_command` raising is what a non-zero exit is from the inside:
    `manage.py` turns a `CommandError` or any uncaught exception into exit 1.
    Returning normally is exit 0."""
    from ingest.management.commands.heartbeat import Command

    def boom(self, run):
        raise ConnectionError("upstream is down")

    monkeypatch.setattr(Command, "job", boom)
    out = _Sink()

    call_command("heartbeat", stdout=out)

    run = IngestRun.objects.get(source=Source.HEARTBEAT, household=household_a)
    assert run.outcome == Outcome.FAILED
    assert run.error == "ConnectionError: upstream is down"
    assert "failed" in out.text


class _Sink:
    def __init__(self):
        self.text = ""

    def write(self, value):
        self.text += value

    def flush(self):
        pass


# =============================================================================
# Freshness
# =============================================================================

NOW = datetime.datetime(2026, 9, 27, 12, 0, tzinfo=datetime.UTC)


@pytest.mark.parametrize(
    ("delta", "words"),
    [
        (datetime.timedelta(seconds=20), "just now"),
        (datetime.timedelta(minutes=1), "1 minute ago"),
        (datetime.timedelta(minutes=45), "45 minutes ago"),
        (datetime.timedelta(hours=1), "1 hour ago"),
        (datetime.timedelta(hours=3, minutes=10), "3 hours ago"),
        (datetime.timedelta(days=3), "3 days ago"),
    ],
)
def test_ago(delta, words):
    assert ago(NOW - delta, NOW) == words


def test_the_ticket_sentences():
    three_hours = NOW - datetime.timedelta(hours=3)
    assert sentence(Source.CANVAS, three_hours, Outcome.OK, NOW) == (
        "Canvas last synced 3 hours ago."
    )
    assert sentence(Source.CANVAS, three_hours, Outcome.NEEDS_LOGIN, NOW) == (
        "Canvas needs you to log in again: run tools/connect_session.py canvas"
    )
    assert sentence(Source.CANVAS, three_hours, Outcome.FAILED, NOW) == (
        "Canvas last synced 3 hours ago. The latest attempt failed."
    )
    assert sentence(Source.CANVAS, None, None, NOW) == "Canvas has never synced."
    assert sentence(Source.CANVAS, None, Outcome.SKIPPED, NOW) == "Canvas is not set up."
    # Ticket 02: a pipeline with no login behind it never points at the script.
    assert sentence(Source.OBD_ROLLUP, None, Outcome.NEEDS_LOGIN, NOW) == (
        "The drive roll-up needs you to log in again."
    )


def _run(household, source, outcome, finished_ago, error=None):
    finished = timezone.now() - finished_ago
    return IngestRun.objects.create(
        household=household,
        source=source,
        started_at=finished - datetime.timedelta(seconds=5),
        finished_at=finished,
        outcome=outcome,
        error=error,
    )


def _by_source(client):
    response = client.get("/api/freshness")
    assert response.status_code == 200, response.data
    return {entry["source"]: entry for entry in response.data["sources"]}


@pytest.mark.django_db
def test_freshness_lists_every_source_even_with_no_runs(token_a):
    body = _by_source(token_a)

    assert set(body) == set(Source.values)
    canvas = body["canvas"]
    assert canvas == {
        "source": "canvas",
        "last_ok_at": None,
        "last_outcome": None,
        "last_error": None,
        "stale": True,
        "sentence": "Canvas has never synced.",
    }


@pytest.mark.django_db
def test_freshness_stale_follows_each_sources_threshold(token_a, household_a):
    _run(household_a, Source.CANVAS, Outcome.OK, datetime.timedelta(hours=3))
    _run(household_a, Source.WEBASSIGN, Outcome.OK, datetime.timedelta(hours=3))

    body = _by_source(token_a)

    assert body["canvas"]["stale"] is True  # 2h threshold
    assert body["canvas"]["sentence"] == "Canvas last synced 3 hours ago."
    assert body["webassign"]["stale"] is False  # 36h threshold


@pytest.mark.django_db
def test_freshness_reports_the_latest_failure_beside_the_last_ok(token_a, household_a):
    _run(household_a, Source.CANVAS, Outcome.OK, datetime.timedelta(minutes=50))
    _run(
        household_a,
        Source.CANVAS,
        Outcome.FAILED,
        datetime.timedelta(minutes=20),
        error="ConnectionError: upstream is down",
    )
    # A run that found the lock held says nothing about the feed, so it does
    # not become `last_outcome`.
    _run(household_a, Source.CANVAS, Outcome.SKIPPED_LOCKED, datetime.timedelta(minutes=5))

    canvas = _by_source(token_a)["canvas"]

    assert canvas["last_outcome"] == "failed"
    assert canvas["last_error"] == "ConnectionError: upstream is down"
    assert canvas["last_ok_at"] is not None
    assert canvas["stale"] is False
    assert canvas["sentence"] == "Canvas last synced 50 minutes ago. The latest attempt failed."


@pytest.mark.django_db
def test_freshness_says_needs_login_and_not_set_up(token_a, household_a):
    _run(household_a, Source.CANVAS, Outcome.NEEDS_LOGIN, datetime.timedelta(minutes=5))
    _run(household_a, Source.WEBASSIGN, Outcome.SKIPPED, datetime.timedelta(minutes=5))

    body = _by_source(token_a)

    assert body["canvas"]["sentence"] == (
        "Canvas needs you to log in again: run tools/connect_session.py canvas"
    )
    assert body["canvas"]["stale"] is True
    assert body["webassign"]["sentence"] == "WebAssign is not set up."
    assert body["webassign"]["stale"] is False


@pytest.mark.django_db
def test_freshness_requires_a_household_member(client):
    assert client.get("/api/freshness").status_code in (401, 403)
