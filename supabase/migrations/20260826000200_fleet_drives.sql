-- LEGION backend-erp, Phase 4: the drives table.
-- Ticket: .scratch/backend-erp/issues/06-fleet-has-no-server-home.md (Kevin's ruling, 2026-08-26)
-- Depends on: 20260825000200_conventions.sql, 20260825000500_aspect_places_fleet.sql
--
-- =============================================================================================
-- WHY THIS EXISTS
-- =============================================================================================
-- Ticket 01 ruling 10 says, in its own parenthetical, that "trips and maintenance still sync as
-- records" - so drives were never phone-only residue. The fleet tables shipped without one anyway,
-- which ticket 06 caught on reaching aspect 3. Kevin ruled 2026-08-26: drives sync.
--
-- `drives` is the fleet aspect's TRANSACTION table in the ERP framing of CLAUDE.md section 1.
-- Mileage, MPG and spend are all derived from it, which is also why ticket 06's second ruling
-- (recaps recompute from drives) is possible at all.
--
-- =============================================================================================
-- KEYED ON sync_id, NOT origin_guid
-- =============================================================================================
-- Every other aspect got `origin_guid` in 20260826000100 because its rows are engine records and
-- `records.guid` was the identity they already had. **Drives are NOT engine records** -
-- `engine/fleet/FleetAspectSeeder.kt` defines only Vehicle, ServiceHistory and MaintenanceSchedule,
-- so drives never left the legacy Room table. They do not need `origin_guid` because `Drive.syncId`
-- already exists and is already documented as the "portable cross-device identity for sync".
--
-- So this table takes `sync_id` as a NOT NULL UNIQUE column: the upload's idempotency key, carried
-- straight over rather than invented here.
--
-- =============================================================================================
-- WHAT IS DELIBERATELY NOT HERE
-- =============================================================================================
-- No `monthly_recaps`, no `yearly_wrapped`. Kevin ruled the same day that recaps RECOMPUTE from
-- drives rather than migrating as rows - the ERP framing makes widgets a reporting layer over
-- transactions, and a stored aggregate beside the rows it aggregates is a second source of truth.
-- **The arithmetic is deliberately not written here.** `vehicle/MonthlyRecapController.kt` and
-- `vehicle/MpgTrust.kt` already implement it on the phone, and transcribing it into SQL unchecked
-- would repeat the mistake CLAUDE.md section 4 rule 1 and ticket 03 ruling 2 exist to prevent: two
-- implementations of one calculation with nothing proving they agree. Deciding whether the recap
-- becomes a view, an RPC, or stays a phone-side computation over synced drives is its own step.

create table if not exists public.drives (
    id          uuid primary key default gen_random_uuid(),

    -- The phone's portable identity for this drive, carried verbatim. Unique, so re-running the
    -- upload is free.
    sync_id     text        not null unique check (length(trim(sync_id)) > 0),

    vehicle_id  uuid        not null references public.vehicles (id) on delete restrict,

    started_at  timestamptz not null,
    ended_at    timestamptz not null,

    miles       double precision not null check (miles >= 0),

    -- NULL, never 0.0, when MAF was silent for the whole drive. `Drive.gallons`' own comment is
    -- emphatic about this and the distinction is load-bearing for MPG: a drive that burned an
    -- unknown amount of fuel must not read as a drive that burned none.
    gallons     double precision check (gallons is null or gallons >= 0),

    -- 'ENGINE_OFF' or 'LINK_LOST' today. Deliberately unconstrained TEXT, matching the phone's own
    -- comment that widening it needs no migration.
    end_reason  text        not null,

    -- DETERMINISTIC, not OBSERVED: a drive is measured by the dongle and finalised by code, with
    -- no model anywhere in the path. 'OBSERVED' is NOT a provenance value - it is a `kind` on
    -- service_history, which is a different column entirely and easy to confuse.
    provenance  public.provenance not null default 'DETERMINISTIC',
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    deleted_at  timestamptz,

    constraint drives_ends_after_start check (ended_at >= started_at)
);

create index if not exists drives_vehicle_started_idx
    on public.drives (vehicle_id, started_at) where deleted_at is null;

comment on table public.drives is
    'One completed drive. The fleet aspect''s transaction table: mileage, MPG and spend all derive '
    'from it, and monthly/yearly recaps are recomputed from it rather than stored (ticket 06).';
comment on column public.drives.sync_id is
    'Drive.syncId from the phone, carried verbatim. The upload''s idempotency key - drives are not '
    'engine records, so they carry no origin_guid.';
comment on column public.drives.gallons is
    'NULL, never 0.0, when MAF was silent for the whole drive. Unknown fuel and no fuel are '
    'different facts and must not collapse.';

do $$
declare t text;
begin
    foreach t in array array['public.drives'] loop
        execute format('drop trigger if exists touch_updated_at on %s', t);
        execute format(
            'create trigger touch_updated_at before update on %s '
            'for each row execute function private.touch_updated_at()', t
        );
        execute format('select private.apply_household_rls(%L)', t);
    end loop;
end $$;
