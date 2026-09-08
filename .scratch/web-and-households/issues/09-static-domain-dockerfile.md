---
map: web-and-households
ticket: "09"
title: "Whitenoise, a multi-stage Dockerfile with the Vite build, and a custom domain on Cloud Run"
type: build
status: open
blockers: ["04"]
blocked-by: ["[[04-web-client-stack]]"]
open-blockers: 0
ready: true
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
- **Domain - checked 2026-09-08, and the first idea is dead.** Cloud Run *domain mappings* are
  preview, "not production-ready" in Google's own words, and the supported-region list
  (asia-east1, asia-northeast1, asia-southeast1, europe-north1, europe-west1, europe-west4,
  us-central1, us-east1, us-east4, us-west1) does not include `us-south1`. A global external
  Application Load Balancer works everywhere and costs a forwarding-rule minimum every month for
  a family site. **Pick: Firebase Hosting with a rewrite to the Cloud Run service** - free tier,
  managed certificate, custom domain, the same project, and the shape Kevin's midconerpdash
  already runs (`deploy/cloudrun/README.md` names it). `firebase.json`: one `rewrites` entry
  `{"source": "**", "run": {"serviceId": "<service>", "region": "us-south1"}}`, no static files
  hosted there (whitenoise serves them from the container; Hosting just proxies). Cloudflare stays
  DNS only for the domain, `A`/`AAAA` or `CNAME` per Firebase's instructions, proxy OFF so
  Firebase can issue the certificate. `ALLOWED_HOSTS` and `CSRF_TRUSTED_ORIGINS` gain the name;
  the Android `ServerConfig` default hint updates. The parents type a name, not
  `legion-757959564788`.
- **Cold start, measured 2026-09-08 against the live service:** first request after idle 5.4 s
  (`/health`, ttfb 5.38 s), the next two 0.37 s and 0.34 s. The ticket's earlier guess of ~2 s was
  wrong by a factor of two and a half. The PWA shell hides it for a returning user; a parent's first
  open of the day does not have a shell yet. `--min-instances=1` is the fix and it is a cost line
  (Cloud Run bills an idle instance's memory-seconds outside the free tier); write both numbers
  into the README and let Kevin choose. Do not sneak it in.

## Done means

`/` on the custom domain serves the shell over HTTPS, `/static/app/*.js` returns with a
`Cache-Control: max-age=31536000, immutable`, `/api/auth/me` still answers the phone, and the Job
runs from the same image.
