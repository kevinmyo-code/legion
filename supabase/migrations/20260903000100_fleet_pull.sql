-- LEGION live-sync: the missing half of fleet sync - a real, automatic PULL of the fleet aspect,
-- not just the one-way projection FleetReconcile/ObdSampleReconcile/MaintenanceScheduleReconcile
-- already upload through.
-- Ticket: .scratch/live-sync/map.md ticket 05 ("the same treatment for the other six aspects"),
--   fleet's own slice.
-- Depends on: 20260825000500_aspect_places_fleet.sql, 20260826000200_fleet_drives.sql,
--   20260826000600_fleet_diagnostics_specs_build.sql, 20260829000200_vehicles_archived.sql
--
-- =============================================================================================
-- public.vehicles.last_obd_mac - Kevin's ruling, 2026-09-03
-- =============================================================================================
-- Reverses PART of ticket 26 ruling 14 (2026-08-29): "It is a MAC address, and a car can change
-- dongles" - the reasoning that kept obdMac off the server entirely. That reasoning still stands
-- for IDENTITY: nothing may key, join, or dedupe on this column, ever. What changed is narrower -
-- a nullable REBUILD HINT, sent best-effort on every vehicle upsert
-- (`com.kevin.legion.backend.VehicleUpload.lastObdMac`'s own doc comment carries the full
-- reasoning). On a wiped phone, FleetSync's pull reads this hint to reconstruct the legacy
-- `vehicles`/`vehicle_sidecar` rows a reconnecting dongle needs to be recognised by - without it,
-- every fleet table downstream of a vehicle stays unreachable until the SAME dongle happens to
-- reconnect, which ticket 26 never actually required and this ticket found blocking nine tables.
--
-- Nullable and possibly stale by design - a car that changed dongles carries its OLD mac here
-- until the next sync, and that staleness must never corrupt anything: see FleetSync.pullVehicles'
-- own "hintFree" check for how a collision (two vehicles claiming the same hint) is refused rather
-- than silently misassigned.
--
-- UNAPPLIED as of this commit - no CLI or project credentials in this environment. Apply by hand
-- (or via CI with real credentials) before FleetSync's pull is exercised against a real Supabase
-- project.

alter table public.vehicles
    add column if not exists last_obd_mac text;

comment on column public.vehicles.last_obd_mac is
    'Best-effort rebuild hint, never an identity. See VehicleUpload.lastObdMac''s own doc comment '
    'and this file''s own header for the 2026-09-03 ruling that added it.';

-- =============================================================================================
-- Realtime. Every table FleetSync.pull merges joins the publication here - a table is never added
-- automatically (20260902000100_events_realtime_publication.sql's own header comment), and
-- shipping this without the step produces a subscription that looks refused rather than one with
-- nothing to say. `obd_samples` and `chassis_quirks` are deliberately EXCLUDED - see
-- FleetRealtime.kt's own class doc for why (obd_samples' own write volume, chassis_quirks never
-- having been blocked by the obdMac gap this ticket fixes). REPLICA IDENTITY left at its default -
-- FleetRealtime's postgres_changes event is a trigger for FleetSync.pull, never a data source, so
-- the payload itself is never read.
-- =============================================================================================
alter publication supabase_realtime add table public.vehicles;
alter publication supabase_realtime add table public.service_history;
alter publication supabase_realtime add table public.drives;
alter publication supabase_realtime add table public.code_events;
alter publication supabase_realtime add table public.code_clear_events;
alter publication supabase_realtime add table public.oil_analyses;
alter publication supabase_realtime add table public.vehicle_specs;
alter publication supabase_realtime add table public.build_entries;
alter publication supabase_realtime add table public.drive_reassignments;
alter publication supabase_realtime add table public.maintenance_schedules;
