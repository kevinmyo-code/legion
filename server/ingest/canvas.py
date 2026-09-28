"""Canvas coursework into `events` (backend-etl ticket 04).

Every run reads every active course's assignments with the student's own
submission, turns each into one `kind = task` row (plus one sub-deadline row
for a discussion whose description names an earlier initial-post deadline),
and writes them through `public.upsert_canvas_task`, the Postgres function
that holds the rules two writers must agree on (migration 0003). What this
module decides is only what Canvas SAID; what may be written is the
function's call.

**Auth is the vault's cookie jar** (map ruling 2): Canvas's REST API accepts
the web session for GETs. A 401, or a redirect to a login page, marks the
session refused (`vault.refuse_session`) and the run records `needs_login`.

**Nothing is written from a partial or suspicious read.** Every page of every
course is fetched before the first write. A course list that comes back empty
while this poller owns rows, or a course whose assignment list comes back
empty while it owns rows in that course, fails the run with nothing written:
an empty answer is far more often a Canvas hiccup than a semester ending in
the half hour since the last run, and tombstoning a semester on a hiccup is
the one mistake here that a person would notice.

**Tombstones, never deletes, and only for rows this poller can place.** An
owned row (it carries `canvas_assignment_id` and no parent) whose
`canvas_course_id` is an active course Canvas answered for, and whose
assignment Canvas no longer returns, gets `deleted_at` and the marker
`canvas_tombstoned_at`. The marker is what lets a later run bring the row
back if the assignment reappears (a teacher unpublishing and republishing),
and its absence is what stops the poller resurrecting a row a person deleted.
A row with no `canvas_course_id` (the 2026-09-05 hand-seeded ones, until a run
has matched them once) cannot be placed in a course and is never tombstoned.

**Course notices are not read.** `decisions.md` 2026-09-04 makes a notice an
`event`, but the assignments endpoint returns only assignments, so this
module writes only tasks.
"""

from __future__ import annotations

import datetime
import html
import json
import re
import time
import urllib.parse
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass, field
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from django.db import connection, transaction
from django.db.models.functions import Now

from ingest import vault
from ingest.drive import Response
from ingest.models import IngestRun, SessionSource

TIMEOUT_SECONDS = 30
MAX_PAGES = 100
PER_PAGE = 100
# One polite retry after a 429, never more; Retry-After is honoured up to this.
MAX_BACKOFF_SECONDS = 120
DEFAULT_BACKOFF_SECONDS = 30

# Canvas prefixes JSON with this for session-authenticated API calls (a CSRF
# guard against JSON hijacking). It must be stripped before parsing.
_JSON_HIJACK_PREFIX = "while(1);"

# Submission states that mean "handed in", before any grading.
_SUBMITTED_STATES = frozenset({"submitted", "pending_review"})


# (method, url, headers, body) -> Response. Tests replace it; nothing else does.
Transport = Callable[[str, str, dict[str, str], bytes | None], Response]


class _NoRedirects(urllib.request.HTTPErrorProcessor):
    """Every status comes back to the caller and no redirect is followed, so a
    302 to the university's login page is SEEN as one rather than followed into
    an HTML page that would then fail to parse as JSON."""

    def http_response(self, request, response):
        return response

    https_response = http_response


_OPENER = urllib.request.build_opener(_NoRedirects)

# The backoff's clock. A module global so a test can replace it.
_sleep = time.sleep


def urllib_transport(
    method: str, url: str, headers: dict[str, str], body: bytes | None
) -> Response:
    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    with _OPENER.open(request, timeout=TIMEOUT_SECONDS) as reply:
        return Response(reply.status, dict(reply.headers.items()), reply.read())


class CanvasRefused(Exception):
    """Canvas refused the saved session. The message is what Canvas did."""


class CanvasError(Exception):
    """Canvas answered, but not with something this poller can trust."""


class CourseForbidden(Exception):
    """A 403 on one course's assignments: that course is closed to this
    student (a concluded or date-restricted course). Not a dead login."""


_LINK_NEXT = re.compile(r'<([^>]+)>\s*;\s*rel="?next"?', re.IGNORECASE)


def next_link(link_header: str | None) -> str | None:
    if not link_header:
        return None
    for part in link_header.split(","):
        match = _LINK_NEXT.search(part)
        if match:
            return match.group(1)
    return None


def parse_json(body: bytes):
    text = body.decode("utf-8-sig").lstrip()
    if text.startswith(_JSON_HIJACK_PREFIX):
        text = text[len(_JSON_HIJACK_PREFIX) :]
    return json.loads(text)


def _cookie_header(cookies: list[dict], host: str) -> str:
    host = host.lower()
    pairs = []
    for cookie in cookies:
        domain = str(cookie.get("domain", "")).lstrip(".").lower()
        if host == domain or host.endswith("." + domain):
            pairs.append(f"{cookie['name']}={cookie['value']}")
    return "; ".join(pairs)


class CanvasClient:
    """One Canvas session. GETs only."""

    def __init__(
        self,
        base_url: str,
        cookie_jar: dict,
        *,
        transport: Transport | None = None,
        sleep: Callable[[float], None] | None = None,
    ):
        self.base_url = base_url.rstrip("/")
        self._host = urllib.parse.urlparse(self.base_url).netloc
        self._cookie = _cookie_header(cookie_jar.get("cookies", []), self._host)
        self._transport = transport or urllib_transport
        self._sleep = sleep or _sleep

    def _get(self, url: str, *, forbidden_is_refusal: bool) -> Response:
        headers = {"Accept": "application/json", "Cookie": self._cookie}
        for attempt in (1, 2):
            response = self._transport("GET", url, headers, None)
            if response.status != 429:
                break
            if attempt == 2:
                raise CanvasError(
                    "Canvas rate-limited this poller twice in a row (HTTP 429). "
                    "Nothing was written; the next scheduled run is the retry."
                )
            self._sleep(_retry_after(response.header("Retry-After")))
        status = response.status
        if 300 <= status < 400:
            location = response.header("Location") or ""
            if vault.is_login_refusal(status, location):
                raise CanvasRefused(f"redirected to a login page (HTTP {status})")
            raise CanvasError(
                f"Canvas answered HTTP {status} with a redirect this poller does not follow."
            )
        if status == 401:
            raise CanvasRefused("HTTP 401")
        if status == 403:
            if forbidden_is_refusal:
                raise CanvasRefused("HTTP 403")
            raise CourseForbidden(url)
        if status != 200:
            raise CanvasError(f"Canvas answered HTTP {status}.")
        return response

    def _paged(self, url: str, *, forbidden_is_refusal: bool) -> list[dict]:
        out: list[dict] = []
        pages = 0
        while url:
            pages += 1
            if pages > MAX_PAGES:
                raise CanvasError(
                    f"Canvas kept paginating past {MAX_PAGES} pages; stopped rather than loop."
                )
            response = self._get(url, forbidden_is_refusal=forbidden_is_refusal)
            try:
                payload = parse_json(response.body)
            except ValueError as exc:
                raise CanvasError("Canvas answered 200 with something that is not JSON.") from exc
            if not isinstance(payload, list):
                raise CanvasError("Canvas answered with a JSON object where a list was expected.")
            out.extend(item for item in payload if isinstance(item, dict))
            url = next_link(response.header("Link"))
        return out

    def active_courses(self) -> list[dict]:
        return self._paged(
            f"{self.base_url}/api/v1/courses?enrollment_state=active&per_page={PER_PAGE}",
            forbidden_is_refusal=True,
        )

    def assignments(self, course_id: int) -> list[dict]:
        return self._paged(
            f"{self.base_url}/api/v1/courses/{course_id}/assignments"
            f"?include%5B%5D=submission&per_page={PER_PAGE}",
            forbidden_is_refusal=False,
        )


def _retry_after(value: str | None) -> float:
    try:
        seconds = float(value) if value is not None else DEFAULT_BACKOFF_SECONDS
    except ValueError:
        seconds = DEFAULT_BACKOFF_SECONDS
    return max(0.0, min(seconds, MAX_BACKOFF_SECONDS))


# =============================================================================
# What Canvas said, as task payloads
# =============================================================================


def parse_instant(value) -> datetime.datetime | None:
    """A Canvas ISO 8601 UTC timestamp as an aware datetime, or None."""
    if not value:
        return None
    parsed = datetime.datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise CanvasError(f"Canvas gave a timestamp with no offset: {value!r}.")
    return parsed.astimezone(datetime.UTC)


def is_submitted(submission: dict) -> bool:
    """Canvas's own word that the work is in.

    `submitted_at` present, or a submitted/pending-review state, or `graded`
    when Canvas does NOT also call it missing: an instructor grading a missing
    assignment zero is `graded` with no submission, and ticking that would say
    "you submitted this" about work that was never handed in. `excused` is not
    a submission and does not tick.
    """
    if submission.get("submitted_at"):
        return True
    state = submission.get("workflow_state")
    if state in _SUBMITTED_STATES:
        return True
    if state == "graded":
        return not submission.get("missing") and submission.get("late_policy_status") != "missing"
    return False


_TAG = re.compile(r"<[^>]+>")
_WEEKDAYS = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
_INITIAL_POST = re.compile(
    r"\b(?:initial|first|original|main)\s+(?:discussion\s+)?(?:post|posting|response|thread)s?\b"
    r"(?P<tail>[^.;!?\n]{0,80})",
    re.IGNORECASE,
)
_WEEKDAY = re.compile(r"\b(" + "|".join(_WEEKDAYS) + r")s?\b", re.IGNORECASE)
_TIME = re.compile(
    r"^[^.;!?\n]{0,25}?\b(?:(?P<noon>noon|midday)|(?P<midnight>midnight)|"
    r"(?P<h>\d{1,2})(?::(?P<m>\d{2}))?\s*(?P<ap>am|pm)\b)",
    re.IGNORECASE,
)


# Where the replies' half of a sentence starts: "initial post due Wednesday,
# replies due Friday" must not read Friday as a second initial-post day.
_REPLIES = re.compile(r"\b(?:repl|respon[ds]\s+to|peers?\b|classmates?\b)", re.IGNORECASE)
_DOTTED_MERIDIEM = re.compile(r"\b([ap])\.m\.", re.IGNORECASE)


def description_text(assignment: dict) -> str:
    """The description as plain text, with "p.m." folded to "pm" so the
    sentence split on full stops does not cut a time in half."""
    raw = assignment.get("description") or ""
    topic = assignment.get("discussion_topic")
    if not raw and isinstance(topic, dict):
        raw = topic.get("message") or ""
    text = html.unescape(_TAG.sub(" ", raw))
    return _DOTTED_MERIDIEM.sub(lambda m: m.group(1) + "m", text)


def initial_post_deadline(
    assignment: dict, due: datetime.datetime | None, zone: ZoneInfo | None
) -> tuple[datetime.datetime | None, str]:
    """The initial-post deadline a discussion's description states, or None
    with the reason there is none.

    Canvas's one `due_at` is the LAST obligation (the replies). The initial
    post lives only in the description, as a weekday: "initial post due
    Wednesday". It is resolved to the latest such weekday strictly before the
    due date, in the course's own timezone, at the stated time or 23:59.
    Anything short of exactly one weekday is ambiguous and yields nothing:
    an invented deadline is worse than a missing one.
    """
    text = description_text(assignment)
    mentions = list(_INITIAL_POST.finditer(text))
    if not mentions:
        return None, "no initial-post deadline in the description"
    days: set[str] = set()
    times: set[tuple[int, int]] = set()
    for mention in mentions:
        tail = _REPLIES.split(mention.group("tail"), maxsplit=1)[0]
        found = list(_WEEKDAY.finditer(tail))
        if not found:
            continue
        days.update(day.group(1).lower() for day in found)
        clock = _TIME.match(tail[found[0].end() :])
        if clock:
            times.add(_clock(clock))
    if len(days) != 1:
        return None, (
            "the description mentions an initial post but names no weekday for it"
            if not days
            else f"the description names more than one initial-post day ({', '.join(sorted(days))})"
        )
    if len(times) > 1:
        return None, "the description names more than one initial-post time"
    if due is None:
        return None, "the discussion has no due date to count back from"
    if zone is None:
        return None, "no timezone is known for this course, so the weekday cannot be placed"
    weekday = _WEEKDAYS.index(days.pop())
    hour, minute = times.pop() if times else (23, 59)
    local_due = due.astimezone(zone)
    for back in range(1, 7):
        day = local_due.date() - datetime.timedelta(days=back)
        if day.weekday() == weekday:
            local = datetime.datetime.combine(day, datetime.time(hour, minute), tzinfo=zone)
            return local.astimezone(datetime.UTC), "canvas_description"
    return None, "the initial-post weekday is the due date's own weekday"


def _clock(match: re.Match) -> tuple[int, int]:
    if match.group("noon"):
        return 12, 0
    if match.group("midnight"):
        return 23, 59
    hour = int(match.group("h")) % 12
    minute = int(match.group("m") or 0)
    if match.group("ap").lower().startswith("p"):
        hour += 12
    return hour, minute


def course_zone(course: dict, fallback: str | None) -> ZoneInfo | None:
    for name in (course.get("time_zone"), fallback):
        if name:
            try:
                return ZoneInfo(str(name))
            except (ZoneInfoNotFoundError, ValueError):
                continue
    return None


def _submission_types(assignment: dict) -> str | None:
    types = assignment.get("submission_types")
    if isinstance(types, list):
        return ",".join(str(t) for t in types)
    return types


def course_label(course: dict) -> str:
    return str(course.get("name") or course.get("course_code") or f"Course {course.get('id')}")


# Canvas's full course name, e.g. "2026FA COSC3318 21007 MAIN - Python Programming":
# a term code, the subject and number run together, a section number, an
# optional "MAIN", then " - " and the course's own name.
_CANVAS_COURSE_NAME = re.compile(
    r"^\s*\d{4}[A-Z]{1,3}\s+(?P<subject>[A-Z]{2,5})\s?(?P<number>\d{4}[A-Z]?)\s+\d{3,6}"
    r"\s+(?:MAIN\s+)?-\s+(?P<title>\S.*?)\s*$"
)
_TITLE_SEPARATOR = " · "


@dataclass(frozen=True)
class CourseName:
    """What a new row is titled with (`label`) and stores as `canvas_course`
    (`code`). Existing rows are never retitled."""

    label: str
    code: str


def derived_course_name(course: dict) -> CourseName:
    """The short form of Canvas's course name, "COSC 3318 Python Programming"
    with code "COSC 3318". A name that does not fit the pattern is kept exactly
    as Canvas gave it: an unrecognised name is left alone, never mangled."""
    name = course_label(course)
    match = _CANVAS_COURSE_NAME.match(name)
    if match is None:
        return CourseName(name, str(course.get("course_code") or name))
    code = f"{match.group('subject')} {match.group('number')}"
    return CourseName(f"{code} {match.group('title')}", code)


def sibling_course_names(
    household,
) -> tuple[dict[int, tuple[str, str | None]], dict[str, CourseName]]:
    """The labels existing rows already use, keyed by `canvas_course_id` and by
    `canvas_course`: the text before " · " in the oldest titled row of each."""
    from legacy.models.dates import Event

    by_id: dict[int, tuple[str, str | None]] = {}
    by_code: dict[str, CourseName] = {}
    rows = (
        Event.objects.filter(household=household, deleted_at__isnull=True)
        .filter(structured_meta__has_key="canvas_assignment_id")
        .exclude(structured_meta__has_key="parent_canvas_assignment_id")
        .order_by("created_at", "id")
    )
    for row in rows:
        title = row.title or ""
        if _TITLE_SEPARATOR not in title:
            continue
        prefix = title.split(_TITLE_SEPARATOR, 1)[0].strip()
        if not prefix:
            continue
        meta = row.structured_meta or {}
        code = meta.get("canvas_course")
        code = str(code) if code else None
        course_id = _course_id_of(row)
        if course_id is not None and course_id not in by_id:
            by_id[course_id] = (prefix, code)
        if code and code not in by_code:
            by_code[code] = CourseName(prefix, code)
    return by_id, by_code


def course_name_for(
    course: dict, by_id: dict[int, tuple[str, str | None]], by_code: dict[str, CourseName]
) -> CourseName:
    """A sibling row's label by course id, then by `canvas_course`, then the
    label derived from Canvas's name."""
    derived = derived_course_name(course)
    try:
        course_id = int(course["id"])
    except (KeyError, TypeError, ValueError):
        course_id = None
    if course_id is not None and course_id in by_id:
        label, code = by_id[course_id]
        return CourseName(label, code or derived.code)
    return by_code.get(derived.code, derived)


def assignment_tasks(
    course: dict,
    assignment: dict,
    zone: ZoneInfo | None,
    name: CourseName | None = None,
) -> tuple[list[dict], str | None]:
    """The payloads for one assignment, and why no sub-deadline was emitted
    when it is a discussion without one (None otherwise)."""
    aid = int(assignment["id"])
    course_id = int(course["id"])
    submission = assignment.get("submission") or {}
    due = parse_instant(assignment.get("due_at"))
    submitted_at = parse_instant(submission.get("submitted_at"))
    not_graded = assignment.get("grading_type") == "not_graded"
    types = _submission_types(assignment) or ""
    name = name or derived_course_name(course)
    title = f"{name.label}{_TITLE_SEPARATOR}{assignment.get('name') or f'Assignment {aid}'}"

    evidence = {
        "canvas_assignment_id": aid,
        "canvas_course_id": course_id,
        "canvas_due_at": assignment.get("due_at"),
        "grading_type": assignment.get("grading_type"),
        "submission_state": submission.get("workflow_state"),
        "submitted_at": submission.get("submitted_at"),
        "score": submission.get("score"),
        "grade": submission.get("grade"),
        "late": submission.get("late"),
        "missing": submission.get("missing"),
        "excused": submission.get("excused"),
        "points_possible": assignment.get("points_possible"),
        "submission_types": _submission_types(assignment),
    }
    parent = {
        "canvas_assignment_id": aid,
        "origin_guid": f"canvas:{aid}",
        "title": title,
        "starts_at": due.isoformat() if due else None,
        # What Canvas said, sent even for a discussion: the function (0004)
        # refuses to tick one, since Canvas calls it submitted at the first post.
        "submitted": is_submitted(submission),
        "submitted_at": submitted_at.isoformat() if submitted_at else None,
        "manual_completion": not_graded,
        # A not_graded placeholder is never inserted: decisions.md 2026-09-04
        # held all fifteen back as duplicates of the seeded WebAssign rows, and
        # Canvas structurally cannot see them done. An existing one is still
        # matched, and gets `manual_completion: true`.
        "insert": not not_graded,
        "insert_reason": "a not_graded placeholder Canvas cannot see done",
        "evidence": evidence,
        # Only a NEW row takes these; the function never retitles a matched one.
        "insert_meta": {"canvas_course": name.code},
    }
    tasks = [parent]
    skipped = None
    if "discussion_topic" in types.split(","):
        when, reason = initial_post_deadline(assignment, due, zone)
        if when is None:
            skipped = reason
        else:
            tasks.append(
                {
                    "parent_canvas_assignment_id": aid,
                    "sub_deadline": "first_post",
                    "origin_guid": f"canvas:{aid}:first_post",
                    "title": f"{title} - initial post due",
                    "starts_at": when.isoformat(),
                    "evidence": {
                        "parent_canvas_assignment_id": aid,
                        "sub_deadline": "first_post",
                        "read_from": reason,
                        "canvas_course_id": course_id,
                    },
                }
            )
    return tasks, skipped


# =============================================================================
# One run
# =============================================================================


@dataclass
class Plan:
    tasks: list[dict] = field(default_factory=list)
    course_ids: set[int] = field(default_factory=set)
    seen_assignment_ids: set[int] = field(default_factory=set)
    watermark: str | None = None
    notes: list[str] = field(default_factory=list)


def _owned_live_rows(household):
    from legacy.models.dates import Event

    return (
        Event.objects.filter(household=household, deleted_at__isnull=True)
        .filter(structured_meta__has_key="canvas_assignment_id")
        .exclude(structured_meta__has_key="parent_canvas_assignment_id")
    )


def _course_id_of(row) -> int | None:
    value = (row.structured_meta or {}).get("canvas_course_id")
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def read_canvas(client: CanvasClient, household, *, fallback_zone: str | None) -> Plan:
    """Everything Canvas says, read in full before anything is written."""
    owned = list(_owned_live_rows(household))
    courses = [c for c in client.active_courses() if c.get("id") is not None]
    readable = [c for c in courses if not c.get("access_restricted_by_date")]
    if not readable and owned:
        raise CanvasError(
            f"Canvas returned no active courses while {len(owned)} Canvas tasks are live. "
            f"That reads as a Canvas fault, not a finished semester, so nothing was written."
        )
    owned_by_course: dict[int, int] = {}
    for row in owned:
        course_id = _course_id_of(row)
        if course_id is not None:
            owned_by_course[course_id] = owned_by_course.get(course_id, 0) + 1

    by_id, by_code = sibling_course_names(household)
    plan = Plan()
    for course in readable:
        course_id = int(course["id"])
        try:
            assignments = client.assignments(course_id)
        except CourseForbidden:
            plan.notes.append(
                f"{course_label(course)}: Canvas refused its assignments (HTTP 403); "
                f"its rows were left exactly as they were."
            )
            continue
        if not assignments and owned_by_course.get(course_id):
            raise CanvasError(
                f"Canvas returned no assignments for {course_label(course)} while "
                f"{owned_by_course[course_id]} of its tasks are live. Nothing was written."
            )
        plan.course_ids.add(course_id)
        zone = course_zone(course, fallback_zone)
        name = course_name_for(course, by_id, by_code)
        for assignment in assignments:
            if assignment.get("id") is None:
                continue
            plan.seen_assignment_ids.add(int(assignment["id"]))
            if assignment.get("published") is False:
                continue
            updated = assignment.get("updated_at")
            if updated and (plan.watermark is None or str(updated) > plan.watermark):
                plan.watermark = str(updated)
            tasks, skipped = assignment_tasks(course, assignment, zone, name)
            plan.tasks.extend(tasks)
            if skipped:
                plan.notes.append(f"{tasks[0]['title']}: no sub-deadline written ({skipped}).")
    return plan


def upsert(household, task: dict, read_at: datetime.datetime) -> dict:
    with connection.cursor() as cursor:
        cursor.execute(
            "select public.upsert_canvas_task(%s, %s::jsonb, %s)",
            [household.pk, json.dumps(task), read_at],
        )
        result = cursor.fetchone()[0]
    return json.loads(result) if isinstance(result, str) else result


def tombstones(household, plan: Plan, read_at: datetime.datetime) -> list[dict]:
    """Owned rows in an answered course whose assignment Canvas no longer
    returns. Soft-deleted with the poller's own marker, never hard-deleted."""
    from legacy.models.dates import Event

    out = []
    for row in _owned_live_rows(household):
        course_id = _course_id_of(row)
        if course_id is None or course_id not in plan.course_ids:
            continue
        try:
            aid = int(row.structured_meta["canvas_assignment_id"])
        except (TypeError, ValueError):
            continue
        if aid in plan.seen_assignment_ids:
            continue
        meta = {**row.structured_meta, "canvas_tombstoned_at": read_at.isoformat()}
        Event.objects.filter(pk=row.pk, deleted_at__isnull=True).update(
            deleted_at=Now(), structured_meta=meta
        )
        out.append({"action": "tombstoned", "id": str(row.pk), "title": row.title})
    return out


def apply_plan(household, plan: Plan, read_at: datetime.datetime) -> list[dict]:
    """Every write, in one transaction: a run lands whole or not at all."""
    with transaction.atomic():
        results = [upsert(household, task, read_at) for task in plan.tasks]
        results.extend(tombstones(household, plan, read_at))
    return results


WRITES = frozenset({"inserted", "updated", "resurrected", "tombstoned"})


def client_for(household, *, transport: Transport | None = None, sleep=None):
    credential, secret = vault.session_for(household, SessionSource.CANVAS)
    config = credential.config or {}
    base_url = config.get("base_url")
    if not base_url:
        raise CanvasError(
            "The stored Canvas login has no base_url. Run tools/connect_session.py canvas "
            "--base-url <your Canvas address> again. Nothing was written."
        )
    client = CanvasClient(base_url, secret, transport=transport, sleep=sleep)
    return credential, client, config.get("time_zone")


def poll(
    run: IngestRun,
    *,
    transport: Transport | None = None,
    sleep=None,
    now: Callable[[], datetime.datetime] | None = None,
) -> tuple[str | None, list[dict], Plan]:
    """One scheduled run for `run.household`. Returns the outcome for
    `run_job`, the per-row results, and the plan read."""
    household = run.household
    credential, client, fallback_zone = client_for(household, transport=transport, sleep=sleep)
    try:
        plan = read_canvas(client, household, fallback_zone=fallback_zone)
    except CanvasRefused as exc:
        raise vault.refuse_session(credential, str(exc)) from exc
    read_at = (now or (lambda: datetime.datetime.now(datetime.UTC)))()
    results = apply_plan(household, plan, read_at)
    run.rows_written = sum(1 for r in results if r.get("action") in WRITES)
    run.rows_unchanged = len(results) - run.rows_written
    run.watermark = plan.watermark
    return None, results, plan


def describe(result: dict) -> str:
    action = result.get("action", "?")
    line = f"{action:<12} {result.get('title', '')}"
    if result.get("changed"):
        line += f"  [{', '.join(result['changed'])}]"
    if action in {"inserted", "updated", "resurrected"}:
        line += f"  due={result.get('starts_at')} done={result.get('done')}"
    if result.get("reason"):
        line += f"  ({result['reason']})"
    return line
