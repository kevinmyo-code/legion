-- LEGION backend-erp, Phase 2: Places and Fleet.
-- Ticket: .scratch/backend-erp/issues/05-migration-path.md (phase 2)
-- Depends on: 20260825000200_conventions.sql
--
-- Both are AUTHORED aspects: rows here are things a person or a sensor recorded, not documents that
-- came through the reconciliation gate, so they get updated_at and stay editable (Kevin,
-- 2026-08-25). Neither carries the immutability trigger.
--
-- Fleet is also where the schema's only BLOCK references live, and they are the clearest case for
-- ADR 0039: under the generic engine, "you may not delete a vehicle that still has service history"
-- was a rule RecordStore enforced in Kotlin on every caller's behalf. Here it is a foreign key with
-- ON DELETE RESTRICT, which no consumer can route around.

-- =============================================================================================
-- PLACES
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- Latitude and longitude stay two numeric columns rather than becoming a PostGIS point.
-- The engine models them as two NUMBERs, nothing in LEGION does spatial queries, and adding a
-- geometry type would mean an extension a stranger has to enable before clone-and-run works.
-- If proximity search ever arrives, that is the moment to reconsider, not before.
--
-- `label` is unique because it is the natural key everything else uses: the legacy table keyed on
-- it directly, and events.trigger_place_label names a place by this string. A duplicate label would
-- make a geofence ambiguous.
-- ---------------------------------------------------------------------------------------------
create table if not exists public.places (
    id         uuid primary key default gen_random_uuid(),
    label      text        not null check (length(trim(label)) > 0),
    latitude   double precision not null check (latitude between -90 and 90),
    longitude  double precision not null check (longitude between -180 and 180),
    provenance public.provenance not null default 'USER',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint places_label_unique unique (label)
);

comment on column public.places.label is
    'The natural key. events.trigger_place_label names a place by this string, and the geofence '
    'layer uses it as the OS requestId, so it must stay unique and stable.';

-- =============================================================================================
-- FLEET
-- =============================================================================================

create table if not exists public.vehicles (
    id                   uuid primary key default gen_random_uuid(),
    name                 text        not null,
    make                 text        not null,
    model                text        not null,
    year                 integer     not null check (year between 1885 and 2200),
    trim                 text,
    engine               text,

    -- False until the VIN decode or the driver confirms the identity. A vehicle can exist
    -- unconfirmed: the OBD dongle reports before anyone has named the car.
    confirmed            boolean     not null default false,

    -- The odometer anchor: a reading, and when it was taken. Mileage since is derived from trips
    -- rather than stored, which is why there is no current_odometer column here.
    odometer_baseline    integer     check (odometer_baseline is null or odometer_baseline >= 0),
    odometer_baseline_at timestamptz,

    provenance           public.provenance not null default 'USER',
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now(),
    deleted_at           timestamptz,

    -- A baseline reading without its timestamp cannot be projected forward, so it is worse than
    -- none: it looks like knowledge and is not.
    constraint vehicles_odometer_baseline_paired check (
        (odometer_baseline is null) = (odometer_baseline_at is null)
    )
);

-- ---------------------------------------------------------------------------------------------
-- service_history: the union of what was done and what is asserted to have been done.
--
-- `kind` is the discriminator the engine introduced and has no legacy counterpart:
--   OBSERVED - LEGION watched it happen, or read it from a document.
--   ASSERTED - the driver said it happened, with no independent evidence.
-- That distinction is the fleet aspect's own small version of provenance, and it is kept as a
-- separate column rather than folded into `provenance` because the two answer different questions:
-- provenance says how the ROW was produced, kind says how the EVENT was witnessed.
-- ---------------------------------------------------------------------------------------------
create table if not exists public.service_history (
    id           uuid primary key default gen_random_uuid(),
    -- RESTRICT is the SQL spelling of the engine's BLOCK delete policy.
    vehicle_id   uuid        not null references public.vehicles (id) on delete restrict,
    service_name text        not null,
    mileage      integer     check (mileage is null or mileage >= 0),
    service_date date,
    cost_cents   bigint,
    kind         text        not null check (kind in ('OBSERVED', 'ASSERTED')),
    provenance   public.provenance not null default 'USER',
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    deleted_at   timestamptz
);

create index if not exists service_history_vehicle_idx on public.service_history (vehicle_id) where deleted_at is null;

comment on column public.service_history.kind is
    'OBSERVED means LEGION saw it or read it from a document; ASSERTED means the driver said so. '
    'Deliberately separate from provenance: provenance is how the ROW was produced, kind is how the '
    'EVENT was witnessed.';

-- ---------------------------------------------------------------------------------------------
-- maintenance_schedules: the interval rules.
--
-- Deliberately carries NO last_done_mileage or last_done_date, matching the engine's own decision.
-- When a service was last done is a fact about SERVICE HISTORY, and duplicating it here is how the
-- two drift into disagreeing. "Is this due" is computed by reading the latest matching
-- service_history row against these intervals.
-- ---------------------------------------------------------------------------------------------
create table if not exists public.maintenance_schedules (
    id               uuid primary key default gen_random_uuid(),
    vehicle_id       uuid        not null references public.vehicles (id) on delete restrict,
    service_name     text        not null,
    interval_miles   integer     check (interval_miles is null or interval_miles > 0),
    interval_months  integer     check (interval_months is null or interval_months > 0),
    -- Free text rather than a CHECK: the engine deliberately left this open, and values like
    -- 'SEEDED' or a manual entry both occur.
    interval_source  text        not null,
    never_done       boolean     not null default true,
    provenance       public.provenance not null default 'USER',
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    deleted_at       timestamptz,

    -- A schedule with neither interval can never come due, which is always a data-entry mistake
    -- rather than an intent.
    constraint maintenance_schedules_has_an_interval check (
        interval_miles is not null or interval_months is not null
    ),
    -- The legacy table's composite primary key was (vehicleId, serviceName); keeping it as a
    -- uniqueness constraint preserves the guarantee without making it the identity.
    constraint maintenance_schedules_unique_per_vehicle unique (vehicle_id, service_name)
);

create index if not exists maintenance_schedules_vehicle_idx on public.maintenance_schedules (vehicle_id) where deleted_at is null;

-- ---------------------------------------------------------------------------------------------
-- Triggers and RLS for all four authored tables.
-- ---------------------------------------------------------------------------------------------
do $$
declare
    t text;
begin
    foreach t in array array[
        'public.places',
        'public.vehicles',
        'public.service_history',
        'public.maintenance_schedules'
    ] loop
        execute format('drop trigger if exists touch_updated_at on %s', t);
        execute format(
            'create trigger touch_updated_at before update on %s '
            'for each row execute function private.touch_updated_at()', t
        );
        execute format('select private.apply_household_rls(%L)', t);
    end loop;
end $$;
