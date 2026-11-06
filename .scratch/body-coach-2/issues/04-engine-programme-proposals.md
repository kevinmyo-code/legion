---
map: body-coach-2
ticket: "04"
title: "Engine: programme change proposals, accept and dismiss, alternatives read"
type: build
status: built
status-detail: "built 2026-11-06 on feat/body-coach-2: programme_proposals, routes, four engine tools, alternatives read; owed: engine deploy with body_coach 0004"
blockers: ["03"]
blocked-by: ["[[03-engine-catalog-and-templates]]"]
tags: [ticket]
---
# Engine: programme change proposals, accept and dismiss, alternatives read

| Route | Does |
|---|---|
| `POST workout/proposals/` | builds a proposal from structured choices; stores it pending; returns words and the diff |
| `GET workout/proposals/` | the pending proposal, or none |
| `POST workout/proposals/<id>/accept/` | applies the stored "after" to the active programme |
| `POST workout/proposals/<id>/dismiss/` | the programme stays |
| `GET workout/alternatives/<exercise>/` | the slot's choices that pass equipment and joints |

`programme_proposals` (born tenanted, `TENANT_TABLES`, tenancy test): request, before, after, diff,
words, status, decided_at, decided_by; one pending per household by partial unique index.

Engine tools (`ask_engine`, writes): `propose_programme_change`, `accept_programme_change`,
`dismiss_programme_change`. Read: `get_exercise_alternatives`. `today_body` gains
`proposal` (the pending one) and the programme's filters; no field renamed.

## Verification

- 4 sessions a week defaults to upper / lower; 2 back to full body; a named split wins.
- No barbell and a sore shoulder leave neither anywhere; a paused slot is named in the words.
- Accept keeps a surviving exercise's next target (progression carried); a new one calibrates.
- Accept after the programme moved refuses with nothing changed; a second proposal expires the first.
- Recruiter refused; tenancy leak test covers the table.
