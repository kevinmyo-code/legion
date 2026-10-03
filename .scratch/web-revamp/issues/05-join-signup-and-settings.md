---
map: web-revamp
ticket: 05
title: "Join, signup and settings"
type: build
status: built
status-detail: "both halves built; owed: Mia signs up from a real invite on her iPhone"
blockers: ["03"]
blocked-by: ["[[03-the-shell-split-by-viewport]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Join, signup and settings

Charted 2026-10-03 with Kevin. Map: `.scratch/web-revamp/map.md`.

See `.scratch/web-revamp/spec.md` D12.

## Build

- Server: `PATCH /api/auth/me` `{name}` (writes `first_name`) and `POST /api/auth/password`
  `{current_password, new_password}` (Django validators, `login` throttle, keeps the session via
  `update_session_auth_hash`). pytest for both. Regenerate `openapi.yaml` and `schema.d.ts`.
- Routes: `/join/$code`, `/signup`, `/settings` (index list), `/settings/household`,
  `/settings/devices`, `/settings/account`, `/settings/appearance`. `/settings/notifications` is not
  linked until ticket "Push notifications" lands.
- `/join` and `/signup` sit outside `_authed`. Layout per `docs/design/join-invite.md`.
- Owner-only actions render for the owner only; a member sees the sentence instead.

## Verification

- [x] pytest: name change, password change (wrong current = 400 sentence, throttled), session kept.
- [x] vitest: invite preview then signup lands on Home; expired code sentence; create/copy/revoke
      invite; remove-member confirm says their private things go too; revoke device.
      (`-join.test.tsx`, `-settings.test.tsx`, `refusal.test.ts`; web half built 2026-10-03.)
- [x] Shots in `research/shots/05/`: every join and Settings screen, 390x844 and 1440x900, light and
      dark, including the dead-code states, a refused signup, a member's household and each confirm.
- [ ] Owed on live (Kevin): send Mia a real invite link; she signs up on her iPhone.
