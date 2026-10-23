---
map: books
ticket: "08"
title: "Build: engine tables and API for synced reading position and bookmarks"
type: build
status: open
blockers: ["07"]
blocked-by: ["[[07-read-on-the-laptop-decision]]"]
open-blockers: 0
ready: true
tags: [ticket]
---

# Build: reading sync on the engine

Rulings in ticket 07. This ticket's contract is what 09 (phone) and 10 (web) build against in
parallel; change it only by amending this file.

## Tables (Django models, migration, both in `TENANT_TABLES`, covered by the tenancy leak test)

`book_positions`: `id`, `household` FK, `user` FK, `book_sha256` (64 hex), `variant`
(`original` | `resizable`), `locator` (JSON, nullable), `total_progression` (float 0-1, check
constraint), `chapter_label` (text, nullable), `recorded_at` (timestamptz, the reader's clock),
`device` (short text, e.g. "A25", "web"), `updated_at`. **Unique (household, user, book_sha256,
variant).**

`book_bookmarks`: `id` (UUID chosen by the client, so an offline phone can create one), `household`,
`user`, `book_sha256`, `variant`, `locator` (JSON), `total_progression`, `label` (nullable),
`note` (nullable), `created_at`, `deleted_at` (nullable tombstone), `updated_at`.

No title, filename or book text in either table.

## API (device token or session; household membership; always the caller's own rows)

| Method | Path | Does |
|---|---|---|
| GET | `/api/books/positions?since=<iso>` | The caller's positions updated after `since` (all if absent). |
| PUT | `/api/books/positions/<sha256>/<variant>` | Upsert. **Newest `recorded_at` wins**: an older write returns 200 with the stored row and `"kept": "newer"`, never overwrites. |
| GET | `/api/books/bookmarks?since=<iso>` | The caller's bookmarks updated after `since`, tombstones included. |
| PUT | `/api/books/bookmarks/<uuid>` | Create or update. |
| DELETE | `/api/books/bookmarks/<uuid>` | Sets `deleted_at`; idempotent. |

- Responses carry server `updated_at` so clients use it as their next `since`.
- 400 in words for a bad hash, variant or progression.
- Add to `openapi.yaml`, regenerate `server/frontend/src/api/schema.d.ts`.

## Tests

Upsert and newest-wins (older write kept out), per-user isolation inside one household, tenancy leak
test covers both tables, tombstone round-trip, `since` paging. Server rules: `uv run --no-sync`,
never `--reuse-db`, one pytest run at a time.

## Deploy

Needs Kevin's OK like every production deploy. A migration: check memory
`legion-live-role-no-private-schema` (nothing in `private.*`).
