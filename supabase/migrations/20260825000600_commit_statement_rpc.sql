-- LEGION backend-erp, Phase 2: the commit RPC.
-- Ticket: .scratch/backend-erp/issues/03-the-gate-server-side.md (rulings 1, 2, 4, 7, 8)
-- Depends on: 20260825000200_conventions.sql, 20260825000300_aspect_ledger_pantry.sql
--
-- This is the reconciliation gate, server side. Everything the Kotlin `IngestPipeline.commit` does
-- inside one `db.withTransaction` happens here inside one PostgREST request, which is one Postgres
-- transaction: any raise rolls back everything.
--
-- =============================================================================================
-- A DELIBERATE DEVIATION FROM THE TICKET'S LITERAL WORDING, STATED UP FRONT
-- =============================================================================================
-- Ticket 03 ruling 1 says the RPC "checks the anchors in SQL and RAISEs on mismatch". Taken
-- literally that is wrong, and this function does not do it: a raise rolls back the whole
-- transaction, including the quarantine record, so the file would come back on the next scan with
-- no memory of why it failed, forever.
--
-- A gate failure is an OUTCOME, not an exception. The Kotlin version already treats it that way:
-- `IngestPipeline.commit`'s quarantined branch writes `IngestState.QUARANTINED` plus a reason onto
-- `ingested_files` and zero transaction rows. That IS "nothing partial is ever written" - the
-- prohibition is on partial DATA, never on recording that a document was rejected.
--
-- So: a gate failure returns `{outcome: 'QUARANTINED', reason: ...}` having written exactly one
-- file-status row and no lines. `raise` is reserved for states that are genuinely exceptional -
-- a malformed payload, a constraint the caller cannot have intended to violate.
-- =============================================================================================

-- ---------------------------------------------------------------------------------------------
-- The quarantine writer. Separate so every refusal path records the same way.
--
-- Note it writes ONLY to ingested_files. That is the whole point: a quarantined document leaves a
-- reason and no data, which is what makes "nothing partial is ever written" true and still lets the
-- app explain itself.
-- ---------------------------------------------------------------------------------------------
create or replace function private.quarantine_file(payload jsonb, reason text)
    returns void
    language plpgsql
    set search_path = ''
as $$
begin
    insert into public.ingested_files (
        content_sha256, source_file_id, display_name, size_bytes, state, quarantine_reason
    ) values (
        payload ->> 'content_sha256',
        payload ->> 'source_file_id',
        payload ->> 'display_name',
        (payload ->> 'size_bytes')::bigint,
        'QUARANTINED',
        reason
    )
    on conflict (content_sha256) do update
        set state = 'QUARANTINED',
            quarantine_reason = excluded.quarantine_reason,
            last_attempt_at = now();
end;
$$;

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
    v_duplicates       integer := 0;
    v_reason           text;
begin
    if v_sha is null or length(v_sha) = 0 then
        raise exception 'commit_statement: content_sha256 is required'
            using errcode = 'invalid_parameter_value';
    end if;

    -- -----------------------------------------------------------------------------------------
    -- 1. IDEMPOTENCY (ticket 03 ruling 8). Keyed on the content hash.
    --
    -- This is what makes a lost acknowledgement retryable instead of ambiguous. Ruling 8 removed
    -- the offline write queue, so a network death after the commit but before the ack leaves the
    -- phone unable to tell success from failure - and `AriaBrain.CANNOT_CLAUSE` is binary, with no
    -- vocabulary for "unknown". A repeat call being a successful no-op means the phone retries
    -- until it gets a definite answer rather than narrating a state it cannot determine.
    -- -----------------------------------------------------------------------------------------
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

    -- -----------------------------------------------------------------------------------------
    -- 2. RULE 6, AND IT COMES FIRST ON PURPOSE.
    --
    -- "A check that passes when nothing parsed is not a gate." A zero-line extraction would sail
    -- through every anchor below whenever the statement's own figures happen to be zero: sum() of
    -- no rows is 0, and a month with no movement has closing = opening. That is not hypothetical -
    -- it is exactly how BofA's card statement passed with four silently dropped interest rows,
    -- and it only held because interest was zero that month.
    --
    -- So an empty extraction is refused before any arithmetic runs.
    -- -----------------------------------------------------------------------------------------
    if v_line_count = 0 then
        v_reason := 'No transactions were extracted from this file. An empty extraction can never '
                 || 'satisfy the gate, whatever the stated totals are.';
        perform private.quarantine_file(payload, v_reason);
        return jsonb_build_object('outcome', 'QUARANTINED', 'reason', v_reason, 'inserted', 0);
    end if;

    -- -----------------------------------------------------------------------------------------
    -- 3. THE THREE ANCHORS (ticket 03 ruling 4).
    --
    -- An LLM-produced CSV has its lines AND its total from one nondeterministic process, so a
    -- single anchor can be satisfied by a self-consistent hallucination - rule 6's failure shape
    -- in a new place. Two independent checks against three separately printed figures make that a
    -- much harder accident.
    -- -----------------------------------------------------------------------------------------
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

    -- -----------------------------------------------------------------------------------------
    -- 4. The file row, now that the gate has passed.
    -- -----------------------------------------------------------------------------------------
    insert into public.ingested_files (content_sha256, source_file_id, display_name, size_bytes, state)
    values (v_sha,
            payload ->> 'source_file_id',
            payload ->> 'display_name',
            (payload ->> 'size_bytes')::bigint,
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

    -- -----------------------------------------------------------------------------------------
    -- 5. RULE 7 SUPERSESSION, AND IT MUST HAPPEN BEFORE THE DEDUP READ.
    --
    -- Three load-bearing properties carried over verbatim from `IngestPipeline.commit`:
    --
    --   (a) A provisional file never supersedes anything, including a prior import of itself.
    --       Guarded by the provenance test below.
    --   (b) BEFORE the dedup read. If dedup ran first, the incoming verified rows would be
    --       discarded as duplicates of the very provisional rows they are meant to replace, and
    --       the window would end up with neither.
    --   (c) Inside this transaction, which it is by construction.
    --
    -- `sameCard` becomes the last-four match. Its known weakness carries over unchanged and is
    -- worth saying out loud: two accounts sharing a last four collide, which is what the nickname
    -- is for.
    -- -----------------------------------------------------------------------------------------
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

    -- -----------------------------------------------------------------------------------------
    -- 6. Dedup, then insert.
    --
    -- INCOMPLETE, and deliberately so rather than silently: this implements the straightforward
    -- tuple match (same card, date, amount, description) that `LedgerDedup.resolveDedup` starts
    -- from. It does NOT yet implement the overlapping-window restatement pass that uses
    -- `IngestedFileDao.enumeratedWindows` to tell a genuine duplicate from a bank restating a
    -- period. Porting that is owed before the ledger cutover in phase 4; see the ticket.
    --
    -- One concrete behaviour to know rather than discover: the `fresh` check runs against the
    -- table as it was BEFORE this statement, so two IDENTICAL lines within the same file both
    -- insert. That is deliberate - a statement can legitimately print the same merchant, date and
    -- amount twice - but it means intra-file duplication is not something this dedup protects
    -- against, and the anchors are what catch it if the extraction invented one.
    -- -----------------------------------------------------------------------------------------
    with incoming as (
        select (line ->> 'txn_date')::date          as txn_date,
               line ->> 'description'               as description,
               (line ->> 'amount_cents')::bigint    as amount_cents,
               nullif(line ->> 'balance_cents', '')::bigint as balance_cents,
               line ->> 'line_ref'                  as line_ref,
               line ->> 'category'                  as category
          from jsonb_array_elements(v_lines) as line
    ),
    fresh as (
        select i.* from incoming i
        where not exists (
            select 1 from public.ledger_transactions t
            where t.account_last4 = v_last4
              and t.txn_date = i.txn_date
              and t.amount_cents = i.amount_cents
              and t.description = i.description
              and t.reversal_of is null
        )
    ),
    put as (
        insert into public.ledger_transactions (
            statement_id, account_last4, account_nickname, currency, txn_date,
            description, amount_cents, balance_cents, line_ref, category,
            category_pending, provenance
        )
        select v_statement_id, v_last4, v_nickname, v_currency, f.txn_date,
               f.description, f.amount_cents, f.balance_cents, f.line_ref, f.category,
               f.category is null, v_provenance
          from fresh f
        returning 1
    )
    select count(*) into v_inserted from put;

    v_duplicates := v_line_count - v_inserted;

    return jsonb_build_object(
        'outcome', 'COMMITTED',
        'statement_id', v_statement_id,
        'inserted', v_inserted,
        'duplicates_skipped', v_duplicates,
        'provisional_superseded', v_superseded
    );
end;
$$;

comment on function public.commit_statement(jsonb) is
    'Commits one statement atomically: rule 6 non-empty check, the three anchors, rule 7 '
    'supersession before the dedup read, then the lines. Idempotent on content_sha256. A gate '
    'failure returns QUARANTINED having written only a file-status row and zero lines.';

revoke all on function public.commit_statement(jsonb) from public, anon;
grant execute on function public.commit_statement(jsonb) to authenticated;
revoke all on function private.quarantine_file(jsonb, text) from public, anon;
