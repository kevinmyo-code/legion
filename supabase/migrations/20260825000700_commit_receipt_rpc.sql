-- LEGION backend-erp, Phase 2: the pantry commit RPC.
-- Ticket: .scratch/backend-erp/issues/05-migration-path.md (phase 2, owed item 1)
-- Depends on: 20260825000200_conventions.sql, 20260825000300_aspect_ledger_pantry.sql
--
-- The sibling of `commit_statement`. It exists because ledger alone proves the gate against ONE
-- anchor shape, and pantry's is genuinely different: two anchors that collapse into one when the
-- receipt prints no subtotal.
--
-- The arithmetic below is a line-by-line mirror of `pantry/PantryReceiptAgent.kt:236-278`, and it
-- must stay that way. Ticket 03 ruling 2 accepted two implementations of the same gate on the
-- condition that a shared test corpus proves they agree; that corpus is the thing that makes this
-- duplication safe rather than merely duplicated.
--
-- Pantry is LLM_RECONCILED by construction, never DETERMINISTIC: a receipt is photographed rather
-- than born-digital, so there is no deterministic extraction path to prefer (CLAUDE.md section 4
-- rule 1). That is a necessity, not a preference.

create or replace function public.commit_receipt(payload jsonb)
    returns jsonb
    language plpgsql
    security invoker
    set search_path = ''
as $$
declare
    v_sha         text := payload ->> 'content_sha256';
    v_items       jsonb := coalesce(payload -> 'items', '[]'::jsonb);
    v_item_count  integer := jsonb_array_length(v_items);

    v_total       bigint := (payload ->> 'total_cents')::bigint;
    -- Nullable on purpose: a receipt that prints no subtotal is the collapse case below.
    v_subtotal    bigint := nullif(payload ->> 'subtotal_cents', '')::bigint;
    v_tax         bigint := coalesce(nullif(payload ->> 'tax_cents', '')::bigint, 0);
    v_other       bigint := coalesce(nullif(payload ->> 'other_charges_cents', '')::bigint, 0);

    v_provenance  public.provenance := coalesce(
        (nullif(payload ->> 'provenance', ''))::public.provenance, 'LLM_RECONCILED');

    v_file_id     uuid;
    v_state       public.ingest_state;
    v_receipt_id  uuid;
    v_items_total bigint;
    v_computed    bigint;
    v_inserted    integer := 0;
    v_reason      text;
begin
    if v_sha is null or length(v_sha) = 0 then
        raise exception 'commit_receipt: content_sha256 is required'
            using errcode = 'invalid_parameter_value';
    end if;

    -- 1. Idempotency, identical in shape and purpose to commit_statement's. See that function for
    -- why this is what makes a lost acknowledgement retryable rather than ambiguous.
    select id, state into v_file_id, v_state
    from public.ingested_files
    where content_sha256 = v_sha;

    if v_state = 'INGESTED' then
        return jsonb_build_object(
            'outcome', 'ALREADY_COMMITTED',
            'content_sha256', v_sha,
            'inserted', 0,
            'note', 'This receipt was already committed. Nothing was written again.'
        );
    end if;

    -- 2. Rule 6 first, before any arithmetic, for the same reason as in commit_statement: with zero
    -- items the anchors below are satisfiable by a receipt whose figures happen to be zero.
    if v_item_count = 0 then
        v_reason := 'No line items were extracted from this receipt. An empty extraction can never '
                 || 'satisfy the gate, whatever the printed total says.';
        perform private.quarantine_file(payload, v_reason);
        return jsonb_build_object('outcome', 'QUARANTINED', 'reason', v_reason, 'inserted', 0);
    end if;

    select coalesce(sum((item ->> 'total_price_cents')::bigint), 0)
      into v_items_total
      from jsonb_array_elements(v_items) as item;

    -- 3. The anchors. Note what is NOT summed here: the estimated_* macro fields. A receipt never
    -- prints calories, so they cannot be gated and must never read as fact (section 4 rule 5).
    if v_subtotal is not null then
        -- Anchor 1: the items are all of, and only, what the subtotal covers.
        if v_items_total <> v_subtotal then
            v_reason := format(
                'The %s extracted items come to %s cents but the receipt prints a subtotal of %s '
                'cents. Nothing was saved.',
                v_item_count, v_items_total, v_subtotal);
            perform private.quarantine_file(payload, v_reason);
            return jsonb_build_object('outcome', 'QUARANTINED', 'reason', v_reason, 'inserted', 0);
        end if;

        -- Anchor 2: subtotal, tax and any other printed charge account for the grand total exactly.
        v_computed := v_subtotal + v_tax + v_other;
        if v_computed <> v_total then
            v_reason := format(
                'The receipt''s own figures do not tie out: subtotal %s plus tax %s plus other %s '
                'is %s cents, not the %s cents it prints as the total. Nothing was saved.',
                v_subtotal, v_tax, v_other, v_computed, v_total);
            perform private.quarantine_file(payload, v_reason);
            return jsonb_build_object('outcome', 'QUARANTINED', 'reason', v_reason, 'inserted', 0);
        end if;
    else
        -- No printed subtotal to split the check on, so the two anchors collapse into one. Still a
        -- real gate: items plus every printed non-item charge must account for the total exactly.
        v_computed := v_items_total + v_tax + v_other;
        if v_computed <> v_total then
            v_reason := format(
                'The %s extracted items plus tax %s plus other %s come to %s cents, not the %s '
                'cents the receipt prints as the total. Nothing was saved.',
                v_item_count, v_tax, v_other, v_computed, v_total);
            perform private.quarantine_file(payload, v_reason);
            return jsonb_build_object('outcome', 'QUARANTINED', 'reason', v_reason, 'inserted', 0);
        end if;
    end if;

    -- 4. Gate passed. File row, then the header, then the lines.
    insert into public.ingested_files (content_sha256, source_file_id, display_name, size_bytes, state)
    values (v_sha,
            payload ->> 'source_file_id',
            payload ->> 'display_name',
            nullif(payload ->> 'size_bytes', '')::bigint,
            'INGESTED')
    on conflict (content_sha256) do update
        set state = 'INGESTED',
            quarantine_reason = null,
            last_attempt_at = now()
    returning id into v_file_id;

    insert into public.receipts (
        ingested_file_id, store, purchase_date, currency,
        total_cents, subtotal_cents, tax_cents, other_charges_cents,
        photo_object_path, provenance
    ) values (
        v_file_id,
        payload ->> 'store',
        (payload ->> 'purchase_date')::date,
        payload ->> 'currency',
        v_total,
        v_subtotal,
        nullif(payload ->> 'tax_cents', '')::bigint,
        nullif(payload ->> 'other_charges_cents', '')::bigint,
        payload ->> 'photo_object_path',
        v_provenance
    )
    returning id into v_receipt_id;

    -- No dedup pass here, and that is a real difference from the ledger rather than an omission.
    -- A receipt is one physical document photographed once; there is no equivalent of a bank
    -- restating a period, and two identical line items on one receipt (two of the same tin) are
    -- genuinely two rows. Idempotency on content_sha256 already stops the same photo committing
    -- twice, which is the only duplication that can actually occur here.
    with put as (
        insert into public.receipt_line_items (
            receipt_id, name, quantity, unit_price_cents, total_price_cents,
            estimated_calories_kcal, estimated_protein_g, estimated_carbs_g, estimated_fat_g,
            provenance
        )
        select v_receipt_id,
               item ->> 'name',
               (item ->> 'quantity')::numeric,
               nullif(item ->> 'unit_price_cents', '')::bigint,
               (item ->> 'total_price_cents')::bigint,
               nullif(item ->> 'estimated_calories_kcal', '')::numeric,
               nullif(item ->> 'estimated_protein_g', '')::numeric,
               nullif(item ->> 'estimated_carbs_g', '')::numeric,
               nullif(item ->> 'estimated_fat_g', '')::numeric,
               v_provenance
          from jsonb_array_elements(v_items) as item
        returning 1
    )
    select count(*) into v_inserted from put;

    return jsonb_build_object(
        'outcome', 'COMMITTED',
        'receipt_id', v_receipt_id,
        'inserted', v_inserted
    );
end;
$$;

comment on function public.commit_receipt(jsonb) is
    'Commits one receipt atomically: rule 6 non-empty check, then pantry''s two anchors (items sum '
    'to subtotal; subtotal plus tax plus other equals total), collapsing to one when no subtotal is '
    'printed. Mirrors PantryReceiptAgent.kt:236-278. Idempotent on content_sha256. Macro estimates '
    'are excluded from every check by construction (section 4 rule 5).';

revoke all on function public.commit_receipt(jsonb) from public, anon;
grant execute on function public.commit_receipt(jsonb) to authenticated;
