---
map: claude-session
ticket: "03"
title: "Build: engine claude app (tasks, machines, files, sessions, status)"
type: build
status: built
status-detail: "Kevin 2026-11-01: build. Built on feat/claude-session-engine (server/claude_session/, ADR 0067); 38 tests green on a throwaway local Postgres, status extras included. Not deployed (Kevin's call); the prune crontab line owes install_schedule.py on deploy. deliver: email_self carried (rulings 16-17)."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Build: engine `claude` app

Server only. Implements the map's API table exactly; the worker and the phone build against it.

## Files

- New app `server/claude_session/` (label `claude_session`, URL prefix `api/claude/`): models
  `ClaudeTask`, `ClaudeTaskFile` (`found` rows with path, name, size; `requested`, `uploaded`),
  `ClaudeMachine` (`name` unique per household, `hostname`, `scope`), `ClaudeCodeSession`.
  Serializers, views, urls, admin, `migrations/0001_initial.py`.
- `server/legion/urls.py`, `server/legion/settings.py`.
- `server/household/tenancy.py`: the four tables in `TENANT_TABLES`.
- Pattern: `server/devicelogs/` (multipart, `household_of`, `scoped`, no body logging).
- Tests `server/tests/test_claude_session.py`: state machine; claim (named machine, unaddressed
  only when one machine online, `needs_machine` otherwise); 90 s offline; 30 min and 10 min
  failures computed at read time; cancel both ways; send-file round trip through the heartbeat;
  the work laptop's `scope: listed` refuses `send-file` (ruling 5); session rows without
  `last_prompt_summary` from a `listed` machine; tenancy; files and rows older than 30 days pruned
  by a management command `claude_session_prune` (run from the existing scheduled jobs).
- ADR 0067 and the `decisions.md` entry (ticket 01). (0066 went to the feed-block branch.)

## Rules

- `request` up to 4 KB, `summary` and `question` up to 16 KB, files up to 25 MB.
- Nothing logs a request, summary or file name.
- Test database: never the production `DATABASE_URL`; a throwaway local Postgres, or tests written
  and reported unrun.
- Deploy is Kevin's call.

## Addition (Kevin 2026-11-01: "yes to status extras")

- Heartbeat accepts `progress: {task_id: text}`; stored on the running task (`progress`,
  `progress_at`), returned in task shapes and `GET status/`, cleared on finish.
- `POST sessions/` accepts state `waiting` (from the `Notification` hook); `GET status/` sorts
  waiting sessions first.
