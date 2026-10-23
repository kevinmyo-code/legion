---
map: books
ticket: "08"
title: "Build: engine tables and API for synced reading position and bookmarks"
type: build
status: built
status-detail: "Built 2026-10-23: books/0001_initial (two tables, both tenanted), five routes, openapi + schema.d.ts regenerated. Server suite green locally (1854 passed, 0 failed, 46 skipped, JUnit XML). Owed at deploy, with Kevin's OK: the migration on live (no private.* reference), then one position PUT from the phone and one GET from the web reader against the deployed engine."
blockers: ["07"]
blocked-by: ["[[07-read-on-the-laptop-decision]]"]
open-blockers: 0
ready: false
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

## As built (2026-10-23)

Where the contract above was silent, these are the choices, stated so 09 and 10 can check against
them. Nothing above was changed.

- **Migration `books/0001_initial`**: `public.book_positions`, `public.book_bookmarks`, born
  tenanted (`household` FK), both in `TENANT_TABLES` and `DJANGO_MANAGED_TENANT_TABLES`. CHECKs on
  variant, `0 <= total_progression <= 1` and `book_sha256 ~ '^[0-9a-f]{64}$'`; unique
  `(household, user, book_sha256, variant)`. No `private.*` reference. Views in
  `server/books/reading.py`.
- **GET lists are a bare JSON array, unpaged**, oldest `updated_at` first. No `{"results", "next"}`
  envelope: each row carries `updated_at` and the client takes the largest as its next `since`.
- **`since` is inclusive (`>=`)**, like every other `?since=` route: the row at the cursor comes
  back again. Missing or unreadable means everything. ISO-8601 with an offset is read as that
  instant (`+` sent as `%2B`).
- **Position row**: `book_sha256, variant, locator, total_progression, chapter_label, recorded_at,
  device, updated_at`. No `id`, no `household`, no `user`.
- **Position PUT body**: `total_progression` and `recorded_at` required; `locator` (any JSON or
  null), `chapter_label`, `device` optional. Always 200. A write whose `recorded_at` is OLDER than
  the stored one is not written: the body is the stored row plus `"kept": "newer"`, and
  `updated_at` does not move. Equal or newer is written and `kept` is absent (a retried write
  lands).
- **Hash**: either case accepted, stored and returned lower-case.
- **Book text is never stored**: a locator's `text` member (Readium's before/highlight/after) is
  dropped on the way in. Everything else in the locator is stored as sent. Locators over 16 KiB
  are refused.
- **Bookmark row**: `id, book_sha256, variant, locator, total_progression, label, note,
  created_at, deleted_at, updated_at`. PUT body: `book_sha256, variant, locator (required),
  total_progression, label, note, created_at (optional, create only)`. Always 200. A PUT on a
  deleted bookmark brings it back. Moving a bookmark to another hash or variant is a 400. An id
  that belongs to someone else's bookmark is a 409, nothing written.
- **DELETE**: 200 with the tombstoned row; again, the same 200 and the same `deleted_at`. An id
  the caller has no bookmark under is **404** `{"detail": ...}`; a reader that made and deleted a
  bookmark offline treats that as done. A non-UUID id is 400.
- **Every refusal is `{"detail": "<sentence>"}`**, starting "Nothing was saved." or "Nothing was
  deleted.". Not `message`.

## Verification

| Step | State |
|---|---|
| Upsert and newest-wins (older write kept out) | done, `tests/test_books_sync.py` |
| Per-user isolation inside one household | done, same file |
| Tenancy leak test covers both tables | done, `test_book_positions_and_bookmarks_are_scoped_by_household`; the column-set checks pass with both tables listed |
| Tombstone round-trip | done |
| `since` paging | done, including `%2B` offsets |
| `openapi.yaml` + `schema.d.ts` | done, staleness test green, `openapi-typescript@7.13.0` |
| Deploy | owed, needs Kevin's OK |

## PROPOSED amendment from ticket 09 (phone), 2026-10-23, settled 2026-10-23 by the build

- `locator` for a PDF or CBZ position or bookmark is `{"page": <0-based source page>}`; for an EPUB it is the
  Readium Locator JSON object as is. Null is allowed and means "use `total_progression`".
- Both GETs should answer `{"results": [...], "next": <iso or null>}` (the phone also reads a bare array).
- Phone sends `device: "phone"` and `note: null` on bookmark create.

- **Settled by the build (orchestrator, 2026-10-23):** locator shapes accepted as proposed (the server stores
  any JSON, stripping only a top-level `text`). Both GETs answer a **bare array**, not the envelope; the phone
  reads both and the web reads a bare array. Device strings `phone` and `web` accepted.
