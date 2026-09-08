---
map: web-and-households
ticket: "05"
title: "Web screens, phase 1: sign in, sign up, household, Today, Lists, Settings; installed on an iPhone"
type: build
status: open
blockers: ["03", "04"]
blocked-by: ["[[03-accounts-signup-invites]]", "[[04-web-client-stack]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Web screens, phase 1

The screens a parent needs on day one, in build order. Each reads and writes only through the
generated client; a screen that reaches around it is the two-implementations drift ADR 0035 forbids.

| Route | Reads | Writes | Notes |
|---|---|---|---|
| `/login` | - | session login | Email + password. Link: "Have an invite? Create an account" |
| `/join/<code>` and `/signup` | invite validity (a `GET /api/auth/invite/<code>` that says join-or-create and the household name, without spending it) | `POST /api/auth/signup` | If the code creates a household, ask for its name. On success, straight to `/` |
| `/` Today | events today + 7 days, checklists and ticks | tick, skip, add event | The screen she opens most. `UNRECONCILED` never appears here; nothing on Today is a figure |
| `/lists` | `item_lists`, `list_items` | add, check off, remove | Groceries |
| `/settings/household` | `GET /api/households/me` | rename, mint invite (shows the code and the share URL once), revoke, remove member | Owner-only controls hidden for members, and the API still refuses |
| `/settings/devices` | `GET /api/auth/devices` | revoke | Shows the phone's token by its device name |
| `/settings/account` | me | change password (session) | - |

**Shell:** a left rail on wide viewports, a bottom bar on narrow ones (ADR 0040's two viewports of
one client). Household name in the header; the assistant's name never hardcoded (§1).

**Copy register:** plain and warm, not the phone's dry Alfred band - the parents did not pick a
persona. No jargon: "unverified", not "UNRECONCILED", on any surface that reaches phase 2.

## Verification

- [ ] Installed to an iPhone home screen from Safari; opens full-screen; login persists across
      launches (ticket 08 of django-engine, carried over verbatim).
- [ ] A tick on the web appears on the phone at its next poll; a voice-added event appears on the
      web on refresh.
- [ ] Two households in one engine: a parent's session sees no Kevin rows on any of the six
      routes (Playwright, against a local engine seeded by `tests/`' fixtures).
- [ ] Lighthouse PWA installability passes.
- [ ] `app/`: one link on `KeyScreen`'s engine sign-in: "No account? Create one on the web at
      <engine URL>". The only phone change in this map.
