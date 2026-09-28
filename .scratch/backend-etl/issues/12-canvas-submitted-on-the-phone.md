---
map: backend-etl
ticket: "12"
title: "The phone shows what Canvas says beside each coursework task (Android terminal)"
type: build
status: open
blockers: ["04"]
blocked-by: ["[[04-canvas-poll]]"]
tags: [ticket]
---

# Canvas as a double check, on the phone

**Owned by the Android terminal** (`app/`). The server side is done by ticket 04's 2026-09-28
change; this ticket only renders it. Ruling and wording: ticket 11, which binds here verbatim:
same table, same words, never colour or a glyph alone.

## What the phone already receives

`events.structured_meta` for every Canvas-backed task (`structured_meta ? 'canvas_assignment_id'`)
arrives through `/api/changes`. Keys: `canvas_submitted` (bool, derived server-side; do not
re-derive it from the raw fields), `submission_state`, `submitted_at`, `missing`, `late`,
`excused`, `score`, `grade`, `points_possible`, `read_at`. Confirm the Room replica keeps
`structured_meta` intact; if a mapper drops it, that is the first fix.

## Build

Wherever a coursework task is listed (the task list, HOME's day view, the dates aspect), show ticket
11's line under the title. The voice path gets the same fact: `read_calendar` already reports `kind`
and `done`; add the Canvas line's words to each coursework item it returns, so "what's due?" can say
"Canvas says you submitted it" without the model inventing it (§7: the assistant never asserts an
outcome it did not observe; here Canvas is the observation, and the words must name Canvas).

## Verification

- [ ] Unit test of the line formatter, every row of ticket 11's table.
- [ ] On the A25: an open Canvas task Canvas calls submitted shows "Canvas says submitted".
