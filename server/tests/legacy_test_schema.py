"""Test-database-only DDL for the `legacy` tables the server writes to.

**This module holds THREE constants now, and its title used to say "the tables
Phase 5 writes to".** `LEGACY_PHASE5_TEST_SCHEMA_SQL` is that original set
(places, voice notes, the eight body tables, the three memory tables) and
everything below describes it. `LEGACY_INGEST_TEST_SCHEMA_SQL` is
django-engine ticket 03's: the five ledger and pantry tables the section 4
gate writes, with the `forbid_mutation_of_facts` trigger that makes a gated row
immutable. `LEGACY_LEDGER_PANTRY_CONFIG_TEST_SCHEMA_SQL` at the foot of the
file is the ledger/pantry API ticket's: the four AUTHORED tables of those two
aspects (`categories`, `category_rules`, `budget_targets`, `grocery_staples`),
which the gate never touches and which stay freely editable. Each has its own
header covering what it mirrors and what it deliberately leaves out. The
`django_db_setup` fixture in `conftest.py` applies all three.

**That last sentence used to read "`conftest.apply_legacy_test_schema` applies
both"**, when there were two blocks; the count is spelled out rather than left
as "all of them" because a reader checking whether their own table is covered
wants to know how many blocks to look through.

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


# ============================================================================
# The five tables django-engine ticket 03 (the gate in Python) writes.
#
# `tests/conftest.py`'s own note said this wall was coming: "every future
# ticket that writes to a `legacy` table (ledger, pantry, fleet in
# execution-plan.md's Phase 5) hits this exact same wall". This is the ledger
# and pantry half of it. Fleet still owes its own block.
#
# Copied from the `supabase/migrations/` files that define these tables on the
# live database, then checked column by column and constraint by constraint
# against the live schema through `information_schema.columns` and
# `pg_constraint` on 2026-09-07 - which is how the three post-hoc ALTERs got
# folded in rather than missed:
#
# - base tables and both triggers: `20260825000200_conventions.sql`,
#   `20260825000300_aspect_ledger_pantry.sql`
# - `origin_guid` and its unique indexes: `20260826000100_origin_guid.sql`
# - `receipts.unaccounted_cents` and its two checks:
#   `20260826000300_receipt_unaccounted.sql` and
#   `20260826000500_receipts_allow_unreconciled.sql`
# - `statements.stated_total_cents` losing NOT NULL, and the scope guard that
#   replaced it: `20260827000300_commit_statement_deterministic_two_anchor.sql`
#
# **What is deliberately NOT copied: `public.commit_statement`,
# `public.commit_receipt`, `private.quarantine_file` and
# `private.ledger_resolve_dedup`.** Those four ARE the gate, and ticket 03
# moves the gate into Python (ADR 0044 decision 2). Mirroring them here would
# mean maintaining a fifth copy of the arithmetic in the one file whose whole
# job is to be a copy of something else.
#
# **What IS copied, and is load-bearing rather than decoration:
# `private.forbid_mutation_of_facts` and its trigger on all four gated
# tables.** ADR 0044 decision 1 keeps that rule in SQL precisely because it has
# to hold even when Django has a bug, so a test database without it would be a
# laxer database than the one this app ships against - and
# `test_ingest_api.py` asserts the refusal directly.
# ============================================================================
LEGACY_INGEST_TEST_SCHEMA_SQL = """
create schema if not exists private;

do $$
begin
    if not exists (select 1 from pg_type where typname = 'provenance') then
        create type public.provenance as enum
            ('DETERMINISTIC', 'LLM_RECONCILED', 'UNRECONCILED', 'USER');
    end if;
    if not exists (select 1 from pg_type where typname = 'ingest_state') then
        create type public.ingest_state as enum (
            'NEW', 'INGESTED', 'QUARANTINED', 'UNREADABLE', 'DUPLICATE_CONTENT', 'NEEDS_LLM'
        );
    end if;
end $$;

-- Blocks every UPDATE, and blocks DELETE except on UNRECONCILED rows - rule 7
-- defines a provisional row as transient and never asserted as fact, so
-- superseding one is a delete rather than a reversal.
create or replace function private.forbid_mutation_of_facts()
    returns trigger
    language plpgsql
    set search_path = ''
as $$
begin
    if tg_op = 'UPDATE' then
        raise exception
            'Row % in % is immutable: it came through the reconciliation gate. Post a reversal '
            '(a new row with reversal_of set) and a replacement instead of editing it.',
            old.id, tg_table_name
            using errcode = 'restrict_violation';
    end if;

    if tg_op = 'DELETE' then
        if old.provenance = 'UNRECONCILED'::public.provenance then
            return old;
        end if;
        raise exception
            'Row % in % is immutable: it came through the reconciliation gate and is not '
            'provisional. Post a reversal instead of deleting it.',
            old.id, tg_table_name
            using errcode = 'restrict_violation';
    end if;

    return null;
end;
$$;

-- ============================================================================
-- ingested_files. The per-file ingestion ledger both aspects hang off, and the
-- one table here with NO forbid_mutation trigger: its state and quarantine
-- reason are updated by every commit and every retry.
-- ============================================================================
create table if not exists public.ingested_files (
    id                uuid primary key default gen_random_uuid(),
    content_sha256    text        not null unique,
    source_file_id    text,
    display_name      text,
    size_bytes        bigint,
    state             public.ingest_state not null default 'NEW',
    quarantine_reason text,
    first_seen_at     timestamptz not null default now(),
    last_attempt_at   timestamptz not null default now()
);

-- ============================================================================
-- LEDGER
-- ============================================================================
create table if not exists public.statements (
    id                 uuid primary key default gen_random_uuid(),
    ingested_file_id   uuid not null references public.ingested_files (id) on delete restrict,
    account_last4      text        not null check (account_last4 ~ '^[0-9]{4}$'),
    account_nickname   text        not null check (length(trim(account_nickname)) > 0),
    currency           text        not null check (currency in ('SGD', 'USD')),
    period_start       date        not null,
    period_end         date        not null,
    -- Nullable as of 2026-08-27, guarded by the scope-guard check below: only a
    -- deterministically parsed statement may omit the printed total, and it is
    -- stored NULL rather than synthesised from sum(lines).
    stated_total_cents bigint,
    opening_balance_cents bigint   not null,
    closing_balance_cents bigint   not null,
    provenance         public.provenance not null,
    created_at         timestamptz not null default now(),
    constraint statements_period_ordered check (period_end >= period_start),
    constraint statements_not_provisional check (provenance <> 'UNRECONCILED'),
    constraint statements_total_only_null_if_deterministic check (
        stated_total_cents is not null or provenance = 'DETERMINISTIC'
    ),
    constraint statements_one_per_file unique (ingested_file_id, account_last4)
);

create table if not exists public.ledger_transactions (
    id                uuid primary key default gen_random_uuid(),
    statement_id      uuid references public.statements (id) on delete restrict,
    account_last4     text        not null check (account_last4 ~ '^[0-9]{4}$'),
    account_nickname  text        not null,
    currency          text        not null check (currency in ('SGD', 'USD')),
    txn_date          date        not null,
    description       text        not null,
    amount_cents      bigint      not null,
    balance_cents     bigint,
    line_ref          text        not null,
    category          text,
    category_pending  boolean     not null default true,
    pending_logged_at timestamptz,
    reversal_of       uuid references public.ledger_transactions (id) on delete restrict,
    provenance        public.provenance not null,
    created_at        timestamptz not null default now(),
    origin_guid       text,
    constraint ledger_txn_header_matches_provenance check (
        (provenance = 'UNRECONCILED' and statement_id is null)
        or (provenance <> 'UNRECONCILED' and statement_id is not null)
    ),
    constraint ledger_txn_reversal_not_provisional check (
        reversal_of is null or provenance <> 'UNRECONCILED'
    )
);

create unique index if not exists ledger_transactions_origin_guid_idx
    on public.ledger_transactions (origin_guid);

-- ============================================================================
-- PANTRY
-- ============================================================================
create table if not exists public.receipts (
    id                 uuid primary key default gen_random_uuid(),
    ingested_file_id   uuid references public.ingested_files (id) on delete restrict,
    store              text        not null,
    purchase_date      date        not null,
    currency           text        not null check (currency in ('SGD', 'USD')),
    total_cents        bigint      not null,
    subtotal_cents     bigint,
    tax_cents          bigint,
    other_charges_cents bigint,
    photo_object_path  text,
    provenance         public.provenance not null,
    created_at         timestamptz not null default now(),
    origin_guid        text,
    -- Rule 7's 2026-08-26 amendment. The unexplained amount gets its own column
    -- and is never stored as tax: `tax := total - sum(lines)` would make the
    -- anchor an identity and would silently absorb a genuinely missed line.
    unaccounted_cents  bigint,
    constraint receipts_not_provisional check (
        provenance <> 'UNRECONCILED' or unaccounted_cents is not null
    ),
    constraint receipts_unaccounted_requires_unreconciled check (
        unaccounted_cents is null
        or (unaccounted_cents <> 0 and provenance = 'UNRECONCILED')
    )
);

create unique index if not exists receipts_origin_guid_idx on public.receipts (origin_guid);

create table if not exists public.receipt_line_items (
    id                uuid primary key default gen_random_uuid(),
    receipt_id        uuid        not null references public.receipts (id) on delete cascade,
    name              text        not null,
    quantity          numeric     not null check (quantity > 0),
    unit_price_cents  bigint,
    total_price_cents bigint      not null,
    -- ESTIMATES. Section 4 rule 5: excluded from every reconciliation check,
    -- and every surface that renders one must say "estimate".
    estimated_calories_kcal numeric,
    estimated_protein_g     numeric,
    estimated_carbs_g       numeric,
    estimated_fat_g         numeric,
    reversal_of       uuid references public.receipt_line_items (id) on delete restrict,
    provenance        public.provenance not null,
    created_at        timestamptz not null default now(),
    origin_guid       text
);

create unique index if not exists receipt_line_items_origin_guid_idx
    on public.receipt_line_items (origin_guid);

-- ============================================================================
-- Immutability for the four gated tables. NOT ingested_files - see its comment.
-- ============================================================================
do $$
declare
    tbl text;
begin
    foreach tbl in array array[
        'statements', 'ledger_transactions', 'receipts', 'receipt_line_items'
    ]
    loop
        execute format('drop trigger if exists forbid_mutation on public.%I', tbl);
        execute format(
            'create trigger forbid_mutation before update or delete on public.%I '
            'for each row execute function private.forbid_mutation_of_facts()',
            tbl
        );
    end loop;
end $$;
"""


# ============================================================================
# The four AUTHORED tables of the ledger and pantry aspects.
#
# The block above holds the five tables the section 4 GATE writes. These four
# are the other half of the same two aspects and are the opposite kind of
# thing: `20260902000400_aspect_ledger_config.sql`'s own header calls them
# AUTHORED - "a hand-typed category or a confirmed categorisation rule is a
# thing the app recorded, never a document that came through the
# reconciliation gate" - so they get `updated_at`, a `deleted_at` tombstone,
# and NO `forbid_mutation_of_facts` trigger. That contrast is why they are a
# separate constant rather than appended to the one above: the two blocks are
# governed by different rules, and a reader should not have to work out which
# half of one block a table falls in.
#
# Copied from the `supabase/migrations/` files that define them on the live
# database, then checked column by column, constraint by constraint and
# trigger by trigger against the live schema through
# `information_schema.columns`, `pg_constraint` and `pg_trigger` on
# 2026-09-07:
#
# - `categories`, `category_rules`, `budget_targets`:
#   `20260902000400_aspect_ledger_config.sql`
# - `grocery_staples`: `20260902000500_aspect_last.sql`
#
# Dropped from the copy, deliberately and for the same reasons the first block
# states: `alter publication supabase_realtime`, `private.apply_household_rls`,
# the two partial read indexes (`category_rules_substring_idx`,
# `budget_targets_category_currency_idx` - they change no result, only a plan)
# and `comment on column`.
#
# Kept, because a test without them would be testing a database this app does
# not have: every unique constraint (including the compound
# `budget_targets_category_currency_month_unique`, which is what makes a second
# target for one category/currency/month a refusal rather than a duplicate),
# the one CHECK these four tables have between them (`budget_targets.currency`),
# and the `touch_updated_at` trigger - load-bearing again, since `updated_at`
# moving on UPDATE is the whole basis of the `?since=` feed.
# ============================================================================
LEGACY_LEDGER_PANTRY_CONFIG_TEST_SCHEMA_SQL = """
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

-- ============================================================================
-- LEDGER CONFIG (20260902000400_aspect_ledger_config.sql)
-- ============================================================================
create table if not exists public.categories (
    id               uuid primary key default gen_random_uuid(),
    name             text        not null,
    is_food_category boolean     not null,
    provenance       public.provenance not null default 'USER',
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    deleted_at       timestamptz,
    origin_guid      text        not null unique,
    constraint categories_name_unique unique (name)
);

create table if not exists public.category_rules (
    id                uuid primary key default gen_random_uuid(),
    category          text        not null,
    substring         text        not null,
    -- The phone's own write instant, carried verbatim. NOT the same fact as
    -- `created_at` below: LedgerController.applyCategoryRules orders rules by
    -- this column, oldest first, so the server's insert clock cannot stand in
    -- for it. See the migration's own header.
    created_at_client timestamptz not null,
    provenance        public.provenance not null default 'USER',
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    deleted_at        timestamptz,
    origin_guid       text        not null unique
);

create table if not exists public.budget_targets (
    id                   uuid primary key default gen_random_uuid(),
    category             text        not null,
    currency             text        not null check (currency in ('SGD', 'USD')),
    amount_cents         bigint      not null,
    -- A bare `date`, not a timestamptz - BudgetTarget.effectiveFromMonthEpoch is
    -- always a UTC month-start instant by convention, so a date round-trips it
    -- with no timezone ambiguity.
    effective_from_month date        not null,
    provenance           public.provenance not null default 'USER',
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now(),
    deleted_at           timestamptz,
    origin_guid          text        not null unique,
    constraint budget_targets_category_currency_month_unique
        unique (category, currency, effective_from_month)
);

-- ============================================================================
-- PANTRY CONFIG (20260902000500_aspect_last.sql). `grocery_items` is NOT here:
-- nothing syncs it, the trip list retired into checklists on 2026-09-05, and no
-- route in this API reads or writes it.
-- ============================================================================
create table if not exists public.grocery_staples (
    id             uuid primary key default gen_random_uuid(),
    name           text        not null,
    display_name   text        not null,
    times_bought   integer     not null default 1,
    last_bought_at timestamptz not null,
    provenance     public.provenance not null default 'USER',
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    deleted_at     timestamptz,
    origin_guid    text        not null unique,
    constraint grocery_staples_name_unique unique (name)
);

-- ============================================================================
-- updated_at on all four. No forbid_mutation trigger anywhere in this block -
-- see its header for why that is the whole distinction.
-- ============================================================================
do $$
declare
    tbl text;
begin
    foreach tbl in array array[
        'categories', 'category_rules', 'budget_targets', 'grocery_staples'
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
