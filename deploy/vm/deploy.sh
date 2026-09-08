#!/bin/bash
#
# The deploy, run ON the Oracle VM. web-and-households ticket 08 (the CD half)
# and ticket 12.
#
# This is the ONLY thing `.github/workflows/deploy.yml` can make the box do: the
# deploy key's `authorized_keys` entry is restricted with
# `command="/opt/legion/deploy/vm/deploy.sh"`, so sshd runs this script and
# ignores whatever the client asked for. `deploy/vm/README.md` has the exact
# line. That is also why this script takes no arguments and reads no input -
# anything it accepted from the caller would be a way around the restriction.
#
# A failure anywhere here must stop the deploy and fail the workflow, never
# leave the box half-updated with a green tick in Actions - hence
# `set -euo pipefail`.
#
# **It is `set`, not `#!/bin/bash -euo pipefail`, and shellcheck is why**
# (SC2096): Linux's shebang handling passes everything after the interpreter as
# ONE argument, so that form invokes `/bin/bash "-euo pipefail"` and bash exits
# with "invalid option" before reading a line of this file. A single-word
# option is fine, which is why `server/entrypoint.sh` may say `#!/bin/sh -e`;
# three are not.
#
# It is also safe to run by hand over a normal SSH session. Nothing below
# depends on being invoked by the workflow.
set -euo pipefail

REPO_DIR=/opt/legion
COMPOSE_FILE="${REPO_DIR}/deploy/docker-compose.yml"
ENV_FILE="${REPO_DIR}/deploy/.env"
HEALTH_URL=http://127.0.0.1:8000/health
HEALTH_TIMEOUT_SECONDS=60

cd "${REPO_DIR}"

# **`--ff-only`, and it is a ruling not a flag.** A box whose checkout has
# diverged from `main` - somebody edited a file in place at 2am to get the
# household running again - is an operator problem that a human has to look at.
# A plain `git pull` would silently merge that edit, or stop halfway through a
# conflict with the stack already down. `--ff-only` refuses, loudly, and leaves
# the running containers exactly as they were.
echo "==> git pull --ff-only"
git pull --ff-only

# **The box builds its own image**, rather than pulling one a runner built.
# Three reasons, in order of weight:
#   1. It is arm64 (Ampere A1). Building arm64 on GitHub's amd64 runners means
#      QEMU emulation, which is slow enough to change how often anyone deploys.
#   2. No registry. Pushing an image needs somewhere to push it, credentials for
#      it, and a retention policy for it - three things that exist only to move
#      bytes between two machines that could have built them.
#   3. A 4-OCPU box rebuilds this image in about a minute, and Docker's layer
#      cache means most deploys touch only the last few layers.
# The cost is honest: a build failure happens here, on the box, after `git pull`
# has already moved the checkout forward. The OLD containers keep serving
# throughout - compose only swaps a container once its new image exists - so a
# failed build is a deploy that did not happen, not an outage.
echo "==> docker compose up -d --build"
docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" \
	up -d --build --remove-orphans

echo "==> docker compose ps"
docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" ps

# **Poll, do not curl once.** `web`'s entrypoint runs `manage.py migrate
# --noinput` BEFORE gunicorn binds a port (see `server/entrypoint.sh`), so
# there is a window after `up -d` returns in which the container is running,
# healthy by Docker's reckoning, and answering nothing at all. A single
# `curl -f` immediately after would fail every deploy that included a
# migration. Sixty seconds is sized for a migration, not for a boot.
echo "==> waiting up to ${HEALTH_TIMEOUT_SECONDS}s for ${HEALTH_URL}"
deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
while true; do
	if curl -fsS --max-time 5 "${HEALTH_URL}" >/dev/null 2>&1; then
		echo "==> healthy"
		curl -fsS --max-time 5 "${HEALTH_URL}"
		echo
		exit 0
	fi
	if [ "${SECONDS}" -ge "${deadline}" ]; then
		break
	fi
	sleep 2
done

# Never exit non-zero without saying what the box saw. A red workflow whose only
# content is "curl failed" sends whoever reads it straight back to the box for
# the one thing that would have explained it.
echo "!!! ${HEALTH_URL} did not answer within ${HEALTH_TIMEOUT_SECONDS}s." >&2
echo "!!! Last 50 lines of the web service:" >&2
docker compose -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" \
	logs --tail=50 web >&2 || true
exit 1
