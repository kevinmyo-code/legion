-- LEGION one-today ticket 05, defect B: never_done's default disagreed between Room and Supabase.
-- Ticket: .scratch/one-today/issues/05-maintenance-has-no-date-axis.md
--
-- Room's MaintenanceItem.neverDone defaults false (data/local/MaintenanceItem.kt). This table's
-- never_done defaulted true (20260825000500_aspect_places_fleet.sql). VehicleController.isDue's
-- first line is `if (item.neverDone) return true`, so the default alone decides whether an
-- un-anchored item (no service_history row on either axis) reads as UNKNOWN or as ALWAYS DUE - and
-- a row round-tripping through the server, if it ever omitted the column, could come back meaning
-- the opposite of what it meant locally.
--
-- Ruling (Kevin, verified against both schemas before this migration was written): false is
-- correct. A schedule item with no service history is genuinely UNKNOWN, not overdue - that is
-- exactly the distinction VehicleController.isUnknown exists to express, and a `true` default
-- would make every un-anchored item across all 54 real rows claim to be due at once, which is
-- noise, not information. Supabase changes to match Room, not the other way round.
--
-- No data fix accompanies this: no code anywhere in the app (backend/, FleetEngineStore.kt) writes
-- to maintenance_schedules yet, and no migration in this repo INSERTs into it - the fleet cutover's
-- server sync for MaintenanceItem is not built. So this default has, as far as this repo's history
-- shows, never actually been read by a real row; there is nothing here to backfill. If that
-- assumption is wrong - if rows were inserted by hand or by a tool outside this repo - they must be
-- checked for `never_done = true` before this default change is relied on, which this migration
-- deliberately does NOT do on Kevin's behalf.
--
-- UNAPPLIED as of this commit - no CLI or project credentials in this environment, matching
-- 20260829000200_vehicles_archived.sql's own note. Apply by hand (or via CI with real credentials).

alter table public.maintenance_schedules
    alter column never_done set default false;
