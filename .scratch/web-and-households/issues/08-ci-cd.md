---
map: web-and-households
ticket: "08"
title: "CI for server, Android and frontend; CD to Cloud Run through Workload Identity Federation"
type: build
status: built
status-detail: "Built 2026-09-08, CI HALF ONLY. `.github/workflows/server.yml` (postgres:16 service, uv + the pinned requirements.txt, ruff, write_openapi + git diff --exit-code, pytest with totals read from the JUnit XML) and `.github/workflows/android.yml` (JDK 17 per AGP 9.2.1's own minimum, sdk.dir from the runner, compileDebugKotlin -Pnokey + testDebugUnitTest + :app:detekt, totals from the XML, Roborazzi diffs uploaded on failure). Both actionlint-clean; the server invocations were run locally, neither workflow has ever run on GitHub. THE CD HALF IS DEFERRED to a rewritten ticket: Kevin reopened hosting mid-build (the engine moves off Cloud Run to an always-on Oracle VM running docker compose), so deploy.yml, deploy/cloudrun/wif.sh and the README's WIF section were not written. frontend.yml still waits on ticket 04. Owed: the first green run of each workflow after a push, and whatever the rewritten CD ticket asks for. CD HALF NOW EXISTS, 2026-09-08, and is `built`: `.github/workflows/deploy.yml` (push to `main` plus `workflow_dispatch`, one job, no build step because the arm64 box builds its own image, `VM_KNOWN_HOSTS` pinned rather than `ssh-keyscan`ed, all four values secrets so a public repo carries no hostname; actionlint 1.7.12 exit 0), `deploy/vm/deploy.sh` (`git pull --ff-only`, `compose up -d --build --remove-orphans`, `ps`, then a 60 s poll of `/health` that dumps `logs --tail=50 web` and exits non-zero if it never answers; shellcheck 0.11.0 exit 0 - it caught that the specified `#!/bin/bash -euo pipefail` shebang is SC2096, a form Linux passes as one argument and bash rejects, so it is `set -euo pipefail` on line 2 instead), and `deploy/vm/README.md` (the prerequisites, the exact `command=\"...\"` forced-command authorized_keys line, and the four secrets by name; the provisioning runbook stays ticket 13's). None of it has ever run: ticket 13's box does not exist, so this is `built` and `tested` only after 13, exactly as this ticket's 'done means' says. Owed: the first green run of all three workflows after a push."
blockers: ["12"]
blocked-by: ["[[12-hosting-oracle-vm]]"]
open-blockers: 0
ready: false
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

## `.github/workflows/deploy.yml` - REWRITTEN 2026-09-08 for the Oracle VM (ticket 12)

The first draft of this section deployed to Cloud Run through Workload Identity Federation. Kevin
reopened hosting the same afternoon; the engine is an always-on Oracle VM running compose, so the
doc's own recipe - Actions builds, then SSHes to the box - is now the right one, with two changes:
the box builds its own image (a 4-OCPU arm64 machine builds the Django image in under a minute, and
that avoids QEMU-emulated arm64 builds on an amd64 runner and a registry to keep), and migrations
run in the container's entrypoint, not in the workflow.

On push to `main` (never `dev`) plus `workflow_dispatch`: `appleboy/ssh-action` (or a plain
`ssh` step with `ssh-keyscan` pinned to a known host key in a variable) using secrets `VM_HOST`,
`VM_USER`, `VM_SSH_KEY` - the private half of a deploy key whose public half sits in the VM's
`authorized_keys` restricted with `command="/opt/legion/deploy/vm/deploy.sh"` so the key can do
exactly one thing. `deploy/vm/deploy.sh`: `git -C /opt/legion pull --ff-only`, `docker compose -f
deploy/docker-compose.yml up -d --build`, `docker compose ps`, and a `curl -f
http://127.0.0.1:8000/health` that fails the workflow if the new container did not come up. The
old container keeps serving until the new one is built; compose swaps them.

Host, user and key are secrets, not variables, and the workflow carries no hostname, so a
stranger's fork deploys to their own box or to nothing.

## Done means

The two CI workflows green on `dev` at least once. The deploy workflow run against a
`workflow_dispatch` trigger before it is trusted on `main`, which needs ticket 13's box to exist -
so this ticket's CD half is `built` when the workflow and `deploy.sh` are written and shell-checked,
and `tested` only after ticket 13. `deploy/vm/README.md` documents the deploy key setup.
