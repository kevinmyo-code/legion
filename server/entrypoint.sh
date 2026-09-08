#!/bin/sh -e
#
# The `web` container's entrypoint: run migrations, then serve.
# web-and-households ticket 09; ticket 13's runbook step 2 depends on it.
#
# **Why this file is in `server/` and not in `deploy/`.**
# Tickets 09 and 13 both name it `deploy/entrypoint.sh`. It cannot live there
# and still reach the image: the Docker build context is `server/` in all three
# places that build this image - `deploy/docker-compose.yml`'s `web` and
# `worker` services (`context: ../server`) and `deploy/cloudrun/deploy.py`,
# which shells out to `docker build -t <tag> server/` or
# `gcloud builds submit --tag <tag> server/`. A `COPY ../deploy/entrypoint.sh`
# is not expressible, BuildKit named contexts would not survive Cloud Build,
# and widening the context to the repo root would tar the whole Android app
# into every build. So the script sits inside the context it has to be copied
# from. Whoever writes ticket 13's runbook: the path is `server/entrypoint.sh`.
#
# `-e` on the shebang, not `set -e` in the body, so it applies from the first
# line. Every command below is load-bearing; there is nothing here whose
# failure should be shrugged off.

# **Migrations run here, on container start, and the safety of that rests on
# there being exactly one `web` replica.** Compose runs one (no `deploy.replicas`
# in `deploy/docker-compose.yml`), so no second process can be applying the same
# migration at the same time, and Django takes no lock of its own that would
# make it safe if one could.
#
# What changes if that ever stops being true - either a second `web` replica, or
# a Cloud Run service that scales past one instance:
#
#   1. Migrations move OUT of this script into a one-off job that runs to
#      completion before the new containers start (`docker compose run --rm web
#      python manage.py migrate --noinput`, or a Cloud Run Job).
#   2. This script keeps the code path but skips it, via
#      `LEGION_SKIP_MIGRATE=1` in that deployment's environment. The switch
#      exists now rather than later so the answer to "how do I stop this
#      racing" is a variable and not a rebuild.
#
# The Cloud Run service kept as ticket 13's thirty-day rollback is exactly the
# case to watch: it can run more than one instance, so if it is ever redeployed
# on this image it should carry LEGION_SKIP_MIGRATE=1.
#
# There is no wait-for-Postgres loop, deliberately. `docker-compose.yml` has no
# `depends_on` for the `postgres` service (its own comment explains why: the
# dependency is only real in the `full` profile and compose has no per-profile
# `depends_on`), so on the very first `up` this may run before Postgres finishes
# initdb. `-e` then exits non-zero, `restart: unless-stopped` brings the
# container back, and the next attempt succeeds. That is a crash-loop that
# converges in seconds and says exactly what is wrong in the logs, which is
# better than a retry loop that hides a genuinely unreachable database.
if [ "${LEGION_SKIP_MIGRATE:-}" = "1" ]; then
	echo "entrypoint: LEGION_SKIP_MIGRATE=1, skipping migrate." >&2
else
	python manage.py migrate --noinput
fi

# Pass-through for anything given a command. `docker compose run --rm web python
# manage.py createsuperuser` and the rest of ticket 13's runbook arrive here as
# "$@", because compose appends a `command:` to the image's ENTRYPOINT. Without
# this branch the container would ignore what it was asked to do and start
# gunicorn instead - a silent wrong answer, and the failure mode most likely to
# waste an hour on migration night.
if [ "$#" -gt 0 ]; then
	exec "$@"
fi

# `${PORT:-8000}`: Cloud Run injects PORT and expects the container to listen on
# exactly it; compose never sets it, so 8000 is the fallback. This is a shell,
# so the expansion is native - see the Dockerfile's EXPOSE comment for why the
# same string in an exec-array CMD was a silent way to bind the wrong port.
#
# **Two workers, on a 4-OCPU box.** The usual (2 x cores) + 1 rule of thumb is
# for a machine dedicated to gunicorn; this one also runs Postgres, Caddy and
# the supercronic worker in containers beside it, and the load is a household -
# two phones syncing and a handful of browser tabs, not the internet. Two sync
# workers give two genuinely concurrent requests and two Postgres connections,
# which is sized to the users rather than to the hardware. The number to watch
# is queueing: if `/health` latency climbs while CPU stays low, requests are
# waiting for a worker and this should go up. Ticket 13 records a baseline
# latency for exactly that comparison.
#
# `--timeout 60` bounds a stuck worker: gunicorn kills and replaces one that has
# not finished a request in a minute, so a single hung upload cannot take half
# the capacity permanently. `--access-logfile -` sends the access log to stdout,
# which is where `docker compose logs web` and `deploy/vm/deploy.sh`'s failure
# dump both read from; without it a deploy that comes up but answers nothing
# looks identical to one that answers fine.
exec gunicorn legion.wsgi:application \
	--bind "0.0.0.0:${PORT:-8000}" \
	--workers 2 \
	--timeout 60 \
	--access-logfile -
