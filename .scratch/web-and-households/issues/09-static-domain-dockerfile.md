---
map: web-and-households
ticket: "09"
title: "Whitenoise, a multi-stage arm64 image with the Vite build, migrate-on-start, and Caddy on the domain"
type: build
status: built
status-detail: "Built 2026-09-08. `server/Dockerfile` is now two stages: `node:24-slim` runs `npm ci` and `npm run build` (`tsc -b && vite build`) in `frontend/`, whose committed `outDir: '../static/app'` resolves to `/static/app` inside the container, and the python stage takes it with `COPY --from=frontend` and runs `collectstatic` with the five `required_env` variables inline on that one RUN - never as `ENV`, which would have baked a throwaway SECRET_KEY into the running image and turned `required_env`'s loud failure into a silent default. supercronic is per-arch on `TARGETARCH` with both SHA1s pinned (amd64 `e63c11a9...`, unchanged; arm64 `0b6c5bb743e0b0dafed1132198c81807927ac413`, new), each confirmed twice: downloaded and `sha1sum`ed, and read off aptible's v0.2.49 release notes. `TARGETARCH` carries a default of amd64 because `gcloud builds submit` (the Cloud Run rollback path) runs the classic builder, which never sets it. New `server/.dockerignore` keeps the developer's own `static/app` and `staticfiles` out of the context - without it `COPY . .` lays a stale build down and `collectstatic` ships a mix of two. `deploy/Caddyfile` is `{$LEGION_DOMAIN::80} { reverse_proxy web:8000 }` with a quoted `email \"{$LEGION_ACME_EMAIL}\"` global; `caddy` in compose gained `env_file: .env` without which both substitutions are empty inside the container and the stack silently serves plain HTTP forever. **The entrypoint is `server/entrypoint.sh`, NOT `deploy/entrypoint.sh` as this ticket and ticket 13 both say.** It cannot live in `deploy/`: the Docker build context is `server/` in all three places that build this image (compose's `web` and `worker`, and `deploy/cloudrun/deploy.py`'s `docker build server/` and `gcloud builds submit server/`), a `COPY ../` is not expressible, and Cloud Build does not support BuildKit named contexts. Whoever writes ticket 13's runbook must use `server/entrypoint.sh`. **VERIFIED WITHOUT DOCKER** (this machine has none): hadolint 2.15.1 exit 0 on the Dockerfile (DL3008/DL4006/DL3059 declined with the reason on the line above each); shellcheck 0.11.0 exit 0 on `entrypoint.sh`; `caddy validate` 2.11.4 `Valid configuration` with the two variables both set and both unset, plus `caddy fmt` clean - and it was that run which proved an UNQUOTED `email {$LEGION_ACME_EMAIL}` is a hard startup failure when empty; the compose YAML parses and every service still carries a profile with the worker's supercronic entrypoint override intact; the inline-env `RUN` form was executed in a real `sh` to prove all five variables reach the command. **THE IMAGE HAS NEVER BEEN BUILT.** Not for arm64, not for amd64, not at all - so `collectstatic` has never run in the container, the `COPY --from=frontend /static/app` path has never been resolved by Docker, and `npm ci` has never run against `node:24-slim`. This ticket's own 'done means' (a `linux/arm64` build, plus a `--profile full up` serving `/`, `/static/app/*.js` with `max-age=31536000, immutable`, and `/api/auth/me`) is entirely UNMET and is owed on the first machine with Docker - the VM in ticket 13, or any laptop with it installed. The latency baseline this ticket asks for is a headed but empty section in `deploy/vm/README.md`, marked NOT MEASURED YET, because there is no box to measure."
blockers: ["04", "12"]
blocked-by: ["[[04-web-client-stack]]", "[[12-hosting-oracle-vm]]"]
open-blockers: 0
ready: false
tags: [ticket]
---

# Static serving, the image, the domain

**Rewritten 2026-09-08 after ticket 12.** The first draft targeted Cloud Run and spent its domain
section discovering that Cloud Run domain mappings do not exist in `us-south1` and its cold-start
section measuring 5.4 s. Both findings are in git history; neither applies to an always-on VM.

- **Dockerfile** becomes two stages: `node:24-slim` runs `npm ci && npm run build` in
  `server/frontend/`, the python stage copies `static/app` in and runs `collectstatic`. **The image
  must build on arm64** - the VM is Ampere. Both base images are multi-arch; the one hardcoded
  architecture in the file is the supercronic download (`supercronic-linux-amd64` and its sha1),
  which becomes `supercronic-linux-${TARGETARCH}` with a per-arch checksum map, or the
  `aptible/supercronic` release for arm64 verified the same way the amd64 one was. Build it once
  with `docker buildx build --platform linux/arm64` (or on the box itself) before calling it done;
  an image that only builds on the laptop is not built.
- **`deploy/entrypoint.sh`**: `python manage.py migrate --noinput`, then `exec gunicorn
  legion.wsgi:application --bind 0.0.0.0:${PORT:-8000}`. The `web` service uses it; the `worker`
  keeps its supercronic entrypoint. Comment: one replica, no race, and what changes if that ever
  stops being true.
- **Whitenoise** serves `/static/` from gunicorn behind Caddy with far-future caching on hashed
  filenames (`CompressedManifestStaticFilesStorage`, ticket 04 already wires it). Caddy could serve
  the files itself; it does not, because one place that knows the static manifest is enough.
- **Media** lands on the `media` compose volume (`MEDIA_ROOT=/app/media`), which is exactly what
  the compose file already does. django-engine 05's R2 repoint is **reversed** by ticket 12: a box
  with a 200 GB free disk does not need a second object store for receipts. The backup in ticket 13
  covers the volume as well as the database.
- **Domain:** `deploy/Caddyfile` gets `legion.<domain> { reverse_proxy web:8000 }`; Caddy issues and
  renews the Let's Encrypt certificate. Cloudflare is DNS only (grey cloud) so HTTP-01 reaches the
  box. `ALLOWED_HOSTS` and `CSRF_TRUSTED_ORIGINS` carry the name; the Android `ServerConfig` default
  hint updates to it. The parents type a name, not an IP.
- **Latency, measured rather than assumed:** `/health` from Houston to the VM's home region,
  written into `deploy/vm/README.md`, so the next person who asks "why is it slow" has a baseline.

## Done means

The image builds for `linux/arm64`; a local `docker compose --profile full up` (on any machine with
Docker - this laptop does not have it, say so if you cannot) serves `/` as the shell, `/static/app/*.js`
with `Cache-Control: max-age=31536000, immutable`, `/api/auth/me` as JSON, and `web`'s first start
runs migrations before gunicorn listens. The Caddyfile and entrypoint are in the repo; the domain
itself is ticket 13's step 2.
