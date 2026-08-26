-- LEGION backend-erp, Phase 4: make the one-time migration upload idempotent.
-- Ticket: .scratch/backend-erp/issues/05-migration-path.md (phase 4, the idempotency blocker)
--
-- =============================================================================================
-- THE PROBLEM
-- =============================================================================================
-- Phase 4 step 1 says each aspect's upload is idempotent "so a re-run is free", and step 2
-- ("diff until clean") assumes re-runs actually happen. Only `places` could honour that: it is
-- the one aspect table with an enforced unique key (`label`). `ledger_transactions`, `receipts`,
-- `receipt_line_items`, `vehicles` and `service_history` have no unique constraint at all, so an
-- upsert has nothing to conflict against and a second run inserts everything again.
--
-- =============================================================================================
-- WHY NOT SEMANTIC NATURAL KEYS
-- =============================================================================================
-- The obvious fix is to give each table a natural key. It does not survive contact:
--
--   * `receipt_line_items` has no natural key EVEN IN PRINCIPLE. Two identical lines on one
--     receipt are legitimate - the same item scanned twice - so any key over (receipt, name,
--     price) would collapse two real rows into one. Losing a line item silently is strictly worse
--     than duplicating one, because the reconciliation gate would then fail a document that was
--     extracted correctly.
--   * `ledger_transactions` has `line_ref`, but `statement_id` is NULL by design for rule-7
--     provisional rows and voice-logged pending charges, and Postgres treats NULLs as DISTINCT,
--     so exactly the rows with the weakest provenance would be the ones free to duplicate.
--   * `receipts` could key on `ingested_file_id`, but a photographed receipt has no file.
--
-- =============================================================================================
-- WHAT THIS DOES INSTEAD
-- =============================================================================================
-- Every row being uploaded is, today, an engine record - and Room v37 gave every engine record a
-- unique, backfilled `records.guid` whose stated purpose is exactly this: a stable identity that
-- is not a per-database autoincrement id. `MIGRATION_36_37` backfills it for every pre-existing
-- row, so "no row is ever guid-less" (its own words).
--
-- So the upload keys on the identity the data ALREADY HAS, rather than on one invented for it.
--
-- `origin_guid` is NULLABLE and unique. Nullable because it is migration PROVENANCE, not identity:
-- it records that a row came from this phone's engine during the phase 4 cutover. A row created
-- after cutover has no engine ancestor and leaves it NULL. Postgres treats NULLs as distinct in a
-- unique index, which is the behaviour wanted here - post-cutover rows must never collide with
-- each other, and only the migrated ones need conflict resolution.
--
-- **This is not the generic engine leaking into the typed schema.** Ruling 7 retires the engine's
-- SHAPE - `records`, `record_types`, `field_defs`, the payload jsonb, the generated forms. A text
-- column recording where a row came from is the same kind of fact as `provenance`, and it stays
-- meaningful long after the engine is deleted: it is what lets anyone ask, later, whether a given
-- row predates the cutover.
--
-- Deliberately NOT added to `places`: `label` is already unique and its reconcile keys on it.
-- Deliberately NOT adding semantic constraints (e.g. `vehicles.name` unique) - those are real
-- product decisions about whether two cars may share a name, they can fail against existing data,
-- and none of that can be validated from a machine with no access to the project.

alter table public.ledger_transactions add column if not exists origin_guid text;
alter table public.receipts            add column if not exists origin_guid text;
alter table public.receipt_line_items  add column if not exists origin_guid text;
alter table public.vehicles            add column if not exists origin_guid text;
alter table public.service_history     add column if not exists origin_guid text;
alter table public.events              add column if not exists origin_guid text;

create unique index if not exists ledger_transactions_origin_guid_idx on public.ledger_transactions (origin_guid);
create unique index if not exists receipts_origin_guid_idx            on public.receipts (origin_guid);
create unique index if not exists receipt_line_items_origin_guid_idx  on public.receipt_line_items (origin_guid);
create unique index if not exists vehicles_origin_guid_idx            on public.vehicles (origin_guid);
create unique index if not exists service_history_origin_guid_idx     on public.service_history (origin_guid);
create unique index if not exists events_origin_guid_idx              on public.events (origin_guid);

comment on column public.ledger_transactions.origin_guid is
    'Phase 4 migration provenance: the records.guid of the engine record this row was uploaded '
    'from, or NULL for a row created after the cutover. Unique, so re-running the upload is free. '
    'NOT an identity column - the row identity is `id`.';
comment on column public.receipts.origin_guid is
    'Phase 4 migration provenance. See ledger_transactions.origin_guid.';
comment on column public.receipt_line_items.origin_guid is
    'Phase 4 migration provenance. See ledger_transactions.origin_guid. This table has no natural '
    'key even in principle - two identical lines on one receipt are legitimate - so this is the '
    'only thing making its upload idempotent.';
comment on column public.vehicles.origin_guid is
    'Phase 4 migration provenance. See ledger_transactions.origin_guid.';
comment on column public.service_history.origin_guid is
    'Phase 4 migration provenance. See ledger_transactions.origin_guid.';
comment on column public.events.origin_guid is
    'Phase 4 migration provenance. See ledger_transactions.origin_guid.';
