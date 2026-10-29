---
map: claude-session
ticket: "02"
title: "Spike: a long-lived headless main session with background helpers, through a lock"
type: build
status: kiv
status-detail: "Parked with the map (Kevin 2026-10-29: dont build it yet)."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Spike: the main session

Ruling 9 wants one main session per machine that stays free while helpers work, like Kevin's chat
window here. Nothing in the repo has run Claude Code that way headless. Answer these on the
Alienware with a throwaway script in the scratchpad, before tickets 03 to 05 commit to a shape.

## Questions

1. Does `claude -p --input-format stream-json --output-format stream-json` stay alive across
   several user messages written to stdin, and with what lifetime limits?
2. Inside it, does a background subagent (Agent tool, run in background) keep running after the
   main turn ends, and does its completion arrive as a new event the worker can read? Can two run
   at once?
3. Can a helper run `python report.py --task N --status done --summary "..." [--file PATH]` through
   Bash with the allowed-tools set ruling 5 implies, and does the worker see it immediately?
4. Does `--resume <id>` bring the main session back after the worker restarts, with its tasks in
   memory?
5. Flags that exist on the installed CLI for: appending a system prompt, permission mode, allowed
   tools, extra directories, turn caps. Names verbatim from `claude --help`.
6. The CLI refuses to nest when `CLAUDECODE` is set; confirm the worker clears it.
7. Lock screen: a Task Scheduler task "at logon, run only when user is logged on" keeps running
   through Win+L, and the CLI's subscription login works from it.
8. Keep-awake: `SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)` from the worker
   while on mains power keeps the PC from sleeping when locked. Lid closed on mains is a Windows
   power setting; record which one Kevin must set.
9. How the ask list (ruling 6) is enforced best: a `PreToolUse` hook that denies matching Bash and
   file operations and makes the session return a question, or prompt only. Prefer the hook.

## Outcome

A short findings section in this ticket, and the chosen shape written into the map's "Shape on
each laptop". If question 2 fails, the fallback is one `claude -p` per task with `--resume` for
follow-ups, several processes at once.
