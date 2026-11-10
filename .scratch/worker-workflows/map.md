---
map: worker-workflows
title: "Worker workflows: named jobs Alfred runs on the Claude worker, and a loop that proposes new ones"
charted: 2026-11-09
charted-by: "Opus (planning approved by Kevin 2026-11-09)"
effort: "`.scratch/worker-workflows/`"
tickets: 16
open: 16
status: open
tags: [map]
---

# Worker workflows

**Kevin, 2026-11-09:** *"legion can call u or the worker session. So certain repeated things can
turn into workflows etc."* Planned 2026-11-09 and kept local until Kevin said build; he did on
2026-11-10 (*"yeah as per ur recs"*), and the engine and worker half is built on
`feat/worker-workflows` (see Build status). Two parts:

- **A. Workflow library.** Named, repeatable jobs Alfred starts by voice on the Claude worker
  (ADR 0067): "audit my logs", "prepare a ship round", "fix finding three", the daily memory
  review, "review Claude's memory".
- **B. Repeated-task loop.** The worker notices requests that recur and proposes a workflow for
  Kevin's yes (the queued idea "Claude worker skills + repeated-task audit loop").

Every example in this map and its tickets is invented. The repo is public.

## Destination

Kevin, in the car: "have Claude audit my logs from this morning." Alfred sends ONE
`claude_session` call with `action: "workflow"`. The engine resolves "audit my logs" to the
Alienware's `log-audit` workflow, the worker hands it to a helper with the `log-audit` skill, and
Alfred later says: "Log audit done. Four findings, the worst is a double greeting after a call.
The report is on the Alienware; want it on your phone?" The memory review runs the same way, minted
by the engine once a day when the laptop is idle. Once a week the worker reads what Kevin keeps
asking for and asks one question: "You asked for a rates sheet four times in two weeks. Make it a
workflow?" Only a yes installs it.

## The recommendation in one table

| Question | Recommended | Ticket |
|---|---|---|
| What a workflow is | A worker skill (`SKILL.md`, the how) plus a `workflow.json` sidecar (the contract: name, aliases, one line, params, asks, outputs, schedule) | [Definition format](issues/02-definition-format.md) |
| Where it lives | The worker's private skills folder, `%APPDATA%\legion-claude\work\.claude\skills\<name>\` (already hook-protected, already how helpers run, outside the public repo). The repo holds the format, the parser, tests and invented fixtures only | [Rulings](issues/01-rulings.md) R1 |
| Engine's copy | Derived, never authored: the worker publishes the catalog (no paths, no bodies) on heartbeat; the engine caches it per machine | [Engine catalog and trigger](issues/03-engine-catalog-and-trigger.md) |
| How Alfred triggers it | One new `action` value, `"workflow"`, on the existing `claude_session` tool (~3 setup tokens of ~17). Name and params ride in `request`; the engine resolves the name | [Rulings](issues/01-rulings.md) R2, [Phone](issues/06-phone.md) |
| How Alfred discovers them | `check` lists each machine's workflow names (result text, zero setup tokens); an unknown name comes back with the known names | [Engine catalog and trigger](issues/03-engine-catalog-and-trigger.md) |
| Confirmation points | Declared in `asks` (so Alfred can say "it stops for your yes before deploying"); enforced exactly as today: ask-list hook, `ship.py` token, `send_mail.py` token. A run never inherits a yes | [Definition format](issues/02-definition-format.md) |
| Scheduling | Catch-up, not a clock: the engine mints a due workflow task at claim time, at most one per workflow, on an idle machine. The memory review is the first instance | [Catch-up runs](issues/04-catch-up-runs.md), R3 |
| Outputs | Spoken summary always; a report file `%USERPROFILE%\.legion\reports\<workflow>\<date>-task-<N>.md` listed with `--found`; a page or email only as today (asked for, or the R3 standing email) | [Worker](issues/05-worker.md) |
| Repeated-task loop | `skill-miner` (already on the Alienware, manual) becomes `workflow-miner`, a weekly catch-up workflow over engine task rows plus a local 180-day task log; proposals local; one question; dismissed shapes never return | [Workflow miner](issues/14-workflow-miner.md), R4 |

## Rulings for Kevin

Four, all in [Rulings](issues/01-rulings.md) with the reasoning. Recommendations:

1. **R1. Where workflows live:** the worker's private skills folder, skill plus `workflow.json`.
   Not an engine table (drifts from the laptop), not repo files (private paths, public repo).
2. **R2. Trigger:** a new `action: "workflow"` (~3 tokens). Alfred uses it only when Kevin names
   Claude ("have Claude audit my logs"), as ruling 2 already says for `send`. No clause change.
3. **R3. Unattended runs:** a scheduled run does its read-only steps and stops at every ask-list
   or deploy step with `needs_confirmation`, like any task (amends ruling 15). The one standing
   yes allowed: a workflow may declare `deliver: email_self`, mail to Kevin only, because
   installing the workflow was the yes. No standing yes for anything else, ever for a deploy.
4. **R4. What the miner reads:** Claude worker tasks only (engine rows plus the local log), at
   procedures' bar (3+ matching requests over 2+ days). Kevin's interactive Claude Code sessions
   stay out in v1.

## Notes

- Execution in scope once Kevin says build (CLAUDE.md §12). Build tickets list what they wait on.
- **Workflows are not procedures** (ADR 0070). A procedure is a deterministic DAG the engine runs
  in milliseconds; a workflow is Claude work on the laptop that takes minutes. Procedures ticket 19
  option B (a `laptop_task` step) could later start a workflow; nothing here needs it.
- **The memory review is one workflow.** Kevin asked for that shape. The memory-review map (local
  branch `docs/memory-review-claude`, not edited here) plans `ClaudeTask.kind = "memory_review"`
  minted at claim (its ticket "Due rule and the memory_review task kind"). This map generalizes
  that ticket rather than forking it: `kind = "workflow"`, `workflow = "memory-review"`, same due
  rule, driven by `workflow.json`. Whichever lands second carries the rename (see
  [Catch-up runs](issues/04-catch-up-runs.md)).
- **`kind` is shared with Layer 2.** The Layer 2 design (Alfred messages interactive sessions,
  three rulings open) plans `kind = "relay"`. Values: `ask` (default), `relay`, `workflow`.
- **Standing agent rules** live in `%USERPROFILE%\.legion\AGENT-RULES.md` (being written in
  parallel). Every workflow message tells the helper to read it first; skills do not copy it.
- **Live worker** was `fix/claude-worker-wait` (53e6b028: helper wait, warm worktree, asklist
  redirect fix); merged into dev by 2026-11-10, and the worker build sits on it.
- Privacy: workflow bodies, reports, the task log and proposals stay on the Alienware. The engine
  sees names, one-liners, param names, ask lines, schedules, and the usual task summaries. Logs
  never carry requests, summaries or paths (ADR 0067). Nothing derived from real tasks is committed.
- Skills: `/grilling` for the rulings ticket, `/tdd` for builds.

## What exists, so nothing here is invented (read 2026-11-09 at origin/dev 8e983a60 and the live worker)

- **Worker skills already are a workflow library without a contract.** Twelve private skills in
  `%APPDATA%\legion-claude\work\.claude\skills\` (inventory kept off the repo; among them
  legion-chores, fix-alfred, build-feature, ship, skill-miner). The main session prompt's rule 11 matches a skill from the free-text
  task and starts the helper with "Use the <name> skill". What is missing: a name the engine and
  Alfred know, params, declared asks, a schedule, a fixed output place, a run history per name.
- **The ask-list hook** (`tools/claude_session/asklist_hook.py`) denies writes under
  `work\.claude` (Kevin's skills), overwrites of Kevin's files, push/deploy/`release_apk.py`/
  `gcloud` (every helper, token or not, line 94). Shipping goes through `ship.py ask` then
  `ship.py run --token` on Kevin's yes.
- **`skill-miner`** already clusters engine task rows (`GET /api/claude/tasks/?limit=100`), drafts
  proposals into `%APPDATA%\legion-claude\skill-proposals\<date>\`, and never installs without a
  yes token. It notes the engine keeps tasks 30 days and no durable log exists.
- **Engine** (`server/claude_session/`): `ClaudeTask` has `origin` (`named`, `offered`), `deliver`
  (`email_self`, `phone`), `steps`, no `kind`. `ClaimView` hands queued tasks to a heartbeat-fresh
  machine. 30-day retention.
- **Phone** (`ClaudeSessionToolbox.kt`): one `claude_session` tool, eight actions. The Live setup
  payload sits ~17 estimated tokens under its ceiling (`LiveSetupPayloadSizeTest`, chars/4).
- **Log audits today** run `tools/phone_audit_engine.py` (on `feat/phone-log-upload`), which shells
  `gcloud`: denied to worker helpers. Device-log reasons `logcat` and `crash` exist only on that
  branch; dev's reasons are drive_end, startup, manual, periodic, conversation. The worker can
  already read conversation records over the API (`fix-alfred`).

## Tickets

| # | Title | Type | Size | Blocked by |
|---|---|---|---|---|
| 01 | [Rulings: where workflows live, trigger, unattended runs, miner sources](issues/01-rulings.md) | grilling | S | - |
| 02 | [Definition format: workflow.json beside SKILL.md, parser and lint](issues/02-definition-format.md) | build | S | 01 |
| 03 | [Engine: workflow kind, catalog cache, name lookup, list](issues/03-engine-catalog-and-trigger.md) | build | M | 01, 02 |
| 04 | [Engine: catch-up runs, minted at claim (generalizes the memory review's due rule)](issues/04-catch-up-runs.md) | build | M | 03 |
| 05 | [Worker: publish the catalog, workflow task message, reports folder, task log](issues/05-worker.md) | build | M | 02, 03 |
| 06 | [Phone: action workflow, workflows in check, Workflows on the Claude session screen](issues/06-phone.md) | build | M | 03 |
| 07 | [Web desk: workflows with last run and Run](issues/07-web-desk.md) | build | S | 03 |
| 08 | [Engine: device logs readable by the worker (logcat and crash over the API)](issues/08-device-logs-read.md) | build | S | feat/phone-log-upload |
| 09 | [Workflow: log-audit](issues/09-wf-log-audit.md) | build | M | 05, 08 |
| 10 | [Workflow: fix-finding](issues/10-wf-fix-finding.md) | build | S | 09 |
| 11 | [Workflow: ship-round](issues/11-wf-ship-round.md) | build | L | 05 |
| 12 | [Workflow: memory-review (the wrapper; the review is the memory-review map)](issues/12-wf-memory-review.md) | build | S | 04, 05 |
| 13 | [Workflow: claude-memory-review](issues/13-wf-claude-memory-review.md) | build | S | 05 |
| 14 | [Workflow miner: the repeated-task loop](issues/14-workflow-miner.md) | build | M | 04, 05 |
| 15 | [ADR 0067 amendment and decisions entry](issues/15-adr-amendment.md) | task | S | 01 |
| 16 | [Live run on the Alienware](issues/16-live-run.md) | task | S | 06, 09, 11, 12, 14 |

Order: 01, then 02 and 15; 03; then 04, 05, 06, 07 in parallel (one engine agent for 03+04, one
worker agent for 05, one phone agent for 06). Workflow skills (09 to 14) are private-folder work,
installed through the worker's own ask (a helper cannot write `.claude\skills` without Kevin's
yes) or by Kevin's interactive session. 08 rides on `feat/phone-log-upload`.

Sizes: S under half a day of agent work, M about a day, L two or more.

## Decisions so far

- **2026-11-09, Kevin: R1 to R4 as recommended** (*"All ur picks"*). Ticket 01 resolved.
- **2026-11-10, Kevin: build** (*"yeah as per ur recs"*), engine and worker half only; the phone
  half later off the merged tree. ADR 0067 amended (ticket 15).
- **Open for Kevin:** fold the memory review into a workflow (ticket 12). It shipped first as its
  own `kind = memory_review`, and the live worker and ask-list hook guard it by that kind, so the
  rename ships only with a worker update. Until then both coexist and one claim mints at most one.

## Build status (2026-11-10, `feat/worker-workflows`)

| Ticket | State |
|---|---|
| 01, 15 | resolved |
| 02 format, 03 engine, 04 catch-up, 05 worker | built, tests green; owed: engine deploy, live worker update, ticket 16 |
| 06 phone | deferred: unmerged branches edit the same files and the voice payload is full; build to the contract below |
| 07 web desk | deferred: not nearly free (node, vitest, `schema.d.ts`) |
| 08, 09, 10, 13 | deferred: they read prod or personal data |
| 11, 14 | deferred: private-folder workflows (14's input, the task log, is built) |
| 12 | deferred: Kevin's ruling above |
| 16 | owed after deploy, worker update and 06 |

## Phone contract (for ticket 06, built against these endpoints)

All under `/api/claude/`, device token as today. New fields are additive; the phone's JSON
already ignores unknown keys.

- **Voice `action: "workflow"`:** `POST tasks/ {request: <Kevin's words>, workflow: <Kevin's
  words>, machine: <name or null>, origin: "named", parent: null}`.
  - 201: `{id, status: queued | needs_machine, machine: {name, state, last_seen_at} | null,
    workflow: <name>, asks: [<"Move the files into dated folders?">, ...]}`. Say "Sent to the
    <machine>: <workflow with hyphens as spaces>." and, when `asks` is not empty, "It stops for
    your yes before ..." from `asks`. `needs_machine`: ask which and `POST tasks/{id}/assign/`
    as today (assign re-checks the catalog and may 400).
  - 400: `{detail, workflows: [<spoken names>]}`. Read `detail` back; it already names them.
  - 409: `{detail}`: a run of that workflow is already queued, running or waiting on his yes.
  - Follow-ups (`parent`, `confirms`) are unchanged; a follow-up to a workflow task stays that
    workflow's on the engine without the phone sending anything new.
- **`check`:** `GET status/` machines each carry `workflows: [{name, says}]`. One line per online
  machine: "Workflows on the <machine>: log audit, ...". Names with hyphens as spaces.
- **Screen (hands path, ADR 0035):** `GET workflows/?machine=` returns per workflow `{machine,
  machine_state, name, spoken, says, aliases, params: [{name, says, required}], asks: [says],
  outputs, deliver, every: {hours, idle_minutes, force_after_hours, retry_after_hours} | null,
  last_run: {task, status, origin, created_at, finished_at} | null, waiting, due}`. Run button:
  `POST workflows/run/ {machine, name, params}` -> 201 `{id, status, machine, workflow, detail}`
  ("Queued log audit on the Alienware."), 400 `{detail, workflows}`, 409 `{detail}`. Both paths
  are one controller call each (`runWorkflow`), as the ticket says.
- **Task rows** everywhere add `kind` (`ask`, `memory_review`, `workflow`), `workflow` (name or
  null) and `params` (or null). Label `origin: "schedule"` rows "Scheduled".
- **Scheduled questions (R3):** the result watcher only watches tasks the phone sent. Poll
  `GET tasks/?origin=schedule&status=needs_confirmation` (filters added for this) and notify once
  per task id in the Claude tasks category; the answer is the ordinary `confirms` follow-up. A
  scheduled `done` is not announced. Silenceable with the existing `quiet`.
- **Payload:** the enum value only (~3 estimated tokens); measure with `LiveSetupPayloadSizeTest`
  first, fall back to R2 option B if it does not fit (that fallback would need an engine change:
  match on `send` too).

## Not yet specified

- Workflows on the work laptop (`listed` scope: summaries only). Probably "repo read-only
  workflows only"; wait for a first real one.
- Workflows set up by voice ("every Monday, do X"): ruling 15's original wish. The miner covers
  proposing; authoring a schedule by voice needs its own wording and a yes flow.
- Parameter types beyond free text (a date window, a finding number) once two workflows need the
  same one.
- A workflow started from a procedure step (procedures ticket 19 option B).
- Reading Kevin's interactive Claude Code sessions for the miner, if R4 is revisited.

## Out of scope

- An engine-side runner or a DAG for workflows: procedures already are that (ADR 0070).
- Layer 2 relay to interactive sessions: its own design; this map only shares the `kind` column.
- Auto-installing a proposed workflow. Always Kevin's yes.
- Any standing yes for deploys, installs, deletes, payments or mail to anyone but Kevin.
