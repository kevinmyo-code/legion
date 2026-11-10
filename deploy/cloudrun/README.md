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

## Shipping a commit: `deploy/ship.py`

One versioned tool ships one commit: the Claude worker, a fast-forward of `dev` and `main`, the
image, migrations, the job, the service, the schedule, a health check and the phone's OTA. It
replaces the per-round bash scripts. Every step checks whether it is already done, so a failed
run is fixed and run again.

```
# from a clean checkout that is (or will be moved to) the commit; .env stays in deploy/cloudrun/
python deploy/ship.py --sha <40-hex> --deploy-tree ../legion-deploy \
    --worker-tree ../legion-claude-on \
    --apk <app-fast.apk> --apk-sha <sha256> --notes "what the phone gets" --dry-run
# the same without --dry-run ships it
```

- **`--dry-run`** runs every read-only check against the live project and prints the plan:
  refs, the live revision and which commit it runs, the worker, the APK, flag decisions,
  the env-drop check, the rollback facts. Nothing is pushed, checked out, built, deployed,
  executed, scheduled or written.
- **Order:** worker, then dev/main, then image `server:<sha>`, then the job on that image's
  DIGEST, then `manage.py migrate` as a one-off job execution, then the service on the same
  digest. Migrations are applied before any request reaches new code, so the service may carry
  `LEGION_SKIP_MIGRATE=1` (set it in `flags.yaml`).
- **The job image and migrate go in a quiet gap**: ship.py waits until no `deploy/crontab` line
  is due for 150 s, so no scheduled task runs the new image against the old schema.
- **Refuses inside :30-:45** (the :40 consolidate cron), on a moved ref, on a non-fast-forward,
  on a dirty tree, on a running worker task, on an APK whose sha256 or versionCode is wrong, on a
  flag `.env` and `flags.yaml` disagree about, and on any live env name the deploy would drop.
- **Flags** live in `flags.yaml` (committed, non-secret). A flag that mounts a secret turns on
  only when that secret exists and the runtime account can read it; otherwise ship.py says what
  is owed. `tools/tests/test_ship.py` checks each name is read by the server and passed by
  `_common.py`.
- **Records** go to `~/.legion/ships/<sha12>.rollback.json` (written once, before anything
  changes) and `<sha12>.shipped.json`. Never in the repo.

## Shipping from GitHub (Workload Identity Federation)

`.github/workflows/ship.yml` runs `deploy/ship.py --no-worker` (no laptop, no APK) when someone
clicks "Run workflow" on the Actions tab. It has no other trigger. It authenticates with Workload
Identity Federation, so no service-account key exists anywhere. **Nothing below has been created;
each step is for the project owner to run once.** Replace `PROJECT_ID`, `PROJECT_NUMBER` and
`OWNER/REPO`.

1. **A deploy service account**, separate from the runtime one:
   ```
   gcloud iam service-accounts create legion-ship --project PROJECT_ID --display-name "LEGION ship (GitHub)"
   ```
2. **Its roles**, the least that ship.py uses:
   ```
   SA=legion-ship@PROJECT_ID.iam.gserviceaccount.com
   for role in roles/run.developer roles/cloudbuild.builds.editor roles/artifactregistry.writer \
               roles/cloudscheduler.admin roles/logging.viewer roles/storage.admin \
               roles/secretmanager.viewer roles/serviceusage.serviceUsageConsumer; do
     gcloud projects add-iam-policy-binding PROJECT_ID --member "serviceAccount:$SA" --role "$role"
   done
   # deploy as the runtime account (the service and the job run as it)
   gcloud iam service-accounts add-iam-policy-binding PROJECT_NUMBER-compute@developer.gserviceaccount.com \
     --member "serviceAccount:$SA" --role roles/iam.serviceAccountUser
   ```
   `roles/secretmanager.viewer` lets ship.py read each secret's IAM policy (the "readable"
   check); it cannot read a secret's value. `roles/storage.admin` is for Cloud Build's source
   upload bucket; narrow it to that bucket if preferred. `install_schedule.py` only adds the job's
   `run.invoker` binding when it is missing; once it exists, `roles/run.developer` is enough.
3. **A pool and a provider that trust only this repository:**
   ```
   gcloud iam workload-identity-pools create github --project PROJECT_ID --location global \
     --display-name "GitHub Actions"
   gcloud iam workload-identity-pools providers create-oidc legion --project PROJECT_ID \
     --location global --workload-identity-pool github \
     --issuer-uri "https://token.actions.githubusercontent.com" \
     --attribute-mapping "google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.ref=assertion.ref,attribute.environment=assertion.environment" \
     --attribute-condition "assertion.repository == 'OWNER/REPO' && assertion.environment == 'production'"
   ```
4. **Let that repository's `production` environment act as the deploy account:**
   ```
   gcloud iam service-accounts add-iam-policy-binding legion-ship@PROJECT_ID.iam.gserviceaccount.com \
     --role roles/iam.workloadIdentityUser \
     --member "principalSet://iam.googleapis.com/projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/github/attribute.repository/OWNER/REPO"
   ```
5. **On GitHub** (Settings > Environments > New environment `production`):
   - Required reviewers: yourself. The click on "Run workflow" then still waits for an approval.
   - Deployment branches: `dev` only.
   - Variables: `GCP_PROJECT` = `PROJECT_ID`,
     `GCP_WIF_PROVIDER` = `projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/github/providers/legion`,
     `GCP_SHIP_SERVICE_ACCOUNT` = `legion-ship@PROJECT_ID.iam.gserviceaccount.com`.
   - Secret `CLOUDRUN_ENV`: the contents of `deploy/cloudrun/.env` (non-secret config, but it
     holds a contact address and the repo is public).
6. **First run with "dry run" ticked.** It prints the same plan as the laptop's `--dry-run`.

Undo: delete the provider, the pool and the account (`gcloud iam ... delete`); the workflow then
fails at its auth step and changes nothing.

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
