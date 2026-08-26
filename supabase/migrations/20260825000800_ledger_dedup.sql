-- LEGION backend-erp, Phase 2: the real dedup, replacing the placeholder in commit_statement.
-- Ticket: .scratch/backend-erp/issues/05-migration-path.md (phase 2, owed item 2)
-- Ports: ledger/LedgerDedup.kt (resolveDedup, dedupKey, looseKey, LedgerCoveredWindow)
--        data/local/IngestedFileDao.kt:153-164 (enumeratedWindows)
--
-- The first cut of `commit_statement` did a single tuple match and said so. This is the real thing:
-- a two-pass matching over a SHARED, DEPLETING credit pool. It is transcribed as a loop rather
-- than expressed as an anti-join, because the algorithm is stateful and a set-based version is only
-- provably equivalent after the arithmetic is worked out. A faithful loop that is obviously right
-- beats a clever join that is probably right, for something that decides whether money gets
-- counted twice.
--
-- =============================================================================================
-- THE PROBLEM IT SOLVES, because the code is meaningless without it
-- =============================================================================================
-- Kevin's July checking PDF covers 06/05 to 07/06. His mid-cycle CSV covers 07/01 to 07/31. Six
-- days are stated twice, and BofA words the same transaction differently in the two exports:
--   'PURCHASE   0706 VPN24.ME EDINBURGH    00'   vs   'VPN24.ME 07/06 PURCHASE EDINBURGH 00'
-- A description-sensitive key catches neither, so the row double-counts. This recurs every month
-- by construction.
--
-- The naive fix - drop description from the key - is worse: it collapses two genuinely separate
-- $4.50 coffees on the same day. So the relaxation is NARROWED to dates some other committed
-- statement has already enumerated completely. Outside such a window nothing changes, because no
-- prior statement claims to have listed those rows.
--
-- =============================================================================================
-- A CONSEQUENCE OF TICKET 03 RULING 5 THAT NEEDED CATCHING
-- =============================================================================================
-- `LedgerAccountIdentity.kt` is emphatic that `sameCard`'s last-four suffix match must NEVER be
-- folded into the dedup key: a checking account ending in the same four digits would absorb a
-- card's rows, "a materially worse bug than the one this file exists to fix".
--
-- Ruling 5 then made the STORED identity last-four plus nickname. So keying dedup on
-- `account_last4` alone would reintroduce precisely that bug through the schema instead of through
-- the predicate. **The key below is therefore (account_last4, account_nickname) together**, which
-- restores plain equality on a full account identity. The nickname is load-bearing, not a label.

-- ---------------------------------------------------------------------------------------------
-- The description normaliser.
--
-- Kotlin: description.trim().replace(Regex("\\s+"), " ").uppercase()
-- Collapse-then-trim is used here rather than trim-then-collapse: they agree, because collapsing
-- maps any run to a single space, and this way one regexp does the interior and the edges.
--
-- KNOWN, ACCEPTED DIVERGENCE: Kotlin's `uppercase()` is Locale.ROOT with full Unicode mapping and
-- expands one-to-many (Eszett becomes SS). Postgres `upper()` is collation-driven and does not.
-- For ASCII bank descriptions this never bites. It matters that the failure direction is known: a
-- divergence causes a false NON-match, which double-counts - the very failure this file prevents.
-- If non-ASCII descriptions ever appear, normalise in the client before sending.
-- ---------------------------------------------------------------------------------------------
create or replace function private.ledger_dedup_description(d text)
    returns text
    language sql
    immutable
    set search_path = ''
as $$
    select upper(btrim(regexp_replace(coalesce(d, ''), '\s+', ' ', 'g')));
$$;

-- ---------------------------------------------------------------------------------------------
-- Enumerated windows: spans another committed statement already listed completely.
--
-- Ports `IngestedFileDao.enumeratedWindows`. Every filter there is load-bearing and kept:
--   same account (plain equality, never sameCard)
--   NOT this statement, so a re-import cannot treat its own prior window as someone else's
--     testimony
--   only COMMITTED statements, because only a statement that passed the gate can claim to have
--     listed everything in its own range
--   overlap with the incoming span, purely a narrowing optimisation
--
-- **Bounds are the statement's ACTUAL first and last transaction dates, not its printed period.**
-- The Kotlin is explicit about this: a printed period can run past the last transaction, and
-- claiming completeness over that tail would be unsupported. Derived here rather than stored, so
-- it cannot drift from the rows themselves.
-- ---------------------------------------------------------------------------------------------
create or replace function private.ledger_enumerated_windows(
    p_last4     text,
    p_nickname  text,
    p_exclude   uuid,
    p_from      date,
    p_to        date
)
    returns table (from_ms date, to_ms date)
    language sql
    stable
    set search_path = ''
as $$
    select min(t.txn_date), max(t.txn_date)
    from public.statements s
    join public.ledger_transactions t on t.statement_id = s.id
    where s.account_last4 = p_last4
      and s.account_nickname = p_nickname
      and (p_exclude is null or s.id <> p_exclude)
    group by s.id
    having min(t.txn_date) <= p_to and max(t.txn_date) >= p_from;
$$;

-- ---------------------------------------------------------------------------------------------
-- The dedup itself.
--
-- Returns the incoming ordinals that should be INSERTED, plus the two counters. The caller does
-- the inserting, so this function stays pure and testable on its own.
--
-- `p_incoming` is a jsonb array; ordinal is its index, which fixes the iteration order. Order does
-- not affect the COUNTS, but it does decide WHICH concrete row survives when a loose key has a mix
-- of in-window and out-of-window survivors, and two Kotlin tests assert on the survivor's identity.
-- ---------------------------------------------------------------------------------------------
create or replace function private.ledger_resolve_dedup(
    p_last4    text,
    p_nickname text,
    p_incoming jsonb,
    p_exclude  uuid,
    p_from     date,
    p_to       date
)
    returns table (insert_ordinals integer[], duplicates_skipped integer, restatements_skipped integer)
    language plpgsql
    stable
    set search_path = ''
as $$
declare
    v_n            integer := jsonb_array_length(p_incoming);
    v_ord          integer;
    v_row          jsonb;
    v_date         date;
    v_amount       bigint;
    v_desc         text;
    v_strict       text;
    v_loose        text;
    v_credit       integer;
    v_in_window    boolean;
    v_survivors    integer[] := '{}';
    v_out          integer[] := '{}';
    v_restatements integer := 0;
    v_strict_pool  jsonb := '{}'::jsonb;
    v_loose_pool   jsonb := '{}'::jsonb;
    rec            record;
begin
    -- Credit pools, built from EXISTING rows only. Incoming is never deduplicated against itself:
    -- two identical lines in one statement are two genuine purchases, and collapsing them is the
    -- original bug this function was written to fix.
    --
    -- Scoped to this account and the incoming date span, matching what the Kotlin callers pre-filter
    -- before calling resolveDedup. Without that scoping a far-future row with the same key would
    -- absorb an incoming one.
    for rec in
        select private.ledger_dedup_description(t.description) as nd,
               t.txn_date, t.amount_cents, count(*) as c
        from public.ledger_transactions t
        where t.account_last4 = p_last4
          and t.account_nickname = p_nickname
          and t.txn_date between p_from and p_to
          and t.reversal_of is null
        group by 1, 2, 3
    loop
        v_strict := rec.txn_date::text || '|' || rec.amount_cents::text || '|' || rec.nd;
        v_loose  := rec.txn_date::text || '|' || rec.amount_cents::text;
        v_strict_pool := jsonb_set(v_strict_pool, array[v_strict],
            to_jsonb(coalesce((v_strict_pool ->> v_strict)::int, 0) + rec.c::int));
        v_loose_pool := jsonb_set(v_loose_pool, array[v_loose],
            to_jsonb(coalesce((v_loose_pool ->> v_loose)::int, 0) + rec.c::int));
    end loop;

    -- PASS ONE: exact matches, with NO window condition. Runs first and completely, so a row that
    -- CAN be matched precisely never spends a loose credit some other row needs. Fusing the passes
    -- changes behaviour; a Kotlin test pins exactly that.
    for v_ord in 0 .. greatest(v_n - 1, 0) loop
        exit when v_n = 0;
        v_row    := p_incoming -> v_ord;
        v_date   := (v_row ->> 'txn_date')::date;
        v_amount := (v_row ->> 'amount_cents')::bigint;
        v_desc   := private.ledger_dedup_description(v_row ->> 'description');
        v_strict := v_date::text || '|' || v_amount::text || '|' || v_desc;
        v_loose  := v_date::text || '|' || v_amount::text;

        v_credit := coalesce((v_strict_pool ->> v_strict)::int, 0);
        if v_credit > 0 then
            -- Consume one strict credit AND one loose credit: both passes draw on the same pool of
            -- existing rows, so one committed row absorbs exactly one incoming row, never two.
            v_strict_pool := jsonb_set(v_strict_pool, array[v_strict], to_jsonb(v_credit - 1));
            v_loose_pool := jsonb_set(v_loose_pool, array[v_loose],
                to_jsonb(greatest(coalesce((v_loose_pool ->> v_loose)::int, 0) - 1, 0)));
        else
            v_survivors := v_survivors || v_ord;
        end if;
    end loop;

    -- PASS TWO: the loose relaxation, gated on the window. With no windows this degenerates to
    -- "insert every survivor", which is the behaviour outside a covered span.
    foreach v_ord in array v_survivors loop
        v_row    := p_incoming -> v_ord;
        v_date   := (v_row ->> 'txn_date')::date;
        v_amount := (v_row ->> 'amount_cents')::bigint;
        v_loose  := v_date::text || '|' || v_amount::text;

        select exists (
            select 1 from private.ledger_enumerated_windows(p_last4, p_nickname, p_exclude, p_from, p_to) w
            where v_date between w.from_ms and w.to_ms
        ) into v_in_window;

        v_credit := coalesce((v_loose_pool ->> v_loose)::int, 0);
        if v_in_window and v_credit > 0 then
            v_loose_pool := jsonb_set(v_loose_pool, array[v_loose], to_jsonb(v_credit - 1));
            v_restatements := v_restatements + 1;
        else
            v_out := v_out || v_ord;
        end if;
    end loop;

    insert_ordinals := v_out;
    -- Computed by subtraction, exactly as the Kotlin does, so every non-inserted row counts once.
    duplicates_skipped := v_n - coalesce(array_length(v_out, 1), 0);
    restatements_skipped := v_restatements;
    return next;
end;
$$;

comment on function private.ledger_resolve_dedup(text, text, jsonb, uuid, date, date) is
    'Port of LedgerDedup.resolveDedup. Two passes over a shared depleting credit pool: exact '
    'strict-key matches first with no window condition, then loose (description-dropped) matches '
    'only inside dates another committed statement already enumerated. Incoming is never deduped '
    'against itself. Keyed on (account_last4, account_nickname) so it stays plain equality on a '
    'full account identity rather than a last-four suffix match.';
