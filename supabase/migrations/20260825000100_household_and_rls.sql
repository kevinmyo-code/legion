-- LEGION backend-erp, Phase 1 foundations.
-- Ticket: .scratch/backend-erp/issues/05-migration-path.md (phase 1)
-- Decisions: ADR 0038 (a BYO Supabase project is the system of record),
--            ticket 02 rulings 2 and 3 (household visibility, dashboard-created accounts).
--
-- Apply with the Supabase CLI:   supabase link --project-ref <ref> && supabase db push
-- Or paste this whole file into the dashboard SQL editor. The CLI path is preferred because it
-- records migration history; the dashboard path bypasses that history and can break a later
-- `db push`, which is Supabase's own documented warning.
--
-- This file is idempotent on purpose: every statement is create-if-not-exists or create-or-replace,
-- so re-applying it is safe. A stranger cloning the repo runs exactly this against their own fresh
-- project and gets a working household. Nothing here is specific to Kevin's project.

-- ---------------------------------------------------------------------------------------------
-- 1. A private schema for helper functions.
--
-- PostgREST exposes the `public` schema. Anything in `private` is unreachable over the API, which
-- is what we want for a SECURITY DEFINER function: it runs with the definer's rights and bypasses
-- RLS, so it must never be callable as an API endpoint in its own right.
-- ---------------------------------------------------------------------------------------------
create schema if not exists private;

revoke all on schema private from anon, authenticated;
grant usage on schema private to authenticated;

-- ---------------------------------------------------------------------------------------------
-- 2. The household roster.
--
-- Two adults today, more later, and NO ROLES EVER (CLAUDE.md section 1, ticket 02 ruling 2). There
-- is deliberately no `role` or `permissions` column: adding one is how tenancy and approval
-- workflows start, and the trust model exists to refuse them.
--
-- Rows are created in the DASHBOARD (ticket 02 ruling 3), never by the app. That is enforced
-- below by simply not writing any insert/update/delete policy: with RLS enabled and no such
-- policy, the anon and authenticated roles cannot write this table at all. Only the service role,
-- which the dashboard uses and which the app never holds, can.
-- ---------------------------------------------------------------------------------------------
create table if not exists public.household_members (
    user_id  uuid primary key references auth.users (id) on delete cascade,
    label    text,
    added_at timestamptz not null default now()
);

comment on table public.household_members is
    'The household roster. Membership is the ONLY authorization concept in LEGION: a member sees '
    'everything, a non-member sees nothing. No roles, no tenancy, no approval workflows. Rows are '
    'added in the Supabase dashboard, never by a client.';

-- ---------------------------------------------------------------------------------------------
-- 3. The membership helper, and why it exists.
--
-- The trap Supabase documents itself: a policy ON household_members that queries household_members
-- recurses, because evaluating the policy requires reading the table whose read is governed by
-- that policy. SECURITY DEFINER breaks the cycle by running the lookup with the function owner's
-- rights, which bypass RLS.
--
-- `set search_path = ''` is not decoration. A SECURITY DEFINER function without a pinned
-- search_path can be hijacked by a caller who puts a malicious table earlier on their own path,
-- so every object below is schema-qualified.
--
-- `(select auth.uid())` rather than a bare `auth.uid()` so Postgres evaluates it once per
-- statement instead of once per row - Supabase's own RLS performance guidance.
-- ---------------------------------------------------------------------------------------------
create or replace function private.is_household_member()
    returns boolean
    language sql
    security definer
    set search_path = ''
    stable
as $$
    select exists (
        select 1
        from public.household_members hm
        where hm.user_id = (select auth.uid())
    );
$$;

revoke all on function private.is_household_member() from public, anon;
grant execute on function private.is_household_member() to authenticated;

comment on function private.is_household_member() is
    'True when the calling user is on the household roster. SECURITY DEFINER so policies that call '
    'it do not recurse through household_members own RLS. Lives in `private` so PostgREST cannot '
    'expose it as an endpoint.';

-- ---------------------------------------------------------------------------------------------
-- 4. RLS on the roster itself.
--
-- Members may READ the roster (so the app can say who is in the household). Nobody may write it
-- from a client - see the note in section 2. A signed-out caller (anon) matches no policy and
-- therefore sees nothing, which is also what makes this table a safe keep-alive target.
-- ---------------------------------------------------------------------------------------------
alter table public.household_members enable row level security;

drop policy if exists household_members_select on public.household_members;
create policy household_members_select
    on public.household_members
    for select
    to authenticated
    using ((select private.is_household_member()));

-- ---------------------------------------------------------------------------------------------
-- 5. The keep-alive endpoint.
--
-- The free tier pauses a project after roughly a week without user database activity, and a paused
-- project rejects every request until a human clicks Resume in the dashboard. Ticket 01 ruling 8
-- removed the offline write queue, so a pause is a hard outage rather than a degraded mode - which
-- is what makes this load-bearing rather than a nicety.
--
-- A scheduled GitHub Actions workflow (.github/workflows/supabase-keepalive.yml) POSTs to
-- /rest/v1/rpc/keepalive once a day. It exists as a dedicated function rather than a SELECT
-- against some real table so that the ping is unambiguous, needs no RLS exception, and reads
-- clearly in the logs as what it is.
--
-- Callable by `anon`, deliberately: the workflow holds only the anon key, never a session. The
-- function returns the server clock and touches no data, so exposing it costs nothing.
-- ---------------------------------------------------------------------------------------------
create or replace function public.keepalive()
    returns timestamptz
    language sql
    set search_path = ''
    stable
as $$
    select now();
$$;

grant execute on function public.keepalive() to anon, authenticated;

comment on function public.keepalive() is
    'Returns the server clock. Exists solely so a scheduled external ping produces real database '
    'activity and keeps the free tier from pausing the project. Touches no data.';
