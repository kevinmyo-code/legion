---
map: claude-session
ticket: "05"
title: "Build: phone tools, the offer rule, results, the Claude session screen"
type: build
status: kiv
status-detail: "Parked with the map (Kevin 2026-10-29: dont build it yet). Phone first; head unit later (ruling 12)."
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
