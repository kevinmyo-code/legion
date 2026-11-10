---
status: accepted
decided: 2026-10-29
decided-by: Kevin
amended: 2026-11-03
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

## Consequences

- Every laptop worker and hook holds an ordinary household device token. The engine cannot tell a
  worker from the phone; the API's "who" is convention, and a household member's token could
  finish a task. Accepted for v1 (Kevin 2026-11-01; ADR 0045: membership is the only
  authorization). **Later: worker endpoints restricted to machine tokens.**
- 25 MB rows in Postgres cost database storage for 30 days; a storage backend would replace them
  when the engine gets one.
- Subscription use is not metered or warned about (*"just run it"*).
- Recurring tasks are not in this decision; they come later, set by voice.
- **Amended 2026-11-09 (Kevin, memory-review map): one built-in recurring kind.** Recurring
  tasks set by voice are still later. The daily memory review (`kind = memory_review`, origin
  `schedule`) is minted by the engine inside a claim from Kevin's own idle Alienware, at most once
  a day and one at a time, catching up once after the laptop was off; never on the work laptop.
  Its request is fixed text and its summary is counts only. Off until `LEGION_MEMORY_REVIEW` is set.
- Source: `.scratch/claude-session/map.md` rulings 1 to 17, tickets 01 to 03.
