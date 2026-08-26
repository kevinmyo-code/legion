-- LEGION backend-erp, Phase 2: the GATED aspects, ledger and pantry.
-- Ticket: .scratch/backend-erp/issues/05-migration-path.md (phase 2)
-- Depends on: 20260825000200_conventions.sql (provenance, immutability trigger, RLS macro).
--
-- These two are the aspects whose rows come through the reconciliation gate (CLAUDE.md section 4),
-- so they are the ones that get the header-plus-lines shape and the immutability trigger. Kevin
-- ruled both on 2026-08-25.
--
-- **Why header plus lines is a schema decision and not a modelling preference.** The gate's whole
-- claim is that a set of lines reconciles against an anchor the DOCUMENT stated. Putting the
-- anchors on a header row that the lines reference by foreign key means a line cannot exist without
-- the anchor it was checked against: "nothing partial is ever written" stops being a property the
-- commit code remembers to enforce and becomes one the database will not allow to be violated.
--
-- Type choices worth stating once, since they repeat:
--   money      -> bigint, always cents, never numeric or float (section 4 rule 3)
--   instants   -> timestamptz, not epoch-millis bigint. The Kotlin side stores millis today; a
--                 typed schema is the point of ADR 0039 and Postgres date arithmetic is worth the
--                 conversion at the client boundary.
--   small sets -> text plus a CHECK, not a Postgres enum. CLAUDE.md section 5's reasoning carries
--                 over: widening a CHECK is a one-line migration, widening an enum type is not.

-- =============================================================================================
-- LEDGER
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- statements: the document header, and the home of the three anchors.
--
-- Ticket 03 ruling 4 requires THREE independent anchors, because an LLM-produced CSV has its lines
-- AND its total from one nondeterministic process, so a single anchor can be satisfied by a
-- self-consistent hallucination. A statement that cannot state all three cannot use the format and
-- falls to rule 7 provisional instead - which is why every anchor column here is NOT NULL. A header
-- row exists only for a document that had something to reconcile against.
--
-- Provisional rows (rule 7) therefore have NO header: `ledger_transactions.statement_id` is
-- nullable precisely so an UNRECONCILED row can exist without one. That nullability is the schema
-- saying, in the only language it has, that a provisional row was never checked against anything.
-- ---------------------------------------------------------------------------------------------
create table if not exists public.statements (
    id                 uuid primary key default gen_random_uuid(),
    ingested_file_id   uuid not null references public.ingested_files (id) on delete restrict,

    -- Account identity: last four digits plus the nickname declared in the CSV (ticket 03 ruling
    -- 5). Last-four alone collides between two accounts sharing it, which is a known and accepted
    -- weakness carried over from `sameCard`; the nickname is what disambiguates, so it is required.
    account_last4      text        not null check (account_last4 ~ '^[0-9]{4}$'),
    account_nickname   text        not null check (length(trim(account_nickname)) > 0),
    currency           text        not null check (currency in ('SGD', 'USD')),

    period_start       date        not null,
    period_end         date        not null,

    -- The three anchors. All NOT NULL by construction, see the note above.
    stated_total_cents bigint      not null,
    opening_balance_cents bigint   not null,
    closing_balance_cents bigint   not null,

    provenance         public.provenance not null,
    created_at         timestamptz not null default now(),

    constraint statements_period_ordered check (period_end >= period_start),
    -- A header is only ever written by a commit that PASSED the gate. A provisional import has no
    -- header at all, so UNRECONCILED here would be a contradiction in terms.
    constraint statements_not_provisional check (provenance <> 'UNRECONCILED'),
    -- One header per file per account: re-committing the same bytes must not mint a second header.
    -- With ingested_files.content_sha256 unique, this is what makes the RPC idempotent end to end.
    constraint statements_one_per_file unique (ingested_file_id, account_last4)
);

comment on table public.statements is
    'One reconciled statement document. Holds the three anchors ticket 03 ruling 4 requires. A '
    'provisional (rule 7) import has NO row here, which is why ledger_transactions.statement_id is '
    'nullable.';

-- ---------------------------------------------------------------------------------------------
-- ledger_transactions: the lines.
-- ---------------------------------------------------------------------------------------------
create table if not exists public.ledger_transactions (
    id                uuid primary key default gen_random_uuid(),

    -- NULL only for rule-7 provisional rows and voice-logged pending charges, which by definition
    -- were never checked against a header. ON DELETE RESTRICT: a header may not be removed while
    -- lines still point at it.
    statement_id      uuid references public.statements (id) on delete restrict,

    account_last4     text        not null check (account_last4 ~ '^[0-9]{4}$'),
    account_nickname  text        not null,
    currency          text        not null check (currency in ('SGD', 'USD')),
    txn_date          date        not null,
    description       text        not null,
    amount_cents      bigint      not null,
    balance_cents     bigint,

    -- Stable per-row source coordinate, carried over from LedgerTransaction.lineRef. Kept because
    -- it is what makes a re-import recognisably the same line rather than a new one.
    line_ref          text        not null,

    category          text,
    category_pending  boolean     not null default true,

    -- Set on a voice-logged pending charge; such a row is UNRECONCILED by construction.
    pending_logged_at timestamptz,

    -- The reversal chain (Kevin, 2026-08-25, SAP's document principle). A correction inserts a new
    -- row pointing at what it reverses. There is deliberately no `reversed_by` column: setting one
    -- would require updating an immutable row.
    reversal_of       uuid references public.ledger_transactions (id) on delete restrict,

    provenance        public.provenance not null,
    created_at        timestamptz not null default now(),

    -- A reconciled line must belong to a header; a provisional one must not.
    constraint ledger_txn_header_matches_provenance check (
        (provenance = 'UNRECONCILED' and statement_id is null)
        or (provenance <> 'UNRECONCILED' and statement_id is not null)
    ),
    -- A reversal must not itself be provisional: you cannot formally reverse something that was
    -- never asserted. Withdrawing a provisional row is a delete, which rule 7 already governs.
    constraint ledger_txn_reversal_not_provisional check (
        reversal_of is null or provenance <> 'UNRECONCILED'
    )
);

create index if not exists ledger_transactions_statement_idx on public.ledger_transactions (statement_id);
create index if not exists ledger_transactions_account_date_idx on public.ledger_transactions (account_last4, txn_date);
-- Rule 7 supersession scans exactly this: provisional rows for one card inside a date window.
create index if not exists ledger_transactions_provisional_idx
    on public.ledger_transactions (account_last4, txn_date)
    where provenance = 'UNRECONCILED';

comment on column public.ledger_transactions.reversal_of is
    'Points at the row this one reverses. Gated rows are never edited (SAP document principle, '
    'Kevin 2026-08-25): a correction is a reversal plus a replacement, so history stays append-only '
    'and every figure is explainable by the entries that produced it.';

-- =============================================================================================
-- PANTRY
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- receipts: the header. Same shape as statements, different anchors.
--
-- Pantry's gate has TWO anchors rather than three (PantryReceiptAgent): line items sum to the
-- subtotal, and subtotal plus tax plus other charges equals the total. Both are printed on the
-- receipt, so both are NOT NULL. `subtotal_cents` is nullable ONLY because some receipts print no
-- subtotal at all, in which case the agent collapses to the single items+tax+other = total check.
--
-- The macro estimates live on the LINE, not here, and are excluded from every anchor by
-- construction (section 4 rule 5): a receipt never prints calories, so they can never be gated and
-- must never read as fact.
-- ---------------------------------------------------------------------------------------------
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

    -- Photo bytes live in Supabase Storage (ticket 01 ruling 10 as amended 2026-08-25); this is the
    -- object path, not a local file path.
    photo_object_path  text,

    provenance         public.provenance not null,
    created_at         timestamptz not null default now(),

    constraint receipts_not_provisional check (provenance <> 'UNRECONCILED')
);

comment on table public.receipts is
    'One receipt document. Pantry has no deterministic extraction path by necessity (a receipt is '
    'photographed, not born-digital), so rows here are LLM_RECONCILED rather than DETERMINISTIC.';

-- ---------------------------------------------------------------------------------------------
-- receipt_line_items: the lines. CASCADE, matching the engine's own delete policy for this
-- reference (the only CASCADE in the whole schema; fleet's two references are BLOCK).
-- ---------------------------------------------------------------------------------------------
create table if not exists public.receipt_line_items (
    id                uuid primary key default gen_random_uuid(),
    receipt_id        uuid        not null references public.receipts (id) on delete cascade,

    name              text        not null,
    quantity          numeric     not null check (quantity > 0),
    unit_price_cents  bigint,
    total_price_cents bigint      not null,

    -- ESTIMATES. A receipt never prints these; they are a model's guess from the product name.
    -- Section 4 rule 5: excluded from every reconciliation check, and every surface that renders
    -- one must say "estimate". Named `estimated_*` so the column itself carries the warning.
    estimated_calories_kcal numeric,
    estimated_protein_g     numeric,
    estimated_carbs_g       numeric,
    estimated_fat_g         numeric,

    reversal_of       uuid references public.receipt_line_items (id) on delete restrict,
    provenance        public.provenance not null,
    created_at        timestamptz not null default now()
);

create index if not exists receipt_line_items_receipt_idx on public.receipt_line_items (receipt_id);

comment on column public.receipt_line_items.estimated_calories_kcal is
    'An estimate, never a printed fact. Excluded from the gate (CLAUDE.md section 4 rule 5). Any '
    'surface rendering this must say so in words.';

-- =============================================================================================
-- Immutability and RLS for all four gated tables.
--
-- The trigger blocks every UPDATE, and blocks DELETE except on UNRECONCILED rows - see
-- 20260825000200_conventions.sql for why that exception is faithful to rule 7 rather than a hole
-- in the rule.
-- =============================================================================================
do $$
declare
    t text;
begin
    foreach t in array array[
        'public.statements',
        'public.ledger_transactions',
        'public.receipts',
        'public.receipt_line_items'
    ] loop
        execute format('drop trigger if exists forbid_mutation on %s', t);
        execute format(
            'create trigger forbid_mutation before update or delete on %s '
            'for each row execute function private.forbid_mutation_of_facts()', t
        );
        execute format('select private.apply_household_rls(%L)', t);
    end loop;
end $$;
