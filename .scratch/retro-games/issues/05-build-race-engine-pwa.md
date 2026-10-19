---
map: retro-games
ticket: "05"
title: "Build: engine match API + game_invite push, PWA Tetris solo and race"
type: build
status: open
blockers: ["04"]
blocked-by: ["[[04-tetris-rules-and-race]]"]
open-blockers: 0
ready: true
tags: [ticket]
---

# Build: engine match API, invite push, PWA Tetris

## Engine (`server/`, new app `games`)

Table `game_matches` (household FK, in `TENANT_TABLES`, tenancy leak test): inviter, invitee
(users of the same household), seed (uint32), status `invited|playing|ended|declined|cancelled|expired`,
`expires_at` (invite + 120 s), `start_at`, `ends_at`, per-player progress (score, lines, level,
topped_out, finished, updated_at). Ends when both finished or `ends_at + 15 s` passed.

| Call | Does |
|---|---|
| `POST /api/games/matches/` `{opponent_user_id}` | Create invite, random seed, send `game_invite` Web Push to the invitee's subscriptions NOW (direct `sender.send`, honouring their preference); the notification opens `/games/match/<id>` |
| `GET /api/games/matches/pending/` | Live invites addressed to me (the phone polls this) |
| `GET /api/games/opponents/` | Other members of my household |
| `POST .../<id>/accept/` | `playing`, `start_at = now + 5 s`, `ends_at = start_at + 180 s` |
| `POST .../<id>/decline/`, `.../cancel/` | Invitee declines, inviter cancels |
| `POST .../<id>/progress/` | Upsert my score/lines/level/topped_out/finished; rejected after `ends_at + 15 s` |
| `GET .../<id>/` | Status, seed, clock, both progresses, result (`won/lost/draw` for the caller) once ended, `server_now` for clock skew |

Purge: a match row is deleted 10 min after it ends or expires (lazily on games requests, plus a
sweep in the `push_dispatch` cron). Push kind `game_invite` added to `KINDS`, preferences and the
settings notifications screen. Regenerate `openapi.yaml`.

## PWA (`server/frontend/`)

- Games route: Tetris solo; "Play <member>" lists opponents and invites; the waiting screen polls
  the match (1 s) and says in words if declined, cancelled or expired.
- `/games/match/<id>`: Accept / Decline, countdown to `start_at` (corrected by `server_now`), race
  with own board plus the opponent's live score (progress POST + GET each 1 s), result screen.
- Touch controls (tap rotate, swipe move, swipe down soft drop, flick down hard drop, hold button)
  and keyboard.
- Rules from ticket 04 in `src/games/tetris/` as pure TS; vitest reads `shared/tetris/fixtures.json`.
  This ticket writes the fixtures (hand-checked score cases, RNG vectors) since it lands first.

## Verification

pytest (one server test DB at a time), vitest, tsc, `docs_check`. Owed: a real race with the PWA on
Mia's phone against the phone app, and the push arriving on her phone.
