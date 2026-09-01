-- LEGION backend-erp: split events from tasks on public.events.kind. Ticket:
-- .scratch/one-today/issues/08-events-are-not-todos.md, "RULED 2026-09-01" (Kevin, verbatim: "we
-- need to split events and actual todos, i dont mark an event done, it just passes whether or not
-- i do it, like classes").
--
-- Depends on: 20260827000200_events_kind.sql (created the column and its original two-value
-- check), 20260828000300_events_kind_car_task_reverted.sql (the most recent narrow, back to
-- ('reminder', 'appointment') after the car_task fold was reverted the same day).
--
-- =============================================================================================
-- WHY THE CHECK MUST BE READ BEFORE WIDENED - checked before writing this, not assumed either way
-- =============================================================================================
-- 20260827000200 added `check (kind in ('reminder', 'appointment'))`. This project has already
-- been bitten once by writing a CHECK from a doc comment instead of reading the live constraint:
-- 20260829000300 found `conversation_audit_kind_check` written UPPERCASE from a Kotlin doc comment
-- while the column actually stored lowercase, and the first real upload was rejected outright with
-- nothing written. `events.kind`'s own values are already lowercase in every migration that
-- touches it (`'reminder'`, `'appointment'`) and the client sends exactly `EventKind.REMINDER`/
-- `EventKind.EVENT`/`EventKind.TASK` (`"reminder"`/`"event"`/`"task"`) - same lowercase convention,
-- confirmed by reading `com.kevin.legion.backend.EventKind` before writing this file, not assumed.
--
-- =============================================================================================
-- THE RECLASSIFICATION, AND WHY IT IS TWO CONSTRAINT CHANGES AROUND ONE UPDATE
-- =============================================================================================
-- A CHECK constraint is validated against every EXISTING row the instant it is added (the same
-- lesson 20260828000300's own header states for its DELETE-before-narrow ordering). Jumping
-- straight from `('reminder', 'appointment')` to the final `('reminder', 'event', 'task')` would
-- reject immediately, because every row still reads `kind = 'appointment'` at that moment - there
-- is no such value in the destination set. So this migration widens FIRST (permitting the old and
-- new spellings to coexist), reclassifies the data, and only then narrows to the final set that no
-- longer mentions `appointment` at all:
--
--   1. Drop the existing check (matched by conkey against the `kind` column's attnum, never by
--      constraint NAME - the same robust match 20260828000300 used, so this cannot accidentally
--      drop an unrelated check that merely mentions "kind" in its own text elsewhere on this table).
--   2. Add a WIDENED check: ('reminder', 'appointment', 'event', 'task') - a transient state, never
--      the end state, that exists only so the UPDATE below can legally write 'event'.
--   3. UPDATE: every `kind = 'appointment'` row becomes `kind = 'event'`, with `done`/`done_at`
--      CLEARED - not merely hidden behind a client that stopped rendering a checkbox for them.
--      Ticket 08's own account of why this is not optional: a `COSC 3334` row was ticked `done`
--      during on-device testing on 2026-09-01, and a row that can never be completed must not go
--      on carrying a stale `true` some later reconcile or feature could read back as fact.
--   4. Drop the widened check, add the FINAL narrowed one: ('reminder', 'event', 'task'). No row
--      can violate this at the moment it is added - step 3 already removed every `appointment`
--      value from the table.
--
-- `reminder` rows are untouched throughout: every WHERE clause below reads `kind = 'appointment'`
-- specifically, never a broader "not reminder" match, so a genuinely completed Notes reminder
-- (`kind = 'reminder'`, ticked and dated for real) is never touched by this file.
--
-- `events_recurring_not_done` (`check (repeat_kind is null or done = false)`, from
-- 20260825000400) is unaffected by the UPDATE above: every row reclassified here already carries a
-- null `repeat_kind` (a Dates `Event`/Google import has never had a LEGION repeat rule of its own -
-- `com.kevin.legion.data.local.Event`'s own doc comment), so clearing `done` to `false` cannot
-- violate a constraint that was already trivially satisfied.
--
-- `task` is a genuinely new value nothing writes yet - Canvas populating real submission state is
-- its own ticket (this ticket's own "Canvas is its own ticket" section). This migration only
-- WIDENS the vocabulary the column will accept; it does not itself produce a single `task` row.
--
-- UNAPPLIED as of this commit - this agent has no Supabase CLI access and no project credentials
-- from this environment, same posture every migration in this directory since phase 2 documents.
-- Kevin (or a future session with credentials) must run this against the live project. **Expected
-- row impact cannot be counted from here either** - reading `select count(*) from public.events
-- where kind = 'appointment'` requires the same credentials this agent does not have; the migration
-- itself is written to be correct regardless of that count (zero, one, or every historical row).

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

-- Transient widened check - permits the OLD ('appointment') and NEW ('event', 'task') spellings to
-- coexist for the duration of the UPDATE below only.
alter table public.events add constraint events_kind_check
    check (kind in ('reminder', 'appointment', 'event', 'task'));

update public.events
   set kind = 'event',
       done = false,
       done_at = null
 where kind = 'appointment';

-- Final narrow: drop the transient check, add the one this migration leaves in place. No row can
-- violate it - the UPDATE above already removed every 'appointment' value from the table.
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
    check (kind in ('reminder', 'event', 'task'));

comment on column public.events.kind is
    'The completable-or-not axis (one-today ticket 08, 2026-09-01, superseding the '
    '"reminder = Notes, appointment = Dates" framing 20260827000200 stated): '
    'reminder - user-set, alarm-bearing, completable. '
    'event - passes, NEVER completable, no checkbox anywhere in the UI (renamed from '
    '''appointment'' - every row that used to read that value was reclassified here, with '
    'done/done_at cleared, not merely hidden). '
    'task - completable, may carry a due date with no alarm; nothing writes one yet, Canvas '
    'populating real submission state is its own ticket. '
    'Set once, at write time, from the record type/write path the writer used - never derived '
    'from a row''s shape or its title.';
