---
map: backend-etl
ticket: "11"
title: "The web app shows what Canvas says beside each coursework task"
type: build
status: built
status-detail: "Built 2026-09-28 (6c1a185, feat/web-canvas-submitted): vitest 69/69, tsc clean, rendered at 384px against a mock. Not deployed. Owed: Kevin sees it on the live Today view after the next deploy."
blockers: ["04"]
blocked-by: ["[[04-canvas-poll]]"]
tags: [ticket]
---

# Canvas as a double check, on the web

**Kevin, 2026-09-28:** *"i'll manually mark things as done. i just need to know what needs doing...
perhaps we can be a double check, like i can manually tick, but the thing also says submitted in
canvas"*, then *"do both"* (web and phone).

`canvas_poll` never ticks. It writes `structured_meta.canvas_submitted` (bool) and the raw evidence
(`submission_state`, `submitted_at`, `missing`, `late`, `excused`, `score`, `grade`) on every
Canvas-backed task. This ticket renders it on the web; ticket 12 is the phone.

## What each task says

Only on rows carrying `structured_meta.canvas_assignment_id`. Words, never colour or a glyph alone
(CLAUDE.md §4 rule 7's posture, and "trust disclosures are not furniture").

| Row `done` | Canvas | Line shown |
|---|---|---|
| no | submitted | **"Canvas says submitted"** (the useful one: tick it) |
| yes | submitted | "Canvas: submitted" |
| yes | not submitted, not excused | **"Canvas: not submitted"** (a mismatch worth seeing) |
| any | `missing: true` | "Canvas: marked missing" |
| any | `excused: true` | "Canvas: excused" |
| no | not submitted | nothing |

A grade, when Canvas has one, follows on the same line: "Canvas: submitted, 95/100".

The evidence's age matters: append "as of <read_at, relative>" when `read_at` is older than the
canvas freshness threshold, and let `/api/freshness`'s canvas sentence stand above the list as it
already does when stale or `needs_login`.

## Build

The Today view and any coursework list in `server/frontend/`. Read the events API payload to
confirm `structured_meta` reaches the client; if it does not, expose `canvas_submitted` and the
fields above through the API serializer rather than re-deriving Canvas semantics in the client.
One formatting function, tested, used everywhere the line appears.

## Verification

- [x] Unit test of the formatter covering every row of the table.
- [x] The Today view against real household data shows "Canvas says submitted" on at least one open
      task, or the ticket records that none exists today.

## Built

2026-09-28. `server/frontend/src/lib/canvas.ts` is the one formatting function
(`canvasLine`), covering every row of the table above plus the grade suffix and the
`read_at`-age suffix, `tested` by `server/frontend/src/lib/canvas.test.ts` (16 cases).
`canvasMetaOf` reads `Event.structured_meta` defensively (it is `unknown` on the wire) and
returns `null` for a row that is not Canvas-backed.

Wired into `server/frontend/src/components/event-row.tsx` - the one row component both the
Today view and the month calendar's day view already share (ADR 0035's one-controller
posture applied to a component) - so both surfaces got the line from one change, not two.

`structured_meta` already reached the client: `api/events.py`'s `EventSerializer.Meta.fields`
already listed it and `schema.d.ts` already typed `Event.structured_meta` (as `unknown`). No
server change was needed or made; the formatter reads the field the poller already writes
(`ingest/canvas.py`'s `evidence` dict, confirmed field-for-field: `submission_state`,
`submitted_at`, `score`, `grade`, `late`, `missing`, `excused`, `points_possible`,
`canvas_submitted`, `read_at`).

Priority when Canvas sets more than one flag (not specified by the table's row-by-row shape,
so a call had to be made): `excused` beats `missing` beats the submitted/not-submitted rows,
on the reasoning that excused is Canvas's most final word on an assignment. Covered by its own
test (`excused beats missing when Canvas sets both`).

The freshness threshold (`read_at` older than 2h) mirrors `ingest/freshness.py`'s
`STALE_AFTER[Source.CANVAS]` by a hardcoded constant in `canvas.ts`, commented as a manual
mirror - there is no endpoint that hands a client a threshold, and `/api/freshness`'s `stale`
is about the last POLL, not one row's `read_at`. If the server value changes this one goes
stale with it silently; nothing catches that today. The `/api/freshness` canvas sentence
itself is not newly wired into the web app by this ticket - it did not exist on `Today`
before this ticket and the ticket's own wording ("as it already does") assumes it is handled
elsewhere; building that banner was out of this ticket's scope (formatter + the row line only).

Verified in-browser (not the live site): `npm run dev` (port 5183) with Playwright
intercepting `/api/auth/me`, `/api/households/me`, `/api/changes` to serve a fabricated
`Event` shaped exactly like the one real open row Kevin traced ("COSC 3334 Intro to
Cybersecurity - Module 2: Discussion - User Authentication", `canvas_submitted: true`,
`done: false`), at a 384x800 viewport. "Canvas says submitted" rendered under the title in
both the day-detail card and the "On today" list, wrapped cleanly at that width. Screenshot
taken and reviewed, then deleted (scratch, not committed).

Not touched: no `structured_meta.canvas_assignment_id`-bearing row was ticked or written by
this work; nothing in `server/` Python changed, so no pytest run was needed (checked first
that none was already running: `Get-CimInstance Win32_Process -Filter "Name='python.exe'"`
showed no `pytest` process at the time).
