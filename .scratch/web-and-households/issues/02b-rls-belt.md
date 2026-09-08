---
map: web-and-households
ticket: "02b"
title: "Postgres RLS keyed on a session variable Django sets per request"
type: build
status: open
blockers: ["02"]
blocked-by: ["[[02b-rls-belt]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# RLS belt

Ticket 02's Django filter is the braces. This is the belt: a future view that forgets to scope
returns nothing rather than another family's rows. CLAUDE.md's checklist: an integrity rule that
must hold even when Django has a bug is SQL shipped by a Django migration.

## Shape

- Middleware `household.middleware.TenantContextMiddleware`, installed after
  `AuthenticationMiddleware`: for an authenticated request with a household, run
  `SET LOCAL app.household_id = %s` inside the request transaction. Requires
  `ATOMIC_REQUESTS = True` on the default connection (`SET LOCAL` outside a transaction is a
  no-op, silently - that silence is the trap). Unauthenticated requests set nothing.
- `RunSQL` migration, looping `household.tenancy.TENANT_TABLES`:
  `ALTER TABLE public.<t> ENABLE ROW LEVEL SECURITY; ALTER TABLE public.<t> FORCE ROW LEVEL
  SECURITY;` and one policy per table: `USING (household_id = current_setting('app.household_id',
  true)::uuid) WITH CHECK (same)`. `FORCE` is what makes it bind the table owner too; without it,
  the role Django connects as on Supabase (owner) bypasses every policy and the belt is decorative.
- **The worker has no request.** Nightly backup, the Canvas poller and anything from
  `deploy/crontab` run with no household set and would see nothing. Give the worker a second role
  with `BYPASSRLS` and its own `DATABASE_URL` (Cloud Run Job env), or have each job iterate
  households and set the variable itself. Pick the role: it is one `CREATE ROLE`, and it keeps job
  code free of tenancy.
- **pytest connects as the owner** and would hit `WITH CHECK` on every fixture insert. The test
  role gets `BYPASSRLS`; ONE dedicated test opens a second connection as a role without it, sets the
  variable to household A, and proves a `SELECT` on each table returns only A's rows and an `INSERT`
  with B's id is refused. That single test is the proof; the rest of the suite is not the place to
  re-prove it 589 times.

## Done means

The proof test green. Every existing test green under the bypass role. A deliberately unscoped
view added in a test (and removed) returns an empty list for a household, not the other family's
rows.
