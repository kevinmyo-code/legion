-- LEGION backend-erp: a deterministically-parsed statement qualifies on two READ anchors.
-- Ticket: .scratch/backend-erp/issues/12-ledger-rows-have-no-statement-header.md, "RULED 2026-08-27"
-- Depends on: 20260825000900_commit_statement_real_dedup.sql (the function this replaces)
--
-- =============================================================================================
-- WHY, IN ONE PARAGRAPH
-- =============================================================================================
-- Ruling 4 demanded THREE anchors for a precise reason: an LLM-produced CSV has its lines AND its
-- total from ONE nondeterministic process, so a self-consistent hallucination could satisfy a
-- single-anchor check - section 4 rule 6's failure shape in a new place. That reasoning is about
-- WHERE the numbers came from, not how many of them there are, and it does not transfer to a
-- deterministically parsed bank PDF: the lines and the printed balances come off the document by
-- code, with no model anywhere in the path. The 2026-08-27 re-ingestion dry run also proved the
-- third anchor does not merely go unread here - it does not exist to be read, for any statement
-- Kevin owns: no bank format prints one combined total (DBS prints separate withdrawal/deposit
-- totals, BofA prints per-section figures). Holding the more trustworthy path to a bar only the
-- less trustworthy path can clear would be perverse.
--
-- So: a DETERMINISTIC statement now qualifies on two READ anchors - opening balance, closing
-- balance - plus `closing - opening = sum(lines)`. `stated_total_cents` is genuinely NULL and is
-- stored as such, never synthesised as `sum(lines)`, which would turn the check into an identity
-- (rule 6's failure shape yet again).
--
-- **The scope guard, which is the load-bearing half of this migration.** An LLM_RECONCILED payload
-- missing the stated total is STILL QUARANTINED. Ruling 4 stands exactly as written for the path it
-- was written about; this amendment is scoped to deterministic extraction and nothing else. Two
-- anchors is a floor, not a discount: a DETERMINISTIC statement supplying fewer than two read
-- anchors (an open/close-less CSV export, say) still falls to rule 7 provisional, unchanged.
--
-- Rule 6 (the non-empty check), rule 7 supersession, the dedup read, ordering, and idempotency on
-- content_sha256 are all untouched - only the anchor-1 branch below is new.
-- =============================================================================================

-- `stated_total_cents` can now be genuinely absent for a DETERMINISTIC statement. Every other
-- provenance still requires it - enforced by the CHECK immediately below, not by NOT NULL alone.
alter table public.statements alter column stated_total_cents drop not null;

alter table public.statements drop constraint if exists statements_total_only_null_if_deterministic;
alter table public.statements add constraint statements_total_only_null_if_deterministic check (
    stated_total_cents is not null or provenance = 'DETERMINISTIC'
);

comment on constraint statements_total_only_null_if_deterministic on public.statements is
    'The 2026-08-27 amendment''s scope guard, written into the schema and not just the RPC: only a '
    'DETERMINISTIC statement may omit stated_total_cents. LLM_RECONCILED still requires all three '
    'anchors, unchanged from ruling 4.';

create or replace function public.commit_statement(payload jsonb)
    returns jsonb
    language plpgsql
    security invoker
    set search_path = ''
as $$
declare
    v_sha              text := payload ->> 'content_sha256';
    v_last4            text := payload ->> 'account_last4';
    v_nickname         text := payload ->> 'account_nickname';
    v_currency         text := payload ->> 'currency';
    v_provenance       public.provenance := (payload ->> 'provenance')::public.provenance;
    v_lines            jsonb := coalesce(payload -> 'lines', '[]'::jsonb);
    v_line_count       integer := jsonb_array_length(v_lines);

    -- `->>` already turns a JSON `null` and an absent key into a genuine SQL NULL (unchanged from
    -- the prior version of this function) - both shapes now mean the same thing on purpose: the
    -- statement states no total.
    v_stated_total     bigint := (payload ->> 'stated_total_cents')::bigint;
    v_opening          bigint := (payload ->> 'opening_balance_cents')::bigint;
    v_closing          bigint := (payload ->> 'closing_balance_cents')::bigint;

    v_file_id          uuid;
    v_existing_state   public.ingest_state;
    v_statement_id     uuid;
    v_sum              bigint;
    v_min_date         date;
    v_max_date         date;
    v_superseded       integer := 0;
    v_inserted         integer := 0;
    v_dupes            integer := 0;
    v_restatements     integer := 0;
    v_ordinals         integer[];
    v_reason           text;
begin
    if v_sha is null or length(v_sha) = 0 then
        raise exception 'commit_statement: content_sha256 is required'
            using errcode = 'invalid_parameter_value';
    end if;

    -- 1. Idempotency (ticket 03 ruling 8), keyed on the content hash. This is what makes a lost
    -- acknowledgement retryable rather than ambiguous, which matters because ruling 8 removed the
    -- offline queue and CANNOT_CLAUSE has no vocabulary for "unknown".
    select id, state into v_file_id, v_existing_state
    from public.ingested_files
    where content_sha256 = v_sha;

    if v_existing_state = 'INGESTED' then
        return jsonb_build_object(
            'outcome', 'ALREADY_COMMITTED',
            'content_sha256', v_sha,
            'inserted', 0,
            'note', 'This file was already committed. Nothing was written again.'
        );
    end if;

    -- 2. Rule 6 first: an empty extraction satisfies every anchor below whenever the statement's own
    -- figures happen to be zero. That is how a card statement once passed with four dropped interest
    -- rows, and it only held because interest was zero that month.
    if v_line_count = 0 then
        v_reason := 'No transactions were extracted from this file. An empty extraction can never '
                 || 'satisfy the gate, whatever the stated totals are.';
        perform private.quarantine_file(payload, v_reason);
        return jsonb_build_object('outcome', 'QUARANTINED', 'reason', v_reason, 'inserted', 0);
    end if;

    select coalesce(sum((line ->> 'amount_cents')::bigint), 0),
           min((line ->> 'txn_date')::date),
           max((line ->> 'txn_date')::date)
      into v_sum, v_min_date, v_max_date
      from jsonb_array_elements(v_lines) as line;

    -- -----------------------------------------------------------------------------------------
    -- 3. ANCHOR 1 (the stated total), now branched on provenance and whether it was even printed.
    --
    -- - No stated total AND not DETERMINISTIC: quarantine outright. This is the amendment's scope
    --   guard - an LLM-produced payload missing its printed total is exactly the unverifiable shape
    --   ruling 4 exists to refuse, and the two-anchor allowance never applies to it.
    -- - No stated total AND DETERMINISTIC: this anchor does not exist to check (no bank format
    --   prints one), so it is skipped rather than faked. Falling through to anchor 2 below is the
    --   whole point of the amendment - the statement is not let off arithmetic, only off a number
    --   its bank never printed.
    -- - Stated total present: checked exactly as before, for every provenance. A DETERMINISTIC
    --   statement that DOES carry a printed total still gets held to it.
    -- -----------------------------------------------------------------------------------------
    if v_stated_total is null then
        if v_provenance <> 'DETERMINISTIC' then
            v_reason := 'This statement states no printed total, and only a deterministically '
                     || 'parsed statement can qualify without one. Nothing was written.';
            perform private.quarantine_file(payload, v_reason);
            return jsonb_build_object('outcome', 'QUARANTINED', 'reason', v_reason, 'inserted', 0);
        end if;
        -- else: no anchor 1 to check. Fall through to anchor 2.
    elsif v_sum <> v_stated_total then
        v_reason := format(
            'Lines sum to %s cents but the statement states a total of %s cents. Nothing was written.',
            v_sum, v_stated_total);
        perform private.quarantine_file(payload, v_reason);
        return jsonb_build_object('outcome', 'QUARANTINED', 'reason', v_reason, 'inserted', 0);
    end if;

    -- -----------------------------------------------------------------------------------------
    -- 4. ANCHOR 2 (the balance delta). Required unconditionally, for every provenance and whether
    -- or not anchor 1 ran - this is the "two anchors is a floor, not a discount" half of the
    -- amendment. A DETERMINISTIC statement with no printed total but also no opening/closing pair
    -- has ZERO anchors and must still fail here, exactly as it always has.
    -- -----------------------------------------------------------------------------------------
    if (v_closing - v_opening) <> v_sum then
        v_reason := format(
            'Closing balance minus opening balance is %s cents but the lines sum to %s cents. '
            'Nothing was written.',
            v_closing - v_opening, v_sum);
        perform private.quarantine_file(payload, v_reason);
        return jsonb_build_object('outcome', 'QUARANTINED', 'reason', v_reason, 'inserted', 0);
    end if;

    -- 5. Gate passed: the file row and the header.
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

    insert into public.statements (
        ingested_file_id, account_last4, account_nickname, currency,
        period_start, period_end,
        stated_total_cents, opening_balance_cents, closing_balance_cents, provenance
    ) values (
        v_file_id, v_last4, v_nickname, v_currency,
        coalesce((payload ->> 'period_start')::date, v_min_date),
        coalesce((payload ->> 'period_end')::date, v_max_date),
        v_stated_total, v_opening, v_closing, v_provenance
    )
    returning id into v_statement_id;

    -- 6. Rule 7 supersession, BEFORE the dedup read. Three properties carried over verbatim:
    -- a provisional file never supersedes anything (the guard below); before the dedup read, or the
    -- incoming verified rows are discarded as duplicates of the provisional rows they replace; and
    -- inside this transaction, which it is by construction.
    if v_provenance <> 'UNRECONCILED' then
        with gone as (
            delete from public.ledger_transactions
            where provenance = 'UNRECONCILED'
              and account_last4 = v_last4
              and txn_date between v_min_date and v_max_date
            returning 1
        )
        select count(*) into v_superseded from gone;
    end if;

    -- 7. The real dedup. See 20260825000800_ledger_dedup.sql for the algorithm and why it is a loop.
    select d.insert_ordinals, d.duplicates_skipped, d.restatements_skipped
      into v_ordinals, v_dupes, v_restatements
      from private.ledger_resolve_dedup(v_last4, v_nickname, v_lines, v_statement_id, v_min_date, v_max_date) d;

    with put as (
        insert into public.ledger_transactions (
            statement_id, account_last4, account_nickname, currency, txn_date,
            description, amount_cents, balance_cents, line_ref, category,
            category_pending, provenance
        )
        select v_statement_id, v_last4, v_nickname, v_currency,
               (line ->> 'txn_date')::date,
               line ->> 'description',
               (line ->> 'amount_cents')::bigint,
               nullif(line ->> 'balance_cents', '')::bigint,
               line ->> 'line_ref',
               line ->> 'category',
               (line ->> 'category') is null,
               v_provenance
          from unnest(v_ordinals) as ord
          cross join lateral (select v_lines -> ord as line) l
        returning 1
    )
    select count(*) into v_inserted from put;

    return jsonb_build_object(
        'outcome', 'COMMITTED',
        'statement_id', v_statement_id,
        'inserted', v_inserted,
        'duplicates_skipped', v_dupes,
        'restatements_skipped', v_restatements,
        'provisional_superseded', v_superseded
    );
end;
$$;

comment on function public.commit_statement(jsonb) is
    'Commits one statement atomically. Rule 6 first, then anchor 1 (stated total, branched: '
    'skipped only for a DETERMINISTIC statement that states none, otherwise required for every '
    'provenance including DETERMINISTIC when it IS printed), then anchor 2 (the balance delta, '
    'unconditional - the floor). Rule 7 supersession before the dedup read. Idempotent on '
    'content_sha256. A gate failure returns QUARANTINED having written only a file-status row and '
    'zero lines.';

revoke all on function public.commit_statement(jsonb) from public, anon;
grant execute on function public.commit_statement(jsonb) to authenticated;

-- =============================================================================================
-- I CANNOT APPLY THIS MIGRATION FROM THIS SESSION.
-- =============================================================================================
-- No Supabase CLI, no project credentials, no way to run this against the real project from here -
-- same limitation `54cdf5e` and `20260827000200` both stated plainly rather than claiming applied.
-- This file is the authored, reviewed migration; running it (`supabase db push`, or pasted into the
-- SQL editor) is still owed.
