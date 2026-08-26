-- LEGION backend-erp, Phase 4: an event may have no date.
-- Ticket: .scratch/backend-erp/issues/07-undated-notes-have-no-server-shape.md (Kevin, 2026-08-26)
-- Depends on: 20260825000400_aspect_dates_notes_merged.sql
--
-- =============================================================================================
-- WHY
-- =============================================================================================
-- The merged table declared `starts_at timestamptz NOT NULL`, on the reasoning that title plus
-- start is "the one required pair". That intent was written against the Dates `Event` shape and
-- never tested against the Notes `Item` shape it was merging with.
--
-- Measured against Kevin's real restored database (592 engine records):
--
--   Notes Item : 56 rows, 3 dated, 53 UNDATED  (95%)
--   Dates Event: 269 rows, all dated
--
-- So the NOT NULL would have left 53 of 56 authored rows unrepresentable. The 269 dated Events are
-- not the reassurance they look like: `Dates.Event` has no live writer except the Google Calendar
-- importer that ticket 01 ruling 5 REMOVES. Strip the import and the aspect is 56 rows, 53 of them
-- homeless.
--
-- The migration's header claim that the merge "drops nothing" was true field-by-field - every
-- column had a home - and false about ROWS. A required pair one side never had is a dropped class
-- of record, not a preserved field.
--
-- =============================================================================================
-- WHAT THIS DOES NOT LICENSE
-- =============================================================================================
-- It does NOT mean an undated item gets an invented date. Ticket 01 ruling 2's inferred-tomorrow
-- is a READ-SIDE rendering rule; storing it would assert something the user never said, which
-- CLAUDE.md section 4 rule 5 forbids. Undated means NULL here, and the agenda decides how to show
-- it at read time.
--
-- `starts_at` is the agenda's sort key, so every ordering query over this table now needs an
-- explicit null policy. `NULLS LAST` is the intended default: a dated thing outranks an undated
-- one on a timeline. That is a query-site decision and is deliberately not baked in here.

alter table public.events alter column starts_at drop not null;

comment on column public.events.starts_at is
    'When the event or dated item starts. NULLABLE since 2026-08-26: a checklist item legitimately '
    'has no date, and 95% of the real Notes items had none. NULL means undated, never unknown-but-'
    'existing, and must never be filled with a guessed date - the inferred-tomorrow default is a '
    'read-side rendering rule, not a stored fact. This is the agenda sort key, so ordering queries '
    'need an explicit null policy; NULLS LAST is the intent.';
