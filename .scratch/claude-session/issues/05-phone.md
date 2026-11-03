---
map: claude-session
ticket: "05"
title: "Build: phone tools, the offer rule, results, the Claude session screen"
type: build
status: built
status-detail: "Built 2026-11-01 on feat/claude-session-phone: one claude_session voice tool, the offer clause, result announcements, the Claude session screen; JVM tests green. Owes: install on the A25 and a run against the deployed engine and a live worker (ticket 07)."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Build: phone

`app/` only. One controller `claude/ClaudeSessionController.kt` behind both the voice tools and the
screen (ADR 0035).

## Tools

| Tool | Kind | What |
|---|---|---|
| `send_to_claude(request, machine?, origin)` | write, BLOCKING | Queues. Result, in words: sent to <machine>, online; <machine> off since <time>, queued; which machine (more than one online); no machine ever connected. Never "done". `origin` is `named` or `offered`. |
| `check_claude(machine?)` | read | Machines and their state, running and recent tasks with summaries, and the interactive sessions (ruling 11). |
| `answer_claude(task_id, answer)` | write | A follow-up to a `needs_confirmation` task, or "now do X with it". |
| `send_claude_file(task_id, file_id)` | write | Only after Kevin said yes to "want it on your phone?". |

## Prompt (file scope in `ai/AriaBrain.kt`, presence tests in `AriaBrainHonestyClauseTest`)

- `send_to_claude` only when the user named Claude or a Claude session this turn, or said yes in
  the very next utterance after Alfred's offer (rulings 2 and 3). Otherwise, after Alfred's own
  tools cannot do it, offer once: "That needs your Claude session. Want me to send it: <one-line
  version>?"
- A sent task is queued, not done; its outcome comes only from `check_claude` or the result
  arrival.
- A finished task with found files: say the name and size and ask "want it on your phone?"; never
  send a file unasked (ruling 8).
- No assistant name in any of it.

## Results

- In an open conversation: the result is fed in as a system note and spoken (the WHEN_IDLE path,
  ADR 0065). Otherwise a notification through the existing proactive raise registry, meeting the
  four compulsion conditions: anchored to the task Kevin sent, actionable, never about his absence,
  silenceable ("stop telling me about Claude tasks").
- Trigger: push if `server/push/` reaches the phone; otherwise the existing periodic poll. No new
  alarm.

## Screen

`ui/claudesession/ClaudeSessionScreen.kt`, soft Material (ADR 0051): machines with state and
"off since"; a text field, machine picker, Send; running and recent tasks, tap for summary,
question (answer box) and found files (a "Send to phone" button per file); the interactive
sessions list. Reached from wherever aspect screens are listed. Compose tests for each machine
state, a question, a found file.

## Voice guide

Copy for each tool in `tools/voice_guide_copy.py`; `python tools/voice_guide.py` must pass.

## Addition (Kevin 2026-11-01: "yes to status extras")

- Sessions list shows state `waiting` as "waiting on you", highest in the list.
- Running tasks show the helper's live step from `GET status/` / task `progress` ("searching
  Downloads"); `check_claude` reads it out when asked what a task is doing.

## Built (2026-11-01, `feat/claude-session-phone`)

- `claude/`: `ClaudeSessionController` (one controller for the tool, the screen and the result
  watcher), `ClaudeWording` (every sentence), `ClaudeSessionBackend` with
  `backend/engine/DjangoClaudeSessionBackend.kt` over `/api/claude/`, `ClaudeResultWatcher` +
  `ClaudeResultPolling`, `ClaudeWatchStore`.
- **Departure: one tool, not four.** `service/ClaudeSessionToolbox.kt` declares ONE `claude_session`
  tool with `action` = `send`, `check`, `answer`, `assign`, `send_file`, `quiet`, `unquiet`, the trade
  `bought_log` and `books` made for `LiveSetupPayloadSizeTest`'s ceiling. `assign` answers "which
  machine?" (Kevin 2026-11-01); `quiet` is "stop telling me about Claude tasks", `unquiet` "tell me
  about Claude tasks again".
- Machine choice: named wins; one online is chosen; more than one online is `needs_machine` then
  `assign/`; none online queues for the only machine there is, or asks which when there are several
  (nothing is created until he answers). No machine ever connected sends nothing.
- `deliver: email_self` from the tool's `email_me` (his own email named) and the screen's "Email it
  to me". A yes to a `needs_confirmation` task is a follow-up with `parent` + `confirms: true`; any
  other follow-up has no `confirms`. Work laptop: no Send to phone, no file offered.
- Prompt: `CLAUDE_SESSION_CLAUSE` at file scope in `ai/AriaBrain.kt`, pinned in
  `AriaBrainHonestyClauseTest`.
- Results: polled (`server/push/` is browser-only) on the Tetris invites' two loops, only while a
  task this phone sent is out. An open conversation gets a system note at the next quiet moment
  (`LiveNotes`, `GeminiLiveSession.offerNote`); otherwise `ProactiveBus.speakIfAllowed`, one rule id
  per task. A requested file is downloaded to Downloads once uploaded (Kevin 2026-11-01: keep).
- **Kevin's answers, 2026-11-01:** results have their own proactive category, `CLAUDE_TASKS`
  ("Claude tasks"), on by default (`ProactiveCategory.DEFAULT_ON`, also for an install that predates
  it) and outside the 3-a-day spoken cap (`ProactiveBus.UNCAPPED_CATEGORIES`); still inside the
  master switch and quiet hours. The screen switch and `quiet`/`unquiet` flip that category. A spoken
  result that asks something (a file offer or a question) opens the mic: `ProactiveRaise.listenHoldMs`
  (20 s) keeps the follow-up window re-arming (`FollowUpWindow` `listen_hold`), so it closes at the
  first expiry after the hold, about 24 s after the mic opens; the user speaking ends the hold. The
  ids a yes needs ride in a model-only hint, never the notification.
- Screen: `ui/claudesession/`, from HOME's More sheet; machines, send box (machine picker, Email it
  to me), tasks (summary, question with Yes and an answer box, files with Send to phone, live step),
  sessions with "waiting on you" first, the announcements switch. Compose tests in
  `ClaudeSessionContentTest`.
- Untested on the device: everything above. Owed to ticket 07.
