---
map: backend-etl
ticket: "11"
title: "The web app shows what Canvas says beside each coursework task"
type: build
status: open
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

- [ ] Unit test of the formatter covering every row of the table.
- [ ] The Today view against real household data shows "Canvas says submitted" on at least one open
      task, or the ticket records that none exists today.
