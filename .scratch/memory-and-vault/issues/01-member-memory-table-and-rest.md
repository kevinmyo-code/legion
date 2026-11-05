---
map: memory-and-vault
ticket: "01"
title: "Member memory: table and REST"
type: build
status: built
status-detail: "engine built 2026-11-05 on feat/memory-vault-engine, suite green; owed: engine deploy (Kevin), then the phone tickets"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Member memory: table and REST

## What

New Django app `server/member_memory/`, table `public.member_memories`, born tenanted.

| Column | Meaning |
|---|---|
| `member` | The member the memory is about. Deleting the member deletes their memories. |
| `owner_user` | ADR 0052's column: equal to `member` while private, null when shared. A check constraint holds `owner_user is null or owner_user = member`; the owner guard trigger holds membership. |
| `text` | At most 500 characters. Never a vault identifier (map decision E). |
| `kind` | `fact` / `preference` / `event` |
| `importance` | 1-10 |
| `decay_class` | `durable` (0.999/h) / `episodic` (0.99/h) |
| `source` | `phone` / `web` / `claude` / `manual` / `consolidation` |
| `supersedes`, `superseded_at` | Decision C |
| `last_recalled_at`, `recall_count` | Rehearsal |
| `source_log` | The conversation record it was distilled from, when there is one |
| `origin_key` | Idempotency key for the import and consolidation, unique per household |

Registered in `TENANT_TABLES`, `DJANGO_MANAGED_TENANT_TABLES` and `OWNER_PATHS`.

**REST** (`/api/member-memories/`), every path through `visible()`:
- `GET /` list (newest first; `kind`, `q`, `include_superseded`, `limit` up to 500)
- `POST /` create (source `phone` or `manual` from a client; `shared`, `supersedes`)
- `GET|PATCH|DELETE /<id>`: DELETE is a hard delete of the row and the chain it superseded
- `POST /recall` `{query, limit}`: scored, rehearses what it returns

Only the member a memory is about may make it private again; any member may edit or forget a
shared one (ADR 0052's shape). Recruiters are refused every route, and so is the work laptop's
token (map decision G).

## Verification

- pytest: privacy (member B never sees A's private memory, sees A's shared one; 404 on another's
  private), hard delete leaves no row, supersede chain deleted with its head, the score orders a
  durable fact above an equally old episodic one, rehearsal bumps `last_recalled_at`, the
  sensitive filter refuses an SSN shape and passes a plate, tenancy leak test, recruiter refused,
  `listed` machine token refused.
