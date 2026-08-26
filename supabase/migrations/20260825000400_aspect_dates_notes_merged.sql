-- LEGION backend-erp, Phase 2: Dates and Notes, MERGED into one events table.
-- Ticket: .scratch/backend-erp/issues/05-migration-path.md (phase 2, ruling 2)
-- Decision: backend-erp ticket 01 ruling 4 (Kevin, 2026-08-25) - todos become Dates events.
-- Depends on: 20260825000200_conventions.sql
--
-- **This is the largest single step in the arc, and the only aspect with no legacy table to fall
-- back to.** Dates was born engine-native on 2026-08-23, so there is no `events` entity in
-- CarDatabase.kt to repoint writes at. Ledger, pantry, fleet and places can retreat to typed tables
-- that still exist; this one is built new either way, which is why ticket 05 ruling 2 puts the
-- merge here rather than doing it twice.
--
-- **The merge, in one line: 21 Item fields and 7 Event fields become one shape.** A due thing is a
-- dated thing, and there is one dated record type rather than two. Field-by-field:
--
--   Event.title       + Item.text              -> title
--   Event.start       + Item.startsAt          -> starts_at        (the agenda's sort key)
--   Event.end         + Item.endsAt            -> ends_at
--   Item.allDay                                -> all_day
--   Event.location                             -> location
--   Event.notes                                -> notes
--   Event.source / googleEventId               -> source / google_event_id
--   Item.done / doneAt / sortOrder             -> done / done_at / sort_order
--   Item.triggerPlaceLabel                     -> trigger_place_label
--   Item.repeat* (7 fields)                    -> repeat_* (7 columns)
--   Item.exact / exactDowngraded               -> exact / exact_downgraded
--   Item.missedAt / missedDismissedAt          -> missed_at / missed_dismissed_at
--   Item.loggedAt                              -> logged_at
--
-- Nothing is dropped. `ItemList` has no counterpart and is not carried, exactly as the engine
-- already decided: one live list makes grouping vestigial.
--
-- Events are AUTHORED, not gated. They get updated_at and are freely editable (Kevin, 2026-08-25):
-- a todo you retitle is not a posting, so the immutability trigger does not apply here.

create table if not exists public.events (
    id                  uuid primary key default gen_random_uuid(),

    -- The one required pair. Everything else is optional because this single table now serves a
    -- checklist tick, a dictated note, a timed reminder and a calendar appointment.
    title               text        not null check (length(trim(title)) > 0),
    starts_at           timestamptz not null,
    ends_at             timestamptz,
    all_day             boolean     not null default false,

    location            text,
    notes               text,

    -- Google Calendar is being removed (ticket 01 ruling 5), but the widening one-time import runs
    -- FIRST and its rows land here (ruling 11), so these columns exist to receive that history.
    -- After the cut they are vestigial-but-populated: evidence of where a row came from, which is
    -- worth more than the two columns cost.
    source              text        not null default 'legion' check (source in ('legion', 'google')),
    google_event_id     text,

    -- Todo-shaped state.
    done                boolean     not null default false,
    done_at             timestamptz,
    sort_order          integer,

    -- Place trigger. Plain text, matching the engine: it names a TaggedPlace label rather than
    -- referencing one, because the geofence layer keys on the label string
    -- (location/GeofenceManager uses it as the geofence requestId). Making it a foreign key here
    -- would create a second source of truth for a name the OS already holds.
    trigger_place_label text,

    -- Recurrence. Occurrences are never materialised: they are computed on read from these rules,
    -- which is why there is no occurrences table and why skips get their own (below).
    repeat_kind         text check (repeat_kind in ('DAILY', 'WEEKLY', 'MONTHLY_ON_DATE', 'YEARLY')),
    repeat_every        integer check (repeat_every is null or repeat_every > 0),
    repeat_days_of_week text,
    repeat_day          integer check (repeat_day is null or repeat_day between 1 and 31),
    repeat_month        integer check (repeat_month is null or repeat_month between 1 and 12),
    repeat_end_kind     text check (repeat_end_kind in ('NEVER', 'ON_DATE', 'AFTER_COUNT')),
    repeat_end_date     date,
    repeat_end_count    integer check (repeat_end_count is null or repeat_end_count > 0),

    -- Exact-alarm bookkeeping. `exact_downgraded` records that the OS refused an exact alarm, which
    -- the user is told about rather than left to discover.
    exact               boolean     not null default false,
    exact_downgraded    boolean     not null default false,

    missed_at           timestamptz,
    missed_dismissed_at timestamptz,
    -- End-of-day sweep bookkeeping for goal-plan checklist items; the idempotence anchor that stops
    -- one plan line being logged twice in a day.
    logged_at           timestamptz,

    provenance          public.provenance not null default 'USER',
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    deleted_at          timestamptz,

    -- A recurring item cannot be ticked done as a whole; only an occurrence can be skipped. This is
    -- the one domain rule NotesController enforced in Kotlin that is cheap enough to state here.
    constraint events_recurring_not_done check (repeat_kind is null or done = false),
    -- An end rule without a recurrence is meaningless and always indicates a bug upstream.
    constraint events_repeat_end_needs_kind check (repeat_end_kind is null or repeat_kind is not null)
);

-- The agenda query is a window scan over starts_at for live rows, so that is the index.
create index if not exists events_starts_at_idx on public.events (starts_at) where deleted_at is null;
create index if not exists events_open_todos_idx on public.events (starts_at) where deleted_at is null and done = false;
create unique index if not exists events_google_event_id_idx on public.events (google_event_id) where google_event_id is not null;

comment on table public.events is
    'Dates and Notes merged (ticket 01 ruling 4). One dated record type: a checklist tick, a note, '
    'a timed reminder and an appointment are all rows here, distinguished by which optional columns '
    'carry values. Authored, not gated, so freely editable.';

-- ---------------------------------------------------------------------------------------------
-- event_skips: one skipped occurrence of a recurring event.
--
-- This is `list_item_skips` carried over, and it is the one legacy notes table that was still being
-- written. It has to be its own table for the same reason it was before: occurrences are computed
-- from the recurrence rule and never stored, so "this one occurrence is skipped" has nowhere else
-- to live. A date rather than an instant, because a skip is about a day.
-- ---------------------------------------------------------------------------------------------
create table if not exists public.event_skips (
    id         uuid primary key default gen_random_uuid(),
    event_id   uuid not null references public.events (id) on delete cascade,
    skip_date  date not null,
    created_at timestamptz not null default now(),
    constraint event_skips_unique unique (event_id, skip_date)
);

comment on table public.event_skips is
    'A skipped occurrence of a recurring event. Occurrences are computed from the recurrence rule '
    'and never materialised, so a skip cannot be a flag on an occurrence row; there are none.';

-- ---------------------------------------------------------------------------------------------
-- Triggers and RLS. Authored tables get updated_at and NOT the immutability trigger.
-- ---------------------------------------------------------------------------------------------
drop trigger if exists touch_updated_at on public.events;
create trigger touch_updated_at
    before update on public.events
    for each row execute function private.touch_updated_at();

select private.apply_household_rls('public.events');
select private.apply_household_rls('public.event_skips');
