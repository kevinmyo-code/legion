-- LEGION backend-erp / live-sync: the ledger CONFIG aspect gets a Supabase home, end to end.
-- Ticket: ledger-config-supabase ("give LEGION's ledger CONFIG a Supabase home, end to end - this
-- is the THIRD aspect built off the body-supabase template,
-- `20260902000200_aspect_body.sql`/`20260902000300_aspect_memory.sql`").
-- Depends on: 20260825000200_conventions.sql
--
-- Three tables, mirroring `app/src/main/java/com/kevin/legion/data/local/` field for field:
--   categories, category_rules, budget_targets.
--
-- **`ledger_transactions` is explicitly OUT OF SCOPE.** It already has a server table
-- (`20260825000300_aspect_ledger_pantry.sql`) and its own upload path (`LedgerReconcile`), and 161
-- of its rows are deliberately blocked by section 4 of CLAUDE.md (never persisted server-side -
-- see `.scratch/live-sync/map.md`'s "written off" note). Nothing in this migration touches that
-- table or its RLS/triggers.
--
-- These three are "the categorisation rules and budgets Kevin built by hand" (the map's own words)
-- - irreplaceable in the sense that re-deriving them means redoing that work, which is why they
-- get a server home at all rather than being left to regenerate.
--
-- All three are AUTHORED, not gated (same posture as body/memory - a hand-typed category or a
-- confirmed categorisation rule is a thing the app recorded, never a document that came through
-- the reconciliation gate), so every table gets `updated_at` and stays freely editable/deletable -
-- no `forbid_mutation_of_facts` trigger anywhere in this file.
--
-- =============================================================================================
-- ORIGIN_GUID: ALL THREE TABLES GET A FRESHLY-MINTED GUID, NONE HAD ONE TO REUSE
-- =============================================================================================
-- Unlike memory's `memories`/`companion_memories` (which reused an existing `syncId` column),
-- none of `categories`/`category_rules`/`budget_targets` had ANY portable identity column before
-- this ticket - see each Room entity's own v62 doc comment. `origin_guid` is `not null unique`
-- here for the same reason body/memory's is: every row, from the very first one, is minted with a
-- real client-side `guid` before it is ever written locally (Room migration v61 -> v62,
-- MIGRATION_61_62, backfills every pre-existing row the same way MIGRATION_59_60/MIGRATION_60_61
-- backfill theirs), so there is no "created directly against the server with no local ancestor"
-- case to leave null for.
--
-- =============================================================================================
-- category_rules.created_at_client vs created_at
-- =============================================================================================
-- `created_at` (below) is this convention's own server-insert timestamp, stamped by Postgres at
-- `default now()` - the same column every other table in this file has. `created_at_client`
-- carries CategoryRule.createdAt VERBATIM from the phone - the instant the rule was actually
-- written there, which [com.kevin.legion.ledger.LedgerController.applyCategoryRules] orders rules
-- BY, oldest first (see that Room entity's own doc comment for why order is load-bearing: the
-- earliest rule written for a merchant is the one that governs it). Reusing the server's own
-- `created_at` for that ordering would silently reorder every rule around whenever it happened to
-- reach the server rather than when it was actually written - exactly the "two owners for one
-- fact" shape CLAUDE.md section 4 rule 8 warns against for a different pair of columns.
--
-- =============================================================================================
-- budget_targets.effective_from_month IS A DATE, NOT A TIMESTAMPTZ
-- =============================================================================================
-- Matches `meal_targets.effective_from_date`/`sleep_targets.effective_from_date`'s own precedent
-- in `20260902000200_aspect_body.sql`: BudgetTarget.effectiveFromMonthEpoch is always a UTC
-- month-start instant by convention (every ledger parser's `atStartOfDay(ZoneOffset.UTC)`), so a
-- bare `date` round-trips it exactly with no timezone ambiguity, and the phone-side unique key
-- (`category`, `currency`, `effectiveFromMonthEpoch`) becomes (`category`, `currency`,
-- `effective_from_month`) here.

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

comment on column public.categories.origin_guid is
    'Freshly minted (Category.guid) - this table had no existing identity column to reuse, unlike '
    'memories/companion_memories. See that field''s own v62 doc comment.';

create table if not exists public.category_rules (
    id                uuid primary key default gen_random_uuid(),
    category          text        not null,
    substring         text        not null,
    -- The phone's own write instant, carried verbatim - see this file's own header comment for why
    -- this is a separate column from `created_at` below.
    created_at_client timestamptz not null,
    provenance        public.provenance not null default 'USER',
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    deleted_at        timestamptz,
    origin_guid       text        not null unique
);

create table if not exists public.budget_targets (
    id                      uuid primary key default gen_random_uuid(),
    category                text        not null,
    currency                text        not null check (currency in ('SGD', 'USD')),
    amount_cents            bigint      not null,
    -- The first month this target applies to - "copy forward", nothing ever deleted, matching
    -- BudgetTarget.effectiveFromMonthEpoch's own local convention. See this file's own header
    -- comment for why this is a bare `date`.
    effective_from_month    date        not null,
    provenance              public.provenance not null default 'USER',
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now(),
    deleted_at              timestamptz,
    origin_guid             text        not null unique,
    constraint budget_targets_category_currency_month_unique unique (category, currency, effective_from_month)
);

-- ---------------------------------------------------------------------------------------------
-- Read indexes. Each active-row query mirrors its own Room DAO's own hot path.
-- ---------------------------------------------------------------------------------------------
create index if not exists category_rules_substring_idx on public.category_rules (substring) where deleted_at is null;
create index if not exists budget_targets_category_currency_idx on public.budget_targets (category, currency) where deleted_at is null;

-- ---------------------------------------------------------------------------------------------
-- Triggers and RLS. Authored tables get updated_at and NOT the immutability trigger - see this
-- file's own header comment.
-- ---------------------------------------------------------------------------------------------
do $$
declare
    tbl text;
begin
    foreach tbl in array array['categories', 'category_rules', 'budget_targets']
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
-- Realtime. All three join the publication in THIS migration - `20260902000100_events_realtime_publication.sql`
-- is exactly why: a table is never added to `supabase_realtime` automatically, and shipping the
-- schema without this step produces a subscription that looks refused rather than a subscription
-- with nothing to say. REPLICA IDENTITY left at its default - LedgerConfigRealtime's
-- postgres_changes event is a trigger for LedgerConfigSync.pull, never a data source, so the
-- payload itself is never read.
-- ---------------------------------------------------------------------------------------------
alter publication supabase_realtime add table public.categories;
alter publication supabase_realtime add table public.category_rules;
alter publication supabase_realtime add table public.budget_targets;
