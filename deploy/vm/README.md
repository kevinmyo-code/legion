# The VM deploy pieces

What `deploy/vm/deploy.sh` and `.github/workflows/deploy.yml` expect to find, and nothing else.
**This is not the provisioning runbook.** Creating the tenancy, the machine, the firewall rules,
the DNS record and the first `docker compose up` is **`PROVISION.md`, beside this file** - follow
that at a terminal. Its plan and verification bar are [[13-vm-provision-migrate-cutover]]
(`.scratch/web-and-households/issues/13-vm-provision-migrate-cutover.md`), and moving the data onto
the box is that ticket's section 3. Kevin runs all of it himself because every step touches his
Oracle tenancy, his domain or his card. Read `PROVISION.md` first if the box does not exist yet.

Hosting decision: [[12-hosting-oracle-vm]]. CI/CD ticket: [[08-ci-cd]]. Image and Caddyfile:
[[09-static-domain-dockerfile]].

## What the box must already have

`deploy.sh` assumes all of this and checks none of it - it is a deploy script, not a provisioner, and
a deploy that repaired its own prerequisites would hide the day one of them disappeared.

| Thing | Why |
|---|---|
| The repo cloned at **`/opt/legion`** | Hardcoded in `deploy.sh` and in the forced command below. Not configurable on purpose: the path is half of what the SSH key is allowed to do. |
| A non-root **`legion`** user, in the **`docker`** group | `deploy.sh` runs `docker compose` with no `sudo`. Group membership needs a fresh login to take effect - a first attempt that fails with "permission denied while trying to connect to the Docker daemon" usually means the session predates the `usermod`. |
| **`/opt/legion/deploy/.env`**, filled in | Every service reads it. `deploy.sh` passes it explicitly with `--env-file` so the result does not depend on which directory the script was invoked from. Copy `deploy/.env.example`; `LEGION_DOMAIN` and `LEGION_ACME_EMAIL` are in it, and Caddy is the only reader of those two. |
| Docker Engine + the compose plugin, **arm64** | From Docker's own apt repository, not Ubuntu's `docker.io`. The image is built on the box (see below). |
| The checkout on `main`, not diverged | `deploy.sh` uses `git pull --ff-only`. A box somebody edited in place refuses to deploy rather than silently merging the edit - that is an operator problem for a human, not something a robot resolves at 3am. |

## The deploy key

One key, one job. Generate it on a machine that is not the VM, put the **public** half on the box
and the **private** half in the repository secret.

```
ssh-keygen -t ed25519 -C "legion-deploy" -f ./legion-deploy -N ""
```

Then, in the `legion` user's `~/.ssh/authorized_keys` on the box, one line - the restriction prefix
followed by the contents of `legion-deploy.pub`:

```
command="/opt/legion/deploy/vm/deploy.sh",no-port-forwarding,no-agent-forwarding,no-X11-forwarding,no-pty ssh-ed25519 AAAA...restofthepublickey legion-deploy
```

**The `command=` prefix is what makes this safe to hand to GitHub.** sshd runs that script and
discards whatever command the client actually sent, so a leaked private key buys one deploy of
whatever is already on `main` - not a shell on the box, not a tunnel into the household network, and
not the `.env` sitting beside the compose file. The workflow still passes the script path in its
`ssh` command line; that is documentation and a convenience for a human using an unrestricted key,
and sshd ignores it either way.

`deploy.sh` must be executable on the box: `chmod +x /opt/legion/deploy/vm/deploy.sh`. The repo is
authored on Windows, where git records shell scripts as mode 100644, so do not assume the clone
carried the bit even though the index sets it.

## The four repository secrets

Settings > Secrets and variables > Actions > **Secrets** (not Variables - the workflow's own header
explains why the hostname is a secret too).

| Secret | Value |
|---|---|
| `VM_HOST` | The box's public IP or hostname. Use the **reserved** IP, not the ephemeral one, or a stop/start silently repoints this. |
| `VM_USER` | `legion` |
| `VM_SSH_KEY` | The whole private key file, `-----BEGIN`/`-----END` lines included. |
| `VM_KNOWN_HOSTS` | The box's own host key line, so the runner verifies what it is connecting to. Take it FROM the box, over a channel you already trust - `cat /etc/ssh/ssh_host_ed25519_key.pub` there, then format it as `<host> ssh-ed25519 AAAA...`, or run `ssh-keyscan <host>` once from a machine you trust and compare the fingerprint against `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` read on the console. The workflow does not run `ssh-keyscan` itself: fetching a host key at deploy time and immediately trusting it verifies nothing. |

## What a deploy does

`git pull --ff-only`, then `docker compose up -d --build --remove-orphans`, then `docker compose
ps`, then it polls `http://127.0.0.1:8000/health` for up to 60 seconds and fails with the last 50
lines of the `web` log if it never answers.

The poll is not belt-and-braces. `web`'s entrypoint runs `manage.py migrate --noinput` **before**
gunicorn binds a port, so there is a window after `up -d` returns in which the container is running
and answering nothing; a single `curl -f` there would fail every deploy that included a migration.

**The box builds its own image.** No registry, no QEMU-emulated arm64 build on an amd64 runner. The
old containers keep serving until the new image exists, so a failed build is a deploy that did not
happen rather than an outage.

## Latency baseline - NOT MEASURED YET

[[09-static-domain-dockerfile]] asks for a real `/health` round-trip from Houston to the VM's home
region, recorded here so that "why is it slow" has something to compare against. **There is no
number here because there is no box yet.** Filling it in is a step of ticket 13, from a phone on
mobile data rather than the LAN:

```
curl -o /dev/null -s -w 'connect=%{time_connect}s ttfb=%{time_starttransfer}s total=%{time_total}s\n' https://<domain>/health
```

For comparison, the Cloud Run engine it replaces measured a **5.4 s cold start** at min-instances 0
(ticket 12), which is the whole reason the VM is always-on. A warm figure from this box that is not
dramatically better than a warm Cloud Run figure would mean the region choice deserves another look.
