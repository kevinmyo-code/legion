-- LEGION backend-erp: the body aspect gets a Supabase home, end to end.
-- Ticket: body-supabase ("give LEGION's body aspect a Supabase home, end to end - this is the
-- template for six more aspects").
-- Depends on: 20260825000200_conventions.sql
--
-- Eight tables, mirroring `app/src/main/java/com/kevin/legion/data/local/` field for field:
--   bodyweight_logs, meal_logs, meal_targets, sleep_logs, sleep_targets,
--   workout_plans, workout_plan_items, workout_set_logs.
--
-- All eight are AUTHORED, not gated (Kevin, 2026-08-25's ruling for notes/dates/places/fleet
-- extends here): a bodyweight reading or a meal log is a thing a person reported, never a document
-- that came through the reconciliation gate, so every table gets `updated_at` and stays freely
-- editable/deletable - no `forbid_mutation_of_facts` trigger anywhere in this file.
--
-- =============================================================================================
-- WHY `origin_guid` IS NOT NULL UNIQUE HERE, UNLIKE EVERY OTHER TABLE THAT HAS ONE
-- =============================================================================================
-- `20260826000100_origin_guid.sql` added a NULLABLE `origin_guid` to five tables as PHASE 4
-- MIGRATION PROVENANCE: null for a row created directly against the server, set only on a row
-- carried over from the phone's pre-cutover engine. That meaning does not apply here - body was
-- born with local sync columns already in mind (Room migration v59 -> v60, same ticket), so
-- EVERY row, from the very first one, is minted with a real client-side `guid` before it is ever
-- written locally. There is no "created directly against the server with no local ancestor" case
-- to leave null for, because every write reaches the server FROM a local row that already has one.
-- `origin_guid` is therefore this table's actual upsert key (`on conflict (origin_guid)`), not
-- migration bookkeeping, and it is declared `not null unique` to say so - a nullable, sometimes-
-- absent column would be the wrong shape for a key every write always supplies.
--
-- =============================================================================================
-- ESTIMATES TRAVEL AS ESTIMATES
-- =============================================================================================
-- CLAUDE.md section 4 rule 5: meal_logs.calories_kcal/protein_g/carbs_g/fat_g are the LLM's guess
-- from the meal's own description, never gated (a plate of food, unlike a receipt, never prints
-- its own calorie count - see MealLog.kt's own doc comment). They are nullable, exactly as the
-- Room column is, and are never summed into anything this schema treats as a reconciled figure.
-- The client is what must go on saying "estimate" out loud (CLAUDE.md's own words: "anything the
-- source does not state is an estimate and must be labelled as one") - this migration's job is
-- only to not launder that fact away by moving it, which is why `provenance` stays 'USER' (a
-- voice log is user-authored) rather than inventing a provenance value that would claim these
-- four columns are something the gate checked.

-- =============================================================================================
-- BODYWEIGHT
-- =============================================================================================
create table if not exists public.bodyweight_logs (
    id            uuid primary key default gen_random_uuid(),
    weight_value  double precision not null check (weight_value > 0),
    weight_unit   text        not null check (weight_unit in ('lbs', 'kg')),
    logged_at     timestamptz not null,
    trust_tier    text        not null check (trust_tier in ('PROVEN', 'REPORTED')),
    provenance    public.provenance not null default 'USER',
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    deleted_at    timestamptz,
    origin_guid   text        not null unique
);

comment on column public.bodyweight_logs.origin_guid is
    'This row''s real sync identity, minted client-side at write time - see this file''s own '
    'header comment for why this is NOT nullable migration provenance the way it is elsewhere.';

-- =============================================================================================
-- MEALS
-- =============================================================================================
create table if not exists public.meal_logs (
    id                uuid primary key default gen_random_uuid(),
    description       text        not null check (length(trim(description)) > 0),
    -- Estimates - see this file's own header comment. Never gated, never summed into a figure
    -- this schema treats as reconciled.
    calories_kcal     integer,
    protein_g         double precision,
    carbs_g           double precision,
    fat_g             double precision,
    logged_at         timestamptz not null,
    source_image_path text,
    trust_tier        text        not null check (trust_tier in ('PROVEN', 'REPORTED')),
    provenance        public.provenance not null default 'USER',
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    deleted_at        timestamptz,
    origin_guid       text        not null unique
);

comment on column public.meal_logs.calories_kcal is
    'An LLM estimate from the meal description, never printed by anything, never gated. Nullable '
    'because an extraction can legitimately fail to produce a usable number.';

create table if not exists public.meal_targets (
    id                        uuid primary key default gen_random_uuid(),
    calories_kcal             integer          not null check (calories_kcal > 0),
    protein_g                 double precision not null check (protein_g >= 0),
    carbs_g                   double precision not null check (carbs_g >= 0),
    fat_g                     double precision not null check (fat_g >= 0),
    -- The first day this target applies to - "copy forward", nothing ever deleted, matching
    -- MealTarget.effectiveFromDateEpoch's own local convention.
    effective_from_date       date             not null,
    provenance                public.provenance not null default 'USER',
    created_at                timestamptz not null default now(),
    updated_at                timestamptz not null default now(),
    deleted_at                timestamptz,
    origin_guid               text        not null unique,
    constraint meal_targets_effective_from_date_unique unique (effective_from_date)
);

-- =============================================================================================
-- SLEEP
-- =============================================================================================
create table if not exists public.sleep_logs (
    id               uuid primary key default gen_random_uuid(),
    -- The WAKE date, not the night the sleep started - matches SleepLog.sleepDate's own
    -- documented convention exactly (a night logged after waking counts under the day it ends).
    sleep_date       date        not null,
    duration_minutes integer     not null check (duration_minutes between 0 and 1440),
    quality          integer     check (quality between 1 and 5),
    notes            text,
    logged_at        timestamptz not null,
    trust_tier       text        not null check (trust_tier in ('PROVEN', 'REPORTED')),
    provenance       public.provenance not null default 'USER',
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    deleted_at       timestamptz,
    origin_guid      text        not null unique
);

create table if not exists public.sleep_targets (
    id                  uuid primary key default gen_random_uuid(),
    target_minutes      integer     not null check (target_minutes between 0 and 1440),
    effective_from_date date        not null,
    provenance          public.provenance not null default 'USER',
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    deleted_at          timestamptz,
    origin_guid         text        not null unique,
    constraint sleep_targets_effective_from_date_unique unique (effective_from_date)
);

-- =============================================================================================
-- WORKOUTS
-- =============================================================================================
create table if not exists public.workout_plans (
    id                     uuid primary key default gen_random_uuid(),
    sessions_per_week      integer     not null check (sessions_per_week >= 0),
    -- Monday of the first week this plan applies to - matches WorkoutPlan.effectiveFromWeekEpoch.
    effective_from_week    date        not null,
    provenance             public.provenance not null default 'USER',
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now(),
    deleted_at             timestamptz,
    origin_guid            text        not null unique,
    constraint workout_plans_effective_from_week_unique unique (effective_from_week)
);

create table if not exists public.workout_plan_items (
    id                     uuid primary key default gen_random_uuid(),
    exercise               text        not null check (length(trim(exercise)) > 0),
    target_sets_per_week   integer     not null check (target_sets_per_week > 0),
    effective_from_week    date        not null,
    -- Nullable, and never backfilled for an old row - matches WorkoutPlanItem.repsPerSet's own
    -- doc comment: a plan written before this field existed said nothing about reps.
    reps_per_set           integer,
    provenance             public.provenance not null default 'USER',
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now(),
    deleted_at             timestamptz,
    origin_guid            text        not null unique,
    constraint workout_plan_items_exercise_week_unique unique (exercise, effective_from_week)
);

create table if not exists public.workout_set_logs (
    id           uuid primary key default gen_random_uuid(),
    exercise     text        not null check (length(trim(exercise)) > 0),
    sets         integer     not null check (sets > 0),
    reps         integer     check (reps is null or reps > 0),
    weight_value double precision check (weight_value is null or weight_value >= 0),
    weight_unit  text        check (weight_unit is null or weight_unit in ('lbs', 'kg')),
    logged_at    timestamptz not null,
    trust_tier   text        not null check (trust_tier in ('PROVEN', 'REPORTED')),
    provenance   public.provenance not null default 'USER',
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    deleted_at   timestamptz,
    origin_guid  text        not null unique
    -- WorkoutSetLog.sourceListItemId (the ListItem id a swept checklist tick produced this row
    -- from) is deliberately NOT carried here - it names a phone-local Room row id from a
    -- different, not-yet-migrated table, and would be a dangling reference server-side with
    -- nothing to point at. The dedup/untick logic it supports stays entirely local.
);

-- ---------------------------------------------------------------------------------------------
-- Read indexes. Each active-row query mirrors its own Room DAO's own hot path.
-- ---------------------------------------------------------------------------------------------
create index if not exists bodyweight_logs_logged_at_idx on public.bodyweight_logs (logged_at) where deleted_at is null;
create index if not exists meal_logs_logged_at_idx        on public.meal_logs (logged_at)        where deleted_at is null;
create index if not exists sleep_logs_sleep_date_idx       on public.sleep_logs (sleep_date)       where deleted_at is null;
create index if not exists workout_set_logs_logged_at_idx  on public.workout_set_logs (logged_at)  where deleted_at is null;
create index if not exists workout_set_logs_exercise_idx   on public.workout_set_logs (exercise)   where deleted_at is null;

-- ---------------------------------------------------------------------------------------------
-- Triggers and RLS. Authored tables get updated_at and NOT the immutability trigger - see this
-- file's own header comment.
-- ---------------------------------------------------------------------------------------------
do $$
declare
    tbl text;
begin
    foreach tbl in array array[
        'bodyweight_logs', 'meal_logs', 'meal_targets', 'sleep_logs', 'sleep_targets',
        'workout_plans', 'workout_plan_items', 'workout_set_logs'
    ]
    loop
        execute format('drop trigger if exists touch_updated_at on public.%I', tbl);
        execute format(
            'create trigger touch_updated_at before update on public.%I '
            'for each row execute function private.touch_updated_at()',
            tbl
        );
        perform private.apply_household_rls(format('public.%I', tbl)::regclass);
    end loop;
end $$;

-- ---------------------------------------------------------------------------------------------
-- Realtime. All eight join the publication in this same migration - EventsRealtime's own history
-- (`20260902000100_events_realtime_publication.sql`) is exactly why: a table is never added to
-- `supabase_realtime` automatically, and shipping the schema without this step produces a
-- subscription that looks refused rather than a subscription with nothing to say, discovered only
-- by running against a real project. REPLICA IDENTITY is left at its default for the same reason
-- stated there - BodyRealtime's postgres_changes event is a trigger for BodySync.pull, never a
-- data source, so the payload itself is never read.
-- ---------------------------------------------------------------------------------------------
alter publication supabase_realtime add table public.bodyweight_logs;
alter publication supabase_realtime add table public.meal_logs;
alter publication supabase_realtime add table public.meal_targets;
alter publication supabase_realtime add table public.sleep_logs;
alter publication supabase_realtime add table public.sleep_targets;
alter publication supabase_realtime add table public.workout_plans;
alter publication supabase_realtime add table public.workout_plan_items;
alter publication supabase_realtime add table public.workout_set_logs;
