-- LEGION backend-erp: widen `public.events`' origin_guid uniqueness from per-guid to per-(guid, kind).
-- Ticket: .scratch/backend-erp/issues/10-fleet-cutover.md, "Defect 1" from the first real
-- on-device FleetReconcile run, 2026-08-28. Ruled by Kevin the same day: "kind-scope the guard
-- and fix the wording." This file is the server half of that ruling; the client half is
-- SupabaseEventsBackend.uploadMigratedEvent's existence check (see its own doc comment).
--
-- =============================================================================================
-- THE PROBLEM
-- =============================================================================================
-- One car task uploaded as 13 of 14. The 14th's guid (e7546107-328b-47fe-a944-ce2d8e1a06d0) was
-- already present on `public.events` - correctly - because an earlier Notes+Dates migration wave
-- had uploaded the SAME engine record's guid under `kind = 'reminder'`. `uploadMigratedEvent`'s
-- existence guard was table-wide on `origin_guid` alone, so it found that row and (correctly, by
-- its own contract) declined to insert a duplicate. But `FleetReconcile`'s car-task diff reads
-- `fetchActive().filter { kind == CAR_TASK }`, which is kind-scoped - it never saw a `car_task`
-- row for that guid. The guid satisfied the guard and failed the diff at the same time: the
-- upload could never run, and the drift could never clear. `isClean` could never go true.
--
-- =============================================================================================
-- WHY THE COLUMN MUST WIDEN, NOT JUST THE CLIENT CHECK
-- =============================================================================================
-- Kind-scoping the client guard alone, without touching the unique index, would turn today's
-- silent skip into a unique-violation at insert time the moment two kinds legitimately share a
-- guid - which is exactly what fix 1 makes routine (see below). Both halves have to land
-- together or the fix trades one failure mode for a louder one.
--
-- 20260826000100_origin_guid.sql's own header already establishes the premise that makes this
-- widening legitimate rather than an identity change: "origin_guid is migration PROVENANCE, not
-- identity... it records that a row came from this phone's engine during the phase 4 cutover."
-- Provenance was previously scoped to "this engine record, once, ever" on `events` specifically
-- (every other Phase 4 table - ledger_transactions, receipts, receipt_line_items, vehicles,
-- service_history - has exactly one row shape per record and stays untouched by this file). But
-- one engine guid can legitimately become MORE THAN ONE `events` row now that a third kind
-- exists: a Notes `Item` and a fleet `car_tasks` row are different pieces of data that happen to
-- share a syncId/guid by construction (both trace to the same underlying engine record from an
-- earlier migration wave - see ticket 10's own "new open question" for why that double-presence
-- exists at all). Provenance is therefore per (guid, kind) now, not per guid: "this row came from
-- this phone's engine, as a NOTE" and "...as a CAR TASK" are two independent facts, and each
-- deserves its own idempotency slot rather than fighting the other for the only one available.
--
-- **Every other origin_guid index (ledger_transactions, receipts, receipt_line_items, vehicles,
-- service_history) is UNCHANGED.** None of those tables has a `kind` column or an analogous
-- multi-shape story, so widening them would be solving a problem they do not have.
--
-- =============================================================================================
-- FINDING THE OLD INDEX ROBUSTLY, NOT BY ASSUMED NAME
-- =============================================================================================
-- 20260826000100 named the index `events_origin_guid_idx` when it created it, and
-- 20260828000100's own header (the car_task kind-check widening) already established the house
-- style this file follows: do not trust that a name is exactly what an earlier migration said,
-- because a name mismatch turns `drop index if exists <guessed name>` into a silent no-op that
-- leaves the OLD, narrower unique constraint underneath - the migration reports success and every
-- co-existing-kind insert keeps failing at runtime, far from here (lesson L37's shape again).
--
-- So the old index is found by what it actually indexes - a unique index on `public.events` whose
-- indexed columns are `origin_guid` alone - rather than by its name, and the drop is still
-- written so a name mismatch cannot half-apply: if the loop below finds nothing (name really was
-- exactly `events_origin_guid_idx` and some other path already dropped it, or it never existed
-- under this shape), nothing is dropped and the `create unique index if not exists` below still
-- lands cleanly, because `if not exists` guards against a second run rather than against a first
-- run finding an unexpected prior state.
--
-- UNAPPLIED as of this commit - there is no CLI credential in this environment. Same caveat as
-- every migration in this file's neighbourhood; apply through the dashboard SQL editor per
-- 20260826000600's own "APPLIED AND VERIFIED" account for the verification shape to follow
-- (query pg_index afterwards, never trust the editor's success panel alone).
-- ---------------------------------------------------------------------------------------------
do $$
declare
    idx record;
begin
    for idx in
        select ic.relname as index_name
        from pg_index i
        join pg_class ic on ic.oid = i.indexrelid
        join pg_class tc on tc.oid = i.indrelid
        join pg_namespace nsp on nsp.oid = tc.relnamespace
        where nsp.nspname = 'public'
          and tc.relname = 'events'
          and i.indisunique
          -- indkey is the exact ordered column list the index covers. A single-column index on
          -- origin_guid alone has indkey = {attnum(origin_guid)} - nothing more, nothing less.
          and i.indkey = (
              select array[att.attnum]::int2vector
              from pg_attribute att
              where att.attrelid = tc.oid and att.attname = 'origin_guid'
          )
    loop
        execute format('drop index if exists public.%I', idx.index_name);
    end loop;
end
$$;

create unique index if not exists events_origin_guid_kind_idx on public.events (origin_guid, kind);

comment on column public.events.origin_guid is
    'Phase 4 migration provenance (see ledger_transactions.origin_guid for the general shape), '
    'NOW SCOPED PER (origin_guid, kind) rather than per origin_guid alone as of '
    '20260828000200_events_origin_guid_per_kind.sql - the same engine guid can legitimately reach '
    'this table more than once, under different kinds (a Notes Item and a fleet car_task can '
    'trace to the same guid). Still NOT an identity column - the row identity is `id` - and still '
    'unique enough to make a re-run of any one kind''s upload free, which is all idempotency ever '
    'required here.';
