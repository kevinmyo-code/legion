-- LEGION backend-erp ticket 26/27: archived is USER state, not device state.
-- Ticket: .scratch/backend-erp/issues/27-the-sidecar-has-no-cross-device-channel.md
--         ("RULED 2026-08-29: archived is the one that genuinely needs a channel")
--
-- Ticket 26 step 1 (fleet cutover, vehicles-only) put a local `vehicle_sidecar` table on the phone
-- to hold seven columns the wave4-carve migration left off the engine record: personaPrompt,
-- voiceName, personaTraits, archived, onboarded, lastOdometerPromptAt, tripMilesSinceBaseline.
-- That silently dropped `vehicles` out of SyncEngine's registry the same commit, which retired the
-- Drive-based channel those seven columns used to travel on between Kevin's two phones - a real
-- consequence flagged the same day it landed (ticket 27).
--
-- Three of the seven (personaPrompt/voiceName/personaTraits) turned out to be vestigial - nothing
-- reads them, full trace in Vehicle.kt's own doc comment - so they simply stop being carried
-- anywhere, server included. `archived` is different: 42 live references, and a car Kevin retired
-- is retired everywhere, exactly the same "USER state, not device state" reasoning every other
-- identity column on this table already gets. It moves here.
--
-- onboarded/lastOdometerPromptAt/tripMilesSinceBaseline stay phone-only on purpose (see
-- VehicleSidecar's own doc comment) - none of them means anything on a device that did not write
-- it, unlike archived.
--
-- APPLIED. (This line said UNAPPLIED until 2026-09-07, when the fleet routing ticket read the live
-- schema and found the column present. There is no migration history table in this project, so a
-- header is the only record of what has run, and a header that lies is worse than no header.)
-- (or via CI with real credentials) before FleetEngineStore's `archived` read/write path is
-- exercised against a real Supabase project.

alter table public.vehicles
    add column if not exists archived boolean not null default false;
