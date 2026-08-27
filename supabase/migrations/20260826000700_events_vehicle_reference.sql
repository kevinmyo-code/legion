-- LEGION backend-erp, Phase 4: a vehicle reference on public.events.
-- Ticket: .scratch/backend-erp/issues/10-fleet-cutover.md
-- Ruling: .scratch/backend-erp/issues/06-fleet-has-no-server-home.md (Kevin, 2026-08-26)
-- Depends on: 20260825000400_aspect_dates_notes_merged.sql (events),
--             20260825000500_aspect_places_fleet.sql (vehicles)
--
-- Ruling 06: `car_tasks` folds into `events` rather than getting its own table - ruling 4 already
-- decided todos become Dates events, and a car task is just a todo that names a vehicle. A second
-- todo table would repeat exactly the duplication ruling 4 removed. The consequence, stated in
-- the ruling and executed here: events needs a way to reference a vehicle, which it has none of
-- today.
--
-- Additive and nullable, deliberately - "most events are not about a car" (ticket 10). A NOT NULL
-- column here would force every note, appointment and plain todo to carry a vehicle it has
-- nothing to do with.
--
-- ON DELETE SET NULL, not RESTRICT, and that is a deliberate departure from the rest of the fleet
-- aspect's block-delete convention (service_history, maintenance_schedules, drives, and every
-- table in 20260826000600 all use RESTRICT). Those are fleet's OWN tables, where losing a service
-- record silently because its car was deleted would be losing fleet history. `events` is not a
-- fleet table - it is a cross-aspect table that fleet merely tags - so deleting a vehicle should
-- not be blocked by, or silently destroy, an unrelated todo or note. The todo survives; it just
-- stops being about a car that no longer exists.
alter table public.events
    add column if not exists vehicle_id uuid references public.vehicles (id) on delete set null;

comment on column public.events.vehicle_id is
    'Nullable: most events are not about a car. Set when a car_task-shaped event names a vehicle '
    '(ticket 06''s ruling that car_tasks fold into events rather than getting their own table). '
    'ON DELETE SET NULL, not RESTRICT - events is not a fleet table, so a deleted vehicle should '
    'not block or destroy an unrelated todo.';

-- Mirrors events_open_todos_idx's shape: the query this serves is "open todos for this car",
-- which is a vehicle_id + done=false scan over live rows.
create index if not exists events_vehicle_open_todos_idx
    on public.events (vehicle_id, starts_at)
    where deleted_at is null and vehicle_id is not null and done = false;
