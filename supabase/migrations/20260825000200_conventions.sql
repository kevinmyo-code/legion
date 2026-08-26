-- LEGION backend-erp, Phase 2: the conventions every aspect table follows.
-- Ticket: .scratch/backend-erp/issues/05-migration-path.md (phase 2)
-- Decisions: ADR 0039 (per-aspect typed tables), CLAUDE.md section 4 (the reconciliation gate).
--
-- This file defines the shared vocabulary: the provenance type, the immutability rule for gated
-- data, the updated_at trigger for authored data, and the household RLS macro. Per-aspect tables
-- live in their own migrations and refer back to these.
--
-- Idempotent, like every migration here: safe to re-apply.

-- ---------------------------------------------------------------------------------------------
-- 1. Provenance.
--
-- CLAUDE.md section 4 rule 4: every row carries where it came from. This is a real column on every
-- data table, never a payload field, because the gate and every rendering surface query it.
--
-- Deliberately a superset of the ledger's IngestMethod, matching RecordProvenance in the app:
-- USER exists because hand-entered rows (a note, a place) are not document-derived and would
-- otherwise have to lie about being DETERMINISTIC.
-- ---------------------------------------------------------------------------------------------
do $$
begin
    if not exists (select 1 from pg_type where typname = 'provenance') then
        create type public.provenance as enum (
            'DETERMINISTIC',    -- parsed from the document by code, no model involved
            'LLM_RECONCILED',   -- a model extracted it, and it PASSED the gate's anchors
            'UNRECONCILED',     -- rule 7: stored provisionally, never asserted as fact
            'USER'              -- a person typed it
        );
    end if;
end $$;

-- ---------------------------------------------------------------------------------------------
-- 2. Immutability for gated data, and the one exception, which is not a carve-out.
--
-- SAP's document principle: a posted document is never edited. A correction posts a REVERSAL and a
-- replacement, so history is append-only and every figure is explainable by the entries that
-- produced it. Kevin ruled this in for gated aspects (ledger, pantry) on 2026-08-25, and ruled it
-- OUT for authored aspects (notes, dates, places, fleet) where a row is a thing you edit, not a
-- posting.
--
-- **UNRECONCILED rows remain deletable, and that is faithful to rule 7 rather than an exception to
-- immutability.** Immutability protects rows asserted as FACT. Rule 7's whole definition of a
-- provisional row is that it is never asserted as fact and is TRANSIENT: when a file that did pass
-- the gate commits over the same account and window, the provisional rows in that window are
-- deleted. A row that was never a claim cannot be falsified by withdrawing it. If provisional rows
-- were instead frozen, rule 7 could not run at all.
--
-- There is deliberately NO `reversed_by` column anywhere. Setting one would require an UPDATE on an
-- immutable row, which is the exact thing being forbidden. A reversal simply carries `reversal_of`
-- pointing at what it reverses, and "is this row reversed" is a lookup, not stored state.
-- ---------------------------------------------------------------------------------------------
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
            -- Rule 7 supersession. Permitted precisely because a provisional row was never
            -- asserted as fact.
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

comment on function private.forbid_mutation_of_facts() is
    'BEFORE UPDATE OR DELETE trigger for gated tables. Blocks every update, and blocks deletes '
    'except on UNRECONCILED rows, which rule 7 defines as transient and never asserted as fact.';

-- ---------------------------------------------------------------------------------------------
-- 3. updated_at, for authored data only.
--
-- Gated tables do not get this trigger: they are never updated, so an updated_at column on one
-- would be a field that can only ever equal created_at, which invites someone to try.
-- ---------------------------------------------------------------------------------------------
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

-- ---------------------------------------------------------------------------------------------
-- 4. The household RLS macro.
--
-- Every data table gets exactly this treatment, so it is written once as a function that a
-- per-aspect migration calls rather than copied into each file, where one of the copies would
-- eventually differ. Two adults, no roles, and membership is the only authorization concept:
-- a member sees and writes everything, a non-member sees nothing.
--
-- `(select private.is_household_member())` is wrapped in a subselect so Postgres evaluates it once
-- per statement rather than once per row - Supabase's own RLS performance guidance.
-- ---------------------------------------------------------------------------------------------
create or replace function private.apply_household_rls(target regclass)
    returns void
    language plpgsql
    set search_path = ''
as $$
declare
    tbl text := target::text;
begin
    execute format('alter table %s enable row level security', tbl);

    -- RLS filters rows; it does not grant access to the table in the first place. Both are
    -- required, and Supabase's project-level default privileges are NOT relied on here: a fresh
    -- project that had them altered would otherwise get tables no client can read, with RLS
    -- looking like the culprit. Explicit is cheaper to debug than implicit.
    execute format('revoke all on %s from anon', tbl);
    execute format('grant select, insert, update, delete on %s to authenticated', tbl);

    execute format('drop policy if exists household_all on %s', tbl);
    execute format(
        'create policy household_all on %s for all to authenticated '
        'using ((select private.is_household_member())) '
        'with check ((select private.is_household_member()))',
        tbl
    );
end;
$$;

comment on function private.apply_household_rls(regclass) is
    'Enables RLS on a table and grants the household full access to it. Called by every per-aspect '
    'migration so the policy exists in exactly one place. Write protection for gated tables comes '
    'from the immutability trigger, not from RLS: RLS answers WHO, the trigger answers WHAT.';

-- ---------------------------------------------------------------------------------------------
-- 5. The file ingestion ledger, server side.
--
-- The app's `ingested_files` (data/local/IngestedFile.kt) moves here, because the commit RPC needs
-- it: ticket 03 ruling 8 makes the RPC idempotent keyed on `content_sha256`, so a repeat call is a
-- successful no-op rather than a second import. That guarantee is what lets the phone retry a lost
-- ack instead of narrating an outcome it cannot determine.
--
-- Columns are added by the per-aspect migration that needs them rather than guessed here; this is
-- the identity and state core.
-- ---------------------------------------------------------------------------------------------
do $$
begin
    if not exists (select 1 from pg_type where typname = 'ingest_state') then
        create type public.ingest_state as enum (
            'NEW',
            'INGESTED',
            'QUARANTINED',
            'UNREADABLE',
            'DUPLICATE_CONTENT',
            'NEEDS_LLM'
        );
    end if;
end $$;

create table if not exists public.ingested_files (
    id                uuid primary key default gen_random_uuid(),
    -- The content hash IS the idempotency key (ticket 03 ruling 8). Unique, so a second commit of
    -- the same bytes cannot create a second row even if the RPC is called twice.
    content_sha256    text        not null unique,
    source_file_id    text,
    display_name      text,
    size_bytes        bigint,
    state             public.ingest_state not null default 'NEW',
    quarantine_reason text,
    first_seen_at     timestamptz not null default now(),
    last_attempt_at   timestamptz not null default now()
);

comment on table public.ingested_files is
    'Per-file ingestion ledger. content_sha256 is unique and is the commit RPC''s idempotency key: '
    'a repeated commit of the same bytes is a successful no-op, which is what makes a lost ack '
    'retryable rather than ambiguous. QUARANTINED means nothing was ever written for that file.';

select private.apply_household_rls('public.ingested_files');
