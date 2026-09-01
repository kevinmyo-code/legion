---
map: voice-notes
ticket: "02"
title: "Where a voice note lives, on the phone and on the server"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Where a voice note lives, on the phone and on the server

## What to build

Notes came off the aspect engine on 2026-08-27, so this is a typed Room table plus a typed Supabase
table - not a `RecordType` seeder, and not a new `kind` on `events`. A voice note is not an event: it
has no due date, no recurrence, no alarm request code, and nothing about the `events` id contract
applies to it.

**Room:** `data/local/VoiceNote.kt` plus `VoiceNoteDao`.

| Column | Notes |
|---|---|
| `id` | Same minting convention as the rest of the local tables |
| `serverId` | Nullable, for the backend round trip |
| `startedAt`, `endedAt` | `endedAt` nullable - null means interrupted (ticket 01) |
| `title` | LLM-derived, editable by hand |
| `summary` | LLM-derived. Nullable: a recording exists before it is summarized |
| `transcript` | Verbatim, LLM-derived. Nullable for the same reason |
| `audioPath` | Nullable; null once audio is dropped, never null while `transcript` is null |
| `kind` | `SOLO` / `MEETING`. Kevin's own two cases |
| `provenance` | `LLM_DERIVED`. Never `DETERMINISTIC` - nothing here is |
| `interrupted` | Explicit boolean, not inferred at read time |

Additive migration, verbatim generated SQL, `exportSchema`, schema JSON committed, migration test,
and **bump `CarDatabase.SCHEMA_VERSION` in lockstep with `@Database(version = ...)`** -
`CarDatabaseSchemaVersionTest` fails the build on drift, and that drift has already shipped once.

**Supabase:** a new timestamp-prefixed migration under `supabase/migrations/` following
`20260825000400_aspect_dates_notes_merged.sql`'s shape - `create table if not exists
public.voice_notes`, `provenance` / `created_at` / `updated_at` / `deleted_at`, an index, the
`touch_updated_at` trigger, then `select private.apply_household_rls('public.voice_notes');`.
**Audio is not uploaded.** The server holds text; the file stays on the phone.

**Client:** `backend/SupabaseVoiceNotesBackend.kt` on the shape `SupabaseEventsBackend` already
uses, with the same dual path - `SupabaseClientProvider.get(context)` returning null means
unconfigured, and unconfigured writes Room directly.

## The delete rule

**ADR 0041: audio, transcript and summary are retained or destroyed together.** Deleting a note
deletes its `.m4a`. A summary outliving its transcript is the specific failure this ticket exists to
prevent, so it gets its own test rather than a comment.

## Verification

- Migration test, old DB opens and upgrades.
- `CarDatabaseSchemaVersionTest` green.
- Unit test: delete removes the row, the transcript and the file.
- Say in the resolution whether the Supabase migration was actually APPLIED or only written.
  `20260827000200_events_kind.sql` shipped unapplied and only its own comment records that.
