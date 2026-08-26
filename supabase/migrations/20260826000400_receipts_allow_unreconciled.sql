-- LEGION backend-erp, Phase 4: let a receipt actually BE the UNRECONCILED row 000300 describes.
-- Ticket: .scratch/backend-erp/issues/08-receipts-whose-anchors-were-never-stored.md
-- Decision: Kevin, 2026-08-26.
-- Depends on: 20260826000300_receipt_unaccounted.sql
--
-- =============================================================================================
-- WHY
-- =============================================================================================
-- 20260826000300 added `unaccounted_cents` plus a check that a non-null value FORCES
-- `provenance = 'UNRECONCILED'`. It did not touch `receipts_not_provisional`
-- (`20260825000300_aspect_ledger_pantry.sql`), which reads `check (provenance <> 'UNRECONCILED')`
-- and was correct the day it was written - before this ticket, a receipt could never legitimately
-- be provisional, so the constraint forbade it outright, the same way `statements_not_provisional`
-- still does for statements (a statement genuinely can never be provisional; nothing in this
-- ticket touches that table). Left as-is, the two constraints together make `unaccounted_cents`
-- unusable: any INSERT that sets it also needs `provenance = 'UNRECONCILED'`, which
-- `receipts_not_provisional` would reject in the same statement. Caught before either constraint
-- was exercised against real data - see this ticket's own PantryReconcile test for the row that
-- would have tripped it.
--
-- This migration narrows `receipts_not_provisional` rather than dropping it outright: a receipt
-- may be UNRECONCILED only together with a non-null `unaccounted_cents` (the mirror image of
-- 000300's own check), never on its own. An ordinary receipt with nothing missing still cannot
-- be inserted as UNRECONCILED - that half of the original guarantee survives unchanged.

alter table public.receipts drop constraint if exists receipts_not_provisional;
alter table public.receipts add constraint receipts_not_provisional check (
    provenance <> 'UNRECONCILED' or unaccounted_cents is not null
);

comment on constraint receipts_not_provisional on public.receipts is
    'A receipt may only be UNRECONCILED when it also carries a non-null unaccounted_cents '
    '(20260826000300) - the two constraints are meant to be read together. An ordinary receipt '
    'with nothing missing can never be provisional.';
