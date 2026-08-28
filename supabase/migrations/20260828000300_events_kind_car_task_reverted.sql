-- LEGION backend-erp: revert the car_task fold. Kevin's ruling, 2026-08-28: "drop the car-task
-- wave." Read in full: .scratch/backend-erp/issues/10-fleet-cutover.md's tail, and the reversal
-- entry appended the same day to .scratch/backend-erp/issues/06-fleet-has-no-server-home.md.
--
-- =============================================================================================
-- THE WHOLE ARC, STATED HONESTLY - this migration erases neither its own history nor 20260828000100's
-- =============================================================================================
-- 1. 20260827000200_events_kind.sql created `public.events.kind` with a two-value check
--    (`reminder`, `appointment`).
-- 2. Ticket 06 ruled 2026-08-26 that `car_tasks` folds into `events` at a THIRD kind, `car_task`,
--    rather than getting a server table of its own.
-- 3. 20260828000100_events_kind_car_task.sql WIDENED that check to allow `car_task` and WAS
--    APPLIED to the live project. `FleetReconcile` gained a car-task upload wave (ticket 10) and
--    it ran for real: 13 rows landed in `public.events` at `kind = 'car_task'`.
-- 4. That real run surfaced a guid collision: `car_tasks.syncId` (2026-08-11 era,
--    MIGRATION_9_10) had already been copied verbatim into `list_items.syncId`, which
--    `EngineDataMigrationWave1` then reused directly as the engine record's `guid`, which
--    `EventsReconcile` had ALREADY uploaded to this same `public.events` table as
--    `kind = 'reminder'` - years before the car-task wave existed. `car_tasks` has had no
--    production writer since that earlier fold; every surviving row necessarily has a Notes
--    sibling under the same guid.
-- 5. A same-day follow-up (20260828000200_events_origin_guid_per_kind.sql, never applied - the
--    file is deleted in this same commit) tried to fix the collision by widening the identity
--    guard from `(origin_guid)` to `(origin_guid, kind)`, so the SAME task could legitimately
--    exist twice - once as a Notes reminder, once as a fleet car_task.
-- 6. Kevin's ruling: that is fixing the wrong half. The fold was never needed - Notes already
--    owned every one of these tasks by the time the car-task wave was conceived. Widening the
--    guard would have made the duplication permanent and sanctioned; dropping the wave removes
--    it. `car_tasks` (the phone-local Room table, and every row Kevin has in it) is UNTOUCHED -
--    this migration removes an export path only, never phone data.
--
-- This migration does the drop side of that ruling, server-side: delete the 13 (or however many
-- remain) now-unmaintained `kind = 'car_task'` rows - nothing will ever update or retract them
-- again, since FleetReconcile's car-task wave no longer exists in the client - and narrow
-- `events_kind_check` back to its original two values.
--
-- DELETE BEFORE NARROW, not the other way around: narrowing the constraint first would reject
-- immediately with the 13 existing `car_task` rows still in the table, since a CHECK constraint
-- is validated against every existing row at the moment it is added.
--
-- 20260828000100 is NOT deleted or edited. It happened, it was real, and it was applied. A
-- migration history that erases its own mistakes is worse than one that admits them (this file's
-- own project convention, stated first in that migration's own header).
--
-- APPLIED AND VERIFIED 2026-08-28 against HomeERPBackend (ref gccxiqusqxkjmjmaadpz), through the
-- dashboard SQL editor, at Kevin's explicit instruction ("do it in chrome"). The editor's
-- "Potential issue detected - this query includes destructive operations" dialog was confirmed
-- deliberately: the DELETE is the point of this file.
--
-- Verified from the catalog, never from the success panel (lesson L37):
--
--   check constraints on public.events     11   (unchanged - none lost)
--   ...of which still mention car_task      0   (the narrowing landed)
--   rows with kind = 'car_task'             0   (13 deleted)
--   public.events total                   354   (Notes/Dates rows untouched)
--   public.vehicles / public.drives      3 / 17  (the fleet projection is intact)
--
-- **The 11 is the check that matters**, for the same reason it did in 20260828000100: four of the
-- other ten check constraints on this table mention "kind" in their text, and a name- or text-based
-- match would have dropped them and put none back. The conkey match below is what makes that
-- impossible; the count is how it was proven rather than assumed.
--
-- Migration history is still bypassed by the dashboard path, so a first CLI use needs
-- `supabase migration repair`, not a re-run. Same caveat as every migration since phase 2.

delete from public.events where kind = 'car_task';

do $$
declare
    c record;
begin
    for c in
        select con.conname
        from pg_constraint con
        join pg_class rel on rel.oid = con.conrelid
        join pg_namespace nsp on nsp.oid = rel.relnamespace
        join pg_attribute att on att.attrelid = rel.oid and att.attname = 'kind'
        where nsp.nspname = 'public'
          and rel.relname = 'events'
          and con.contype = 'c'
          and con.conkey = array[att.attnum]
    loop
        execute format('alter table public.events drop constraint %I', c.conname);
    end loop;
end
$$;

alter table public.events add constraint events_kind_check
    check (kind in ('reminder', 'appointment'));

comment on column public.events.kind is
    'What this row IS: a Notes Item (reminder, owned by NotesController/AlarmScheduler) or a '
    'Dates Event / Google import (appointment). A third value, car_task, briefly existed here '
    '(20260828000100) for a car_tasks-to-events fold that turned out to duplicate a fold Notes '
    'had already performed years earlier - reverted by this migration, 2026-08-28. Set once, at '
    'upload, from the record type the writer read - never derived from shape.';
