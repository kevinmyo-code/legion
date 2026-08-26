-- LEGION backend-erp, Phase 4: the unaccounted amount on a receipt.
-- Ticket: .scratch/backend-erp/issues/08-receipts-whose-anchors-were-never-stored.md
-- Decision: Kevin, 2026-08-26.
-- Depends on: 20260825000300_aspect_ledger_pantry.sql
--
-- =============================================================================================
-- WHY
-- =============================================================================================
-- Three receipts on the phone charge more than their captured lines account for (802, 48 and 151
-- cents; two of the three gaps are ~6.6%, which is Texas sales tax). They are not corrupt and the
-- gate was probably not bypassed: the legacy `pantry_receipts` table only ever had `totalCents`,
-- with no subtotal/tax/other columns, so the agent gated on figures it held in memory and then
-- persisted only the total and the lines. **The gate's own inputs were never stored, so the check
-- cannot be reproduced from storage.** The source photos are gone, so re-extraction is impossible.
--
-- =============================================================================================
-- WHY NOT JUST STORE THE GAP AS TAX
-- =============================================================================================
-- Because `tax := total - sum(lines)` makes `sum(lines) + tax = total` true BY CONSTRUCTION. The
-- anchor stops being a check and becomes an identity - CLAUDE.md section 4 rule 6's exact failure
-- shape ("a check that passes when nothing parsed is not a gate"). Worse, a genuinely missed line
-- item would be absorbed into "tax" and the row would then read as verified. The gap has to stay
-- visible AS a gap.
--
-- So: its own column, named for what it is. Money the receipt charged that no captured line
-- explains. It is never summed into an anchor and never rendered as tax.

alter table public.receipts add column if not exists unaccounted_cents bigint;

alter table public.receipts drop constraint if exists receipts_unaccounted_requires_unreconciled;
alter table public.receipts add constraint receipts_unaccounted_requires_unreconciled
    check (
        unaccounted_cents is null
        or (unaccounted_cents <> 0 and provenance = 'UNRECONCILED')
    );

comment on column public.receipts.unaccounted_cents is
    'Money the receipt charged that no captured line item explains, in cents. NOT tax: the receipt '
    'printed a tax figure once, but it was never persisted and the photo is gone, so naming this '
    'tax would assert a fact nothing states. NULL means fully accounted. A non-null value forces '
    'provenance UNRECONCILED (see the check constraint), because a row with unexplained money is '
    'by definition not verified - and it must never be summed into a reconciliation anchor, or the '
    'gate becomes an identity (section 4 rule 6).';
