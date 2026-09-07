-- LEGION: the client_uuid index becomes total, because a partial one cannot be inferred.
-- Depends on: 20260906000100_conversation_audit_client_uuid.sql
-- APPLIED 2026-09-07 by the session that made the mistake it corrects.
--
-- The migration this supersedes created the index PARTIAL:
--
--     create unique index ... on public.conversation_audit (client_uuid)
--         where client_uuid is not null;
--
-- The reasoning was that the 277 pre-UUID rows carry NULL and should be exempt, and that stating
-- the predicate made the intent checkable rather than incidental. Both halves were wrong.
--
-- Wrong on the mechanism: a plain unique index ALREADY permits many NULLs, because Postgres treats
-- them as distinct. The predicate bought exactly nothing the default did not give.
--
-- Wrong on the consequence, which is the part that mattered: PostgREST's `on_conflict=client_uuid`
-- emits a bare `ON CONFLICT (client_uuid)`, and Postgres cannot infer a PARTIAL index from a bare
-- conflict target. Every upload batch was rejected outright with
--
--     there is no unique or exclusion constraint matching the ON CONFLICT specification
--
-- so the fix that was supposed to unstick 142 audit rows swapped one silent discard for a loud
-- rejection and moved nothing. Proven against the live database in a rolled-back transaction on
-- 2026-09-07: the bare form was refused, the same statement with `where client_uuid is not null`
-- appended was accepted.
--
-- Total index. The 277 NULL rows are unaffected - verified, they are all still there.
-- ---------------------------------------------------------------------------------------------

drop index if exists public.conversation_audit_client_uuid_idx;

create unique index if not exists conversation_audit_client_uuid_idx
    on public.conversation_audit (client_uuid);
