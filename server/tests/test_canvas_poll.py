"""backend-etl ticket 04: `canvas_poll`, over recorded Canvas JSON.

The fixtures in `tests/canvas_fixtures/` are hand-written in the shape
Canvas's REST API returns (courses with `enrollment_state=active`,
assignments with `include[]=submission`), including the `while(1);` prefix
Canvas puts on session-authenticated JSON and a `Link: rel="next"` page.

Owed and not provable here: the live run against Kevin's Canvas, diffed
against the 102 hand-seeded DETERMINISTIC tasks. `manage.py canvas_poll
--dry-run` is that diff, once a Canvas session is connected.
"""

from __future__ import annotations

import datetime
import io
import json
from pathlib import Path
from urllib.parse import quote

import pytest
from cryptography.fernet import Fernet
from django.core.management import call_command
from django.db import connection

from ingest import canvas, vault
from ingest.drive import Response
from ingest.freshness import freshness_for
from ingest.models import IngestRun, Outcome, Source, SourceCredential
from legacy.models.dates import Event

FIXTURES = Path(__file__).resolve().parent / "canvas_fixtures"
BASE = "https://canvas.example.edu"
COURSES_URL = f"{BASE}/api/v1/courses?enrollment_state=active&per_page=100"


def assignments_url(course_id: int, page: int | None = None) -> str:
    extra = f"&page={page}" if page else ""
    return (
        f"{BASE}/api/v1/courses/{course_id}/assignments"
        f"?include%5B%5D=submission{extra}&per_page=100"
    )


def load(name: str):
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


UTC = datetime.UTC


def utc(*args) -> datetime.datetime:
    return datetime.datetime(*args, tzinfo=UTC)


# =============================================================================
# An in-memory Canvas
# =============================================================================


class FakeCanvas:
    """Serves the fixtures. Tests mutate `courses` / `pages` to change what
    Canvas says, and push onto `queued[url]` to make one URL answer
    something else (a 401, a 429) the next time it is asked."""

    def __init__(self):
        self.courses = load("courses.json")
        self.pages = {
            101: [load("assignments_101_page1.json"), load("assignments_101_page2.json")],
            202: [load("assignments_202.json")],
        }
        self.queued: dict[str, list[Response]] = {}
        self.calls: list[tuple[str, dict]] = []

    def assignment(self, aid: int) -> dict:
        for pages in self.pages.values():
            for page in pages:
                for item in page:
                    if item["id"] == aid:
                        return item
        raise KeyError(aid)

    def drop(self, aid: int) -> None:
        for pages in self.pages.values():
            for page in pages:
                page[:] = [item for item in page if item["id"] != aid]

    def __call__(self, method, url, headers, body):
        self.calls.append((url, dict(headers)))
        if self.queued.get(url):
            return self.queued[url].pop(0)
        if url == COURSES_URL:
            # Session-authenticated Canvas JSON carries this prefix.
            return Response(200, {}, b"while(1);" + json.dumps(self.courses).encode())
        for course_id, pages in self.pages.items():
            for index, page in enumerate(pages):
                if url == assignments_url(course_id, index + 1 if index else None):
                    headers_out = {}
                    if index + 1 < len(pages):
                        headers_out["Link"] = (
                            f'<{assignments_url(course_id, index + 2)}>; rel="next", '
                            f'<{assignments_url(course_id)}>; rel="first"'
                        )
                    return Response(200, headers_out, json.dumps(page).encode())
        if "/courses/" in url and "/assignments" in url:
            return Response(200, {}, b"[]")
        return Response(404, {}, b'{"errors":[{"message":"not found"}]}')


@pytest.fixture
def vault_key(monkeypatch):
    monkeypatch.setenv(vault.VAULT_KEY_ENV, Fernet.generate_key().decode())


def cookie_jar() -> dict:
    return {
        "cookies": [
            {"name": "canvas_session", "value": "cookie-abc", "domain": "canvas.example.edu"},
            {"name": "idp", "value": "not-for-canvas", "domain": "sso.example.org"},
        ]
    }


@pytest.fixture
def fake(monkeypatch):
    fake = FakeCanvas()
    monkeypatch.setattr(canvas, "urllib_transport", fake)
    slept: list[float] = []
    monkeypatch.setattr(canvas, "_sleep", slept.append)
    fake.slept = slept
    return fake


@pytest.fixture
def connected(vault_key, household_a):
    return vault.store(household_a, "canvas", cookie_jar(), config={"base_url": BASE})


def poll() -> str:
    out = io.StringIO()
    call_command("canvas_poll", stdout=out)
    return out.getvalue()


def last_run(household) -> IngestRun:
    return IngestRun.objects.filter(household=household, source=Source.CANVAS).latest("started_at")


def task(household, aid: int) -> Event:
    return Event.objects.get(
        household=household, structured_meta__canvas_assignment_id=aid, deleted_at__isnull=True
    )


def sub(household, parent: int) -> Event:
    return Event.objects.get(
        household=household, structured_meta__parent_canvas_assignment_id=parent
    )


def seed(household, **fields) -> Event:
    """A row as the 2026-09-05 hand script or the phone left it."""
    import uuid

    from django.db.models.functions import Now

    defaults = dict(
        id=uuid.uuid4(),
        household=household,
        title="seeded",
        all_day=False,
        source="legion",
        done=False,
        exact=False,
        exact_downgraded=False,
        provenance="DETERMINISTIC",
        created_at=Now(),
        updated_at=Now(),
        kind="task",
    )
    defaults.update(fields)
    return Event.objects.create(**defaults)


# =============================================================================
# First run inserts, second writes nothing
# =============================================================================


@pytest.mark.django_db
def test_first_run_inserts_and_an_identical_second_run_writes_nothing(fake, connected, household_a):
    poll()
    first = last_run(household_a)
    assert first.outcome == Outcome.OK
    # 5001, 5002, 5003 + its initial post, 5005 (ambiguous: no sub), 6001, 6002.
    # 5004 is a not_graded placeholder and is not inserted.
    assert first.rows_written == 7
    assert Event.objects.filter(household=household_a).count() == 7
    assert first.watermark == "2026-09-14T17:45:00Z"

    snapshot = {
        row.pk: (row.updated_at, row.structured_meta, row.done, row.starts_at)
        for row in Event.objects.filter(household=household_a)
    }
    poll()
    second = last_run(household_a)
    assert second.outcome == Outcome.OK
    assert second.rows_written == 0
    assert second.rows_unchanged == 8  # seven rows plus the not_inserted placeholder
    after = {
        row.pk: (row.updated_at, row.structured_meta, row.done, row.starts_at)
        for row in Event.objects.filter(household=household_a)
    }
    assert after == snapshot


@pytest.mark.django_db
def test_inserted_rows_carry_the_evidence(fake, connected, household_a):
    poll()
    quiz = task(household_a, 5001)
    assert quiz.kind == "task"
    assert quiz.provenance == "DETERMINISTIC"
    assert quiz.source == "legion"
    assert quiz.origin_guid == "canvas:5001"
    # Canvas says "2026FA MATH3391 20937 - Probability and Statistics I".
    assert quiz.title == "MATH 3391 Probability and Statistics I · Module 2: Quiz"
    # Canvas says it is in; the row is still open (Kevin, 2026-09-28).
    assert quiz.done is False
    assert quiz.done_at is None
    meta = quiz.structured_meta
    assert meta["canvas_submitted"] is True
    assert meta["canvas_assignment_id"] == 5001
    assert meta["canvas_course_id"] == 101
    assert meta["canvas_course"] == "MATH 3391"
    assert meta["submission_state"] == "graded"
    assert meta["submitted_at"] == "2026-09-12T18:00:00Z"
    assert meta["score"] == 9
    assert meta["grade"] == "9"
    assert meta["canvas_due_at"] == "2026-09-14T04:59:59Z"
    assert "read_at" in meta

    homework = task(household_a, 5002)
    assert homework.done is False
    assert homework.done_at is None
    assert homework.structured_meta["submission_state"] == "unsubmitted"
    assert homework.structured_meta["canvas_submitted"] is False


@pytest.mark.django_db
def test_sunday_2359_chicago_is_stored_as_the_exact_instant(fake, connected, household_a):
    """23:59:59 CDT on Sunday 2026-09-13 is 04:59:59Z on Monday. A past bug
    put Sunday assignments on the wrong day; the instant must survive exact."""
    poll()
    quiz = task(household_a, 5001)
    assert quiz.starts_at == utc(2026, 9, 14, 4, 59, 59)
    with connection.cursor() as cursor:
        cursor.execute(
            "select (starts_at at time zone 'America/Chicago')::text from events where id = %s",
            [quiz.pk],
        )
        assert cursor.fetchone()[0] == "2026-09-13 23:59:59"


@pytest.mark.django_db
def test_every_page_is_followed_and_the_cookie_is_the_canvas_one(fake, connected, household_a):
    poll()
    urls = [url for url, _ in fake.calls]
    assert assignments_url(101, 2) in urls
    assert Event.objects.filter(structured_meta__canvas_assignment_id=5005).exists()
    _, headers = fake.calls[0]
    assert headers["Cookie"] == "canvas_session=cookie-abc"


# =============================================================================
# Discussions and their sub-deadlines
# =============================================================================


@pytest.mark.django_db
def test_a_discussion_yields_parent_and_initial_post_rows(fake, connected, household_a):
    poll()
    parent = task(household_a, 5003)
    first_post = sub(household_a, 5003)
    assert parent.starts_at == utc(2026, 9, 19, 4, 59, 59)  # Friday 23:59:59 CDT
    # "Initial post due Wednesday by 11:59 PM": Wednesday 2026-09-16 23:59 CDT.
    assert first_post.starts_at == utc(2026, 9, 17, 4, 59, 0)
    assert first_post.kind == "task"
    assert first_post.done is False
    assert first_post.origin_guid == "canvas:5003:first_post"
    assert first_post.title.endswith("Module 3: Discussion - Sampling Bias - initial post due")
    assert first_post.structured_meta["sub_deadline"] == "first_post"
    assert first_post.structured_meta["read_from"] == "canvas_description"
    assert "canvas_assignment_id" not in first_post.structured_meta


@pytest.mark.django_db
def test_a_discussion_canvas_calls_submitted_stays_open_with_the_evidence_kept(
    fake, connected, household_a
):
    """Kevin, 2026-09-27: "discussions leave em to me". Canvas marks a
    discussion submitted on the FIRST post while its due date is the replies
    deadline, so neither the parent nor its sub-deadline is ticked from it."""
    poll()
    fake.assignment(5003)["submission"].update(
        workflow_state="graded", submitted_at="2026-09-16T20:00:00Z", score=10, grade="10"
    )
    poll()
    parent = task(household_a, 5003)
    assert parent.done is False
    assert parent.done_at is None
    assert parent.structured_meta["manual_completion"] is True
    assert parent.structured_meta["submitted_at"] == "2026-09-16T20:00:00Z"
    assert parent.structured_meta["submission_state"] == "graded"
    assert parent.structured_meta["score"] == 10
    assert parent.structured_meta["grade"] == "10"
    assert parent.structured_meta["canvas_submitted"] is True
    first_post = sub(household_a, 5003)
    assert first_post.done is False
    assert first_post.done_at is None
    assert first_post.structured_meta["manual_completion"] is True
    assert first_post.structured_meta["canvas_submitted"] is True


@pytest.mark.django_db
def test_a_discussion_first_seen_submitted_is_inserted_open(fake, connected, household_a):
    fake.assignment(5003)["submission"].update(
        workflow_state="submitted", submitted_at="2026-09-16T20:00:00Z"
    )
    fake.assignment(5005)["submission"].update(workflow_state="graded", score=5)
    poll()
    for aid in (5003, 5005):
        row = task(household_a, aid)
        assert row.done is False
        assert row.done_at is None
        assert row.structured_meta["manual_completion"] is True
    assert sub(household_a, 5003).done is False


@pytest.mark.django_db
def test_a_hand_seeded_discussion_is_marked_manual_and_not_ticked(fake, connected, household_a):
    """The live rows carry `submission_types` as a string and no manual flag."""
    seeded = seed(
        household_a,
        title="Module 3 discussion",
        structured_meta={"canvas_assignment_id": 5003, "submission_types": "discussion_topic"},
    )
    fake.assignment(5003)["submission"].update(
        workflow_state="submitted", submitted_at="2026-09-16T20:00:00Z"
    )
    poll()
    row = Event.objects.get(pk=seeded.pk)
    assert row.done is False
    assert row.structured_meta["manual_completion"] is True
    assert row.structured_meta["submitted_at"] == "2026-09-16T20:00:00Z"


@pytest.mark.django_db
def test_a_discussion_ticked_by_hand_stays_done(fake, connected, household_a, auth_client):
    poll()
    parent = task(household_a, 5003)
    first_post = sub(household_a, 5003)
    for row in (parent, first_post):
        response = auth_client.patch(f"/api/events/{row.pk}", {"done": True}, format="json")
        assert response.status_code == 200
    ticked = Event.objects.get(pk=parent.pk)

    fake.assignment(5003)["submission"].update(workflow_state="unsubmitted", submitted_at=None)
    poll()
    fake.assignment(5003)["submission"].update(
        workflow_state="submitted", submitted_at="2026-09-16T20:00:00Z"
    )
    poll()
    again = Event.objects.get(pk=parent.pk)
    assert again.done is True
    assert again.done_at == ticked.done_at
    assert again.structured_meta["submitted_at"] == "2026-09-16T20:00:00Z"
    assert Event.objects.get(pk=first_post.pk).done is True


@pytest.mark.django_db
@pytest.mark.parametrize(
    "types", ["discussion_topic", "online_text_entry,discussion_topic", "online_upload", None]
)
def test_a_direct_rpc_call_cannot_tick(household_a, types):
    """The rule lives in Postgres: a caller that sends `submitted` with no
    manual flag cannot tick any row, discussion or not, on insert or update."""
    payload = {
        "canvas_assignment_id": 7001,
        "origin_guid": "canvas:7001",
        "title": "A discussion",
        "starts_at": "2026-09-19T04:59:59+00:00",
        "submitted": True,
        "submitted_at": "2026-09-16T20:00:00+00:00",
        "evidence": {"canvas_assignment_id": 7001, "submission_types": types},
    }
    read_at = utc(2026, 9, 20)
    assert canvas.upsert(household_a, payload, read_at)["done"] is False
    bare = {**payload, "evidence": {"canvas_assignment_id": 7001}}
    assert canvas.upsert(household_a, bare, read_at)["action"] in {"unchanged", "updated"}
    row = Event.objects.get(household=household_a, origin_guid="canvas:7001")
    assert row.done is False
    assert row.done_at is None
    assert row.structured_meta["canvas_submitted"] is True
    if types and "discussion_topic" in types:
        assert row.structured_meta["manual_completion"] is True
    else:
        assert "manual_completion" not in row.structured_meta


@pytest.mark.django_db
def test_the_live_dry_runs_submitted_discussion_is_inserted_open(fake, connected, household_a):
    """The 2026-09-27 live dry run planned this one `done=True`."""
    poll()
    row = task(household_a, 6002)
    assert row.title == "COSC 3318 Python Programming · Module 2: Discussion - User Authentication"
    assert row.done is False
    assert row.done_at is None
    assert row.structured_meta["manual_completion"] is True
    assert row.structured_meta["submitted_at"] == "2026-09-14T02:10:00Z"
    assert row.structured_meta["submission_state"] == "submitted"


@pytest.mark.django_db
def test_an_open_row_canvas_calls_submitted_stays_open(fake, connected, household_a):
    """Kevin, 2026-09-28: "i'll manually mark things as done". Canvas's word
    is shown beside the task (`canvas_submitted`), never applied to it."""
    poll()
    before = task(household_a, 5002)
    assert before.structured_meta["canvas_submitted"] is False
    fake.assignment(5002)["submission"].update(
        workflow_state="submitted", submitted_at="2026-09-18T12:00:00Z"
    )
    poll()
    row = task(household_a, 5002)
    assert row.done is False
    assert row.done_at is None
    assert row.provenance == before.provenance
    assert row.structured_meta["canvas_submitted"] is True
    assert row.structured_meta["submitted_at"] == "2026-09-18T12:00:00Z"
    assert row.structured_meta["submission_state"] == "submitted"
    assert "manual_completion" not in row.structured_meta
    assert "manual_completion" not in task(household_a, 5001).structured_meta


@pytest.mark.django_db
def test_a_first_post_ticked_on_the_phone_stays_as_the_phone_left_it(
    fake, connected, household_a, auth_client
):
    poll()
    first_post = sub(household_a, 5003)
    response = auth_client.patch(f"/api/events/{first_post.pk}", {"done": True}, format="json")
    assert response.status_code == 200
    ticked = Event.objects.get(pk=first_post.pk)

    fake.assignment(5003)["submission"].update(
        workflow_state="submitted", submitted_at="2026-09-16T20:00:00Z"
    )
    poll()
    assert task(household_a, 5003).done is False  # a discussion is ticked by hand only
    again = Event.objects.get(pk=first_post.pk)
    assert again.done is True
    assert again.done_at == ticked.done_at
    assert again.updated_at == ticked.updated_at


@pytest.mark.django_db
def test_an_ambiguous_description_invents_no_sub_deadline(fake, connected, household_a):
    out = poll()
    assert not Event.objects.filter(structured_meta__parent_canvas_assignment_id=5005).exists()
    assert "more than one initial-post day" in out


@pytest.mark.django_db
def test_a_hand_made_sub_deadline_row_is_left_alone(fake, connected, household_a):
    hand = seed(
        household_a,
        title="MATH 3391 · Module 3: Discussion - first post due",
        starts_at=utc(2026, 9, 17, 4, 59, 0),
        done=True,
        structured_meta={
            "read_from": "syllabus",
            "sub_deadline": "first_post",
            "parent_canvas_assignment_id": 5003,
        },
    )
    hand.refresh_from_db()
    poll()
    rows = Event.objects.filter(structured_meta__parent_canvas_assignment_id=5003)
    assert rows.count() == 1
    kept = rows.get()
    assert kept.pk == hand.pk
    assert (kept.title, kept.done, kept.structured_meta, kept.updated_at) == (
        hand.title,
        hand.done,
        hand.structured_meta,
        hand.updated_at,
    )


def _deadline(description: str, due="2026-09-19T04:59:59Z", zone="America/Chicago"):
    return canvas.initial_post_deadline(
        {"description": description}, canvas.parse_instant(due), canvas.ZoneInfo(zone)
    )


def test_initial_post_parsing_cases():
    when, _ = _deadline("<p>Initial post due Wednesday.</p>")
    assert when == utc(2026, 9, 17, 4, 59)
    when, _ = _deadline("Post your initial response by Thursday at noon, then reply by Saturday.")
    assert when == utc(2026, 9, 17, 17, 0)
    when, _ = _deadline("First post due Wednesday 11:59 p.m. Replies due Friday.")
    assert when == utc(2026, 9, 17, 4, 59)
    when, _ = _deadline("Initial post due Wednesday, replies due Friday.")
    assert when == utc(2026, 9, 17, 4, 59)
    assert _deadline("Discuss the reading with your classmates.")[0] is None
    assert _deadline("Your initial post is due before the replies.")[0] is None
    assert _deadline("Initial post due Friday.")[0] is None  # the due date's own weekday
    assert (
        canvas.initial_post_deadline(
            {"description": "Initial post due Wednesday."},
            canvas.parse_instant("2026-09-19T04:59:59Z"),
            None,
        )[0]
        is None
    )


def test_submission_state_to_done():
    assert canvas.is_submitted({"submitted_at": "2026-09-01T00:00:00Z"})
    assert canvas.is_submitted({"workflow_state": "submitted"})
    assert canvas.is_submitted({"workflow_state": "pending_review"})
    assert canvas.is_submitted({"workflow_state": "graded", "missing": False})
    assert not canvas.is_submitted({"workflow_state": "graded", "missing": True})
    assert not canvas.is_submitted({"workflow_state": "graded", "late_policy_status": "missing"})
    assert not canvas.is_submitted({"workflow_state": "unsubmitted"})
    assert not canvas.is_submitted({"workflow_state": "unsubmitted", "excused": True})
    assert not canvas.is_submitted({})


# =============================================================================
# Matching, manual completion, not_graded
# =============================================================================


@pytest.mark.django_db
def test_a_hand_seeded_row_matches_on_assignment_id_and_keeps_title_and_guid(
    fake, connected, household_a
):
    seeded = seed(
        household_a,
        title="MATH 3391 · Quiz 2 (my title)",
        starts_at=utc(2026, 9, 14, 4, 59, 59),
        origin_guid="semester:canvas:math-3391-quiz-2",
        structured_meta={
            "canvas_assignment_id": 5001,
            "canvas_course": "MATH 3391",
            "match": "exact",
            "read_at": "2026-09-05T03:39:36Z",
            "submission_state": "unsubmitted",
        },
    )
    poll()
    row = Event.objects.get(pk=seeded.pk)
    assert row.title == "MATH 3391 · Quiz 2 (my title)"
    assert row.origin_guid == "semester:canvas:math-3391-quiz-2"
    assert row.done is False  # Canvas says graded; the tick is still Kevin's
    assert row.structured_meta["canvas_submitted"] is True
    assert row.structured_meta["match"] == "exact"
    assert row.structured_meta["submission_state"] == "graded"
    assert row.structured_meta["canvas_course_id"] == 101
    assert not Event.objects.filter(origin_guid="canvas:5001").exists()


@pytest.mark.django_db
def test_a_row_without_the_id_matches_on_origin_guid(fake, connected, household_a):
    seeded = seed(household_a, title="Problem set", origin_guid="canvas:5002", structured_meta={})
    poll()
    row = Event.objects.get(pk=seeded.pk)
    assert row.title == "Problem set"
    assert row.structured_meta["canvas_assignment_id"] == 5002
    assert Event.objects.filter(structured_meta__canvas_assignment_id=5002).count() == 1


@pytest.mark.django_db
def test_manual_completion_is_never_touched(fake, connected, household_a):
    seeded = seed(
        household_a,
        title="Problem set",
        structured_meta={"canvas_assignment_id": 5002, "manual_completion": True},
    )
    fake.assignment(5002)["submission"].update(
        workflow_state="submitted", submitted_at="2026-09-18T12:00:00Z"
    )
    poll()
    row = Event.objects.get(pk=seeded.pk)
    assert row.done is False
    assert row.done_at is None
    assert row.structured_meta["manual_completion"] is True
    assert row.structured_meta["submitted_at"] == "2026-09-18T12:00:00Z"  # evidence still read


@pytest.mark.django_db
def test_a_not_graded_placeholder_is_not_inserted_but_an_existing_one_is_marked_manual(
    fake, connected, household_a
):
    out = poll()
    assert not Event.objects.filter(structured_meta__canvas_assignment_id=5004).exists()
    assert "not_inserted" not in out  # quiet on a scheduled run

    seeded = seed(household_a, title="WebAssign HW", structured_meta={"canvas_assignment_id": 5004})
    fake.assignment(5004)["submission"].update(
        workflow_state="graded", submitted_at="2026-09-15T00:00:00Z"
    )
    poll()
    row = Event.objects.get(pk=seeded.pk)
    assert row.structured_meta["manual_completion"] is True
    assert row.done is False


@pytest.mark.django_db
def test_a_hand_ticked_row_stays_done_when_canvas_says_unsubmitted(fake, connected, household_a):
    ticked_at = utc(2026, 9, 10, 12, 0, 0)
    seeded = seed(
        household_a,
        title="done by hand",
        done=True,
        done_at=ticked_at,
        provenance="USER",
        structured_meta={"canvas_assignment_id": 5002},
    )
    poll()
    row = Event.objects.get(pk=seeded.pk)
    assert row.done is True
    assert row.done_at == ticked_at
    assert row.provenance == "USER"
    assert row.structured_meta["canvas_submitted"] is False  # the mismatch ticket 11 shows


# =============================================================================
# Tombstones
# =============================================================================


@pytest.mark.django_db
def test_an_assignment_canvas_stops_returning_is_tombstoned_then_can_return(
    fake, connected, household_a
):
    poll()
    row = task(household_a, 5002)
    fake.drop(5002)
    poll()
    gone = Event.objects.get(pk=row.pk)
    assert gone.deleted_at is not None
    assert "canvas_tombstoned_at" in gone.structured_meta
    assert last_run(household_a).rows_written == 1

    fake.pages[101][0].append(load("assignments_101_page1.json")[1])
    poll()
    back = Event.objects.get(pk=row.pk)
    assert back.deleted_at is None
    assert "canvas_tombstoned_at" not in back.structured_meta


@pytest.mark.django_db
def test_a_row_deleted_by_hand_is_not_resurrected_or_duplicated(
    fake, connected, household_a, auth_client
):
    poll()
    row = task(household_a, 5002)
    assert auth_client.delete(f"/api/events/{row.pk}").status_code == 204
    poll()
    assert Event.objects.get(pk=row.pk).deleted_at is not None
    assert Event.objects.filter(structured_meta__canvas_assignment_id=5002).count() == 1


@pytest.mark.django_db
def test_rows_in_an_unanswered_course_or_without_a_course_are_never_tombstoned(
    fake, connected, household_a
):
    no_course = seed(
        household_a, title="seeded, never matched", structured_meta={"canvas_assignment_id": 9999}
    )
    other = seed(
        household_a,
        title="last semester",
        structured_meta={"canvas_assignment_id": 8888, "canvas_course_id": 404},
    )
    poll()
    assert Event.objects.get(pk=no_course.pk).deleted_at is None
    assert Event.objects.get(pk=other.pk).deleted_at is None


@pytest.mark.django_db
def test_sub_deadline_and_user_rows_are_never_tombstoned(fake, connected, household_a):
    poll()
    mine = seed(household_a, title="call mum", kind="reminder", provenance="USER")
    fake.drop(5003)
    poll()
    assert task_or_none(household_a, 5003) is None
    assert sub(household_a, 5003).deleted_at is None
    assert Event.objects.get(pk=mine.pk).deleted_at is None


# =============================================================================
# Course labels on new rows
# =============================================================================


@pytest.mark.parametrize(
    ("name", "label", "code"),
    [
        (
            "2026FA COSC3318 21007 MAIN - Python Programming",
            "COSC 3318 Python Programming",
            "COSC 3318",
        ),
        (
            "2026FA COSC3334 20997 MAIN - Introduction to Cybersecurity",
            "COSC 3334 Introduction to Cybersecurity",
            "COSC 3334",
        ),
        (
            "2026FA MATH3391 20937 - Probability and Statistics I",
            "MATH 3391 Probability and Statistics I",
            "MATH 3391",
        ),
        (
            "2026FA MKTG3303 20608 - Principles of Marketing",
            "MKTG 3303 Principles of Marketing",
            "MKTG 3303",
        ),
        ("Fall Orientation 2026", "Fall Orientation 2026", "ORIENT-26"),
        ("COSC 4320 Software Engineering", "COSC 4320 Software Engineering", "ORIENT-26"),
    ],
)
def test_the_course_label_is_derived_from_canvas_s_name(name, label, code):
    derived = canvas.derived_course_name({"id": 1, "name": name, "course_code": "ORIENT-26"})
    assert (derived.label, derived.code) == (label, code)


@pytest.mark.django_db
def test_new_rows_take_the_label_a_sibling_row_of_the_same_course_id_uses(
    fake, connected, household_a
):
    seed(
        household_a,
        title="COSC 3318 Python Programming (Dr. Lee) · Module 1: Assignment",
        structured_meta={
            "canvas_assignment_id": 9101,
            "canvas_course_id": 202,
            "canvas_course": "COSC 3318",
        },
    )
    poll()
    row = task(household_a, 6001)
    assert row.title == "COSC 3318 Python Programming (Dr. Lee) · Sprint 1 Report"
    assert row.structured_meta["canvas_course"] == "COSC 3318"


@pytest.mark.django_db
def test_new_rows_fall_back_to_a_sibling_matched_by_canvas_course(fake, connected, household_a):
    """The live hand-seeded rows carry `canvas_course` and no course id."""
    seed(
        household_a,
        title="COSC 3318 Python · Module 1: Assignment",
        structured_meta={"canvas_assignment_id": 9102, "canvas_course": "COSC 3318"},
    )
    poll()
    row = task(household_a, 6001)
    assert row.title == "COSC 3318 Python · Sprint 1 Report"
    assert row.structured_meta["canvas_course"] == "COSC 3318"


@pytest.mark.django_db
def test_with_no_sibling_the_label_is_derived(fake, connected, household_a):
    poll()
    row = task(household_a, 6001)
    assert row.title == "COSC 3318 Python Programming · Sprint 1 Report"
    assert row.structured_meta["canvas_course"] == "COSC 3318"


@pytest.mark.django_db
def test_an_unrecognised_course_name_is_kept_unchanged(fake, connected, household_a):
    fake.courses[1].update(name="Capstone: Team Rocket", course_code="CAP-TR")
    poll()
    row = task(household_a, 6001)
    assert row.title == "Capstone: Team Rocket · Sprint 1 Report"
    assert row.structured_meta["canvas_course"] == "CAP-TR"


@pytest.mark.django_db
def test_an_existing_row_is_never_retitled(fake, connected, household_a):
    seeded = seed(
        household_a,
        title="Sprint report (mine)",
        structured_meta={"canvas_assignment_id": 6001, "canvas_course": "old"},
    )
    poll()
    row = Event.objects.get(pk=seeded.pk)
    assert row.title == "Sprint report (mine)"
    assert row.structured_meta["canvas_course"] == "old"


def task_or_none(household, aid):
    return Event.objects.filter(
        household=household, structured_meta__canvas_assignment_id=aid, deleted_at__isnull=True
    ).first()


# =============================================================================
# Suspicious reads write nothing
# =============================================================================


@pytest.mark.django_db
def test_an_empty_course_list_with_live_rows_fails_and_writes_nothing(fake, connected, household_a):
    poll()
    before = {r.pk: r.updated_at for r in Event.objects.all()}
    fake.courses = []
    poll()
    run = last_run(household_a)
    assert run.outcome == Outcome.FAILED
    assert "no active courses" in run.error
    assert {r.pk: r.updated_at for r in Event.objects.all()} == before
    assert Event.objects.filter(deleted_at__isnull=False).count() == 0


@pytest.mark.django_db
def test_an_empty_assignment_list_for_a_course_with_rows_fails_and_writes_nothing(
    fake, connected, household_a
):
    poll()
    fake.pages[101] = [[]]
    fake.assignment(6001)["submission"].update(
        workflow_state="submitted", submitted_at="2026-09-20T00:00:00Z"
    )
    poll()
    run = last_run(household_a)
    assert run.outcome == Outcome.FAILED
    assert "no assignments" in run.error
    assert Event.objects.filter(deleted_at__isnull=False).count() == 0
    assert task(household_a, 6001).done is False  # the other course's change waits too


@pytest.mark.django_db
def test_an_empty_first_read_is_ok(fake, connected, household_a):
    fake.courses = []
    poll()
    assert last_run(household_a).outcome == Outcome.OK
    assert Event.objects.count() == 0


@pytest.mark.django_db
def test_a_failed_page_mid_run_writes_nothing(fake, connected, household_a):
    fake.queued[assignments_url(202)] = [Response(500, {}, b"oops")]
    poll()
    assert last_run(household_a).outcome == Outcome.FAILED
    assert Event.objects.count() == 0


@pytest.mark.django_db
def test_a_course_closed_to_the_student_is_skipped_and_its_rows_kept(fake, connected, household_a):
    poll()
    fake.queued[assignments_url(202)] = [Response(403, {}, b'{"status":"unauthorized"}')]
    out = poll()
    run = last_run(household_a)
    assert run.outcome == Outcome.OK
    assert task(household_a, 6001).deleted_at is None
    assert "HTTP 403" in out
    assert SourceCredential.objects.get(household=household_a).invalid_since is None


# =============================================================================
# Refusals and rate limits
# =============================================================================


@pytest.mark.django_db
def test_a_401_records_needs_login_and_marks_the_session(fake, connected, household_a):
    fake.queued[COURSES_URL] = [Response(401, {}, b'{"status":"unauthenticated"}')]
    poll()
    run = last_run(household_a)
    assert run.outcome == Outcome.NEEDS_LOGIN
    assert "HTTP 401" in run.error
    assert "connect_session.py canvas" in run.error
    assert "cookie-abc" not in run.error
    assert SourceCredential.objects.get(household=household_a).invalid_since is not None
    assert Event.objects.count() == 0
    entry = freshness_for(
        IngestRun.objects.filter(household=household_a), Source.CANVAS, datetime.datetime.now(UTC)
    )
    assert entry["sentence"].startswith("Canvas needs you to log in again")

    # The next run stops at the stamp instead of replaying the dead session.
    calls = len(fake.calls)
    poll()
    assert last_run(household_a).outcome == Outcome.NEEDS_LOGIN
    assert len(fake.calls) == calls


@pytest.mark.django_db
def test_a_redirect_to_login_records_needs_login(fake, connected, household_a):
    fake.queued[COURSES_URL] = [Response(302, {"Location": f"{BASE}/login/saml"}, b"")]
    poll()
    assert last_run(household_a).outcome == Outcome.NEEDS_LOGIN


@pytest.mark.django_db
def test_a_429_backs_off_once_honouring_retry_after(fake, connected, household_a):
    fake.queued[COURSES_URL] = [Response(429, {"Retry-After": "7"}, b"")]
    poll()
    assert fake.slept == [7.0]
    assert last_run(household_a).outcome == Outcome.OK


@pytest.mark.django_db
def test_two_429s_record_failed_and_write_nothing(fake, connected, household_a):
    fake.queued[COURSES_URL] = [
        Response(429, {"Retry-After": "9000"}, b""),
        Response(429, {}, b""),
    ]
    poll()
    run = last_run(household_a)
    assert run.outcome == Outcome.FAILED
    assert "429" in run.error
    assert fake.slept == [canvas.MAX_BACKOFF_SECONDS]
    assert Event.objects.count() == 0


def test_every_request_has_a_timeout():
    assert canvas.TIMEOUT_SECONDS > 0
    assert "timeout=TIMEOUT_SECONDS" in Path(canvas.__file__).read_text(encoding="utf-8")


# =============================================================================
# Households
# =============================================================================


@pytest.mark.django_db
def test_only_the_household_with_a_canvas_login_runs(fake, connected, household_a, household_b):
    poll()
    assert last_run(household_a).outcome == Outcome.OK
    assert last_run(household_b).outcome == Outcome.SKIPPED
    assert not Event.objects.filter(household=household_b).exists()


@pytest.mark.django_db
def test_matching_never_crosses_households(fake, connected, household_a, household_b):
    theirs = seed(household_b, title="B's copy", structured_meta={"canvas_assignment_id": 5001})
    poll()
    assert Event.objects.get(pk=theirs.pk).structured_meta == {"canvas_assignment_id": 5001}
    assert task(household_a, 5001).structured_meta["canvas_submitted"] is True


# =============================================================================
# Dry run
# =============================================================================


@pytest.mark.django_db
def test_dry_run_prints_the_plan_and_writes_nothing(fake, connected, household_a):
    out = io.StringIO()
    call_command("canvas_poll", "--dry-run", stdout=out)
    text = out.getvalue()
    assert "DRY RUN" in text
    assert "inserted" in text and "Module 2: Quiz" in text
    assert "Rolled back; nothing was written." in text
    assert Event.objects.count() == 0
    assert not IngestRun.objects.filter(source=Source.CANVAS).exists()


@pytest.mark.django_db
def test_dry_run_reports_a_refusal_without_stamping_it(fake, connected, household_a):
    fake.queued[COURSES_URL] = [Response(401, {}, b"")]
    out = io.StringIO()
    call_command("canvas_poll", "--dry-run", stdout=out)
    assert "NeedsLogin" in out.getvalue()
    assert SourceCredential.objects.get(household=household_a).invalid_since is None


# =============================================================================
# The phone sees the change: updated_at, and only when something changed
# =============================================================================


@pytest.mark.django_db(transaction=True)
def test_writes_bump_updated_at_for_the_changes_feed_and_no_ops_do_not(
    fake, vault_key, household_a, auth_client
):
    """`transaction=True`: inside one wrapping transaction Postgres's `now()`
    never moves, so an UPDATE and a no-op would look the same."""
    try:
        vault.store(household_a, "canvas", cookie_jar(), config={"base_url": BASE})
        poll()
        homework = task(household_a, 5002)
        stamp = homework.updated_at

        poll()
        assert task(household_a, 5002).updated_at == stamp
        since = quote((stamp + datetime.timedelta(microseconds=1)).isoformat())
        feed = auth_client.get(f"/api/changes?aspects=events&since={since}").json()
        assert [e for e in feed["events"] if e["id"] == str(homework.pk)] == []

        fake.assignment(5002)["submission"].update(
            workflow_state="submitted", submitted_at="2026-09-19T01:00:00Z"
        )
        poll()
        changed = task(household_a, 5002)
        assert changed.done is False
        assert changed.structured_meta["canvas_submitted"] is True
        assert changed.updated_at > stamp
        feed = auth_client.get(f"/api/changes?aspects=events&since={since}").json()
        delivered = [e for e in feed["events"] if e["id"] == str(homework.pk)]
        assert len(delivered) == 1 and delivered[0]["done"] is False
    finally:
        Event.objects.all().delete()


# =============================================================================
# Whitespace in titles (2026-09-28 live run)
# =============================================================================


@pytest.mark.parametrize(
    ("raw", "tidy"),
    [
        (" Module 2: Assignment ", "Module 2: Assignment"),
        ("Module 3:  Assignment  ", "Module 3: Assignment"),
        ("Tab\tand\nnewline", "Tab and newline"),
        ("", ""),
        (None, ""),
    ],
)
def test_tidy_name_trims_and_collapses(raw, tidy):
    assert canvas.tidy_name(raw) == tidy


@pytest.mark.django_db
def test_a_new_row_s_title_carries_no_stray_whitespace(fake, connected, household_a):
    fake.assignment(6001)["name"] = "  Sprint 1   Report  "
    poll()
    assert task(household_a, 6001).title.endswith(" · Sprint 1 Report")
    assert "  " not in task(household_a, 6001).title


@pytest.mark.django_db
def test_a_blank_name_falls_back_to_the_assignment_id(fake, connected, household_a):
    fake.assignment(6001)["name"] = "   "
    poll()
    assert task(household_a, 6001).title.endswith(" · Assignment 6001")


@pytest.mark.django_db(transaction=True)
@pytest.mark.parametrize("as_migrated", [False, True], ids=["tidy_sql", "migration_operation"])
def test_migration_0006_tidies_only_poller_titles_and_bumps_updated_at(household_a, as_migrated):
    """`transaction=True` so the migration's `now()` is later than the seed's.

    Run twice: the bare UPDATE, and the migration's own operation (the DO block
    guarded on `public.events`), which is what live runs. In the pytest
    database `migrate` ran before conftest built `public.events`, so the guard
    made it a no-op there; here the table exists and the block must work."""
    import importlib

    module = importlib.import_module("ingest.migrations.0006_tidy_canvas_title_whitespace")
    tidy_sql = module.Migration.operations[0].sql if as_migrated else module.TIDY_SQL
    try:
        messy = seed(
            household_a,
            title="COSC 3318 Python Programming ·  Module 2: Assignment ",
            origin_guid="canvas:179692",
            structured_meta={"canvas_assignment_id": 179692},
        )
        clean = seed(
            household_a,
            title="COSC 3318 Python Programming · Module 1: Assignment",
            origin_guid="canvas:179000",
            structured_meta={"canvas_assignment_id": 179000},
        )
        by_hand = seed(
            household_a,
            title="  my own   title ",
            origin_guid=None,
            structured_meta={"canvas_assignment_id": 179999},
        )
        no_aid = seed(
            household_a,
            title="  not a canvas   task ",
            origin_guid="canvas:elsewhere",
            structured_meta={"note": "x"},
        )
        before = {row.pk: row.updated_at for row in Event.objects.all()}

        with connection.cursor() as cursor:
            cursor.execute(tidy_sql)

        messy.refresh_from_db()
        assert messy.title == "COSC 3318 Python Programming · Module 2: Assignment"
        assert messy.updated_at > before[messy.pk]
        for row, title in (
            (clean, "COSC 3318 Python Programming · Module 1: Assignment"),
            (by_hand, "  my own   title "),
            (no_aid, "  not a canvas   task "),
        ):
            row.refresh_from_db()
            assert row.title == title
            assert row.updated_at == before[row.pk]
    finally:
        Event.objects.all().delete()


# =============================================================================
# Wiring
# =============================================================================


def test_the_crontab_runs_canvas_poll_every_half_hour():
    crontab = Path(__file__).resolve().parents[2] / "deploy" / "crontab"
    assert "*/30 * * * * python manage.py canvas_poll" in crontab.read_text().splitlines()


# =============================================================================
# ADR 0052: Canvas rows are private to the member whose login was read
# =============================================================================


@pytest.mark.django_db
def test_new_rows_are_private_to_the_member_whose_canvas_login_was_read(
    fake, vault_key, household_a, household_user
):
    vault.store(
        household_a, "canvas", cookie_jar(), config={"base_url": BASE}, user=household_user
    )
    seeded = seed(
        household_a,
        title="matched by hand",
        origin_guid="semester:canvas:math-3391-quiz-2",
        structured_meta={"canvas_assignment_id": 5001, "canvas_course": "MATH 3391"},
    )
    poll()
    assert last_run(household_a).outcome == Outcome.OK
    inserted = Event.objects.filter(household=household_a).exclude(pk=seeded.pk)
    assert inserted.count() == 6, list(inserted.values_list("title", flat=True))
    # Assignments and the discussion's sub-deadline alike.
    for row in inserted:
        assert row.owner_user_id == household_user.pk, row.title
        assert row.created_by_id == household_user.pk, row.title
    # A row that already existed keeps the visibility it had: shared.
    assert Event.objects.get(pk=seeded.pk).owner_user_id is None


@pytest.mark.django_db
def test_an_unattributed_canvas_login_inserts_shared_rows(fake, connected, household_a):
    """A credential nobody is recorded as having stored (a two-owner household
    at backfill time) inserts shared rows, as before ADR 0052."""
    assert connected.user_id is None
    poll()
    rows = Event.objects.filter(household=household_a)
    assert rows.count() == 7
    assert not rows.exclude(owner_user__isnull=True).exists()


@pytest.mark.django_db
def test_handing_over_a_session_records_who_did(vault_key, auth_client, household_user):
    from ingest.models import SourceCredential

    response = auth_client.put(
        "/api/ingest/sessions/canvas",
        {"secret": cookie_jar(), "config": {"base_url": BASE}},
        format="json",
    )
    assert response.status_code in (200, 201), response.data
    assert "user" not in response.data and "user_id" not in response.data
    assert SourceCredential.objects.get(source="canvas").user_id == household_user.pk
