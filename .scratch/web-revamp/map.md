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
- [Pick the web design language](issues/01-pick-the-web-design-language.md): C, soft Material light. ADR 0053.
- Private rows: ADR 0052 written; CLAUDE.md section 1 amended.
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

## Status - 2026-10-03 evening

**All 17 build tickets built and on `dev` (`db73b6e`).** 491 vitest / 0 failed; server CI on dev
green (1391 passed, 44 skipped), its first green run since 2026-09-29. Ticket 18 is a handoff list
for the Android side and stays open.

**Owed by Kevin, in order, none of it done by an agent:**
1. Deploy (manual `workflow_dispatch`); seven new migrations apply on live (ingest 0009-0015,
   checklists 0003-0004, push 0001).
2. `manage.py make_private` twice: `--origin-prefix canvas:` and `--structured-meta-key course`.
3. Push: VAPID keys, and `deploy/cloudrun/_common.py` must pass `VAPID_*` to the service and job
   (it does not yet; push stays off in words until it does), then install the new scheduler line.
4. Invite Mia from `/settings/household`; she installs the PWA on her iPhone and subscribes.
5. Check her spend card against the phone's Money figure to the cent (known parity risk: the phone
   keys accounts on nickname, the server on last4).

**Open questions for Kevin** (defaults built, each a small change): rail 232 px vs C's 96 px;
Canvas tasks tickable but not editable; tasks in the week view's all-day lane; weeks start Sunday;
adding a vehicle (needs the origin_guid ruling); place rename and radius; voice-note delete on web;
grocery plural rule on the server; weekly spend bars (needs a server field); a 4th pin refused;
owners minting `creates_household` invites; codeless signup on the web; account routes also accept
device tokens; `preferences/off` without CSRF (can only turn a kind off).

## Flagged for Kevin

- Spend endpoint skips web-and-households 11's RLS blocker: **approved** by Kevin 2026-10-03.
- Server work rides in this map: **approved** by Kevin 2026-10-03.
- **Live data never read.** Class-schedule rows are inferred from phone code; which `account_last4`
  values exist live is unknown. The BofA pull HAS run against the real site (Kevin, 2026-10-03).

## Not yet specified

- Workbench Home's exact panel layout beyond D-list contents: drawn by the chosen prototype.
- Whether parents join this household or get their own (ADR 0045 allows either); no screen depends
  on it.

## Out of scope

See spec "Out of Scope".
