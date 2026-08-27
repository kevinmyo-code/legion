-- LEGION backend-erp, Phase 4: fleet's remaining server tables.
-- Ticket: .scratch/backend-erp/issues/10-fleet-cutover.md
-- Ruling: .scratch/backend-erp/issues/06-fleet-has-no-server-home.md (Kevin, 2026-08-26)
-- Depends on: 20260825000200_conventions.sql, 20260825000500_aspect_places_fleet.sql (vehicles),
--             20260826000200_fleet_drives.sql (drives, referenced only in comments below)
--
-- =============================================================================================
-- WHAT SHIPS HERE, AND WHY IT IS ALL ONE WAVE
-- =============================================================================================
-- Ruling 06 settled seven tables at once: code_events, code_clear_events, oil_analyses and
-- chassis_quirks sync as low-volume historical/reference facts (the same reasoning ruling 10
-- used to keep obd_samples local does not apply to any of them - none is high-frequency);
-- vehicle_specs and build_entries sync as master data and user-authored content respectively;
-- drive_reassignments syncs and must land in the same wave as the fact it corrects (`drives`,
-- already live in 20260826000200) so a correction can never trail its fact into a later release.
-- None of the seven depends on any of the others, so they ship together in one file rather than
-- being split into waves that would buy nothing - the only real ordering constraint (vehicles
-- before anything that references it) is already satisfied because `vehicles` shipped on
-- 2026-08-25.
--
-- Explicitly NOT here, per ruling 06: `obd_samples` (phone-only, ephemeral, high-frequency,
-- ruling 10), `monthly_recaps` and `yearly_wrapped` (recomputed from `drives`, never stored).
--
-- =============================================================================================
-- IDEMPOTENCY KEYS - CONFIRMED AGAINST sync/SyncEngine.kt AND THE ROOM ENTITIES, NOT ASSUMED
-- =============================================================================================
-- Registry entries read directly off SyncEngine.kt's REGISTRY (each Spec's naturalPk/hasSyncId):
--   code_events         Spec(..., naturalPk = false, hasSyncId = true)   -> sync_id
--   code_clear_events   Spec(..., naturalPk = false, hasSyncId = true)   -> sync_id
--   oil_analyses        Spec(..., naturalPk = false, hasSyncId = true)   -> sync_id
--   build_entries       Spec(..., naturalPk = false, hasSyncId = true)   -> sync_id
--   drive_reassignments Spec(..., naturalPk = false, hasSyncId = true)   -> sync_id
--   vehicle_specs       Spec("vehicle_specs", listOf("vehicleId"), naturalPk = true) -> vehicle_id
--   chassis_quirks      Spec("chassis_quirks", listOf("quirkId"), naturalPk = true)  -> quirk_id
-- This matches ticket 10's paragraph exactly - no disagreement found between the ticket, the
-- registry and the five @Entity classes read for their syncId columns (BuildEntry.kt,
-- ChassisQuirk.kt, CodeClearEvent.kt, CodeEvent.kt, DriveReassignment.kt, OilAnalysis.kt,
-- VehicleSpec.kt). `vehicle_specs` and `chassis_quirks` genuinely have no syncId column on the
-- phone at all; their Room @PrimaryKey already is the natural key, so the server table's primary
-- key IS the natural key rather than a separate uuid `id` plus a unique constraint.
--
-- =============================================================================================
-- vehicleId ON THE PHONE IS Vehicle.obdMac (TEXT); vehicle_id HERE IS THE SERVER uuid
-- =============================================================================================
-- Every phone entity below stores `vehicleId: String` (Vehicle.obdMac). `public.vehicles.id` is a
-- uuid, matching how `20260826000200_fleet_drives.sql` already handles the same mismatch for
-- `drives.vehicle_id` - the upload path resolves obdMac to the vehicle's server uuid before
-- writing (see `engine/fleet/FleetRecordBridge.vehicleGuid`, which derives a deterministic uuid
-- from `"fleet-vehicle:$obdMac"` for the vehicle row itself). Nothing here re-derives that; these
-- tables just take the resolved uuid as a normal foreign key.
--
-- =============================================================================================
-- PROVENANCE PER TABLE - READ OFF WHAT ACTUALLY PRODUCED THE ROW, NOT COPIED FROM drives
-- =============================================================================================
-- code_events / code_clear_events: an ELM327 read and a Mode 04 clear-and-reread, both code-driven
--   with no model in the path -> DETERMINISTIC, same reasoning as `drives`.
-- oil_analyses: "voice-entered or typed in the ControlPanel" per OilAnalysis.kt's own doc comment
--   - a person transcribing a lab report -> USER, NOT DETERMINISTIC. This is the one place this
--   file's provenance choice diverges from `drives`' default and it is deliberate.
-- build_entries: "driver-entered" logbook lines -> USER.
-- drive_reassignments: a correction a person made in the car manager UI -> USER.
-- vehicle_specs: mostly NHTSA vPIC VIN-decode output (a deterministic API call, no model) with a
--   handful of manual columns (paintColor/paintCode/buildNotes) the driver typed themselves. One
--   provenance column cannot carry two answers for one row; DETERMINISTIC is chosen because it
--   describes the majority of the row's content and because the manual columns are individually
--   named in their own comments below, the same way service_history's `kind` column separates a
--   different two-way distinction rather than trying to fold it into `provenance`.
-- chassis_quirks: parsed from a bundled JSON asset by code -> DETERMINISTIC, matching provenance's
--   own definition of that value verbatim ("parsed from the document by code").
--
-- =============================================================================================
-- TWO TRAPS fleet_drives.sql ALREADY PAID FOR, RE-CHECKED HERE
-- =============================================================================================
-- 'OBSERVED' is a `kind` value on service_history, NOT a provenance value - nothing below uses it
-- as a provenance default. Every nullable numeric column with a real meaning (ppm readings, cost,
-- mileage) stays NULL with no zero default; two sentinel encodings on the phone
-- (ChassisQuirk.mileageLow/High and costLow/High use -1 for "no bound"/"unknown") are converted to
-- real NULL here instead of carrying the -1 forward, because a magic number is the same trap as a
-- zero default wearing a different digit.
--
-- =============================================================================================
-- MONEY
-- =============================================================================================
-- Two candidates found. `BuildEntry.cost` is `Double` dollars on the phone and ticket 11
-- deliberately left it that way (it is sparse driver-entered data, not an accounting figure) -
-- but CLAUDE.md section 3's money rule is unconditional for anything durable server-side, so it
-- becomes `cost_cents bigint` here; the upload path converts dollars to cents, not this schema.
-- `ChassisQuirk.costLow/costHigh` are `Int` USD with a -1 "unknown" sentinel - also converted to
-- `bigint` cents, with -1 mapped to NULL rather than carried forward as -100.

-- =============================================================================================
-- code_events: one ELM327 DTC read plus its Mode 02 freeze frame.
-- =============================================================================================
create table if not exists public.code_events (
    id              uuid primary key default gen_random_uuid(),
    sync_id         text        not null unique check (length(trim(sync_id)) > 0),
    vehicle_id      uuid        not null references public.vehicles (id) on delete restrict,

    occurred_at     timestamptz not null,
    -- Estimate, not a fact: a frozen snapshot of VehicleController.currentMileage at read time,
    -- with nothing captured to prove how stale it was. Carried as-is (see CodeEvent.kt's own doc
    -- comment) - this is diagnostic metadata, not a reconciliation-gated figure, so it is nullable
    -- rather than labelled with a gate tag.
    mileage         integer     check (mileage is null or mileage >= 0),

    -- codesJson's JSON array, e.g. ["P0420","P0128"]. Never empty for a row that exists at all.
    codes           jsonb       not null check (jsonb_typeof(codes) = 'array'),
    -- NULL, not '{}', when the adapter returned no freeze frame - some older ELM327 clones skip
    -- Mode 02 entirely. The phone's "" convention becomes real NULL here rather than an empty
    -- object standing in for absence.
    freeze_frame    jsonb       check (freeze_frame is null or jsonb_typeof(freeze_frame) = 'object'),

    provenance      public.provenance not null default 'DETERMINISTIC',
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    deleted_at      timestamptz
);

create index if not exists code_events_vehicle_idx
    on public.code_events (vehicle_id, occurred_at) where deleted_at is null;

comment on table public.code_events is
    'One ELM327 DTC snapshot plus its Mode 02 freeze frame, keyed on sync_id per SyncEngine.kt. '
    'Dongle-read and code-finalised, no model in the path, hence DETERMINISTIC.';
comment on column public.code_events.freeze_frame is
    'NULL, never an empty object, when the adapter returned no freeze frame. Same absence-is-not-'
    'a-value posture as drives.gallons.';

-- =============================================================================================
-- code_clear_events: the outcome of one Mode 04 clear-codes send.
-- =============================================================================================
create table if not exists public.code_clear_events (
    id              uuid primary key default gen_random_uuid(),
    sync_id         text        not null unique check (length(trim(sync_id)) > 0),
    vehicle_id      uuid        not null references public.vehicles (id) on delete restrict,

    -- The moment Mode 04 was actually sent. A row only ever exists for CLEARED/RETURNED/
    -- UNVERIFIED - NOTHING_TO_CLEAR and REFUSED never send anything and never reach this table
    -- (CodeClearEvent.kt's own doc comment, D2/D8).
    occurred_at     timestamptz not null,
    mileage         integer     check (mileage is null or mileage >= 0),

    -- The call-2 snapshot immediately before the send (never the call-1 prompt read, which can be
    -- stale by confirmation time).
    codes_before    jsonb       not null check (jsonb_typeof(codes_before) = 'array'),
    freeze_frame    jsonb       check (freeze_frame is null or jsonb_typeof(freeze_frame) = 'object'),
    -- Three-way, not two: NULL means the post-send re-read was never attempted or never completed
    -- (UNVERIFIED); '[]' means the re-read ran and found nothing (CLEARED); a non-empty array
    -- names RETURNED's survivors. The phone's "" vs "[]" distinction becomes NULL vs '[]' here -
    -- collapsing them the way freeze_frame's absence is collapsed would erase exactly the
    -- three-way distinction CodeClearEvent.kt calls out as load-bearing.
    codes_after     jsonb       check (codes_after is null or jsonb_typeof(codes_after) = 'array'),

    outcome         text        not null check (outcome in ('CLEARED', 'RETURNED', 'UNVERIFIED')),
    -- Diagnostic only, per D1: sendCommand returns "" on failure and a quiet link answers exactly
    -- like a real ack, so outcome is never asserted off this field.
    ack_raw         text        not null default '',

    provenance      public.provenance not null default 'DETERMINISTIC',
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    deleted_at      timestamptz,

    -- The three-way distinction stated as a real constraint rather than left to trust: UNVERIFIED
    -- means no re-read landed, the other two outcomes require one.
    constraint code_clear_events_after_matches_outcome check (
        (outcome = 'UNVERIFIED') = (codes_after is null)
    )
);

create index if not exists code_clear_events_vehicle_idx
    on public.code_clear_events (vehicle_id, occurred_at) where deleted_at is null;

comment on table public.code_clear_events is
    'One Mode 04 clear-codes outcome. Exists only for CLEARED/RETURNED/UNVERIFIED - the two '
    'no-op outcomes never send anything and never reach this table (D2/D8).';
comment on column public.code_clear_events.codes_after is
    'NULL means the re-read never completed (UNVERIFIED). Empty array means it ran clean '
    '(CLEARED). Non-empty names the survivors (RETURNED). Do not collapse NULL and empty array - '
    'CodeClearEvent.kt calls this three-way distinction load-bearing.';

-- =============================================================================================
-- oil_analyses: a used-oil lab report, transcribed by the driver.
-- =============================================================================================
create table if not exists public.oil_analyses (
    id                   uuid primary key default gen_random_uuid(),
    sync_id              text        not null unique check (length(trim(sync_id)) > 0),
    vehicle_id           uuid        not null references public.vehicles (id) on delete restrict,

    analyzed_at          timestamptz not null,
    mileage              integer     check (mileage is null or mileage >= 0),
    oil_brand            text        not null default '',
    oil_grade            text        not null default '',   -- e.g. '5W-30'
    drain_interval_miles integer     check (drain_interval_miles is null or drain_interval_miles > 0),

    -- Wear metals, parts-per-million. NULL means the lab did not report that element - older
    -- reports omit some metals - never 0, which would claim a clean reading the lab never made.
    iron                 integer     check (iron is null or iron >= 0),
    copper               integer     check (copper is null or copper >= 0),
    lead                 integer     check (lead is null or lead >= 0),
    tin                  integer     check (tin is null or tin >= 0),
    aluminum             integer     check (aluminum is null or aluminum >= 0),
    chromium             integer     check (chromium is null or chromium >= 0),
    nickel               integer     check (nickel is null or nickel >= 0),

    -- Contaminants, parts-per-million. Same NULL-means-unreported rule.
    sodium               integer     check (sodium is null or sodium >= 0),
    potassium            integer     check (potassium is null or potassium >= 0),
    silicon              integer     check (silicon is null or silicon >= 0),
    boron                integer     check (boron is null or boron >= 0),
    magnesium            integer     check (magnesium is null or magnesium >= 0),

    -- Oil condition.
    fuel_percent         double precision check (fuel_percent is null or fuel_percent >= 0),
    water_percent        double precision check (water_percent is null or water_percent >= 0),
    tbn                  double precision check (tbn is null or tbn >= 0),
    viscosity_cst        double precision check (viscosity_cst is null or viscosity_cst >= 0),
    lab_notes            text        not null default '',

    -- A person transcribed a lab report; no code or model produced this row.
    provenance           public.provenance not null default 'USER',
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now(),
    deleted_at            timestamptz
);

create index if not exists oil_analyses_vehicle_idx
    on public.oil_analyses (vehicle_id, analyzed_at) where deleted_at is null;

comment on table public.oil_analyses is
    'A used-oil lab report, voice-entered or typed by the driver (OilAnalysis.kt). USER '
    'provenance, not DETERMINISTIC - a person transcribed these numbers, code did not derive them.';
comment on column public.oil_analyses.iron is
    'ppm. NULL means the lab did not report iron, never 0 - a report that omits an element is not '
    'the same fact as one that measured zero of it.';

-- =============================================================================================
-- chassis_quirks: the bundled Chassis Quirk Index. Reference data, not per-vehicle observation.
-- =============================================================================================
create table if not exists public.chassis_quirks (
    -- Natural key: the stable slug already IS the identity on the phone (ChassisQuirk.quirkId),
    -- so there is no separate uuid id and no sync_id - matching SyncEngine.kt's
    -- naturalPk = true entry for this table.
    quirk_id             text primary key check (length(trim(quirk_id)) > 0),
    -- Comma-delimited chassis codes this quirk applies to, e.g. "E46,E46M3" - carried verbatim
    -- rather than normalised into a join table, matching the phone's own shape. Not a fleet
    -- vehicle_id: this is platform reference data, independent of any specific vehicle row.
    chassis              text        not null,
    engine               text        not null default '',
    title                text        not null,
    symptom              text        not null,
    verification_steps   text        not null,
    -- NULL, not -1, when there is no bound. The phone's -1-means-unbounded sentinel is exactly
    -- the zero-default trap CLAUDE.md's fleet_drives lesson warns against, wearing a different
    -- number.
    mileage_low          integer     check (mileage_low is null or mileage_low >= 0),
    mileage_high         integer     check (mileage_high is null or mileage_high >= 0),
    severity             text        not null check (severity in ('MONITOR', 'SERVICE_SOON', 'CRITICAL')),
    -- Typical repair cost range, in cents (CLAUDE.md section 3: money is bigint cents, never a
    -- Double or a raw Int). NULL, not -1, for "unknown/DIY-variable".
    cost_low_cents       bigint      check (cost_low_cents is null or cost_low_cents >= 0),
    cost_high_cents      bigint      check (cost_high_cents is null or cost_high_cents >= 0),
    fix_notes            text        not null default '',
    source_url           text        not null default '',

    -- Parsed from a bundled JSON asset by code; no model involved.
    provenance           public.provenance not null default 'DETERMINISTIC',
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now()
    -- Deliberately no deleted_at: this table is REPLACE-semantics reference content re-seeded on
    -- APK updates and community PRs (ChassisQuirk.kt's own doc comment), not a per-user record a
    -- driver deletes.
);

comment on table public.chassis_quirks is
    'The bundled Chassis Quirk Index. Reference data shared across the household, not a '
    'per-vehicle observation - hence no vehicle_id and no deleted_at. Natural key is quirk_id, '
    'matching SyncEngine.kt.';
comment on column public.chassis_quirks.mileage_low is
    'NULL means no lower bound. The phone stores -1 for this; do not carry -1 forward here.';
comment on column public.chassis_quirks.cost_low_cents is
    'Typical repair cost range, cents. NULL means unknown/DIY-variable (the phone''s -1 '
    'sentinel), never 0.';

-- =============================================================================================
-- vehicle_specs: the factory-spec "build details encyclopedia" for one car.
-- =============================================================================================
create table if not exists public.vehicle_specs (
    -- Natural key AND the FK: one row per vehicle, matching SyncEngine.kt's
    -- naturalPk = true entry keyed on vehicleId, and the phone's own REPLACE-on-conflict
    -- semantics (VehicleSpec.kt's doc comment).
    vehicle_id           uuid primary key references public.vehicles (id) on delete restrict,

    vin                  text        not null default '',

    -- Powertrain (NHTSA vPIC VIN decode).
    engine_cylinders     integer     check (engine_cylinders is null or engine_cylinders > 0),
    displacement_l       double precision check (displacement_l is null or displacement_l > 0),
    engine_hp            integer     check (engine_hp is null or engine_hp > 0),
    engine_config        text        not null default '',
    fuel_type            text        not null default '',
    transmission_style   text        not null default '',
    transmission_speeds  text        not null default '',
    drive_type           text        not null default '',

    -- Identity / provenance (vPIC).
    body_class           text        not null default '',
    doors                integer     check (doors is null or doors > 0),
    series               text        not null default '',
    vehicle_type         text        not null default '',
    manufacturer         text        not null default '',
    plant_city           text        not null default '',
    plant_country        text        not null default '',

    -- Manual: vPIC cannot supply factory paint, so the driver types these three. Not covered by
    -- this table's DETERMINISTIC default - see the file header's provenance note.
    paint_color          text        not null default '',
    paint_code           text        not null default '',
    build_notes          text        not null default '',

    -- NULL, not 0, for "never decoded". The phone uses 0L as its sentinel; real NULL here for the
    -- same reason every other sentinel in this file was converted rather than carried forward.
    decoded_at           timestamptz,

    -- Mostly a machine VIN decode with no model involved; see the file header for why this is
    -- DETERMINISTIC despite the three manual columns above.
    provenance           public.provenance not null default 'DETERMINISTIC',
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now()
);

comment on table public.vehicle_specs is
    'Factory build details for one vehicle, mostly NHTSA vPIC VIN-decode output plus three '
    'manually-typed columns vPIC cannot supply (paint, build notes). One row per vehicle - '
    'vehicle_id is both the primary key and the foreign key, matching the phone''s REPLACE '
    'semantics.';
comment on column public.vehicle_specs.decoded_at is
    'NULL means never decoded. The phone stores 0L for this; do not carry 0 forward as a '
    'timestamp - it would read as an actual moment in 1970.';

-- =============================================================================================
-- build_entries: one driver-authored logbook line (mod, part, repair, consumable, or spend).
-- =============================================================================================
create table if not exists public.build_entries (
    id            uuid primary key default gen_random_uuid(),
    sync_id       text        not null unique check (length(trim(sync_id)) > 0),
    vehicle_id    uuid        not null references public.vehicles (id) on delete restrict,

    -- 'mod' | 'part' | 'repair' | 'consumable' | 'other' on the phone. Left as unconstrained TEXT
    -- rather than a CHECK, matching BuildEntry.kt's own comment that this list is open-ended.
    entry_type    text        not null,
    title         text        not null check (length(trim(title)) > 0),
    vendor        text        not null default '',
    part_number   text        not null default '',
    -- Cents, never a Double dollar figure - CLAUDE.md section 3. NULL means the driver logged
    -- what was done with no dollar figure; never 0, which would assert it was free.
    cost_cents    bigint      check (cost_cents is null or cost_cents >= 0),
    logged_at     timestamptz not null,
    mileage       integer     check (mileage is null or mileage >= 0),
    notes         text        not null default '',

    -- Driver-entered logbook content; no code or model produced this row.
    provenance    public.provenance not null default 'USER',
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    deleted_at    timestamptz
);

create index if not exists build_entries_vehicle_idx
    on public.build_entries (vehicle_id, logged_at) where deleted_at is null;

comment on table public.build_entries is
    'One driver-authored logbook line: a mod, part, repair, consumable, or general spend entry. '
    'cost_cents is cents per CLAUDE.md section 3, converting BuildEntry.cost''s Double dollars at '
    'the upload boundary; NULL means no figure was logged, never 0.';

-- =============================================================================================
-- drive_reassignments: "these samples belong to a different car" correction rules.
-- =============================================================================================
create table if not exists public.drive_reassignments (
    id             uuid primary key default gen_random_uuid(),
    sync_id        text        not null unique check (length(trim(sync_id)) > 0),

    -- The car the window is currently attributed to.
    vehicle_id     uuid        not null references public.vehicles (id) on delete restrict,
    -- The car it should be attributed to instead. Deliberately the same FK shape as vehicle_id
    -- above, not a self-join trick - a correction naming its own vehicle_id as new_vehicle_id
    -- would be a no-op the app should never produce, not something this schema forbids outright,
    -- since a client retry of an already-applied correction is a legitimate no-op write.
    new_vehicle_id uuid        not null references public.vehicles (id) on delete restrict,

    -- Drive window, inclusive both ends - matches DriveReassignment.kt's own "a time RANGE, not a
    -- list of row ids" reasoning: if more samples land in this window later, from any device, the
    -- rule still catches them.
    from_at        timestamptz not null,
    to_at          timestamptz not null,

    -- A person corrected this in the car manager UI; not a code-derived fact.
    provenance     public.provenance not null default 'USER',
    created_at     timestamptz not null default now(),
    -- LWW clock: DriveReassignment.kt calls out re-stamping this on every edit so a later
    -- correction always wins over an earlier one.
    updated_at     timestamptz not null default now(),
    deleted_at     timestamptz,

    constraint drive_reassignments_window check (to_at >= from_at)
);

create index if not exists drive_reassignments_vehicle_idx
    on public.drive_reassignments (vehicle_id, from_at) where deleted_at is null;

comment on table public.drive_reassignments is
    'A correction rule, not a mutation of drives themselves: "samples in this window belong to '
    'new_vehicle_id, not vehicle_id." Must ship in the same wave as drives (20260826000200) so a '
    'fact and its correction never split across two systems - ticket 06''s ruling.';

-- =============================================================================================
-- Triggers and RLS for all seven tables. All are authored/observed fleet data, not gated
-- documents - no immutability trigger, matching every other table in the fleet aspect.
-- =============================================================================================
do $$
declare
    t text;
begin
    foreach t in array array[
        'public.code_events',
        'public.code_clear_events',
        'public.oil_analyses',
        'public.chassis_quirks',
        'public.vehicle_specs',
        'public.build_entries',
        'public.drive_reassignments'
    ] loop
        execute format('drop trigger if exists touch_updated_at on %s', t);
        execute format(
            'create trigger touch_updated_at before update on %s '
            'for each row execute function private.touch_updated_at()', t
        );
        execute format('select private.apply_household_rls(%L)', t);
    end loop;
end $$;
