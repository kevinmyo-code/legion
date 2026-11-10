---
status: accepted
decided: 2026-10-29
decided-by: Kevin
amended: 2026-11-10
source: "[[decisions#2026-10-29 - Claude session: Alfred hands tasks to Claude Code on Kevin's laptops (Kevin)]]"
tags: [adr]
---

# 67. Claude session: Alfred hands tasks to Claude Code on Kevin's laptops

## Standing

**Alfred may send a task to Claude Code on Kevin's laptops only when Kevin named it, or said yes to
a one-line offer as the very next thing he said.** The task runs headless on whichever claude.ai
subscription the laptop's CLI is logged into (on the Alienware, Kevin's personal Max plan, limits
shared with his own sessions), never the API. The engine (ADR 0044) holds the task, its summary, any file
Kevin asked for and each machine's status, 30 days, tenanted by household (ADR 0045). The
laptop's transcript never leaves the laptop. It is called "Claude session" in speech, on screen
and in code.

## Context

Kevin wanted the assistant to do what Claude Code does: find a file, write a small script, parse a
PDF, book something. Alfred's own tools cannot. Kevin pays for a Claude subscription and does not
want API spend (*"i was hoping more of using my monthly sub and not the api"*). Ticket 02's spike
(2026-11-01, Claude Code 2.1.316) showed one long-lived headless `claude -p` stream-json session
can take several messages, start a background helper per task, stay free, and resume by id.

## Decision

- **Named or asked.** Named ("use Claude on my Alienware to...") is sent. Not named: Alfred offers
  only after his own tools cannot do it, in one line, and only the next thing Kevin says counts as
  the yes. A yes covers that one task. ADR 0031's outcome verbs apply: Alfred says "sent" only
  after `POST /api/claude/tasks/` succeeded, and "done" only for a task the engine says is done.
- **Machines by spoken name.** "Alienware" and "work laptop". A named machine wins. Unnamed goes to
  the only machine online; with more than one online the task comes back `needs_machine`, Alfred
  asks which, and the answer assigns it (`POST tasks/{id}/assign/`).
- **Scope per machine.** The Alienware is `full`: every fixed drive, read and write, as Kevin's
  Windows user, not elevated. The work laptop is `listed`: its repos folder only, summaries only.
  The engine enforces the second half: for a `listed` machine it keeps no found-file rows,
  refuses send-file and upload, and drops `last_prompt_summary` from its interactive session rows.
  **The work laptop may email Kevin a summary, never a file** (ruling 18, Kevin 2026-11-01): it
  takes `deliver: "email_self"`, and the mail carries summary text only.
- **The ask list.** Deleting or overwriting Kevin's files, sending, booking or paying, installing
  software and changing system settings stop and come back as one question
  (`needs_confirmation`). Everything else runs. On the Alienware (Kevin, 2026-11-01) this is
  permission mode `dontAsk`, a broad explicit allowlist, and a `PreToolUse` hook that denies the
  ask-list operations with a reason; the hook is the enforcement, the allowlist a second guard.
  That is worker configuration (ticket 04), not engine code. Kevin's yes is a follow-up task with
  `confirms: true`, accepted only when its parent is `needs_confirmation`; the worker unlocks the
  asked step for that task alone.
- **Delivery by mail (amended 2026-11-01).** Alfred has his own Gmail; every send is on the ask
  list, except that **naming his own email in the request is the yes for that one task** ("...and
  email it to me"): the phone sets `deliver: "email_self"` on the task, and the worker's
  `send_mail.py` mails the file to Kevin from Alfred's account on the laptop. The engine carries
  the flag and never sends mail. Mail to anyone else still asks. WhatsApp delivery was chosen and
  dropped the same day.
- **Gmail and Amazon orders (amended 2026-11-04, Kevin).** On the Alienware a helper may read
  Kevin's Gmail, read only (search and read thread; `session.py` `GMAIL_READ`). For Amazon orders,
  mail first, then the carrier's public tracking page (UPS, USPS, FedEx) when the mail names one;
  never Amazon's own site. A headless read of his Amazon orders page was built and dropped the same
  day: Amazon sent the unattended browser to a fresh sign-in (`max_auth_age=0`) hours after Kevin
  signed in, and getting past that would be getting past a bot check (Kevin chose option A). The
  summary keeps only the answer (CLAUDE.md section 7, carve-out 2026-11-04). The work laptop has
  no Gmail.
- **Availability.** A locked laptop is on; powered off is off. A machine with no heartbeat for
  90 s is offline. A running task is failed after 30 minutes, or after its machine is silent for
  10. All three are computed when read, never written by a job.
- **Files.** A helper lists what it found; nothing uploads until Kevin says yes to "want it on your
  phone?". Files up to 25 MB, stored in Postgres beside the task because Cloud Run's filesystem
  does not persist. Results and files are deleted after 30 days (`manage.py
  claude_session_prune`, daily, from `deploy/crontab`). **On deploy,
  `deploy/cloudrun/install_schedule.py` must be run** or Cloud Scheduler never runs the prune.
  **Files Kevin names in the request (amended 2026-11-09, Kevin, ADR 0080)** take a different
  path: `deliver: files`, no second yes, a private bucket, deleted on receipt and at 24 hours, kept
  app-private on the phone. The offered-file flow above is unchanged.
- **Interactive sessions are readable** (machine, project, state, a one-line summary of the last
  prompt, except from a `listed` machine). A session waiting on Kevin is listed first, and a
  running task shows its helper's current step (120 characters, cleared when it finishes).
- **A hands path exists** (ADR 0035): the Claude session screen on the phone, first.
- **Nothing logs a request, a summary or a file name**, on the engine or in admin.

- **A researched page reaches the phone** (amended 2026-11-03, Kevin, ticket 09). `deliver:
  phone` asks for the result as one HTML page. The worker uploads it at finish (asking for it was
  the yes); the engine keeps it 5 MB at most, `text/html` only, and serves it only through a signed
  link that names one household and one file and lives 10 minutes, with `Content-Security-Policy:
  sandbox allow-scripts`, so its scripts run in an opaque origin. Alfred asks before opening it;
  a yes, a notification tap or the screen's button opens it in LEGION's browser. The web desk lists
  it under its task. The work laptop never takes one. Retention is the task's 30 days.

- **Workflows: named jobs, and recurring runs (amended 2026-11-10, Kevin, map worker-workflows).**
  Rulings R1 to R4 (2026-11-09, *"All ur picks"*), built on Kevin's *"yeah as per ur recs"*.
  - **R1, where they live.** A workflow is one of Kevin's private worker skills plus a
    `workflow.json` contract beside its `SKILL.md`, in the worker's skills folder on the laptop: the
    name, spoken aliases, one line, params, where it stops for his yes (`asks`), outputs, delivery,
    machine scope and an optional schedule. The skill text and anything personal never leave the
    laptop or enter the public repo, which holds the format, its lint, the matcher and invented
    examples (`tools/claude_session/workflows.py`). The engine keeps a derived copy per machine,
    sent by the worker when its hash changes, and drops any entry holding a path, an address or an
    amount. The engine never authors a workflow.
  - **R2, the trigger.** Alfred starts one with `action: "workflow"` on the `claude_session` tool,
    only when Kevin names Claude, exactly as for `send` (the named-or-asked rule above is
    unchanged). The engine resolves his words with the worker's matcher; a name it does not know
    comes back with the names it does. A run is one at a time per workflow and machine.
  - **R3, unattended runs, which resolves the line below that recurring tasks "come later".** A
    workflow that declares `every` is caught up, not clocked: inside a claim from its own idle,
    token-bound laptop, with nothing queued or running, the engine mints at most one due run (the
    longest overdue; the memory review first), never two, and a laptop that was off runs it once
    when it is next on. A scheduled run does its read-only and own-work-folder steps and **stops at
    every ask-list step and every ship or deploy step** with `needs_confirmation`, exactly like a
    task Kevin sent; it carries no token of any kind, and a question it asks blocks the next run
    until he answers. **The one standing yes is `deliver: email_self`**, mail to Kevin only,
    because installing the workflow (itself on the ask list) was the yes, and the worker honours it
    only when the laptop's own `workflow.json` declares it. A page or files to the phone, mail to
    anyone else, and any deploy never stand. `LEGION_WORKFLOW_SCHEDULES=off` stops every schedule;
    `LEGION_WORKFLOW_SCHEDULES_OFF` names single ones. A schedule may not exceed the 30 day
    retention, and the 30 minute cap holds.
  - **R4, the repeated-task miner** reads Claude worker tasks only (the engine's task rows and a
    local task log on the laptop, `%USERPROFILE%\.legion\task-log.jsonl`, request text, 180 days,
    never leaving the laptop), proposes at 3 or more similar requests over 2 or more days, and
    never installs anything itself; Kevin's interactive Claude Code sessions stay out.
  - **Workflows are not procedures** (ADR 0070): a procedure is a deterministic DAG the engine runs
    in milliseconds; a workflow is Claude work on a laptop that takes minutes. A worker or the
    engine never creates or edits a standing watch through a workflow.
  - **The memory review stays its own kind** (`memory_review`) for now: folding it into a workflow
    renames rows the live worker guards by kind, so it waits for Kevin's call.

## Consequences

- Every laptop worker and hook holds an ordinary household device token. The engine cannot tell a
  worker from the phone; the API's "who" is convention, and a household member's token could
  finish a task. Accepted for v1 (Kevin 2026-11-01; ADR 0045: membership is the only
  authorization). **Later: worker endpoints restricted to machine tokens.**
- 25 MB rows in Postgres cost database storage for 30 days; a storage backend would replace them
  when the engine gets one.
- Subscription use is not metered or warned about (*"just run it"*).
- Recurring tasks are not in this decision; they come later, set by voice. (Resolved for
  workflows by the 2026-11-10 amendment above; setting a schedule by voice is still later.)
- **Amended 2026-11-09 (Kevin, memory-review map): one built-in recurring kind.** Recurring
  tasks set by voice are still later. The daily memory review (`kind = memory_review`, origin
  `schedule`) is minted by the engine inside a claim from Kevin's own idle Alienware, at most once
  a day and one at a time, catching up once after the laptop was off; never on the work laptop.
  Its request is fixed text and its summary is counts only. Off until `LEGION_MEMORY_REVIEW` is set.
- **Amended 2026-11-09 evening (Kevin: "Everything auto"): the review applies what passes.**
  Replaces memory-review rulings R1 (proposals only for 14 runs) and R3 (everything but exact
  duplicates a proposal with Accept all). With `LEGION_MEMORY_REVIEW=on` there is no shadow
  period and no `confidence: high` gate: every change the helper sends (merge, fuzzy merge,
  correction, expiry, relabel, retire) that passes the engine's code checks applies on the night.
  - **Cap:** at most `LEGION_MEMORY_REVIEW_CAP` (default 25) applied a night; the rest leave no row
    and wait for the next night.
  - **Code checks kept:** every row named exists, is live and is the member's own; a merge or
    correction adds no word its rows (or Kevin's cited newer words) lack; an expiry names a date the
    memory prints, at least 2 days past; nothing identity- or bank-shaped; retire and expire never
    hard-delete (the row leaves recall with `retired_reason`); R4 evidence (a paraphrased reason plus
    record ids); the household memory lock; the off switch and the demo door.
  - **Contradictions:** the newer words win, except that a memory Kevin saved himself beats one
    distilled. Explicit sources are `manual` (typed on the Memory screen), `phone`, `web` and
    `claude` ("remember ..." said to an assistant); distilled are `consolidation` (the hourly job)
    and `review`. A correction is itself distilled, so it applies on its own only to a distilled
    row; against an explicit row it waits in To check.
  - **Undo:** every applied change is restored from the Retired screen (`revert`); undoing a row a
    later night merged again undoes that later change first. A row Kevin restored is never changed
    on its own again (the review may still propose), and restored exact copies are not merged by code
    again.
  - **Guard:** if Kevin undoes more than 30% of the last 14 runs' applied changes (at least 10), `on`
    behaves as `shadow` until that falls. To check holds only what the code checks reject.
- Source: `.scratch/claude-session/map.md` rulings 1 to 17, tickets 01 to 03;
  `.scratch/worker-workflows/map.md` rulings R1 to R4 (2026-11-10 amendment).
