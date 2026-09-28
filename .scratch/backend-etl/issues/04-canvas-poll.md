---
map: backend-etl
ticket: "04"
title: "canvas_poll on the server, every 30 minutes"
type: build
status: resolved
status-detail: "Verified live 2026-09-28: dry run diffed against live (80 matched, 7 new, 8 hand sub-deadlines left alone, 15 WebAssign placeholders not inserted, 0 tombstones, no open row ticked); real run wrote 7 rows, schedule unpaused every 30 min. Follow-up: two new titles carry Canvas whitespace."
blockers: ["01", "02"]
blocked-by: ["[[01-job-runner-and-freshness]]", "[[02-session-vault-and-login-handover]]"]
tags: [ticket]
---

# canvas_poll

Absorbs two-clients 03, chief-of-staff 06 and django-engine 06 job 2. **Every binding rule in
`.scratch/two-clients/issues/03-canvas-poller.md` ("Binding rules" and "Where the sub-deadline rule
is enforced") binds here verbatim**: coursework is `kind=task`; `done` comes from `submitted_at`
with `provenance=DETERMINISTIC` and the evidence in `structured_meta`; discussions split into
sub-deadlines and a parent's submission never ticks them; a `manual_completion: true` row is never
touched; match on `structured_meta->>'canvas_assignment_id'`, then `origin_guid`; upstream deletion
is a tombstone; re-running an identical payload writes nothing.

**Superseded 2026-09-28 (Kevin): `done` never comes from Canvas.** The poller writes
`structured_meta.canvas_submitted` and the evidence; the tick is by hand. See Built.

Change from those tickets: auth is the vault's cookie jar (ruling 2), not a token. Canvas's REST API
accepts the web session for GETs. `/api/v1/courses?enrollment_state=active`, then
`/api/v1/courses/{id}/assignments?include[]=submission&per_page=100`, following `Link` pagination.
On 429: back off, record `failed`, never hammer.

Watermark: the max `updated_at` Canvas returned, for the record only. Every run reads all active
assignments, because a submission does not always bump the assignment's `updated_at`.

crontab: `*/30 * * * * manage.py canvas_poll`.

## Verification

- [x] pytest against recorded Canvas fixtures: first run inserts, second writes zero rows.
- [x] pytest: a discussion yields parent + sub-deadline rows; parent submitted leaves reply row open.
- [x] pytest: a 401 records `needs_login`.
- [x] Live run against Kevin's Canvas, diffed against the 102 existing `DETERMINISTIC` tasks; every
      difference explained.

## Built (2026-09-27, `feat/backend-etl`)

- `ingest/canvas.py` (Canvas client over `urllib`, the read, the payloads, tombstones),
  command `canvas_poll [--dry-run]`, and `ingest/migrations/0003_upsert_canvas_task.py`:
  **`public.upsert_canvas_task(household, task jsonb, read_at)`**, the RPC two-clients 03
  names, in plpgsql. It holds the rules both writers must agree on: match on
  `structured_meta->>'canvas_assignment_id'` then `origin_guid`; `done` set from Canvas only
  on a row with no `parent_canvas_assignment_id` and no `manual_completion: true`; an
  unchanged row is not UPDATEd, so `touch_updated_at` never fires and `/api/changes` does not
  re-deliver it. Tests: `tests/test_canvas_poll.py`, fixtures in `tests/canvas_fixtures/`
  (two pages, `while(1);` prefix, graded quiz, unsubmitted set, discussion with "Initial post
  due Wednesday by 11:59 PM", ambiguous discussion, `not_graded` placeholder, a
  date-restricted course).
- `deploy/crontab`: `*/30 * * * * python manage.py canvas_poll`.
- Live `structured_meta` keys read 2026-09-27 (pg MCP, read-only) and matched exactly:
  `canvas_assignment_id` (a JSON number), `canvas_course` (a label, e.g. "COSC 3318"),
  `submission_state`, `submitted_at`, `score`, `grade`, `late`, `missing`, `excused`,
  `points_possible`, `submission_types` (a string), `read_at`, `match`,
  `matched_server_title`; sub-deadline rows carry `parent_canvas_assignment_id`,
  `sub_deadline` (`first_post`), `read_from` (`syllabus` / `canvas_description`). No live row
  has `manual_completion` or a course id. New keys the poller adds: `canvas_course_id`,
  `canvas_due_at` (Canvas's own string), `grading_type`, `canvas_tombstoned_at`.
- Decided in the build, not by this ticket:
  - **The rules live in the RPC, not in Python** (two-clients 03 "Where the sub-deadline rule
    is enforced", ADR 0042). Python decides only what Canvas said.
  - **"Submitted"** = `submitted_at` present, or state `submitted`/`pending_review`, or
    `graded` unless Canvas also says `missing` / `late_policy_status = missing` (a zero for
    missing work is `graded` with nothing handed in). `excused` does not tick.
  - **Canvas never ticks anything; submission is shown, not applied** (Kevin, 2026-09-28:
    *"i'll manually mark things as done. i just need to know what needs doing. so we dont
    really need to pull canvas submission state. but perhaps we can be a double check, like i
    can manually tick, but the thing also says submitted in canvas"*). Supersedes both the
    2026-09-27 "only ever ticks" rule and the discussions-only rule of migration 0004.
    Enforced in the RPC, migration `0005_canvas_never_ticks.py` (0003 and 0004 are live, so the
    function is replaced, never edited): a new row is inserted `done = false` whatever Canvas
    says; an existing row's `done`, `done_at` and `provenance` are absent from every UPDATE, so
    the poller neither ticks nor unticks. Every upsert writes `structured_meta.canvas_submitted`
    (bool, the "Submitted" definition above) beside the raw evidence (`submission_state`,
    `submitted_at`, `score`, `grade`, `late`, `missing`, `excused`); a first-post sub-deadline
    row carries its parent's value, since Canvas marks a discussion submitted at the first post.
    The web and the phone render it (tickets 11, 12). `manual_completion` is still stamped on
    discussions and `not_graded` placeholders, and `canvas_is_discussion` stays, though neither
    changes `done` any more. The first live run after 0005 updates every Canvas row once to add
    the key, so `/api/changes` re-delivers them once.
  - **`not_graded` placeholders are never inserted** (decisions.md 2026-09-04 held all 15
    back as duplicates of the seeded WebAssign rows); an existing matched one gets
    `manual_completion: true`.
  - **Initial-post parsing:** the description must name exactly one weekday in an
    "initial/first post|response" clause (the clause is cut at "repl"/"peers"/
    "classmates"); resolved to the latest such weekday strictly before `due_at`, at the stated
    time or 23:59, in the course's `time_zone` (then `config.time_zone`). No weekday, two
    weekdays, no timezone, or the due date's own weekday: no sub-deadline, and a note says why.
  - **Sub-deadline rows:** `origin_guid canvas:<id>:first_post`. An existing live row with the
    same parent and `sub_deadline` that the poller did not create (all 22 live ones) is left
    exactly as it is, never duplicated. The poller changes only its own sub-deadline rows'
    due time, never their `done`, and never tombstones them.
  - **Tombstones** only for owned rows (id, no parent) whose `canvas_course_id` is a course
    Canvas answered for this run. The 80 hand-seeded rows have no course id until a run
    matches them, so they cannot be tombstoned before then. A tombstone carries
    `canvas_tombstoned_at`; only such a row is brought back if the assignment reappears. A row
    deleted by hand is never resurrected or duplicated.
  - **A 403 on one course's assignments skips that course** (concluded or restricted), rows
    untouched, a note in the output; 401, or a 302 to a login path, is `needs_login`. A 403 on
    the course list is `needs_login`.
  - **429:** sleeps `Retry-After` (capped 120s, default 30s) and retries once; a second 429
    records `failed`. 30s timeout per request, at most 100 pages per list.
  - **Every read lands before the first write, in one transaction.** Empty course list with
    live owned rows, or an empty assignment list for a course with live owned rows: `failed`,
    nothing written.
  - Matched rows keep `title`, `origin_guid`, `kind`, `canvas_course`; `starts_at` follows
    Canvas only when Canvas gives a due date. New rows: `origin_guid canvas:<id>`, title
    `<course label> · <assignment name>`.
  - **New rows use the short course label** (Kevin, 2026-09-27, after the live dry run titled
    one `2026FA COSC3318 21007 MAIN - Python Programming · Module 2: Assignment`). The label is
    the title prefix (text before " · ") of the oldest live row of the same household with the
    same `canvas_course_id`, else one whose `canvas_course` equals the derived code; with no
    sibling it is derived from Canvas's name: term code, section number and `MAIN -` dropped,
    `COSC3318` spaced, giving `COSC 3318 Python Programming`. A name that does not fit that
    pattern is kept exactly as Canvas gave it. New rows' `canvas_course` is the short code
    (`COSC 3318`). An existing row is never retitled (the RPC's UPDATE has no `title`). `read_at` is rewritten only when something else
    changed. `watermark` = max assignment `updated_at`, recorded only.
  - Course notices are not read: the assignments endpoint returns only assignments.
  - **New titles carry no stray whitespace** (the follow-up in status-detail). The live run
    wrote `COSC 3318 Python Programming ·  Module 2: Assignment ` and a Module 3 twin: Canvas's
    names had leading and trailing spaces. `canvas.tidy_name` trims the assignment name and
    collapses every whitespace run to one space before the title is built. Data migration
    `0006_tidy_canvas_title_whitespace.py` tidies rows already written, and ONLY rows the
    poller created (`structured_meta ? 'canvas_assignment_id'` AND `origin_guid LIKE
    'canvas:%'`) whose title actually changes; it sets `updated_at = now()` (the
    `touch_updated_at` trigger does the same on live) so `/api/changes` delivers the fix. Read
    live 2026-09-28 (read-only): exactly the two rows above match. Tested both as the bare
    UPDATE and as the migration's guarded DO block.
- Owed: the live run (box 4). Once `connect_session.py canvas` has run,
  `python manage.py canvas_poll --dry-run` prints every planned insert, update and tombstone
  and rolls back. Expect the first run to update most of the 80 matched rows once (new keys
  `canvas_course_id`, `canvas_due_at`, `grading_type`), and no sub-deadline inserts where a
  hand-made row exists. Whether Canvas's course objects carry `time_zone` is reasoned, not
  seen: without it no sub-deadline is emitted and the output says so. Migration 0003 must be
  applied to live (`migrate`) before the first scheduled run.
