---
map: backend-etl
ticket: "05"
title: "webassign_read, daily, completion only"
type: build
status: open
blockers: ["04"]
blocked-by: ["[[04-canvas-poll]]"]
tags: [ticket]
---

# webassign_read

Carries `.scratch/two-clients/issues/05-webassign-completion-read.md` whole. Its open auth question
is ruled (ruling 2: a session stored by the login script). Completion only, never dates. Matching
per that ticket. Session expiry is the expected failure: `needs_login`, said in words by freshness.
Scheduled only after `canvas_poll` has run clean for a week, per django-engine 06.

crontab: `0 6 * * * manage.py webassign_read`.
