---
map: backend-erp
ticket: "06"
kind: research
date: 2026-08-25
---
# Supabase feasibility on the real free tier

Researched 2026-08-25 against supabase.com docs/pricing, postgrest.org, and supabase-kt's GitHub.
Every claim cited. NOT ESTABLISHED where sources were silent.

## 1. Free-tier limits and the pause policy

From https://supabase.com/pricing (fetched 2026-08-25):

| Limit | Free | Pro ($25/mo) |
|---|---|---|
| Active projects | 2 (extras can be paused indefinitely) | per-project billing |
| Database size | 500 MB (shared CPU, 500 MB RAM) | 8 GB disk, then $0.125/GB |
| File storage | 1 GB | 100 GB |
| API requests | Unlimited | Unlimited |
| Auth MAU | 50,000 | 100,000 |
| Realtime | 200 concurrent peak, 2M messages/mo | higher |
| Edge functions | 500k invocations | higher |
| Egress | 5 GB | 250 GB |
| Backups | none stated on pricing page for free; Pro has "daily backups stored for 7 days" | 7-day daily backups |
| Pausing | "Free projects are paused after 1 week of inactivity" | no pausing |

**Pause policy in exact terms** (https://supabase.com/docs/guides/platform/free-project-pausing):
- Trigger: "A Free plan project is considered inactive if it does not receive sufficient user
  database activity over the past week." Docs say "a few user requests to the database each day
  over the previous week" typically prevents pausing.
- Warning: two emails - "a warning email roughly one week before the pause takes effect" and a
  confirmation once paused.
- Data: NOT lost. "The project will return to its previous state, including data and
  configurations."
- Resume: MANUAL. Dashboard, select project, click "Resume project", confirm. Not automatic on
  incoming API traffic - a paused project rejects requests until a human resumes it.
- Restore window: "Once the project is paused, there is a 1-year window to restore the project on
  the platform from within Supabase Studio." After that, one-click restore is gone; backup
  downloads remain for restore into a NEW project
  (https://supabase.com/docs/guides/platform/upgrading#paused-projects).
- Exact request-count threshold for "sufficient activity": NOT ESTABLISHED - docs say
  "sufficient" without a number. Third-party posts claim one request/day suffices (e.g.
  https://tellmewhendown.com/blog/why-supabase-pauses-your-project) - reasoned, not primary.
- Free-tier point-in-time backups: pricing page lists backups only under Pro; third parties state
  free tier has zero backup retention (https://simplebackups.com/blog/supabase-free-tier-paused).
  Primary source is silent on an explicit "zero" - traced to pricing omission, corroborated
  third-party.

**Vacation scenario, honestly:** the app itself generates DB traffic only when a phone talks to
it. Two users away for 8+ days with the app closed = a pause is plausible. Consequence is an
outage, not data loss: writes fail until someone clicks Resume in the dashboard. A trivial daily
keep-alive ping (WorkManager on either phone, or a scheduled GitHub Action) defeats it, but that
is a crutch the design has to carry forever, and a paused project during the vacation itself
means the ERP is down exactly when a receipt photo from a trip wants ingesting.

## 2. supabase-kt maturity

https://github.com/supabase-community/supabase-kt:
- Kotlin Multiplatform client, ~839 stars, 3,149 commits, actively maintained by Jan Tennert
  under the supabase-community org. COMMUNITY project, not Supabase-official, but it is the
  client the official Supabase Kotlin docs reference (https://supabase.com/docs/reference/kotlin).
- Latest release 3.7.0, 2026-07-20 (custom OAuth providers, passkeys, binary realtime
  broadcasts); 3.6.0 on 2026-04-28 - steady cadence
  (https://github.com/supabase-community/supabase-kt/releases).
- Modules: auth-kt, postgrest-kt, realtime-kt, storage-kt, functions-kt, compose-auth. Full
  coverage of what LEGION needs.
- Android: min SDK 26 (desugaring below that). LEGION targets modern Android - fine.
- Uses Ktor underneath. Plain okhttp against PostgREST's REST endpoints is always available as a
  fallback; the API is plain HTTP+JWT.

## 3. RPC transactions for the gate

https://docs.postgrest.org/en/latest/references/transactions.html:
- Every PostgREST request runs inside ONE transaction; there is no way to hold a transaction
  open across HTTP requests.
- An RPC (Postgres function exposed via `/rpc/name`) executes atomically inside that request's
  transaction: any raised error rolls back everything.
- Fit for §4: the "commit whole file or quarantine" gate maps exactly to one
  `commit_statement(jsonb)` SQL function - insert all rows, verify sum against printed total in
  SQL, `RAISE` on mismatch, and nothing partial ever lands. Better than Room's client-side
  transaction for multi-device: the atomicity lives server-side.
- Caveat: GET-invoked functions run READ ONLY; commits must be POSTed. Traced from same page.

## 4. RLS for a household

https://supabase.com/docs/guides/database/postgres/row-level-security:
- Standard team pattern: a membership table + policies checking it via `auth.uid()`.
- Known trap documented by Supabase themselves: mutually-referencing policies recurse; fix is a
  `security definer` helper function in a private schema returning the caller's household ids,
  and policies use `id in (select private.user_household_ids())`.
- For LEGION's model (two adults, all rows visible to both) the degenerate form is even simpler:
  one `household_members(user_id)` table, every data table gets
  `using (auth.uid() in (select user_id from ...))` via the helper. No roles, no tenancy - §1's
  trust model holds. Index policy-filtered columns; wrap `auth.uid()` in `(select ...)` for
  per-statement caching (both from the same doc).

## 5. Migration bootstrap for a BYO project

https://supabase.com/docs/guides/deployment/database-migrations:
- Canonical path: Supabase CLI. `supabase migration new`, timestamped SQL in
  `supabase/migrations/`, `supabase link`, `supabase db push` (`--include-seed` for seed data).
- Dashboard SQL editor CAN execute arbitrary SQL, but docs warn schema changes made there bypass
  migration history and later break `db push`.
- Clone-and-run implication: a stranger standing up their own project must either (a) install the
  CLI and run `db push` against the repo's committed migrations - clean, versioned, repeatable -
  or (b) paste one consolidated bootstrap SQL file into the dashboard editor - lower friction,
  no history. Both work; (a) is the honest recommendation, (b) as documented fallback. Whether
  the APP could self-bootstrap its schema over PostgREST: NOT ESTABLISHED, and almost certainly
  no - PostgREST exposes data, not DDL, short of shipping a DDL-executing RPC, which would be
  runtime DDL and against §1.

## 6. Realtime on Android

https://supabase.com/docs/reference/kotlin/subscribe:
- realtime-kt exposes `postgresChangeFlow<PostgresAction>` as Kotlin Flows over a websocket
  channel; insert/update/delete events per table.
- Requirements: realtime replication is OFF by default per table and must be enabled;
  `REPLICA IDENTITY FULL` for old-row payloads on update/delete.
- Free tier: 200 concurrent connections, 2M messages/mo (pricing page) - two phones is nothing.
- Constraint to design around: a websocket dies when Android dozes the app. Freshness on wake
  should be re-fetch on foreground + realtime while foregrounded, not a claimed always-on
  socket. Reasoned from Android platform behaviour, not a Supabase source.

## 7. Google OAuth on Android via Supabase Auth

https://supabase.com/docs/guides/auth/social-login/auth-google:
- Native flow: Credential Manager prompts for the Google account, yields an ID token, app calls
  `supabase.auth.signInWith(IDToken) { idToken = ...; provider = Google; nonce = ... }`. No
  browser tab.
- Requires TWO Google Cloud OAuth client IDs: a Web client ID (the one actually used in app
  code) and an Android client ID carrying the app's SHA-1 fingerprint. "You have to create OAuth
  client IDs for both a Web and Android application. The Web client ID is the one used in your
  Android app."
- Browser-redirect `signInWithOAuth` exists as fallback.
- Clone-and-run: the SHA-1-keyed Android client ID is the SAME trap the Drive finding already
  flagged (§2 open finding 1). A stranger with their own signing cert must create their own
  Google Cloud OAuth clients and paste both ids - BYO-everything makes this a documented setup
  step, not a blocker, but it does not disappear. Alternative that avoids Google Cloud entirely:
  Supabase email/password or magic-link auth, zero Google config. Traced from same doc family.

## Verdict

The free tier fits a two-user household ERP on every hard number: 500 MB Postgres dwarfs the
Room DB, API requests are unlimited, 50k MAU and 200 realtime connections are absurd headroom
for two phones, and the 2-active-project cap costs nothing. The one real hazard is the 7-day
inactivity pause: data survives, but resume is a manual dashboard click, and an idle vacation
week can take the backend down until someone notices the email and clicks Resume. A daily
keep-alive write defeats it cheaply but permanently couples uptime to a crutch. Secondary
free-tier weakness: no backups (Pro gets 7-day dailies), so the xlsx mirror / export surface
carries real weight as the recovery story. Cheapest paid fallback is Pro at $25/mo, which
removes pausing and adds daily backups - steep for a household app; a $10-25/mo self-hosted VPS
running the open-source stack is the other exit but forfeits the zero-ops premise. Recommendation:
free tier is workable WITH an in-app keep-alive and eyes-open acceptance of manual resume;
budget Pro only if a silent pause ever actually bites.

## Assumptions ledger

- Free-tier limits, pause policy, restore window, emails, Pro pricing: **traced** (supabase.com
  pricing + free-project-pausing + upgrading docs, fetched 2026-08-25).
- Exact activity threshold that prevents pausing: **NOT ESTABLISHED** in primary sources;
  one-request/day claim is third-party, **reasoned**.
- Free tier has zero backup retention: **reasoned** from pricing-page omission + third-party
  corroboration; primary source never states "zero".
- supabase-kt versions, cadence, modules, min SDK, community ownership: **traced** (GitHub repo
  and releases).
- PostgREST one-transaction-per-request, atomic RPC: **traced** (postgrest.org docs).
- RLS household pattern + recursion fix: **traced** (Supabase RLS guide).
- Migration CLI flow and dashboard warning: **traced** (Supabase migrations guide). App
  self-bootstrap over PostgREST impossible: **reasoned**.
- Realtime Kotlin API and per-table enablement: **traced** (Kotlin reference). Doze killing the
  socket: **reasoned** from Android platform behaviour.
- Google OAuth dual-client-ID requirement and signInWithIdToken flow: **traced** (Supabase
  Google auth guide). SHA-1 clone-and-run friction parallel to the Drive finding: **reasoned**.
