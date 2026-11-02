---
map: recruiter-demo
ticket: "01"
title: "Recruiter role and tool allowlist (default deny)"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Recruiter role and tool allowlist (default deny)

## Role (decided, 2026-11-02)

`HouseholdMember.role` gains `recruiter` (widen `max_length` 8 -> 16, AlterField migration). Role follows
the USER, so a web session cookie and a token for that user are both recruiter: no way to escape it by
changing how you sign in. Only throwaway visitor users in the demo household carry it. ADR 0068 records
this as an amendment to ADR 0045: the first role that touches data, and only to narrow it.

## What

A recruiter caller is identified by its token (role `recruiter`), never by anything the browser says.
Its tool surface is an explicit ALLOWLIST, default deny, applied at BOTH places the web surface
already uses: the locked Live token's declarations (`assistant/session.py`) and the tool door
(`assistant/surface.refusal_for`, checked in `AssistantToolView.post` before `run_tool`).

- New: `surface.RECRUITER_TOOLS` (named list), `surface.tools_for(user)`, `refusal_for(name, user)`.
- Allowed: read tools over demo-household rows only (ledger, fleet, pantry, notes, dates, places) and
  `about_kevin` (ticket 10).
- Never: anything reaching an outside account or machine (mail, calendar, Drive, Spotify, Plaid,
  device logs, `claude_session`, code sessions), and every write tool.
- `DeviceToken.scope = read` as the second fence: a write tool refuses even if the allowlist is wrong.
- A tool added to the registry later does NOT reach recruiters until someone adds it to the list.

## Verify

- Test: every registry tool not in `RECRUITER_TOOLS` refuses for a recruiter token, with words saying
  nothing ran. The test enumerates `TOOLS`, so a new tool fails it until classified.
- Test: the recruiter Live setup's declarations equal `RECRUITER_TOOLS` exactly.
