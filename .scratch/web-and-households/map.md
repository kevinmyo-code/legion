---
map: web-and-households
title: "One engine, many households, a real web client"
charted: 2026-09-08
charted-by: "Kevin + Fable"
effort: "`.scratch/web-and-households/`"
tickets: 14
open: 14
status: open
tags: [map]
---

# One engine, many households, a real web client

**Kevin, 2026-09-08**, after reading a Gemini architecture discussion (Supabase > Cube > Django >
React/PWA, Android sideloaded): *"this is what im trying to achieve... check if we are using the
frontend libraries, android libraries etc that are mentioned here. also the hosting decisions. you
can improve on it, tell me if something is not right. also wondering if we really need cube. my goal
is > my parents can also register as users, they create a new household group etc. and we do have a
django web app but not very intuitive. make a comprehensive plan, then use opus to implement."*

Three things are true today and the plan follows from them:

1. **There is no web app.** `server/` has no templates, no HTML, no static pipeline. The "django web
   app" is Django admin with three models registered. django-engine ticket 08 (a Django-templates +
   HTMX PWA) has never been started.
2. **There is exactly one household, by ruling.** CLAUDE.md §1 says "no roles, no tenancy, ever";
   ADR 0044 rule 3 says one household per server; `household/models.py` says "there is no signup and
   no invite flow". No data table carries a household or user column. Parents registering and
   creating their own group is a **ruling change**, not a feature (ticket 01, ADR 0045).
3. **The engine is live on Cloud Run and every aspect is routed** (85 paths, `server/openapi.yaml`).
   The web client does not need a new backend; it needs a client generated from the contract that
   already exists.

## The doc, checked against the repo

| Doc says | Repo has | Verdict |
|---|---|---|
| Supabase > **Cube** > Django > React | Supabase Postgres > Django > Android. No Cube anywhere | **Drop Cube** (ticket 10). Whole DB is ~30k rows under a 500 MB ceiling; Postgres aggregates that in milliseconds. Cube would be a second rules layer beside Django (ADR 0044: rules live once), a second container needing a persistent volume (does not fit Cloud Run min-instances 0), and a second place to re-implement tenancy. The doc's own "skip Cube if" list (small user base, low concurrency, sub-500 ms queries, no dynamic pivoting) is all true here |
| Supabase with built-in Auth, RLS, Realtime | Supabase as **managed Postgres only**; Auth/Realtime/PostgREST go dark at cutover (django-engine 10) | Doc is wrong for LEGION. The `auth.uid()`-keyed RLS policies are dead after cutover; tenancy must be enforced in Django and re-keyed in SQL (ticket 02b) |
| React + Vite + TS, Tailwind, shadcn/ui, ECharts, monolith in the Django repo, Vite build into `static/`, one `index.html` shell | Nothing. ADR 0040 originally named React + Vite + TS; ticket 08 later chose Django templates + HTMX | **Adopt React + Vite + TS** (ticket 04), supersede ticket 08's HTMX choice. Improvement over the doc: generate the TypeScript client from `openapi.yaml` so drift is a compile error, not a runtime surprise. Recharts via shadcn charts first; ECharts only if a Canvas-scale chart (OBD telemetry, 20k points) needs it |
| Multi-tenancy: shared schema + `company_id` column, enforced in Cube security context + Django middleware; signup creates group in Django | One household, a membership flag, no tenant column anywhere | **Shared schema + `household_id`**, enforced at ONE Django choke point (`SyncedModelViewSet.get_queryset`) plus Postgres RLS keyed on a session variable Django sets per request (tickets 02, 02b). Invite-only signup (ticket 03); open signup is a one-env-var switch, off by default |
| One always-on Ubuntu VM (Oracle A1 free) running Cube + Django in compose; GitHub Actions SSH-deploys; or Render free tier | **Cloud Run** service + Job + Scheduler, min 0, deployed by `deploy/cloudrun/deploy.py`; compose kept for the BYO path; no CI at all | **The doc wins, minus Cube - REOPENED by Kevin 2026-09-08** after the cold start was measured at 5.4 s. The map first said "keep Cloud Run" on the strength of the 2026-09-05 survey's Oracle objections (halved allowance, reclamation); a Pay As You Go tenancy answers both. Oracle A1 VM, Postgres in compose, Caddy, Actions deploying over SSH (tickets 12, 13, 08, 09) |
| Android: Vico charts, Kizitonwose calendar, Ktor **or** Retrofit, kotlinx-serialization **or** Moshi, Coil, Room, Porcupine wake word | Ktor+OkHttp (`EngineHttp`), kotlinx-serialization, Room on KSP, Compose/M3, hand-built `DeckCharts` (Tufte kit, tested), Vosk wake word. No Vico, Kizitonwose, Coil, Retrofit, Moshi | **Add nothing.** Ktor and kotlinx are the doc's own first picks and are in. `DeckCharts` exists and quant-viz ruled "no new chart types"; charting for the family belongs on the web (ADR 0040: the phone is the specialised client). A second HTTP or JSON stack is drift. Hilt (architecture ticket) is decided and NOT landed - unchanged by this map |
| JWT for the phone | Per-device opaque token, revocable alone (ADR 0044 rule 3) | Keep tokens. JWT loses per-device revocation without a denylist |
| Django templates only as an `index.html` shell | Agree | Ticket 04 |
| Nothing on offline, outbox, backups, the §4 gate, provenance | All built and hardware-verified | The doc has no equivalent; these are what the plan must not regress. `unverified` is printed in words on every web surface (MEMORY: trust disclosures are not furniture) |

## What this map does NOT reopen

- **No roles inside a household** beyond one `owner` used only to invite and remove members. Data
  access within a household stays all-or-nothing. No approval workflows: an invite is a code, not a
  request that waits.
- **Clone-and-run** (ADR 0003, 0044 rule 5) stands: a stranger's compose stack simply has one
  household. Kevin hosting one engine for his own family is the plain reading of the goal, and it is
  recorded as such in ADR 0045.
- **The phone changes almost nothing.** Its token already identifies a user; the user now identifies
  a household; the API scopes by it. Signup and household management are web-only hands paths - they
  are not voice capabilities, so ADR 0035 does not demand a phone screen.

## The tickets

| # | Type | What | Blocked by |
|---|---|---|---|
| 01 | decision | Households are tenants (ADR 0045, proposed). Reopens §1 "no tenancy" and 0044 rule 3 on Kevin's own words | - |
| 02 | build | `household_id` on every data table, backfilled; one Django choke point; leak tests per aspect | 01 |
| 02b | build | Postgres RLS keyed on `app.household_id`, set per request; worker role bypasses | 02 |
| 03 | build | Accounts: signup, create household, invite codes, join, members; session auth for the browser | 02 |
| 04 | decision | Web client stack: React + Vite + TS, generated API client, PWA, served by Django. Supersedes django-engine 08's HTMX | - |
| 05 | build | Web screens phase 1: sign in / sign up / household / Today / Lists / Settings. Installed on an iPhone | 03, 04 |
| 06 | build | Web screens phase 2: Ledger, Pantry, Body, Fleet, Places, Voice notes, and a glanceable home | 05, 11 |
| 07 | decision | Email delivery (invite by mail, verification, password reset). Until decided, invites are links Kevin shares | - |
| 08 | build | CI (server, Android, frontend) and CD to Cloud Run via Workload Identity Federation | - |
| 09 | build | Static serving (whitenoise), multi-stage Dockerfile with the Vite build, custom domain on Cloud Run | 04 |
| 10 | decision | No Cube. Resolved here with the reasoning; the escalation trigger is named | - |
| 11 | build | Report endpoints the dashboard reads: aggregates computed once, `unverified` carried through | 02 |
| 12 | decision | Hosting: always-on Oracle VM, Postgres in compose, PAYG tenancy. Resolved on Kevin's words; reopens django-engine 07 | - |
| 13 | build | The move: provision, migrate the data, cut over, backups drilled, retire Cloud Run and Supabase | 12, 09 |

**Order.** 01 is Kevin's ack. 02 and 04 start together (disjoint files). 08's CI half can start
now. 03 after 02. 05 after 03 and 04. 11 after 02. 09 after 04; 13 after 09, and 13 gates nothing
on this map but everything on the phone's URL. 06 last. 07 is independent and can stay open for a
while: invite links work without mail.

**Execution.** Opus builders, one Gradle/one pytest writer at a time (MEMORY: contention fakes a
pass). Server and web work in `server/`; nothing in this map touches `app/` except a one-line link
on the sign-in screen (ticket 05).
