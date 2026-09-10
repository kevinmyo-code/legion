# Provisioning the Oracle VM, start to finish

The operational half of [[13-vm-provision-migrate-cutover]]. That ticket is the plan and the
verification bar; this is the sequence to follow at a terminal. `README.md` beside this file is what
the DEPLOY expects afterwards and is a different subject.

**Nothing here has been executed.** The image in this repo has never been built on any machine —
Docker is not installed on the dev laptop — so treat the first `docker compose up` as the first real
test of `server/Dockerfile`, not as a formality. Where a step is a known trap it says so.

Hosting ruling: [[12-hosting-oracle-vm]]. Everything below assumes Postgres runs ON the box, in
compose, per that ruling.

---

## 1. Tenancy (Oracle console, browser)

1. **Upgrade to Pay As You Go.** Always Free was halved to 2 OCPU / 12 GB on 2026-06-15, and idle
   Always-Free instances get reclaimed (7-day window; CPU 95th percentile, network and memory all
   under 20 % — a family server sits under that easily). A PAYG tenancy keeps 4 OCPU / 24 GB at $0
   inside the free limits.
   **Reported, not documented:** that PAYG is exempt from reclamation comes from Oracle's community
   forum, not their docs. Worth one support ticket before you rely on it.
2. **Create a Budget with alerts at $1 and $5.** The whole point of the upgrade is a $0 bill, and an
   alert is how that stays a fact rather than a hope. Do this before the machine exists.

## 2. The machine

| Setting | Value | Why |
|---|---|---|
| Shape | `VM.Standard.A1.Flex`, **4 OCPU, 24 GB** | The PAYG allowance. Ampere = **arm64**, which the image must match |
| Image | **Ubuntu 24.04, aarch64** (Canonical) | Oracle Linux would need a different Docker install |
| Boot volume | 100 GB | Inside the 200 GB Always-Free block storage |
| Public IP | **Reserved**, not ephemeral | An ephemeral IP changes on stop/start and takes your DNS with it |
| SSH key | Paste your public key at creation | There is no password login |

**Home region is `us-chicago-1`** (fixed at signup, 2026-09-10; it cannot be changed later). Good
for Houston — better than Ashburn, which is where the signup verification mail came from and is not
the same thing. §7 asks you to record the real latency from a phone.

**Trial versus PAYG, check this before choosing the shape.** The signup mail describes a free trial
with credits and says billing is still being set up. A trial tenancy may only offer the Always-Free
allowance (2 OCPU / 12 GB), not the 4 OCPU / 24 GB this runbook assumes. Confirm in
Billing & Cost Management that the upgrade actually completed; if it has not, either finish it or
build the box at 2/12 and resize later — an A1.Flex shape can be scaled without rebuilding, but the
idle-reclamation exposure in §1 stays until the tenancy is PAYG.

## 3. Networking — the step that costs people an evening

**Two firewalls, and Oracle only tells you about one.**

1. **VCN security list** (console): add ingress rules for **80** and **443** from `0.0.0.0/0`. Port
   22 is already open; narrow it to your own IP if you like.
2. **The box's own iptables.** Oracle's Ubuntu images ship rules that drop everything except 22, so
   80/443 stay dead even after the security list is right — and the symptom is a silent timeout, not
   a refusal:

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

Check with `sudo iptables -L INPUT --line-numbers` — the ACCEPT lines must sit **above** the
catch-all REJECT.

## 4. Base setup (ssh in as `ubuntu`)

```bash
sudo apt-get update && sudo apt-get -y upgrade
sudo apt-get -y install unattended-upgrades git curl ca-certificates
sudo dpkg-reconfigure --priority=low unattended-upgrades   # accept the default: security updates on

# Docker Engine from Docker's own apt repo, NOT Ubuntu's docker.io
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo "deb [arch=arm64 signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# the user the deploy runs as
sudo useradd -m -s /bin/bash legion
sudo usermod -aG docker legion
sudo mkdir -p /opt/legion && sudo chown legion:legion /opt/legion
```

**Group membership needs a fresh login.** `su - legion` (or reconnect) before running `docker`, or
you get "permission denied while trying to connect to the Docker daemon" and waste time on it.

Verify: `docker run --rm hello-world` as `legion`, and `docker --version`, `docker compose version`.

## 5. The stack

```bash
sudo -u legion -i
git clone https://github.com/kevinmyo-code/legion.git /opt/legion
cd /opt/legion
cp deploy/.env.example deploy/.env
```

Fill in `deploy/.env`:

| Key | Value |
|---|---|
| `SECRET_KEY` | `python3 -c "import secrets; print(secrets.token_urlsafe(50))"` |
| `DJANGO_DEBUG` | `false` |
| `ALLOWED_HOSTS` | `legion.<your-domain>` |
| `CSRF_TRUSTED_ORIGINS` | `https://legion.<your-domain>` |
| `POSTGRES_USER` / `POSTGRES_DB` | `legion` / `legion` |
| `POSTGRES_PASSWORD` | a fresh strong one |
| `DATABASE_URL` | `postgres://legion:<that password>@postgres:5432/legion` — the host is the compose **service name** |
| `MEDIA_ROOT` | `/app/media` |
| `COMPOSE_PROFILES` | `full` |
| `LEGION_DOMAIN` | `legion.<your-domain>` (no scheme) |
| `LEGION_ACME_EMAIL` | your address — with it, Caddy also configures ZeroSSL as a fallback issuer |
| **`LEGION_BOOTSTRAP_HOUSEHOLD_ID`** | **`052aa119-aed0-4d80-b9ee-63f44ad65c27`** — must match the live database, every existing row carries it |
| `LEGION_BOOTSTRAP_HOUSEHOLD_NAME` | `Home` |

`chmod 600 deploy/.env`, and `chmod +x deploy/vm/deploy.sh server/entrypoint.sh` — the repo is
authored on Windows, so the clone may not carry the executable bit.

## 6. DNS

Cloudflare: `A  legion.<your-domain>  ->  <reserved IP>`, **proxy OFF (grey cloud)**.

Proxying breaks Let's Encrypt's HTTP-01 challenge, and the failure looks like Caddy hanging rather
than anything naming DNS. Turning the proxy on later is a real option but needs Full (strict) plus an
origin certificate — not now.

Wait for `dig +short legion.<your-domain>` to return the IP before the next step.

## 7. First run

```bash
cd /opt/legion
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs -f web
```

The first build compiles the web client in a Node stage and then `collectstatic`s it, so it is
slower than every later one. `web`'s entrypoint runs `manage.py migrate --noinput` **before**
gunicorn binds a port — so there is a window where the container is up and answering nothing. That
is expected and it is why `deploy.sh` polls rather than curling once.

Then:

```bash
curl -s https://legion.<your-domain>/health      # {"db":"ok"}
curl -o /dev/null -s -w 'connect=%{time_connect}s ttfb=%{time_starttransfer}s total=%{time_total}s\n' \
     https://legion.<your-domain>/health
```

Record that second line in `README.md`'s latency section, **from a phone on mobile data rather than
the LAN** — the point is what the household actually experiences.

**Expect the first build to need a fix.** The multi-stage arm64 Dockerfile has never been built
anywhere. If it fails, capture the failing stage and stop rather than editing files on the box: a box
somebody edited in place refuses to deploy afterwards, because `deploy.sh` uses `git pull --ff-only`.

## 8. Data, users, and only then the phone

The database in compose starts **empty**; migrations create the schema but no rows. Moving the live
data across is §3 of ticket 13 and is its own sitting — dump from Supabase, strip the Supabase-era
RLS policies the restore will choke on, restore, and count every table both sides.

**One thing that got easier:** the tenancy migration is already applied to the live database as of
2026-09-10, so the dump carries `household_id` and the bootstrap household with it. Nothing needs
re-running on the far side, provided `LEGION_BOOTSTRAP_HOUSEHOLD_ID` above matches.

**Do not repoint the phones until the counts match.** Cloud Run and Supabase stay up as the rollback
for thirty days (ticket 13 §4), and rollback is one URL on two devices.
