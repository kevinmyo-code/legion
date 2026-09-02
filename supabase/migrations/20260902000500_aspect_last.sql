-- LEGION backend-erp / live-sync: the LAST aspect slice gets a Supabase home, end to end.
-- "give LEGION's lists, goals and grocery staples a Supabase home, end to end" - the FOURTH
-- aspect built off the body-supabase template (20260902000200_aspect_body.sql /
-- 20260902000300_aspect_memory.sql / 20260902000400_aspect_ledger_config.sql), copied from the
-- current file rather than an older description of it.
-- Depends on: 20260825000200_conventions.sql
--
-- Four tables across three domains (`.scratch/live-sync/map.md`'s "Lists"/"Goals"/"Pantry config"
-- rows, bundled into one migration/one interface for the same reason
-- 20260902000400_aspect_ledger_config.sql bundled three unrelated-ish tables): goals,
-- grocery_staples, item_lists, list_items.
--
-- **All four are AUTHORED, not gated** (same posture as body/memory/ledger-config - a stated goal,
-- a staple bought again, a hand-typed list item are things the app recorded, never a document that
-- came through the reconciliation gate), so every table gets `updated_at` and stays freely
-- editable/deletable - no `forbid_mutation_of_facts` trigger anywhere in this file.
--
-- =============================================================================================
-- ORIGIN_GUID: ALL FOUR TABLES REUSE AN EXISTING syncId COLUMN, NONE MINTS A FRESH guid
-- =============================================================================================
-- Unlike `categories`/`category_rules`/`budget_targets` (which had no portable identity column at
-- all), every one of `goals`/`grocery_staples`/`item_lists`/`list_items` already carried a
-- `syncId` column on the phone before this ticket - see each Room entity's own v63 doc comment.
-- `origin_guid` here IS that column's value, carried verbatim (Room migration v62 -> v63,
-- MIGRATION_62_63, dedups any pre-existing collision before the phone-side unique index goes on).
--
-- =============================================================================================
-- goals.supersedes_guid, not goals.supersedesId
-- =============================================================================================
-- Goal.supersedesId is a local autoincrement surrogate key with no portable meaning across two
-- devices' own copies of the same revision chain - the phone resolves it to the PRIOR revision's
-- own syncId at push time (see LastAspectsWriteThrough.goalPayload's own comment) and this column
-- stores that string, nullable for a lineage's first row. `lineage_id` is stored as the bigint it
-- already is on the phone (Goal.lineageId is UUID.randomUUID().leastSignificantBits - already a
-- portable random value, not a local autoincrement key), so it needs no equivalent translation.
--
-- =============================================================================================
-- list_items.list_origin_guid, not list_items.listId
-- =============================================================================================
-- Same non-portability reasoning as goals.supersedes_guid above, applied to a foreign key instead
-- of a self-reference: ListItem.listId is a local autoincrement surrogate against item_lists.id,
-- so this column is a TEXT foreign key against item_lists.origin_guid instead. See
-- LastAspectsBackend's own class doc for the full reasoning.

create table if not exists public.goals (
    id               uuid primary key default gen_random_uuid(),
    lineage_id       bigint      not null,
    aspect           text        not null,
    statement        text        not null,
    target_value     double precision,
    unit             text,
    metric_key       text,
    deadline_epoch   timestamptz,
    status           text        not null default 'active',
    supersedes_guid  text,
    closed_at        timestamptz,
    -- The phone's own write instant, carried verbatim - same created_at_client/created_at split
    -- category_rules already uses (20260902000400_aspect_ledger_config.sql's own header comment).
    created_at_client timestamptz not null,
    provenance       public.provenance not null default 'USER',
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    deleted_at       timestamptz,
    origin_guid      text        not null unique
);

create index if not exists goals_lineage_id_idx on public.goals (lineage_id);
create index if not exists goals_aspect_status_idx on public.goals (aspect, status) where deleted_at is null;

create table if not exists public.grocery_staples (
    id               uuid primary key default gen_random_uuid(),
    name             text        not null,
    display_name     text        not null,
    times_bought     integer     not null default 1,
    last_bought_at   timestamptz not null,
    provenance       public.provenance not null default 'USER',
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    deleted_at       timestamptz,
    origin_guid      text        not null unique,
    constraint grocery_staples_name_unique unique (name)
);

create table if not exists public.item_lists (
    id                uuid primary key default gen_random_uuid(),
    name              text        not null,
    tickable          boolean     not null default true,
    sort_order        integer     not null default 0,
    last_used_at      timestamptz not null,
    archived          boolean     not null default false,
    created_at_client timestamptz not null,
    provenance        public.provenance not null default 'USER',
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    deleted_at        timestamptz,
    origin_guid       text        not null unique
);

create table if not exists public.list_items (
    id                  uuid primary key default gen_random_uuid(),
    -- See this file's own header comment for why this is a TEXT foreign key against
    -- item_lists.origin_guid rather than an integer against item_lists.id.
    list_origin_guid    text        not null references public.item_lists (origin_guid),
    text                text        not null,
    done                boolean     not null default false,
    done_at             timestamptz,
    sort_order          integer     not null default 0,
    created_at_client   timestamptz not null,
    starts_at           timestamptz,
    ends_at             timestamptz,
    all_day             boolean     not null default true,
    trigger_place_label text,
    repeat_kind         text,
    repeat_every        integer,
    repeat_days_of_week text,
    repeat_day          integer,
    repeat_month        integer,
    repeat_end_kind     text,
    repeat_end_date     timestamptz,
    repeat_end_count    integer,
    exact               boolean     not null default false,
    exact_downgraded    boolean     not null default false,
    missed_at           timestamptz,
    missed_dismissed_at timestamptz,
    logged_at           timestamptz,
    provenance          public.provenance not null default 'USER',
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    deleted_at          timestamptz,
    origin_guid         text        not null unique
);

-- ---------------------------------------------------------------------------------------------
-- Read indexes. Each mirrors its own Room DAO's own hot path.
-- ---------------------------------------------------------------------------------------------
create index if not exists list_items_list_origin_guid_idx on public.list_items (list_origin_guid) where deleted_at is null;
create index if not exists list_items_starts_at_idx on public.list_items (starts_at) where deleted_at is null;

-- ---------------------------------------------------------------------------------------------
-- Triggers and RLS. Authored tables get updated_at and NOT the immutability trigger - see this
-- file's own header comment.
-- ---------------------------------------------------------------------------------------------
do $$
declare
    tbl text;
begin
    foreach tbl in array array['goals', 'grocery_staples', 'item_lists', 'list_items']
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
-- Realtime. All four join the publication in THIS migration - see
-- 20260902000100_events_realtime_publication.sql for why this step cannot be skipped (a
-- subscription to a table never added here looks refused rather than a subscription with nothing
-- to say). REPLICA IDENTITY left at its default - LastAspectsRealtime's postgres_changes event is
-- a trigger for LastAspectsSync.pull, never a data source, so the payload itself is never read.
-- ---------------------------------------------------------------------------------------------
alter publication supabase_realtime add table public.goals;
alter publication supabase_realtime add table public.grocery_staples;
alter publication supabase_realtime add table public.item_lists;
alter publication supabase_realtime add table public.list_items;
