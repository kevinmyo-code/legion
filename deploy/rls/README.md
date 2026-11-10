# Row-level security: turning it on

ADR 0045's second layer (architecture review 2026-11-10, #11). Code: `server/household/rls.py`,
`server/household/rls_middleware.py`, `server/household/migrations/0009_rls_backstop.py`.

**What it does.** With `LEGION_RLS=on`, every request runs as the Postgres role `legion_app`
(no BYPASSRLS, owns nothing) inside one transaction, with `app.household_id` set to the
caller's household. Policies on the tenant tables let that role see and write only that
household's rows. A view that forgets its `household=` filter then returns nothing from another
household instead of leaking it. Jobs, management commands and migrations keep the connecting
role, which owns the tables and so is not bound by the policies (the bypass path).

**What it does not do.** It guards against a Python bug, not SQL injection: injected SQL could
`RESET ROLE`. The stronger form (step 7) closes that.

## Phase 1 tables (the ones the recruiter demo can reach)

`events`, `event_skips`, `suggestion_pins`, `checklists`, `checklist_items`, `checklist_ticks`,
`purchases`, `places`, `voice_notes`, `item_lists`, `list_items`, `categories`, `category_rules`,
`budget_targets`, `statements`, `ledger_transactions`, `ledger_transaction_categories`,
`merchant_aliases`, `finance_members`, `finance_accounts`, `finance_balance_snapshots`,
`finance_income_plans`, `finance_policies`, `grocery_staples`, `receipts`, `receipt_line_items`,
`vehicles`, `vehicle_specs`, `drives`, `drive_reassignments`, `code_events`, `code_clear_events`,
`obd_samples`, `oil_analyses`, `service_history`, `maintenance_schedules`, `chassis_quirks`,
`build_entries`, `procedures`, `procedure_versions`, `procedure_runs`, `procedure_steps`,
`assistant_companions`, `assistant_calls`, `device_logs`.

Two exceptions to "own household only", both narrow:
- `procedures`, `procedure_versions`: a built-in (no household) is readable by everyone, never
  writable from a request.
- `device_logs`: a recruiter visitor's conversation record has no household; a request may write
  and read back only its OWN user's such rows (`app.user_id`).

Phase 2 (not yet covered): body, memory, ID vault, Claude, watches, open loops, push, books,
briefs, games, MCP calls, ingest runs, source credentials, Plaid items. Same mechanism: add the
names to `RLS_TABLES` and a migration that calls `apply_rls()` again.

## Kevin's steps (prod DB; nothing here has been run)

Run in the Supabase SQL editor (or `psql` with the DATABASE_URL secret), in order. Steps 1-3
change nothing the engine notices; step 5 is the switch; step 6 is the undo.

1. **Read only: what the engine connects as, and who else can reach the tables.**
   ```sql
   select current_user, r.rolsuper, r.rolbypassrls, version()
     from pg_roles r where r.rolname = current_user;
   select c.relname, pg_get_userbyid(c.relowner) as owner, c.relrowsecurity
     from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public' and c.relkind = 'r' order by 1;
   -- Supabase grants anon/authenticated on new public tables by default. Any Django-managed
   -- tenant table listed here with relrowsecurity = false is readable through the REST API with
   -- the anon key until the migration in step 4 turns RLS on for it.
   select table_name, grantee, string_agg(privilege_type, ',')
     from information_schema.role_table_grants
    where table_schema = 'public' and grantee in ('anon', 'authenticated')
    group by 1, 2 order by 1;
   ```
   Expect the engine's role to own the Django-managed tables. If the legacy tables are owned by
   another role (e.g. `supabase_admin`), the engine reads them today only because its role has
   BYPASSRLS; that stays true and changes nothing below.
2. **Create the role** (once):
   ```sql
   create role legion_app nologin nosuperuser nobypassrls noinherit;
   grant legion_app to postgres;            -- the role DATABASE_URL logs in as
   ```
   On Postgres 16+, if `set role legion_app` later says permission denied:
   `grant legion_app to postgres with set true;`.
3. **Grant it DML** (re-runnable): paste `deploy/rls/grants.sql`. It also sets default
   privileges so tables later migrations create get the same grants.
4. **Ship a commit that contains this branch.** Migration `household.0009_rls_backstop` enables
   RLS and creates the policies. With `LEGION_RLS` unset nothing changes for the engine (it owns
   the tables); Supabase's `anon`/`authenticated` lose access to any Django-managed phase-1 table
   that had RLS off (they should never have had it).
5. **Switch it on:** `LEGION_RLS=on` (in `deploy/cloudrun/flags.yaml` once ship-as-code lands,
   else `deploy/cloudrun/.env`), ship. Then check: the web app and the phone load, `/try` enters
   a visit, and the logs show no `row-level security` or `permission denied` errors:
   `gcloud logging read 'resource.type="cloud_run_revision" AND textPayload:"row-level security"' --freshness=1h`
6. **Undo:** remove `LEGION_RLS` and ship (or redeploy the previous revision). The policies stay
   and are inert. Dropping them: `python manage.py migrate household 0008` as a job execution.
7. **Later, the stronger form:** give `legion_app` LOGIN and a password, store a second secret
   `DATABASE_URL_APP` for the service, keep `DATABASE_URL` (owner) for the job, and drop the
   `SET LOCAL ROLE`. Then even injected SQL cannot leave the role. Needs a settings change to read
   the second URL; not built.

## Performance notes

- One extra round trip per request (`SET LOCAL ROLE` + `set_config`), plus one when a token
  authenticates. Sub-millisecond on the same network; Supabase is cross-region today, so call it
  one more RTT (single-digit ms) per request. `reasoned`
- The policy predicate is `household_id = <constant>`; `current_setting()` is STABLE, so the
  planner evaluates it once per statement and uses the existing `household_id`-leading indexes
  (ADR 0045 re-keyed every unique index to lead with it). Python already adds the same filter,
  so plans should not change. `reasoned`; not measured against prod data.
- Each request is one transaction now. A slow external call inside a request (Gemini,
  `ask_engine` up to 45 s) holds the connection "idle in transaction" for that long. With the
  session pooler and 2 workers x 4 instances that is at most 8 connections, same as today.
  Watch `idle_in_transaction_session_timeout` if Supabase sets one. `reasoned`
- A 500 now rolls back the request's writes as a whole (previously each statement autocommitted).
  That is the safer behaviour, but it is a change. `reasoned`
- A streaming response produces its body after the transaction ends, under the owner role.
  No current view streams tenant rows. `traced` (grep for StreamingHttpResponse)
