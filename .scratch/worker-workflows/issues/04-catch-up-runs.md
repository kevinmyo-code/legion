---
map: worker-workflows
ticket: "04"
title: "Engine: catch-up runs, minted at claim (generalizes the memory review's due rule)"
type: build
status: open
status-detail: ""
size: M
blockers: ["03"]
blocked-by: ["[[03-engine-catalog-and-trigger]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Catch-up runs

Kevin, 2026-11-09, for the memory review: *"If laptop is off, then do the review the next time it
turns on. Doesn't have to be a strict schedule."* That rule, written once, for every workflow with
an `every`.

The memory-review map (local branch `docs/memory-review-claude`) designed the mechanism and
rejected the alternatives (a second Windows scheduled task, an engine cron, a worker-local due
file). This ticket keeps its design and swaps the hard-coded kind for the catalog's schedule. It
does not edit that map; whichever ticket lands second carries the rename (ticket 03).

## Due rule (computed when read, never written by a job)

A workflow W on machine M is due when all hold:

- W's catalog entry on M has `every`, and `LEGION_WORKFLOW_SCHEDULES` is not `off`, and W is not
  in `LEGION_WORKFLOW_SCHEDULES_OFF` (comma list, a per-workflow kill switch).
- No `done` task for W on M in the last `every.hours`.
- No task for W is `queued`, `running` or `needs_confirmation` (a run waiting on Kevin blocks the
  next one: no pile of unanswered questions).
- The last `failed` or `cancelled` run of W is older than `every.retry_after_hours`.
- An extra predicate for W, if the engine registers one (`claude_session.due.EXTRA[name]`). The
  memory review registers its own (a successful `memory_reviews` row in 24 h, the member has
  private memories). Every other workflow has none.

Run history comes from `claude_tasks` (30-day retention). A schedule longer than 30 days is
refused at catalog intake.

## Minted at claim

- Inside `ClaimView`, before queued tasks are read, in the same transaction with a row lock.
- Only for a machine that reported itself idle in its last heartbeat: no running task and
  `input_idle_s >= every.idle_minutes * 60`; or due for `every.force_after_hours` and no task
  running. (`input_idle_s` and `on_mains` arrive in the heartbeat per the memory-review ticket;
  this ticket adds them if that one has not landed.)
- **At most one minted task per claim**, the longest-overdue first, so a laptop off for a week
  runs its backlog one at a time, never all at once and never twice.
- The minted task: `kind = workflow`, `workflow = W`, `origin = schedule`, `request` = "Scheduled
  run of <name>." (no personal text), `params` = null (defaults apply), `deliver` from the catalog.

## By hand

`POST workflows/run/ {machine, name}`: queues one now (the screen's and web desk's Run button,
also what the phone's `action: "workflow"` amounts to). Same one-at-a-time rule.

## Verification

- [ ] pytest: due after `every.hours`; not due while queued, running or waiting on Kevin; retry
      wait after a failure; kill switches; idle gate and force-after; one mint per claim with two
      due; a week of silence then one claim mints exactly one; a `listed` machine never minted a
      `full`-only workflow; concurrent claims mint one.
- [ ] The memory review's tests from its own map pass against this shape.
