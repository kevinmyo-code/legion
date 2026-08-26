-- LEGION backend-erp, Phase 2: wire the real dedup into commit_statement.
-- Ticket: .scratch/backend-erp/issues/05-migration-path.md (phase 2, owed item 2)
-- Depends on: 20260825000800_ledger_dedup.sql
--
-- Replaces the placeholder tuple match with `private.ledger_resolve_dedup`. Everything else about
-- the function is unchanged: rule 6 first, the three anchors, rule 7 supersession BEFORE the dedup
-- read, idempotency on content_sha256, and a gate failure returning QUARANTINED rather than raising.
--
-- The supersession-before-dedup ordering matters more now, not less. With a real dedup in place, a
-- provisional row still present when the dedup runs would absorb the very reconciled row that is
-- meant to replace it, and the window would end up with neither.

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

    -- 3. The three anchors (ticket 03 ruling 4).
    select coalesce(sum((line ->> 'amount_cents')::bigint), 0),
           min((line ->> 'txn_date')::date),
           max((line ->> 'txn_date')::date)
      into v_sum, v_min_date, v_max_date
      from jsonb_array_elements(v_lines) as line;

    if v_sum <> v_stated_total then
        v_reason := format(
            'Lines sum to %s cents but the statement states a total of %s cents. Nothing was written.',
            v_sum, v_stated_total);
        perform private.quarantine_file(payload, v_reason);
        return jsonb_build_object('outcome', 'QUARANTINED', 'reason', v_reason, 'inserted', 0);
    end if;

    if (v_closing - v_opening) <> v_sum then
        v_reason := format(
            'Closing balance minus opening balance is %s cents but the lines sum to %s cents. '
            'Nothing was written.',
            v_closing - v_opening, v_sum);
        perform private.quarantine_file(payload, v_reason);
        return jsonb_build_object('outcome', 'QUARANTINED', 'reason', v_reason, 'inserted', 0);
    end if;

    -- 4. Gate passed: the file row and the header.
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

    -- 5. Rule 7 supersession, BEFORE the dedup read. Three properties carried over verbatim:
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

    -- 6. The real dedup. See 20260825000800_ledger_dedup.sql for the algorithm and why it is a loop.
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

revoke all on function public.commit_statement(jsonb) from public, anon;
grant execute on function public.commit_statement(jsonb) to authenticated;
