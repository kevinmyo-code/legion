---
map: web-and-households
ticket: "04"
title: "Web client stack: React + Vite + TypeScript, a client generated from openapi.yaml, a PWA shell served by Django"
type: decision
status: open
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Web client stack

**Supersedes django-engine ticket 08's stack paragraph** (Django templates + HTMX + one CSS file).
Its screens table and trust-disclosure rule carry over verbatim into tickets 05 and 06. ADR 0040's
original text named React + Vite + TypeScript; this restores it with one change - the client is
generated from the contract, not hand-written over `supabase-js`.

## Why React and not HTMX, stated so it can be disagreed with

- Kevin's ask is a UI his parents will use daily and find intuitive, with glanceable charts, a
  calendar, lists, forms. That is the component-library problem, and the React ecosystem has the
  libraries (shadcn/ui, Recharts, FullCalendar, TanStack Table) that HTMX asks you to hand-build.
- The API contract already exists and is machine-readable. A generated typed client makes a
  server change a compile error in the web build; HTMX partials cannot be checked that way.
- The PWA shell (installable on an iPhone, cached shell, API never cached) is a two-line plugin in
  Vite and a hand-written service worker under HTMX.
- What HTMX would have won - no Node toolchain - is real. Node 24 is on the dev machine and the
  Docker build gets a node stage (ticket 09). Accepted.

## The stack, pinned

| Piece | Pick | Why this one |
|---|---|---|
| Framework | React 19, TypeScript strict | The doc's pick; the AI-generation target |
| Build | Vite 6, `server/frontend/`, `build.outDir = ../static/app` | Monolith, one repo, one deploy, no CORS |
| Routing | TanStack Router (file-based) | Typed routes; loaders pair with Query |
| Server state | TanStack Query | Matches the pull-based posture; caching per query key is the web's Room |
| API client | `openapi-typescript` (types) + `openapi-fetch` (runtime) from `server/openapi.yaml` | Drift is a type error. `npm run gen:api`; CI runs it and `git diff --exit-code` |
| Styling | Tailwind v4 + shadcn/ui | Doc's pick; components copied into the repo, not a dependency to fight |
| Charts | Recharts through shadcn charts | One library; the trust-disclosure rule (`unverified` in words) is a label prop away. **Escalation trigger:** a Canvas-scale series (OBD telemetry at 20k points) gets ECharts for that one chart |
| Calendar | FullCalendar core (MIT) React wrapper | Month/week/day/list; premium not needed |
| PWA | `vite-plugin-pwa`, `navigateFallbackDenylist: [/^\/api/, /^\/admin/, /^\/media/]`, `NetworkOnly` for `/api` | Cache the shell, never a response. The cold start on Cloud Run is hidden behind a cached shell |
| Forms | react-hook-form + zod | Client validation mirrors the serializers' messages; the server is still the authority |
| Tests | Vitest + Testing Library; Playwright for the two install/flow checks in ticket 05 | Screenshot tests are not in scope; the phone has Roborazzi, the web gets one smoke per screen |

## Django side (in this ticket, small)

- `pip install whitenoise`; `STATIC_ROOT`, `STATICFILES_DIRS = [BASE_DIR / "static"]`,
  `WhiteNoiseMiddleware` second in the list; `collectstatic` in the Dockerfile (ticket 09).
- `web/views.py::spa_index` serves `static/app/index.html`; `legion/urls.py` gets
  `re_path(r"^(?!api/|admin/|static/|media/|health).*$", spa_index)` LAST.
- Dev loop: `npm run dev` on 5173 with Vite's proxy for `/api` and `/admin` to 8000; cookies
  work on the same host.

## Done means

`server/frontend/` builds, `npm run gen:api` is idempotent against the committed `openapi.yaml`,
`/` serves the shell from Django, `/api/*` is untouched, a Vitest smoke passes, and the decision is
logged in `decisions.md`. Ticket 05 opens.
