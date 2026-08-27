-- LEGION backend-erp, Phase 4: the LEGION::v1 description block, rescued into a real column.
-- Ticket: .scratch/backend-erp/issues/01-what-the-backend-owns.md ruling 11 / issues/05-migration-path.md ruling 3.
-- Depends on: 20260825000400_aspect_dates_notes_merged.sql (events).
--
-- The Dates Event import (CalendarImportController.kt, phone-side change landing in the same
-- work as this migration) now parses a Google event's `LEGION::v1` description block - class
-- metadata (course, source, conflict, status - observed live per ruling 11) that is AUTHORED IN
-- Google Calendar and lives nowhere else - into its own engine field (DatesAspectSeeder.
-- FIELD_STRUCTURED_META), rather than leaving it buried inside a raw description string. Ruling 7
-- retires the generic engine once every aspect's data has a typed home on this server; without
-- this column the metadata would still be lost the day the engine goes, just later than the day
-- Google goes. This column is what makes it actually outlive both.
--
-- jsonb, not text: the block's keys are open-ended and per-event (a fixed column per key is not
-- expressible without a capability-plugin field-registration API this codebase does not have
-- yet - DatesAspectSeeder.FIELD_STRUCTURED_META's own doc comment), and jsonb lets Postgres index
-- and query into it if a future feature needs to (e.g. "every event where course = 'COSC4320'"),
-- which a text blob could not do without the caller remembering to parse it first every time -
-- exactly the "client remembers to do it" shape ruling 6 already rejected for a different column.
-- Nullable: most events (legion-authored, or a Google event with no block at all) carry no
-- structured metadata.
--
-- UNAPPLIED as of this commit - this agent has no Supabase CLI access and no project credentials
-- from this environment, same posture 54cdf5e documented for the fleet schema migration. Kevin
-- (or a future session with credentials) must run this against the live project before
-- EventsReconcile's Dates branch can actually land a value in it.
alter table public.events
    add column if not exists structured_meta jsonb;

comment on column public.events.structured_meta is
    'The parsed key/value map from a Google event''s LEGION::v1 description block (course, '
    'source, conflict, status, etc - open-ended per event). Null when the event carries no such '
    'block. See DatesAspectSeeder.FIELD_STRUCTURED_META for the phone-side field this mirrors.';
