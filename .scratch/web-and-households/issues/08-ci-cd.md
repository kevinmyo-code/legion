---
map: web-and-households
ticket: "08"
title: "CI for server, Android and frontend; CD to Cloud Run through Workload Identity Federation"
type: build
status: open
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# CI/CD

Today `.github/workflows/` holds one file, the Supabase keepalive. The doc's GitHub Actions idea is
right; its SSH-to-a-VM deploy is not, because the engine is on Cloud Run.

## `.github/workflows/server.yml`

On push and PR touching `server/**`: Python 3.13, `uv sync`, `ruff check`, `pytest` against a
`postgres:16` service container (`DATABASE_URL` pointed at it; the `LEGION_PG_URL`-gated legacy
read-only tests skip, as they do locally without it), JUnit XML uploaded as an artifact, and
`manage.py write_openapi` followed by `git diff --exit-code server/openapi.yaml` so a stale contract
fails the build the same way the staleness test does.

## `.github/workflows/android.yml`

On push and PR touching `app/**` or `gradle/**`: JDK 17, Android SDK from the runner image,
`./gradlew compileDebugKotlin -Pnokey testDebugUnitTest --no-daemon`, totals read from the JUnit XML
by a five-line script (never the console), detekt against its baseline. Gradle cache keyed on the
lockfiles. Roborazzi runs in verify mode; a golden diff is a failure with the image attached.

## `.github/workflows/frontend.yml`

On push and PR touching `server/frontend/**` or `server/openapi.yaml`: Node 24, `npm ci`, `npm run
gen:api` + `git diff --exit-code`, `tsc --noEmit`, `vitest run`, `vite build`.

## `.github/workflows/deploy.yml`

On push to `main` (never `dev`; `main` mirrors `dev` when Kevin or Claude merges it, CLAUDE.md §8):
authenticate with `google-github-actions/auth` via Workload Identity Federation - a provider and a
service account created once by a documented `gcloud` script in `deploy/cloudrun/wif.sh`, no JSON key
in the repo or in Actions secrets - build the image with Cloud Build (`gcloud builds submit`), deploy
the service and re-point the Job at the new image, exactly what `deploy.py`/`deploy_job.py` do by
hand. Those scripts stay for the manual path. Region, project and service name come from Actions
variables, not the workflow file, so a stranger's fork deploys to THEIR project.

## Done means

All four workflows green on `dev` at least once, with the deploy workflow run against a
`workflow_dispatch` trigger before it is trusted on `main`. `deploy/cloudrun/README.md` documents
the WIF setup in the same start-to-finish register it already uses.
