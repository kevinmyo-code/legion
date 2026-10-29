---
map: clerk
title: "The clerk: Alfred hands work to Claude Code on Kevin's laptop"
charted: 2026-10-29
charted-by: "Kevin + Fable"
effort: "`.scratch/clerk/`"
tickets: 6
open: 6
status: open
tags: [map]
---

# The clerk: Alfred hands work to Claude Code on Kevin's laptop

**Kevin, 2026-10-29:** *"how can i get the ai to do something that claude code could do. like finding
something, booking an appointment etc ... writing small custom code, python scripts etc if i wanted a
pdf parsed."* Offered three shapes: the Claude Agent SDK on an API key (rejected: *"i was hoping more
of using my monthly sub and not the api"*), Claude Code cloud routines fired through GitHub, and the
Claude Code CLI run headless on a machine logged into his account. **Ruling: the CLI on his own
laptop.** *"i like A more and i think we can use it when i keep the laptop on, so for example i just
leave it on when i wanna go out and i think i might need to use a session."*

## Destination

Kevin, in the car: "have the clerk parse the statement I dropped in Drive and tell me the totals".
Alfred queues it and says in words whether the laptop is on ("the clerk picked it up" / "the laptop
is off since two, it runs when you turn it on"). On the laptop a poller claims the task, runs
`claude -p` in a folder of its own, posts the summary and any files back to the engine. Alfred
raises once when it lands, anchored to the task Kevin asked for, silenceable. Anything that commits
in the world (book, pay, send, submit) stops at the last step and comes back as a question. The
same thing is reachable by hand on a Clerk screen (ADR 0035).

## What is binding

- **Subscription, not API.** The worker shells out to the installed `claude` CLI logged into
  Kevin's claude.ai account. No `ANTHROPIC_API_KEY` anywhere in this map. The Agent SDK is not used.
- **The engine is the queue and the record** (ADR 0044). The laptop never listens on a port; it
  polls. Tables household-scoped, in `TENANT_TABLES`, tenancy tests.
- **Honesty.** `delegate_task` succeeding means "queued", never "done". The result row is the only
  source of an outcome verb. Worker offline is said in words with the last-seen time.
- **Compute is free, commitments ask.** The worker's system prompt forbids booking, paying,
  sending, submitting or deleting outside the task folder without a `needs_confirmation` return.
  Alfred relays the question; "yes" is a follow-up task that resumes the same CLI session.
- **Third-party content stays read-through** (CLAUDE.md section 7): a task that reads mail or a
  document other people wrote returns its answer; the worker's transcript is not uploaded, only
  the summary and the files the task produced.
- **Not in v1:** attachments from the phone (v1 tasks name a Drive file or a URL), a logged-in
  browser profile for booking, the work laptop (one worker, `machine` field ready for more).

## The API, so three builders can run at once

All under `/api/clerk/`, `Authorization: Token <device key>` (`DeviceTokenAuthentication`), scoped
with `household.tenancy.scoped`. Tables `clerk_tasks`, `clerk_task_files`, `clerk_workers`.

| Method and path | Who | Body / result |
|---|---|---|
| `POST tasks/` | phone, head unit, web | `{request, kind: "task" or "followup", parent: id?}` returns `{id, status: "queued", worker: {machine, state, last_seen_at} or null}` |
| `GET tasks/?limit=&status=` | phone, web | newest first: `{id, request, status, summary, question, created_at, finished_at, machine, files: [{id, name, size}]}` |
| `GET tasks/{id}/` | all | same shape, full `summary` and `question` |
| `POST tasks/{id}/cancel/` | phone, web | queued becomes cancelled; running becomes `cancel_requested` (worker kills the CLI) |
| `GET status/` | phone, head unit | `{workers: [{machine, state: "idle" or "busy" or "offline", last_seen_at, task_id}], queued, running}`; offline means no heartbeat for 90 s |
| `POST workers/heartbeat/` | worker | `{machine, state, task_id, claude_version}` every 20 s, returns `{cancel_requested: [ids]}` |
| `POST tasks/claim/` | worker | `{machine}` returns the next queued task (oldest first; a followup only after its parent finished) or 204 |
| `POST tasks/{id}/finish/` | worker | multipart: `status` (`done`, `failed`, `needs_confirmation`), `summary`, `question`, `session_id`, `duration_ms`, `turns`, files `out[]`; returns 200 |
| `GET tasks/{id}/files/{file_id}/` | all | the bytes |

Status machine: `queued`, `running`, then one of `done`, `failed`, `needs_confirmation`, `cancelled`.
A task whose worker heartbeat goes silent for 10 min while `running` becomes `failed` with
"worker lost".

## Tickets

- 01 decision (resolved 2026-10-29): headless Claude Code on Kevin's laptop, on the subscription
- 02 build: engine `clerk` app: models, endpoints, status, tenancy, tests, migration, ADR 0066
- 03 build: the laptop worker `tools/clerk/`: poller, `claude -p` runner, result upload, Task Scheduler install, logs
- 04 build: phone tools `delegate_task`, `check_clerk`, `confirm_task`, the done raise, Clerk screen (hands path); head unit `check_clerk` and `delegate_task`
- 05 test: install the worker on Kevin's laptop, first task from the phone, the offline case, the confirmation case
- 06 build: Claude Code sessions reporter (Kevin, 2026-10-28, "layer 1"): hooks post session state to the engine, `ask_claude` tool; the clerk heartbeat already says whether the laptop is on
