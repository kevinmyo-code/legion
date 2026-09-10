---
map: web-and-households
ticket: "13"
title: "The move: provision the Oracle VM, migrate Postgres, cut over, retire Cloud Run and Supabase"
type: build
status: resolved
blockers: ["12", "09"]
blocked-by: ["[[12-hosting-oracle-vm]]", "[[09-static-domain-dockerfile]]"]
open-blockers: 1
ready: false
status-detail: "DROPPED 2026-09-10 (Kevin): 'kill oracle vm decision'. There is no VM and no move; the engine stays on Cloud Run (ticket 12's reversal). Nothing here was built. What survives and is worth keeping is deploy/vm/PROVISION.md and deploy/vm/deploy.sh - if a box is ever wanted again, the runbook and the two traps it records (Oracle's Ubuntu images drop 80/443 in iptables even after the VCN security list is right; Cloudflare proxying breaks the ACME challenge) are the expensive half. The section-3 data move is moot: the tenancy migration is applied to the live Supabase database and the phone already talks to Cloud Run. Superseded context, 2026-09-10 earlier:  Section 1 of this ticket (tenancy upgrade, machine, network, iptables) is his; the runbook, the arm64 image, the deploy script and the data move stay here. He also ruled the same day that there is NO rush to deploy - 'do it right' - so the web client is built and verified properly before any cutover, rather than shipped to Cloud Run to meet a date."
tags: [ticket]
---

# The move

Everything here is a runbook in **`deploy/vm/PROVISION.md`** (written 2026-09-10; `README.md` beside
it is the DEPLOY's prerequisites, a different subject), and every step below is a checkbox in it. **Kevin runs the steps
that touch his Oracle tenancy, his domain and his card; an agent writes them, checks them against
Oracle's own docs, and does not execute them.**

## 1. Tenancy and machine

- Upgrade the OCI tenancy to Pay As You Go. Create an OCI Budget with an alert at $1 and at $5 -
  the whole point of the upgrade is $0, and a budget alert is how that stays a fact.
- `VM.Standard.A1.Flex`, 4 OCPU, 24 GB, **Ubuntu 24.04 aarch64** (Canonical image), 100 GB boot
  volume (inside the 200 GB Always-Free block storage), a **reserved** public IP (an ephemeral one
  changes on stop/start and takes the domain with it), home region.
- Network: the VCN security list opens 22 (Kevin's IP only), 80 and 443. **Oracle's Ubuntu images
  also ship iptables rules that drop everything but 22** - `sudo iptables -I INPUT 6 -m state
  --state NEW -p tcp --dport 80 -j ACCEPT` (and 443), then `sudo netfilter-persistent save`. This
  is the step every first-time OCI user loses an evening to; it goes in the runbook in bold.
- `unattended-upgrades` on; Docker Engine (arm64) + compose plugin from Docker's apt repo; a
  non-root `legion` user in the `docker` group; the deploy key from ticket 08 in its
  `authorized_keys`, restricted with `command=` to the deploy script.

## 2. The stack

- `git clone` to `/opt/legion`; `deploy/.env` from `.env.example` with a fresh `SECRET_KEY`, real
  `POSTGRES_PASSWORD`, `ALLOWED_HOSTS=legion.<domain>`, `CSRF_TRUSTED_ORIGINS=https://legion.<domain>`,
  `COMPOSE_PROFILES=full`.
- `deploy/Caddyfile`: `legion.<domain> { reverse_proxy web:8000 }`. Caddy issues the certificate
  the first time 80/443 are reachable under that name.
- Cloudflare DNS: `A legion.<domain> -> <reserved IP>`, proxy OFF (grey cloud) so Let's Encrypt's
  HTTP-01 challenge reaches Caddy. Proxy ON is a later choice and needs Full (strict) with an
  origin certificate; not now.
- **Migrations run on start.** `server/entrypoint.sh` - **not `deploy/entrypoint.sh`, which is what
  this line said until 2026-09-10 and was never reachable**: the image's build context is `server/`
  in all three places that build it, and `COPY ../` is not expressible. Ticket 09 ships it:
  `python manage.py migrate --noinput` then `exec gunicorn ...`. One `web` replica, so this is not
  a race; the comment says so and says what changes if it ever becomes two.
- `docker compose up -d`; `docker compose logs -f web` until gunicorn is listening;
  `curl https://legion.<domain>/health` returns `{"db":"ok"}`.

## 3. Moving the data - a migration night, counted

1. **Freeze**: both phones foreground, outboxes drained, app closed (django-engine 10 step 1).
2. **Dump** from Supabase over the session pooler: `pg_dump --format=custom --no-owner
   --no-privileges --schema=public --schema=django`. Both schemas - `settings.py` sets
   `search_path=django,public`, and Django's own tables live in `django`. The `auth`, `storage` and
   `private` schemas are Supabase's and are NOT dumped; nothing Django reads lives there since the
   users moved to `household_user`.
3. **Strip Supabase-isms before restore, and expect errors if you do not.** The dump carries RLS
   policies calling `auth.uid()` and the `private.is_household_member()` function referencing
   `auth.users` - none of which exist on a vanilla Postgres. Restore with `pg_restore --no-owner
   --no-privileges --exit-on-error=false`, capture the error list, and confirm every error is one of
   those policies or that function. Then `DROP POLICY` each surviving Supabase-era policy and drop
   the function; ticket 02b's RLS replaces them on the new keying. The row-security posture is
   Django's until 02b lands, exactly as it was on Supabase after cutover made Auth dark.
4. **Count**: every `public` table, row count on Supabase vs the VM, equal. Ledger cents totals per
   account equal. The query and its output go into this ticket's status-detail (dj-10's bar).
5. `manage.py migrate --check` clean; `manage.py write_openapi` unchanged.

## 4. Cutover and rollback

- Both phones: `ServerConfig` URL -> `https://legion.<domain>`; sign in; `/api/auth/me` green; a
  full pull; Room row counts vs server equal on every synced table. One write each way with the
  web app once ticket 05 exists.
- **Rollback for thirty days**: Cloud Run stays deployed at min-instances 0 (idle costs nothing)
  and Supabase stays up. Rollback is the URL on two phones and re-granting writes on Supabase; rows
  written to the VM in between are re-entered by hand, and the count is on the debug screen.
- **After thirty clean days**: `gcloud run services delete` and `jobs delete`; delete the Supabase
  project; delete `.github/workflows/supabase-keepalive.yml`; move `supabase/` to
  `server/legacy/supabase/` with a `FROZEN.md` banner; `deploy/cloudrun/` goes the same way;
  `README.md`'s build status and MEMORY.md's engine line updated.

## 5. Backups gate the cutover

django-engine ticket 06 (nightly `pg_dump`, drilled restore) was "before any other worker job" under
ADR 0044 and was deferred while Supabase held the data. It is not deferrable once the data is on a
box Kevin owns. **Done means a nightly dump has run once and a restore has been drilled from it into
a scratch database** - before step 4, not after. Target: OCI Object Storage (20 GB Always Free, same
tenancy, no third account) via `rclone` or the OCI CLI, encrypted with `age`, 30 daily + 12 monthly
retention. The `worker` service's `/etc/crontab` gets its first line.

## Verification

- [ ] `https://legion.<domain>/health` from a phone on mobile data, not the LAN.
- [ ] Step 3.4 counts in status-detail.
- [ ] Step 4 Room-vs-server counts in status-detail.
- [ ] A restore drill from a nightly dump, dated, in status-detail.
- [ ] `docker compose ps` shows four healthy services after a `sudo reboot`.
- [ ] Budget alert email received once, deliberately triggered by lowering the threshold to $0.
