-- LEGION backend-erp: a third public.events.kind for the car_tasks fold, `car_task`.
-- Ticket: .scratch/backend-erp/issues/10-fleet-cutover.md ("car_tasks fold into events").
-- Ruling: .scratch/backend-erp/issues/06-fleet-has-no-server-home.md, "car_tasks FOLDS INTO
--         events, and does not get its own table."
-- Depends on: 20260827000200_events_kind.sql (kind text not null default 'reminder',
--             check (kind in ('reminder', 'appointment'))).
--
-- NOT depended on: 20260826000700_events_vehicle_reference.sql. `events.vehicle_id` exists and a
-- car task is fleet data, so the column looks like its natural home - but `CarTask` is GLOBAL, not
-- keyed to a vehicle, deliberately (read its entity doc: an item added while the OBD adapter is
-- disconnected must not vanish when a different profile becomes active). So every uploaded
-- car_task leaves `vehicle_id` NULL. It is left unset rather than guessed, and the column stays
-- there for the day car tasks gain a real vehicle reference.
--
-- =============================================================================================
-- WHY A THIRD KIND, RATHER THAN REUSING 'reminder'
-- =============================================================================================
-- A car task IS a kind of todo, and 'reminder' is what the phone's local Notes store already
-- means by "a todo I own". Reusing it looked free. It is not, and the failure shape is the exact
-- one 20260827000200's own header names: `EventsReconcile`'s refill wipes every local
-- `kind = 'reminder'` row and refills it from the server (that is what "refill" means for a
-- kind the phone does not itself author from scratch). A car task stored as `reminder` would
-- therefore be pulled down into the phone's Notes store on the very next reconcile, and
-- `AlarmScheduler`'s startup sweep - which iterates every reminder it does not recognise as
-- something else - would treat it as a reminder it owns and schedule or flag it as missed. That
-- is the 2026-08-26 51-false-missed-reminders incident's exact shape, replayed one column over.
--
-- A distinct `kind` value removes this by construction: `EventsReconcile` filters on kind before
-- it ever decides what is a reminder, so a `car_task` row simply never enters the reminder
-- bucket, and there is no filter for a future engineer to remember to add or forget. This is the
-- same argument 20260827000200 already made for why `kind` is a stored column rather than a
-- derived one - a rule enforced by construction survives; a rule enforced by someone remembering
-- does not.
--
-- `car_task` rows are uploaded by fleet (`FleetReconcile`, from the phone's local `car_tasks`
-- table, which fleet keeps as ruling 14's projection - fleet is NOT cut over, it is a one-way
-- upload), never by `EventsReconcile`, and are never downloaded back into the phone's Notes or
-- Dates stores. They exist in `public.events` purely so a second phone's fleet aspect can read
-- them - the same cross-device sync every other fleet wave provides.
--
-- UNAPPLIED as of this commit - this agent has no Supabase CLI access and no project credentials
-- from this environment, same posture every migration in this folder since 54cdf5e has recorded.
-- Kevin (or a future session with credentials) must run this against the live project before
-- FleetReconcile's car-task wave or EventsReconcile's kind-filtered refill mean anything
-- server-side.
-- ---------------------------------------------------------------------------------------------
-- The widening, written so it cannot half-apply.
--
-- The obvious form - `drop constraint if exists events_kind_check` then add - assumes Postgres
-- named 20260827000200's unnamed inline check exactly that. It almost certainly did. But if it
-- did NOT, `drop ... if exists` no-ops silently, the `add` succeeds under a fresh name, and the
-- ORIGINAL two-value check survives underneath: the migration reports success and every
-- `car_task` insert is still rejected, at runtime, far from here. That is a success panel lying
-- about an effect, which is exactly what this project has already been bitten by once
-- (lesson L37).
--
-- So the old constraint is found by what it CONSTRAINS rather than by what it is called.
--
-- The match is on `conkey`, the exact set of columns a constraint covers, and it must equal
-- {kind} ALONE. Matching on the constraint's TEXT (`pg_get_constraintdef(...) ilike '%kind%'`)
-- was the first draft and is wrong in a way that is quiet and expensive: a future cross-column
-- rule - "an appointment must have a starts_at", say - mentions kind too, and a text match would
-- drop it here and never put it back. A single-column match cannot reach such a constraint.
--
-- Idempotent: re-running finds and drops the constraint this file itself added, and puts back an
-- identical one.
-- ---------------------------------------------------------------------------------------------
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
    check (kind in ('reminder', 'appointment', 'car_task'));

comment on column public.events.kind is
    'What this row IS: a Notes Item (reminder, owned by NotesController/AlarmScheduler), a '
    'Dates Event / Google import (appointment), or a fleet car_task uploaded by FleetReconcile '
    '(car_task, never refilled to the phone and never treated as a reminder - see this '
    'migration''s header for the incident this kind exists to prevent). Set once, at upload, '
    'from the record type the writer read - never derived from shape.';
