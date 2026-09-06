"""Test-database-only DDL for the `legacy` tables Phase 5 writes to.

## Why this file exists at all

`legacy` is `managed = False` with `MIGRATION_MODULES = {"legacy": None}`
(`legion/settings.py`) - Supabase's own migrations own those tables' DDL,
on purpose, so Django never touches it. That is exactly right for the LIVE
database and exactly wrong for pytest's own ephemeral one, which
`django_db_setup` creates empty and migrates with Django's migrations
alone: `legacy` contributes none, so a fresh test database has no
`public.places`, no `public.memories`, and so on. Every write comes back as
a 400 whose `save_or_400` message is `relation "places" does not exist`,
which looks exactly like a validation refusal until you read it.

`tests/conftest.py` hit this first for `public.events` and mirrored that
one table inline, with a note: "every future ticket that writes to a
`legacy` table (ledger, pantry, fleet in execution-plan.md's Phase 5) hits
this exact same wall and will want the same pattern, or a shared one.
Flagged in this ticket's own final report rather than generalised here."
This module is that shared one, for the four aspects Phase 5 routes. The
events SQL stays where it is in `conftest.py`, with its own comment intact.

## What this is a mirror OF

Copied from the `supabase/migrations/` files that define these tables on
the live database, and checked column by column against the live schema
through `legion_reader` on 2026-09-06:

- `places`: `20260825000500_aspect_places_fleet.sql`
- `voice_notes`: `20260901000100_voice_notes.sql`
- the eight body tables: `20260902000200_aspect_body.sql`
- the three memory tables: `20260902000300_aspect_memory.sql`

Dropped from the copy, deliberately: `alter publication supabase_realtime`
(no Realtime here, and ADR 0044 retires it anyway), `private.apply_household_rls`
(Django connects as the engine role, which is `bypassrls`; there are no
policies in a test database to bypass), the read indexes (they change no
result, only a plan), and `comment on column` (the reasoning they carry is
in the migration files, which are the thing to read).

Kept, because a test without them would be testing a database this app does
not have: every CHECK constraint, every unique constraint, and the
`touch_updated_at` trigger. The trigger in particular is load-bearing for
this ticket - `updated_at` moving on UPDATE is the whole basis of the
`?since=` feed.

## This never runs anywhere but a test database

Guarded twice, the same way the events SQL is: it is layered on top of
pytest-django's `django_db_setup`, which only ever targets the test
database it just created, AND `conftest.apply_legacy_test_schema` refuses
to execute unless the connected database's own name contains "test".
"""
from __future__ import annotations

# `create or replace` and `if not exists` throughout, so re-running against
# a `--reuse-db` database is a no-op rather than an error. The enum guard is
# a `do $$` block because Postgres has no `create type if not exists`.
LEGACY_PHASE5_TEST_SCHEMA_SQL = """
create schema if not exists private;

do $$
begin
    if not exists (select 1 from pg_type where typname = 'provenance') then
        create type public.provenance as enum
            ('DETERMINISTIC', 'LLM_RECONCILED', 'UNRECONCILED', 'USER');
    end if;
end $$;

create or replace function private.touch_updated_at()
    returns trigger
    language plpgsql
    set search_path = ''
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

-- ============================================================================================
-- PLACES (20260825000500_aspect_places_fleet.sql). No origin_guid - `label` is the natural key.
-- ============================================================================================
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

-- ============================================================================================
-- VOICE NOTES (20260901000100_voice_notes.sql). No origin_guid, no audio column.
-- ============================================================================================
create table if not exists public.voice_notes (
    id           uuid primary key default gen_random_uuid(),
    started_at   timestamptz not null,
    ended_at     timestamptz,
    title        text,
    summary      text,
    transcript   text,
    kind         text        not null check (kind in ('SOLO', 'MEETING')),
    provenance   text        not null default 'LLM_DERIVED' check (provenance = 'LLM_DERIVED'),
    interrupted  boolean     not null default false,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    deleted_at   timestamptz,
    constraint voice_notes_summary_needs_transcript
        check (summary is null or transcript is not null)
);

-- ============================================================================================
-- BODY, all eight (20260902000200_aspect_body.sql)
-- ============================================================================================
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

create table if not exists public.meal_logs (
    id                uuid primary key default gen_random_uuid(),
    description       text        not null check (length(trim(description)) > 0),
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

create table if not exists public.meal_targets (
    id                        uuid primary key default gen_random_uuid(),
    calories_kcal             integer          not null check (calories_kcal > 0),
    protein_g                 double precision not null check (protein_g >= 0),
    carbs_g                   double precision not null check (carbs_g >= 0),
    fat_g                     double precision not null check (fat_g >= 0),
    effective_from_date       date             not null,
    provenance                public.provenance not null default 'USER',
    created_at                timestamptz not null default now(),
    updated_at                timestamptz not null default now(),
    deleted_at                timestamptz,
    origin_guid               text        not null unique,
    constraint meal_targets_effective_from_date_unique unique (effective_from_date)
);

create table if not exists public.sleep_logs (
    id               uuid primary key default gen_random_uuid(),
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

create table if not exists public.workout_plans (
    id                     uuid primary key default gen_random_uuid(),
    sessions_per_week      integer     not null check (sessions_per_week >= 0),
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
);

-- ============================================================================================
-- MEMORY, all three (20260902000300_aspect_memory.sql)
-- ============================================================================================
create table if not exists public.memories (
    id           uuid primary key default gen_random_uuid(),
    text         text        not null check (length(trim(text)) > 0),
    logged_at    timestamptz not null,
    provenance   public.provenance not null default 'USER',
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    deleted_at   timestamptz,
    origin_guid  text        not null unique
);

create table if not exists public.companion_memories (
    id                uuid primary key default gen_random_uuid(),
    vehicle_id        text        not null,
    text              text        not null check (length(trim(text)) > 0),
    category          text        not null
                      check (category in ('car_anchored', 'driver', 'relationship')),
    source            text        not null
                      check (source in ('consolidated', 'reflection', 'stated')),
    importance        integer     not null default 5 check (importance between 1 and 10),
    logged_at         timestamptz not null,
    last_accessed_at  timestamptz,
    provenance        public.provenance not null default 'USER',
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    deleted_at        timestamptz,
    origin_guid       text        not null unique
);

create table if not exists public.memory_audit (
    id           uuid primary key default gen_random_uuid(),
    event        text        not null
                 check (event in ('written', 'deleted', 'recall', 'recalled', 'spoken')),
    store        text        not null
                 check (store in ('memories', 'companion_memories', 'speech')),
    detail       text        not null,
    ref_id       bigint,
    vehicle_id   text,
    logged_at    timestamptz not null,
    provenance   public.provenance not null default 'USER',
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    deleted_at   timestamptz,
    origin_guid  text        not null unique
);

-- ============================================================================================
-- Triggers. Every table above is AUTHORED, not gated: touch_updated_at and never
-- private.forbid_mutation_of_facts - both source migrations say so in their own headers.
-- ============================================================================================
do $$
declare
    tbl text;
begin
    foreach tbl in array array[
        'places', 'voice_notes',
        'bodyweight_logs', 'meal_logs', 'meal_targets', 'sleep_logs', 'sleep_targets',
        'workout_plans', 'workout_plan_items', 'workout_set_logs',
        'memories', 'companion_memories', 'memory_audit'
    ]
    loop
        execute format('drop trigger if exists touch_updated_at on public.%I', tbl);
        execute format(
            'create trigger touch_updated_at before update on public.%I '
            'for each row execute function private.touch_updated_at()',
            tbl
        );
    end loop;
end $$;
"""
