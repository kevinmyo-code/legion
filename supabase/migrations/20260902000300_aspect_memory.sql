-- LEGION backend-erp / live-sync: the memory aspect gets a Supabase home, end to end.
-- Ticket: memory-supabase ("give LEGION's memory aspect a Supabase home, end to end - this is the
-- SECOND aspect built off the body-supabase template, `20260902000200_aspect_body.sql`").
-- Depends on: 20260825000200_conventions.sql
--
-- Three tables, mirroring `app/src/main/java/com/kevin/legion/data/local/` field for field:
--   memories, companion_memories, memory_audit.
--
-- All three are AUTHORED, not gated (same posture as body - a remembered fact or a spoken line is
-- a thing the app recorded, never a document that came through the reconciliation gate), so every
-- table gets `updated_at` and stays freely editable/deletable - no `forbid_mutation_of_facts`
-- trigger anywhere in this file.
--
-- =============================================================================================
-- ORIGIN_GUID: TWO TABLES REUSE AN EXISTING PHONE-SIDE COLUMN, ONE DOES NOT
-- =============================================================================================
-- `memories`/`companion_memories` already carry a client-minted `syncId` column on the phone
-- (present since long before this migration, never previously wired to anything) - see
-- `MemoryEntry.syncId`'s own v61 doc comment for why that column, not a new `guid`, is what this
-- schema's `origin_guid` maps onto. `memory_audit` has no such column, so its `origin_guid` is
-- backed by a freshly-minted phone-side `guid` instead - see `MemoryAudit.guid`'s own v61 doc
-- comment. Either way, `origin_guid` is `not null unique` here for the identical reason
-- `20260902000200_aspect_body.sql`'s own header comment gives: every row, from the very first one,
-- is minted with a real client-side identity before it is ever written locally, so there is no
-- "created directly against the server with no local ancestor" case to leave null for.
--
-- =============================================================================================
-- embedding_vector / embedding_model DO NOT TRAVEL HERE - DELIBERATELY
-- =============================================================================================
-- `CompanionMemory.embeddingVector`/`.embeddingModel` are a pure function of this same row's own
-- `text` column, which DOES travel - CLAUDE.md's regenerate test (`.scratch/live-sync/map.md`)
-- says an embedding re-derives from the text it was computed from, so shipping the (potentially
-- large, model-pinned) vector itself would upload something the `text` column alone can reproduce.
-- `companion_memories` below therefore has no `embedding_vector`/`embedding_model` columns at all.
--
-- =============================================================================================
-- WHY memory_audit HAS NO DELETE PATH HERE
-- =============================================================================================
-- Nothing on the phone ever soft-deletes an individual `memory_audit` row - see that table's own
-- `MemoryAudit.deleted` doc comment. `deleted_at` is still declared below (the generic merge the
-- phone runs treats all three tables identically), but no RPC/policy here assumes it will ever be
-- set by anything other than a human operating on Postgres directly.
--
-- =============================================================================================
-- CLAUDE.md sec 7 - READ-THROUGH CONTENT NEVER LEAVES THE DEVICE
-- =============================================================================================
-- Traced before this migration was written, not assumed: every write site for these three tables
-- either cannot be reached from a read-through tool call (`memories`, gated by
-- `LiveToolbox.rememberBlockedByReadThroughTool`), is fed only from `episodic_turns`, which already
-- excludes a mail-touched turn entirely (`companion_memories`' consolidation/reflection writers),
-- or redacts to a fixed placeholder string before the row is ever inserted
-- (`memory_audit`'s SPOKEN rows, via `auditContent()`). This migration's job is only to not
-- launder that guarantee away by moving data past it - nothing here re-opens it.

-- =============================================================================================
-- MEMORIES (the flat, explicitly-remembered-fact table)
-- =============================================================================================
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

comment on column public.memories.origin_guid is
    'Backed by MemoryEntry.syncId on the phone, not a new column - see that field''s own v61 doc '
    'comment for why an existing identity is reused rather than duplicated.';

comment on column public.memories.logged_at is
    'MemoryEntry.timestamp carried verbatim - when the driver said "remember this", not when the '
    'row happened to sync.';

-- =============================================================================================
-- COMPANION_MEMORIES (the consolidated/reflected/stated table)
-- =============================================================================================
create table if not exists public.companion_memories (
    id                uuid primary key default gen_random_uuid(),
    vehicle_id        text        not null,
    text              text        not null check (length(trim(text)) > 0),
    category          text        not null check (category in ('car_anchored', 'driver', 'relationship')),
    source            text        not null check (source in ('consolidated', 'reflection', 'stated')),
    importance        integer     not null default 5 check (importance between 1 and 10),
    logged_at         timestamptz not null,
    last_accessed_at  timestamptz,
    provenance        public.provenance not null default 'USER',
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    deleted_at        timestamptz,
    origin_guid       text        not null unique
    -- No embedding_vector/embedding_model - see this file's own header comment.
);

comment on column public.companion_memories.origin_guid is
    'Backed by CompanionMemory.syncId on the phone, not a new column - same reuse as memories.origin_guid.';

comment on column public.companion_memories.vehicle_id is
    'The phone''s local ActiveVehicle key (an OBD MAC / local id), not a foreign key into any '
    'server vehicle table - car_anchored memories are scoped by this string, matching '
    'CompanionMemoryDao.getRecallScan''s own phone-side WHERE clause.';

-- ---------------------------------------------------------------------------------------------
-- MEMORY_AUDIT (the append-only audit trail)
-- ---------------------------------------------------------------------------------------------
create table if not exists public.memory_audit (
    id           uuid primary key default gen_random_uuid(),
    event        text        not null check (event in ('written', 'deleted', 'recall', 'recalled', 'spoken')),
    store        text        not null check (store in ('memories', 'companion_memories', 'speech')),
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

comment on column public.memory_audit.origin_guid is
    'Freshly minted (MemoryAudit.guid) - unlike memories/companion_memories, this table had no '
    'existing identity column to reuse. See that field''s own v61 doc comment.';

comment on column public.memory_audit.ref_id is
    'MemoryAudit.refId carried verbatim - a phone-LOCAL row id in whichever store this line '
    'concerns, 0/absent meaning "not applicable". Never a foreign key here: the id space it '
    'refers to is per-device (Room autoincrement), not this table''s own uuids.';

-- ---------------------------------------------------------------------------------------------
-- Read indexes.
-- ---------------------------------------------------------------------------------------------
create index if not exists memories_logged_at_idx on public.memories (logged_at) where deleted_at is null;
create index if not exists companion_memories_vehicle_id_idx on public.companion_memories (vehicle_id) where deleted_at is null;
create index if not exists companion_memories_category_idx  on public.companion_memories (category)   where deleted_at is null;
create index if not exists memory_audit_logged_at_idx on public.memory_audit (logged_at) where deleted_at is null;
create index if not exists memory_audit_event_idx      on public.memory_audit (event)      where deleted_at is null;

-- ---------------------------------------------------------------------------------------------
-- Triggers and RLS. Authored tables get updated_at and NOT the immutability trigger - see this
-- file's own header comment.
-- ---------------------------------------------------------------------------------------------
do $$
declare
    tbl text;
begin
    foreach tbl in array array['memories', 'companion_memories', 'memory_audit']
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
-- with nothing to say. REPLICA IDENTITY left at its default - MemoryRealtime's postgres_changes
-- event is a trigger for MemorySync.pull, never a data source, so the payload itself is never read.
-- ---------------------------------------------------------------------------------------------
alter publication supabase_realtime add table public.memories;
alter publication supabase_realtime add table public.companion_memories;
alter publication supabase_realtime add table public.memory_audit;
