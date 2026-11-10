# Cloud Run deployment

Ticket 07's amendment (`.scratch/django-engine/issues/07-where-it-runs.md`): compute is a
**Cloud Run service** (the Django app, `deploy.py`) plus a **Cloud Run Job** (the worker's
management commands, `deploy_job.py`) fired by **Cloud Scheduler** (`install_schedule.py`).
Database stays the existing Supabase Postgres; media is Cloudflare R2 or GCS (ticket 05).
Modelled on the pattern already running in Kevin's `midconerpdash` project - same
`deploy.py`/`deploy_job.py`/`install_schedule.py` split, same reasoning ("the figures have to
keep refreshing when no workstation is switched on"; here, the phone has to keep syncing when
no laptop is switched on) - rewritten for LEGION rather than copied, since it is a different
app on a different repo.

## Why Cloud Run over a home box

Kevin, 2026-09-05: *"my github has the midconerpdash project which i put on cloudflare... it
runs even if my laptop is off."* Up when every household machine is off, no hardware, no power
settings, no tunnel daemon to babysit. The accepted cost is a cold start of a second or two
after idle traffic, and Google's free tier is a monthly allowance, not a promise - the
`--max-instances` cap in `deploy.py` is what makes a runaway bill impossible, not goodwill.

## Free-tier allowances, as of 2026-09-05

Pricing changes; this is a snapshot, not a promise. Re-check
<https://cloud.google.com/run/pricing> and <https://cloud.google.com/scheduler/pricing> before
relying on a number here.

| Resource | Free tier (per month) |
|---|---|
| Cloud Run requests | 2,000,000 requests |
| Cloud Run compute | 180,000 vCPU-seconds, 360,000 GiB-seconds |
| Cloud Run networking | 1 GiB egress from North America (worth noting since a household is one person's phone, not the internet) |
| Cloud Scheduler | 3 jobs, free (LEGION uses one per crontab line - see `deploy/crontab`, ticket 06) |
| Artifact Registry | 0.5 GB storage free; the built image is well under that |
| Cloud Build | 120 build-minutes/day free |

With `--min-instances=0`, a household running one Django app for two phones and a web app
should stay inside every one of these limits without trying.

## First deploy, start to finish

1. **Install the gcloud SDK.** <https://cloud.google.com/sdk/docs/install> Open a *new* shell
   afterward - a freshly installed SDK is not on PATH until then, which reads as "this is
   broken" rather than "open a new window."
2. **Authenticate:**
   ```
   gcloud auth login
   gcloud config set project YOUR-PROJECT-ID
   ```
   Every script in this folder also accepts the project via `GOOGLE_CLOUD_PROJECT` (env var or
   `deploy/cloudrun/.env`) and refuses to run without one either way - there is no
   Kevin-hosted default project (CLAUDE.md section 7).
3. **Create the three secrets** the scripts reference by name (never by value - nothing in this
   folder ever holds SECRET_KEY or DATABASE_URL in memory):
   ```
   python -c "import secrets; print(secrets.token_urlsafe(50))" | gcloud secrets create SECRET_KEY --data-file=-
   echo -n "postgres://user:password@host:port/dbname" | gcloud secrets create DATABASE_URL --data-file=-
   python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode(), end='')" | gcloud secrets create LEGION_VAULT_KEY --data-file=-
   ```
   Then let the runtime service account read each one (`PROJECT_NUMBER-compute@developer.gserviceaccount.com` by default):
   ```
   gcloud secrets add-iam-policy-binding LEGION_VAULT_KEY --member=serviceAccount:PROJECT_NUMBER-compute@developer.gserviceaccount.com --role=roles/secretmanager.secretAccessor
   ```
   Three more, the household's own Plaid team keys (ADR 0057, the bank feed), from
   dashboard.plaid.com > Developers > Keys. Never paste them anywhere else:
   ```
   echo -n "<client id>" | gcloud secrets create PLAID_CLIENT_ID --data-file=-
   echo -n "<production secret>" | gcloud secrets create PLAID_SECRET --data-file=-
   echo -n "<sandbox secret>" | gcloud secrets create PLAID_SANDBOX_SECRET --data-file=-
   ```
   and grant the runtime account `roles/secretmanager.secretAccessor` on each, as above. Both the
   service and the scheduled job mount all three. `PLAID_ENV` (plain, in `.env`) defaults to
   `production` and picks both Plaid's host and the secret (`PLAID_SECRET` for production,
   `PLAID_SANDBOX_SECRET` for sandbox), so **switching is one line**: `PLAID_ENV=sandbox` in
   `.env`, then redeploy the service and the job. Sandbox links never touch a real bank and never
   spend one of the Trial plan's 10 lifetime connections; the web page says "Test mode (Plaid
   sandbox): no real bank data" while it is on.
   The `DATABASE_URL` is the Supabase session pooler connection string (ticket 07's decided
   database layer), not a value this repo ever holds.
4. **Copy the non-secret config:**
   ```
   cp .env.example .env
   ```
   Fill in `CLOUD_RUN_REGION` if `us-south1` is not right for you, and leave `ALLOWED_HOSTS`
   blank for now - the first deploy tells you the `*.run.app` URL, which you then add and
   redeploy.
5. **Deploy the service:**
   ```
   python deploy.py
   ```
   Prints the `*.run.app` URL at the end. Add it to `ALLOWED_HOSTS` in `.env`, then run
   `python deploy.py` again so the running service actually accepts requests to its own URL.
6. **Deploy the worker Job**, pointed at the exact image `deploy.py` just built (printed in its
   output, or recompute it: `REGION-docker.pkg.dev/PROJECT/legion/server:latest`):
   ```
   python deploy_job.py --image us-south1-docker.pkg.dev/YOUR-PROJECT/legion/server:latest
   ```
7. **Wire the schedule** from `deploy/crontab` (backend-etl ticket 01 created it with the
   `heartbeat` line; later tickets add theirs). Re-run after any change to that file:
   ```
   python install_schedule.py
   ```
8. **Point Cloudflare DNS** at the Cloud Run URL for a household domain, if you want one nicer
   than `*.run.app`. Cloud Run terminates its own HTTPS; Cloudflare here is DNS only unless you
   also want its Access product in front, which is optional.

## Everyday commands

```
python deploy.py                          rebuild, push, redeploy the service
python deploy.py --dry-run                print every gcloud command, run none of them
python deploy_job.py --image ...          redeploy the worker Job from an existing image
python install_schedule.py                (re)apply deploy/crontab to Cloud Scheduler
python install_schedule.py --status       show what is currently scheduled
python install_schedule.py --remove       delete every scheduled entry
gcloud run services logs read legion --region us-south1
gcloud run jobs executions list --job legion-worker --region us-south1
gcloud run jobs execute legion-worker --region us-south1 --args=manage.py,backup_nightly
```

## Push notifications (optional)

Off until you do this; the web's notifications page says so in words. web-revamp ticket 15.

1. Generate a key pair (the one-liner is in `deploy/.env.example`, under VAPID).
2. Store the private key as a secret and let the runtime account read it:
   ```
   echo -n "<VAPID_PRIVATE_KEY>" | gcloud secrets create VAPID_PRIVATE_KEY --data-file=-
   gcloud secrets add-iam-policy-binding VAPID_PRIVATE_KEY --member=serviceAccount:PROJECT_NUMBER-compute@developer.gserviceaccount.com --role=roles/secretmanager.secretAccessor
   ```
3. Put `VAPID_PUBLIC_KEY` and `VAPID_SUBJECT=mailto:you@example.com` in `deploy/cloudrun/.env`.
   Setting the public key is what mounts the private secret, so do step 2 first or the deploy fails.
4. `python deploy.py`, then `python deploy_job.py --image <tag it printed>`, then
   `python install_schedule.py` for the `push_dispatch` line.

## Conditions keys (optional)

`check_conditions` reads NWS, USGS and the National Hurricane Center with no key. Two sources need
one and say "not set up" until then. Round 5, 2026-11-10.

1. **NASA FIRMS** (satellite fire detections): request a free MAP_KEY at
   https://firms.modaps.eosdis.nasa.gov/api/map_key/ (an email address; the key arrives by email).
   ```
   echo -n "<MAP_KEY>" | gcloud secrets create LEGION_FIRMS_MAP_KEY --data-file=-
   gcloud secrets add-iam-policy-binding LEGION_FIRMS_MAP_KEY --member=serviceAccount:PROJECT_NUMBER-compute@developer.gserviceaccount.com --role=roles/secretmanager.secretAccessor
   ```
   then `LEGION_FIRMS=on` in `deploy/cloudrun/.env`.
2. **DriveTexas** (Texas road closures and flooding): sign up for an API key at
   https://api.drivetexas.org/ and accept its terms (its data feed answers 401 without one).
   Same two commands with `LEGION_DRIVETEXAS_KEY`, then `LEGION_DRIVETEXAS=on`. The feed's
   fields were not visible without a key, so re-record `server/tests/fixtures/conditions/
   drivetexas_standin.geojson` from a real answer and check `conditions/sources.py`'s `_road_text`.
3. `python deploy.py`.

## GitHub status (optional, read-only)

Off until you do this; `github_status` says GitHub is not set up. Round 5, 2026-11-10.

1. On GitHub: Settings, Developer settings, Personal access tokens, Fine-grained tokens, Generate
   new token. Resource owner: you. Repository access: Only select repositories (pick yours).
   Repository permissions, all **Read-only**: Actions, Contents, Issues, Pull requests, Pages
   (Metadata is read-only automatically). Nothing else, and never write.
2. Store it as a secret and let the runtime account read it:
   ```
   echo -n "<TOKEN>" | gcloud secrets create LEGION_GITHUB_TOKEN --data-file=-
   gcloud secrets add-iam-policy-binding LEGION_GITHUB_TOKEN --member=serviceAccount:PROJECT_NUMBER-compute@developer.gserviceaccount.com --role=roles/secretmanager.secretAccessor
   ```
3. Put `LEGION_GITHUB_REPOS=<owner>/<repo>[,<owner>/<repo>]` in `deploy/cloudrun/.env`. Setting it
   is what mounts the secret, so do step 2 first or the deploy fails.
4. `python deploy.py`.

## What is pure standard library and what is not

`deploy.py`, `deploy_job.py`, `install_schedule.py` and `_common.py` import only `argparse`,
`os`, `re`, `shutil`, `subprocess`, `sys`, `tempfile`, and `pathlib` - the same "pure Python on
the standard library, so the image needs no compiler and no extra wheels" property the
reference's Dockerfile calls out for its own pipeline. Nothing in this folder needs `pip
install` to run; it only needs `gcloud` (and, for `--build-with local-docker`, `docker`) on
PATH.
