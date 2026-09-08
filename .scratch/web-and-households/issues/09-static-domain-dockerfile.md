---
map: web-and-households
ticket: "09"
title: "Whitenoise, a multi-stage Dockerfile with the Vite build, and a custom domain on Cloud Run"
type: build
status: open
blockers: ["04"]
blocked-by: ["[[04-web-client-stack]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Static serving, the image, the domain

- **Dockerfile** becomes two stages: `node:24-slim` runs `npm ci && npm run build` in
  `server/frontend/`, the python stage copies `static/app` in and runs `collectstatic`. The image
  still serves as both the service and the Job. Cloud Build does the build (Docker is not installed
  on the dev machine, and does not need to be).
- **Whitenoise** serves `/static/` from gunicorn with far-future caching on hashed filenames
  (`CompressedManifestStaticFilesStorage`). No Nginx, no bucket for static; the bundle is a few MB.
- **Media** stays on the R2/GCS decision (django-engine 05); receipt photos never go through
  whitenoise.
- **Domain:** a Cloudflare DNS record to the Cloud Run domain mapping (or a Cloudflare proxied CNAME
  to the `run.app` host if mappings are unavailable in `us-south1`; check, do not assume). The
  parents type a name, not `legion-757959564788`. `ALLOWED_HOSTS` and `CSRF_TRUSTED_ORIGINS` gain
  it; the Android `ServerConfig` default hint updates.
- **Cold start:** measured, written into the README, and the PWA shell hides it. If it grows past
  ~3 s, `--min-instances=1` is a cost line Kevin can choose, not a fix to sneak in.

## Done means

`/` on the custom domain serves the shell over HTTPS, `/static/app/*.js` returns with a
`Cache-Control: max-age=31536000, immutable`, `/api/auth/me` still answers the phone, and the Job
runs from the same image.
