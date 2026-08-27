-- LEGION backend-erp, Phase 4: the reminder/appointment discriminator on public.events.
-- Ticket: .scratch/backend-erp/issues/11-notes-write-path-rewire.md, "RULED 2026-08-27" #1.
-- Depends on: 20260825000400_aspect_dates_notes_merged.sql (events).
--
-- The Notes+Dates merge put a Dates `Event` and a Notes `Item` in the SAME table with nothing
-- that says which one a row IS. `source = 'legion'` cannot do it - every legion-authored row,
-- todo or appointment, carries that same value. NotesController (notes/AlarmScheduler.kt's own
-- start-up sweep included) must never treat an appointment as a reminder it owns - that is the
-- 2026-08-26 incident (51 already-deleted todos AND every calendar appointment read as overdue
-- reminders) - and the column below is what lets it tell the two apart.
--
-- Why a stored column rather than deriving it from shape ("has a sortOrder, therefore a todo"):
-- the phone is what knows which record type a row came from, and that knowledge exists nowhere
-- else once ruling 7 retires the phone-side engine. A derived rule works only until the day the
-- shape it leans on changes for an unrelated reason - the same failure mode CLAUDE.md section 4
-- rule 6 names for a reconciliation check that passes on nothing.
--
-- Default 'reminder' is deliberate, not arbitrary: a row whose origin is unknown is treated as
-- something the app owns, which is the conservative direction. An appointment wrongly treated as
-- a reminder is visible and merely annoying (it shows up somewhere it should not); a reminder
-- wrongly treated as an appointment silently never fires at all.
--
-- UNAPPLIED as of this commit - this agent has no Supabase CLI access and no project credentials
-- from this environment, same posture 54cdf5e/20260827000100 documented. Kevin (or a future
-- session with credentials) must run this against the live project before EventsReconcile's
-- kind-tagging and NotesController's kind-filtered reads mean anything server-side.
alter table public.events
    add column if not exists kind text not null default 'reminder'
        check (kind in ('reminder', 'appointment'));

comment on column public.events.kind is
    'What this row IS: a Notes Item (reminder, owned by NotesController/AlarmScheduler) or a '
    'Dates Event / Google import (appointment). Set once, at upload, from the record type '
    'EventsReconcile read - never derived from shape. See ticket 11''s 2026-08-27 ruling for why '
    'this exists: source=''legion'' cannot distinguish a todo from a legion-authored appointment, '
    'and conflating the two is what caused the 2026-08-26 false-missed-reminders incident.';
