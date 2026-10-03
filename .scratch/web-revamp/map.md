---
map: web-revamp
title: "Web revamp: one household client, a workbench and a family view"
charted: 2026-10-03
charted-by: "Kevin + Opus"
effort: "`.scratch/web-revamp/`"
tickets: 18
open: 18
status: open
tags: [map]
---
# Web revamp: one household client, a workbench and a family view

**Kevin, 2026-10-03:** *"another agent is working on the android native app. you work on the web
frontend. revamp it. both desktop and mobile pwa. make a detailed plan. interview me if not sure.
lets build a spec first before we start implementing."*

## Destination

The household web app as the spec describes it: Mia lives in the PWA on her iPhone (today, lists,
calendar, spend, push), Kevin reviews and edits every aspect at the desktop, and private rows keep
Kevin's coursework his. Spec: `.scratch/web-revamp/spec.md`. Execution is in scope.

## Notes

- **The spec holds every decision.** Tickets point at its sections (D1 to D14) and add only build
  steps and verification. If a ticket and the spec disagree, the spec wins and the ticket is a bug.
- **Worktree only.** Another agent works on `app/` in the main checkout. This effort runs in
  `.claude/worktrees/web-revamp` (or a fresh worktree per builder) and never stages outside
  `server/`, `docs/`, `.scratch/web-revamp/`, `memory/library/decisions.md`.
- **Server tickets touch the engine** (06, 08, 11, 14, 15, part of 05). Memory
  `legion-django-engine-split` says the server terminal owns `server/`; Kevin assigned this effort
  the web, and the web cannot ship without these. Flagged for Kevin.
- Skills: `frontend` agent for every screen; `prototype` for ticket 01; `verify` is Android-centric,
  so web verification is the spec's Verification list.
- Kevin wants clickable prototypes, not ASCII, for any open visual choice (memory
  `kevin-wants-clickable-prototypes`).

## Build order

```
01 pick look -> 02 tokens -> 03 shell -> 05 settings ----------------------> 15 push
                                    \-> 07 shared/private (needs 06)          ^
06 private rows (server) -----------/                                         |
08 skips + recurrence (server) ---> 09 event sheet (needs 03, 07, 14) -> 13 calendar workbench
14 reminder column (server) -------/                                          |
11 spend (server) -> 10 family Home/Lists/Calendar (needs 03, 07, 09)        |
               \---> 12 money workbench (needs 03, 04) <-- ledger first ------/
04 live refresh (no blockers)
16 pantry + body, 17 fleet + places + notes (need 03)
18 phone follow-ups (handoff, not built here)
```

Server tickets 06, 08, 11, 14 and web ticket 04 have no blockers and can start while Kevin picks the
look.

## Decisions so far

Made in the 2026-10-03 interview, all recorded in the spec:

- Scope: the full web client, phased, one map.
- Look: reopened; three clickable prototypes, Kevin picks (ticket "Pick the web design language").
- Surfaces split by viewport, rendered not hidden (D1). ADR 0045 allows no roles.
- Mia's PWA: today, ticks, add/edit events, groceries as a shared checklist, shared lists and events,
  spend on the two BofA accounts this month (D6).
- Private rows, server-enforced; Canvas and class schedule private, hand-made shared by default,
  shared marked pink (D3, ADR 0052 to write).
- Ledger source is the daily BofA activity pull, not statements; the web never ingests (D9).
- Spend computed once on the engine, parity-tested against the phone (D5).
- Full repeat editing with "just this one" (D4).
- Push: list changes, event reminders, morning tasks; iPhone install walkthrough (D7).
- Dark mode following the system (D2). No offline writes.
- Workbench order: Money, Calendar, Pantry and body, Fleet places and notes.

## Flagged for Kevin

- **Spend endpoint overrides web-and-households 11's RLS blocker** for that one route (spec D5).
- **Server work assigned here** despite the server/Android terminal split (Notes).
- **Live data never read.** Class-schedule rows are inferred from phone code; which `account_last4`
  values exist live is unknown; the BofA pull has never run against the real site (backend-etl 09).

## Not yet specified

- Workbench Home's exact panel layout beyond D-list contents: drawn by the chosen prototype.
- Whether parents join this household or get their own (ADR 0045 allows either); no screen depends
  on it.

## Out of scope

See spec "Out of Scope".
