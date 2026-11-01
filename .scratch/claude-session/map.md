---
map: claude-session
title: "Claude session: Alfred talks to Claude Code on Kevin's laptops"
charted: 2026-10-29
charted-by: "Kevin + Fable"
effort: "`.scratch/claude-session/`"
tickets: 8
open: 6
status: open
tags: [map]
---

# Claude session: Alfred talks to Claude Code on Kevin's laptops

**Kevin, 2026-10-29:** *"how can i get the ai to do something that claude code could do. like finding
something, booking an appointment etc ... writing small custom code, python scripts etc if i wanted a
pdf parsed."* Specified by interview the same day (*"lets spec this first"*, *"dont build it yet"*).
Replaces the same-day `.scratch/clerk/` chart; the name "clerk" is retired.

## Destination

Kevin, in the car: "use Claude on my Alienware to look for the AW4 wiring PDF in my downloads".
Alfred sends it and says in words whether the Alienware is on. On the Alienware one long-lived main
Claude session, the same shape as Kevin's own chat window here, takes the message, hands the work to
a helper subagent, and stays free for the next message. The answer comes back in Alfred's voice:
"found it, AW4 wiring rev C, in Downloads, two megabytes. Want it on your phone?" Only on a yes does
the file upload. "What's my LEGION session doing?" reads the interactive sessions running on each
machine. The same is reachable by hand on the Claude session screen.

## Rulings (Kevin, 2026-10-29)

1. **The subscription, not the API.** Headless Claude Code on a machine logged into Kevin's
   claude.ai account. No `ANTHROPIC_API_KEY`, no Agent SDK. *"i was hoping more of using my monthly
   sub and not the api"*; *"i like A more."* Whichever account the machine's CLI is logged into is used (Kevin
   2026-11-01: *"whatever is logged in on the laptop, thats ok"*); the Alienware is a personal
   Max plan, limits shared with Kevin's own sessions.
2. **Nothing is sent unless Kevin named it or said yes.** *"anything that goes to the claude session
   should be named or asked right."* Named ("use Claude on my Alienware to..."): sent. Not named:
   Alfred offers only after his own tools cannot do it, with a one-line version of the task (*"one
   line version of the task"*), and a yes counts only as the very next thing Kevin says (*"only the
   next thing"*). A yes covers that one task.
3. **It is called "Claude session"** in speech, on screen and in code.
4. **Machines have spoken names: "Alienware" and "work laptop."** Named machine wins. Unnamed: the
   only machine online; more than one online, Alfred asks which.
5. **Scope.** The Alienware has full access, *"no, i want full access to this pc"*: every fixed
   drive, read and write, as Kevin's Windows user, not elevated. The work laptop has its repos
   folder only (*"work laptop only repos for now"*) and returns summaries, never files.
6. **The ask list (Kevin agreed the default):** deleting or overwriting Kevin's files, sending,
   booking or paying, installing software, and changing system settings stop and come back as one
   question. Everything else runs.
7. **Availability: locked is on, powered off is off.** *"if the laptop is on but the lockscreen is
   up, i should still be able to use it. only if the power is off then i cant."* The worker runs in
   Kevin's logon session through a lock, and keeps the machine from sleeping while on mains power.
   Powered off, Alfred says "off since <time>" and queues.
8. **Results.** Alfred speaks up when a task finishes if a conversation is open, otherwise a
   notification (agreed default). Files reach the phone only when Kevin says so; Alfred offers
   (*"only when i say so, alfred can say do u want it on your phone"*). Results and files stay on
   the engine 30 days.
9. **Limits.** A task is stopped after 30 min. Subscription use is not warned about (*"just run
   it"*). **Several tasks run at once:** *"same like here, one session orchestrates using helper
   subagents. main window is the chat window."*
10. **Continuity.** A follow-up ("now email it to me") reaches the same main session, which
    remembers the task (yes to 24 h continuity; the main session is long-lived, so it holds).
11. **Reading interactive sessions is in v1** (*"yes check on current sessions running"*): which
    Claude Code sessions are open on which machine, on which project, doing what.
12. **A hands path is needed** (ADR 0035): the Claude session screen. **Phone first;** the head
    unit later.
13. **What reaches the engine:** summaries, questions, task files on request, and session status.
    The main session's transcript stays on the laptop.

14. **Alfred has his own email account** (*"alfreds own account sounds good"*, 2026-10-29, after
    the use case "make Dad an Excel sheet of exchange rates and email it to him"). A Gmail created
    for Alfred sends task mail, with Kevin cc'd on every send; Kevin's own Gmail is never used to
    send from a laptop. Sending stays on the ask list (ruling 6): every send is a yes. Its app
    password lives only in the worker config on the Alienware. Replies into that inbox are mail
    other people wrote to Kevin: read-through only (CLAUDE.md section 7), never stored.
15. **Recurring tasks: later, not v1.** *"we'll figure these routine tasks later on, itbe cool if
    i can talk to alfred to set routine tasks too."* When they come, they are set up by voice
    ("every Monday, send Dad the rates sheet") as well as by hand, and what still needs a yes per
    run is decided then.

16. **WhatsApp dropped, Gmail instead** (Kevin 2026-11-01: *"whatsapp is abit hard then, gmail is
    much easier yeah, lets keep gmail then"*). Use case kept: *"ask alfred to open a claude
    session, use it to find something on the internet, summarize it in a file format i want, and
    then get that claude session to send it to my whatsapp number"*, delivered by mail from
    Alfred's Gmail (ruling 14) instead. WhatsApp Cloud API was chosen then dropped the same day for
    its setup (Meta business account, spare number, 24 h window, templates).
17. **Naming your own email in the request is the yes** for that one task ("...and email it to
    me"). Only Kevin's own address; mail to anyone else stays on the ask list (ruling 6). The phone
    sets `deliver: "email_self"` on the task when Kevin names it. The worker's `send_mail.py`
    sends it to Kevin (no cc needed), file attached.
18. **The work laptop may email Kevin a summary, never a file** (Kevin 2026-11-01). A `listed`
    machine takes `deliver: "email_self"`; the mail carries the summary text only, no attachment.
    The engine still refuses every file path for it (send-file, upload, found rows); the worker
    sends no attachment from it (ticket 04).

## Shape on each laptop

```
Task Scheduler "LEGION Claude session" (at logon, runs through lock)
  worker.py   (env: CLAUDECODE and CLAUDE_CODE_* stripped)
    heartbeat to engine every 20 s, claims messages, SetThreadExecutionState while on AC
    one main session:
      claude -p --input-format stream-json --output-format stream-json --verbose
        --session-id <uuid> (first start) | --resume <uuid> (restart); rotated daily
        --permission-mode dontAsk --permission-prompts none   (tested; work laptop + --add-dir repos)
        Alienware full access (ruling 5): bypassPermissions + hook, UNTESTED (see ticket 02)
        --allowedTools "Bash(python *)" "PowerShell(python *)" Agent Read Glob Grep ...
        --settings <worker settings: PreToolUse ask-list hook> --setting-sources <explicit>
        --append-system-prompt <main window rules>
      stdin  {"type":"user","message":{"role":"user","content":"[task 41] ..."}}
      main turn starts a background helper (Agent, run_in_background), replies one line, ends
      stdout system/task_started, task_progress (live step), task_notification (done),
             then an unprompted main turn + result per finished helper
      helpers report through:  python report.py --task 41 --status done --summary "..."
                               [--question "..."] [--file PATH]
    worker forwards report.py results to the engine
    30 min cap: worker-side (no CLI turn cap flag)
  ask list (ruling 6): PreToolUse hook denies with a reason; helper returns needs_confirmation
  hooks in ~/.claude/settings.json post interactive session status (ticket 06)
```

Ticket 02 (2026-11-01) confirmed this shape on the Alienware: background helpers run concurrently
in one headless stream-json session and their completions arrive unprompted. The per-task process
fallback is not needed.

## The API

All under `/api/claude/`, `Authorization: Token <device key>` (`DeviceTokenAuthentication`), scoped
with `household.tenancy.scoped`. Tables `claude_tasks`, `claude_task_files`, `claude_machines`,
`claude_code_sessions`.

| Method and path | Who | Body and result |
|---|---|---|
| `POST tasks/` | phone, web | `{request, machine: name or null, parent: id or null, origin: "named" or "offered"}` returns `{id, status, machine: {name, state, last_seen_at} or null}`; `status` is `queued`, or `needs_machine` when unnamed and more than one machine is online |
| `GET tasks/?limit=&status=&machine=` | phone, web | newest first: `{id, request, machine, status, summary, question, created_at, finished_at, files: [{id, name, size, uploaded}]}` |
| `GET tasks/{id}/` | all | same shape |
| `POST tasks/{id}/cancel/` | phone, web | queued becomes `cancelled`; running becomes `cancel_requested` |
| `POST tasks/{id}/assign/` | phone, web | `{machine}`: Kevin answered "which machine?" for a `needs_machine` task; it becomes `queued` on that machine and returns the create shape; 409 if the task is not `needs_machine`, 404 for an unknown task or machine (Kevin 2026-11-01) |
| `POST tasks/{id}/send-file/` | phone, web | `{file_id}`: Kevin said yes to "want it on your phone?"; marks the file requested; the worker uploads it on its next heartbeat |
| `GET status/` | phone | `{machines: [{name, scope, state: "idle", "busy" or "offline", last_seen_at, running: [task ids]}], sessions: [{machine, project, state, last_prompt_summary, last_seen_at}]}`; offline is no heartbeat for 90 s |
| `POST machines/heartbeat/` | worker | `{name, hostname, scope, state, running, claude_version}` every 20 s; returns `{cancel_requested: [ids], files_requested: [{task, file_id, path}]}` |
| `POST tasks/claim/` | worker | `{name}` returns every queued task for that machine (or unaddressed, if it is the only one online); else 204 |
| `POST tasks/{id}/finish/` | worker | `status` (`done`, `failed`, `needs_confirmation`), `summary`, `question`, `turns`, `duration_ms`, `found: [{path, name, size}]` (listed, not uploaded) |
| `POST tasks/{id}/files/` | worker | multipart upload of one requested file |
| `GET tasks/{id}/files/{file_id}/` | all | the bytes |
| `POST sessions/` | hooks | `{machine, session_id, project, state, last_prompt_summary, at}`; the work laptop omits `last_prompt_summary` |

States: `queued`, `running`, then `done`, `failed`, `needs_confirmation` or `cancelled`. Running past
30 min, or with the machine silent for 10 min, becomes `failed` with the reason in words. Computed
at read time.

**As built (ticket 03, 2026-11-01), where the table was silent.** Task ids are integers. Task rows
also carry `parent`, `origin`, `deliver` (null or `email_self`), and each file `requested`; in rows
`machine` is the name string, on create it is the object. `finish` also accepts `status:
"cancelled"`, only for a task in `cancel_requested` (the worker's exit after TaskStop). A computed
failure puts its reason in `summary`; `finish` on it is refused 409. `needs_machine` is resolved by
`assign/`. A follow-up with no machine named goes to its parent's machine.
Claim needs a prior heartbeat (404 otherwise). `GET status/` shows sessions seen in the last 24 h
and not `ended`. A `listed` machine: `found` not kept, send-file and upload 403, `deliver:
email_self` allowed for the summary only (ruling 18), `last_prompt_summary` dropped. A sessions post for an unknown machine creates it as
`listed` until a heartbeat says otherwise. Status extras: heartbeat `progress: {"41": "reading
Downloads"}` (120 chars) lands on that machine's in-flight tasks as `progress`/`progress_at`, shown
in task rows and as `machines[].progress: [{task, progress, progress_at}]` in `GET status/`,
cleared on finish; `waiting` sessions sort first. Every caller is an ordinary household device
token in v1 (Kevin 2026-11-01); later, worker endpoints restricted to machine tokens.

## Tickets (all build tickets KIV until Kevin says build)

- 01 decision (resolved 2026-10-29): rulings 1 to 13
- 02 spike (in progress 2026-11-01): shape confirmed; lock-screen run (Q7) pending
- 03 build (built 2026-11-01, `feat/claude-session-engine`, not deployed): engine `claude_session` app, ADR 0067
- 04 build (KIV): the worker `tools/claude_session/`
- 05 build (KIV): phone tools, the offer rule, results, the Claude session screen
- 06 build (KIV): interactive sessions reporter hooks
- 08 (dropped 2026-11-01): WhatsApp delivery; replaced by `deliver: email_self` in tickets 03/04
- 07 test (KIV): on the Alienware: the Downloads find, the offer, locked screen, powered off, an ask-list stop, two tasks at once, a session check
