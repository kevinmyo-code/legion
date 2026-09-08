---
map: web-and-households
ticket: "09"
title: "Whitenoise, a multi-stage arm64 image with the Vite build, migrate-on-start, and Caddy on the domain"
type: build
status: open
blockers: ["04", "12"]
blocked-by: ["[[04-web-client-stack]]", "[[12-hosting-oracle-vm]]"]
open-blockers: 0
ready: true
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
