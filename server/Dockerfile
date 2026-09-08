# syntax=docker/dockerfile:1
#
# Single image for the `web` and `worker` compose services (ADR 0044: worker
# is "same image, entrypoint supercronic /etc/crontab") AND for Cloud Run's
# service and Job (ticket 07 amendment: same image, the Job overrides the
# command to `python manage.py <task>` instead of running gunicorn).
#
# **The image must build for linux/arm64** (web-and-households tickets 09 and
# 12): the engine moves to an Oracle `VM.Standard.A1.Flex`, which is Ampere.
# Both base images below are multi-arch; the one thing that was hardcoded to
# amd64 was the supercronic download, and that is now keyed on `TARGETARCH`.
# Cloud Run (amd64) stays as the thirty-day rollback, so both architectures
# have to keep working out of one file.
#
# Two stages. Stage 1 builds the Vite bundle; stage 2 is the Python image the
# containers actually run, and it takes the bundle from stage 1 rather than
# from the developer's working tree - see `.dockerignore` for why that
# distinction is correctness rather than tidiness.

# ---------------------------------------------------------------------------
# Stage 1: the web client (web-and-households ticket 04)
# ---------------------------------------------------------------------------
FROM node:24-slim AS frontend

WORKDIR /frontend

# package.json + lockfile first, on their own layer: `npm ci` then re-runs only
# when a dependency actually changes, not on every source edit.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./

# `npm run build` is `tsc -b && vite build` (frontend/package.json). Both halves
# matter: a type error must fail the image build, not ship.
#
# **Where the output lands, and why nothing here overrides it.**
# `vite.config.ts` sets `build.outDir: '../static/app'`, relative to the Vite
# root - which is this WORKDIR - so inside the container it resolves to the
# absolute path `/static/app`. That is outside `/frontend`, which is exactly
# what stage 2 needs for its `COPY --from`. No `--outDir` flag and no build arg
# is needed, and `vite.config.ts` (ticket 04's file) is not edited: the config
# that produces `server/static/app` on a laptop produces `/static/app` in the
# container from the same line. `emptyOutDir: true` is set in that config, so
# Vite does not refuse to clean a directory outside its own root.
RUN npm run build

# ---------------------------------------------------------------------------
# Stage 2: the engine
# ---------------------------------------------------------------------------
FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

# UTC on purpose, not a city-named zone. CLAUDE.md forbids handing an IANA
# timezone id (a database key that happens to contain a place) anywhere the
# model or a user-facing surface can read it; `legion/settings.py` sets
# TIME_ZONE = "UTC" for the same reason the midconerpdash reference sets
# TZ=America/Chicago - so "today" in a nightly job means the same day
# everywhere - but LEGION's server stores instants and leaves the offset to
# the device limb, so the container clock stays UTC rather than adopting
# Houston's.

# DL3008 asks for `curl=<version>`. Not pinned, on purpose: Debian's archive
# keeps only the current version of a package in `slim`'s suite, so a pinned
# version stops resolving the first time Debian ships a security update and the
# image then fails to build for a reason that has nothing to do with this repo.
# The base image tag (`python:3.13-slim`) is the pin that matters here.
# hadolint ignore=DL3008
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# supercronic runs the worker's crontab (ticket 06 fills it in) without
# needing a full cron daemon or root-owned crontab files in the image.
#
# **Per-architecture, because the VM is arm64 and Cloud Run is amd64.**
# Both SHA1s below were confirmed twice on 2026-09-08, and the two methods
# agree:
#
#   1. Downloaded each asset and hashed it -
#      `curl -fsSLO https://github.com/aptible/supercronic/releases/download/v0.2.49/supercronic-linux-arm64`
#      then `sha1sum supercronic-linux-arm64`.
#   2. Read the SHA1s aptible publishes in the v0.2.49 release notes
#      (<https://github.com/aptible/supercronic/releases/tag/v0.2.49>), which
#      carry a ready-made Dockerfile stanza per architecture.
#
#   amd64  e63c11a9726b775a6a11801e81af4f3fb926aa68  (unchanged - this is the
#          value this file already carried, and method 1 reproduced it, which
#          is what says the method used for arm64 is sound)
#   arm64  0b6c5bb743e0b0dafed1132198c81807927ac413  (new)
#
# `TARGETARCH` is set by BuildKit from the build platform, so `docker build` on
# the arm64 VM and `docker buildx build --platform linux/arm64` both get
# "arm64" with no flag. It is given a default anyway: `gcloud builds submit`
# (deploy/cloudrun/deploy.py's default path) runs the CLASSIC docker builder,
# which does not populate the predefined platform args at all, and an empty
# TARGETARCH would ask GitHub for `supercronic-linux-` and fail. An explicit
# build arg always beats an ARG default, so BuildKit still wins where it is
# used, and amd64 - which is what Cloud Run is - is the fallback where it is
# not. An architecture with no pinned checksum fails loudly rather than
# downloading something unverified.
ARG SUPERCRONIC_VERSION=v0.2.49
ARG SUPERCRONIC_SHA1_AMD64=e63c11a9726b775a6a11801e81af4f3fb926aa68
ARG SUPERCRONIC_SHA1_ARM64=0b6c5bb743e0b0dafed1132198c81807927ac413
ARG TARGETARCH=amd64
# DL4006 wants `SHELL ["/bin/sh", "-o", "pipefail", "-c"]` before a RUN
# containing a pipe. The pipe here is `echo <sha1> | sha1sum -c -`, whose LAST
# command is the check itself - so its exit status is the one the shell reports,
# `set -eu` on the first line makes a non-zero status fatal, and the only way
# the left-hand side could fail is `echo` failing. Setting a global pipefail
# SHELL to satisfy a lint would change the behaviour of every other RUN in the
# file for a pipe that is already checked at the point it matters.
# hadolint ignore=DL4006
RUN set -eu; \
    case "${TARGETARCH}" in \
      amd64) supercronic_sha1="${SUPERCRONIC_SHA1_AMD64}" ;; \
      arm64) supercronic_sha1="${SUPERCRONIC_SHA1_ARM64}" ;; \
      *) echo "No supercronic SHA1 is pinned for TARGETARCH='${TARGETARCH}'." >&2; \
         echo "Add one from the ${SUPERCRONIC_VERSION} release notes rather than skipping the check." >&2; \
         exit 1 ;; \
    esac; \
    asset="supercronic-linux-${TARGETARCH}"; \
    curl -fsSLO "https://github.com/aptible/supercronic/releases/download/${SUPERCRONIC_VERSION}/${asset}"; \
    echo "${supercronic_sha1}  ${asset}" | sha1sum -c -; \
    chmod +x "${asset}"; \
    mv "${asset}" /usr/local/bin/supercronic

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# The bundle stage 1 built, not the one on the developer's disk. `static/app/`
# is in `.dockerignore` precisely so the `COPY . .` above cannot have put a
# stale copy here first.
COPY --from=frontend /static/app /app/static/app

# **collectstatic runs at build time, and that is the point.**
# `settings.STORAGES["staticfiles"]` is WhiteNoise's
# `CompressedManifestStaticFilesStorage`: this step is where every file gets its
# content-hashed name, its compressed sibling and its entry in
# `staticfiles.json`. Doing it in the image means every container starts with an
# identical, already-hashed set of files and nothing writes to static at runtime.
#
# **Why the environment is inline on this RUN and not `ENV`.**
# `legion/settings.py` calls `required_env()` for SECRET_KEY, DJANGO_DEBUG,
# ALLOWED_HOSTS, DATABASE_URL and MEDIA_ROOT - it raises naming the variable
# rather than falling back to a default, on purpose (CLAUDE.md section 7: no
# Kevin-hosted default). Importing settings to run collectstatic therefore needs
# all five to exist. Setting them with `ENV` would bake them into the image and
# they would survive into the RUNNING container, where a household that forgot
# to set SECRET_KEY would silently get this throwaway one instead of the loud
# failure `required_env` exists to produce. Inline on the RUN, they exist for
# this one process and are in no layer's environment afterwards.
#
# `DATABASE_URL` has to be a syntactically valid `postgres://` URL because
# `_parse_database_url()` runs at import and rejects any other scheme - but
# nothing connects to it here, and that was confirmed by reading the code rather
# than assumed: `collectstatic` declares
# `requires_system_checks = [Tags.staticfiles]`, so the model and database
# checks never run, and `whitenoise.storage.CompressedManifestStaticFilesStorage`
# extends Django's `ManifestStaticFilesStorage`, which is filesystem-only. The
# host `localhost` below is a placeholder nothing dials.
#
# `mkdir -p /app/media` rides along in the same instruction because MEDIA_ROOT
# has to name a real directory for a bare `docker run` with no volume mounted.
# Compose mounts the `media` named volume over this path, and on the VM that
# volume is what ticket 13's backup covers alongside the database.
RUN mkdir -p /app/media \
    && SECRET_KEY=build-time-only-never-used-at-runtime \
    DJANGO_DEBUG=false \
    ALLOWED_HOSTS=localhost \
    DATABASE_URL=postgres://build:build@localhost:5432/build \
    MEDIA_ROOT=/app/media \
    python manage.py collectstatic --noinput

# An empty crontab so the `worker` service's supercronic has a file to read
# before ticket 06 fills it in, and the executable bit on the `web` entrypoint.
#
# `chmod +x` is not decoration: this repo is authored on Windows, where git
# records shell scripts as mode 100644 and the filesystem carries no executable
# bit for the build context to preserve. The same trap is already written down
# for `gradlew` in `.github/workflows/android.yml`.
#
# DL3059 (consolidate consecutive RUNs) is declined here and it is the one lint
# in this file worth arguing rather than just silencing: every RUN above carries
# the reasoning for what it does immediately over it, and folding four unrelated
# concerns - apt, a checksummed download, a static build, and this - into one
# instruction to save two layers would put four explanations in one comment
# block and break `pip install`'s layer cache on any change to any of them.
# hadolint ignore=DL3059
RUN mkdir -p /etc \
    && touch /etc/crontab \
    && chmod +x /app/entrypoint.sh

# Cloud Run injects PORT and expects the container to listen on exactly that
# port; compose never sets it, so 8000 is the fallback both for compose's `web`
# service and for a bare `docker run` on this image. The `${PORT:-8000}`
# expansion now happens inside `entrypoint.sh`, which is a shell script and
# expands it natively. That replaces the shell-form `CMD` this file used to
# carry, and the original reason for that shell form still stands and is worth
# keeping written down: an exec-array `CMD` passes `${PORT:-8000}` to gunicorn
# as a literal string with no shell to substitute it, which is a silent way to
# bind the wrong port and fail a health check for a reason nothing logs.
EXPOSE 8000

# ENTRYPOINT, and deliberately NO CMD. The two overrides that exist both work:
#
#   - compose's `worker` sets `entrypoint: ["supercronic", "/etc/crontab"]`,
#     which replaces this. Compose's `entrypoint:` also clears the image's
#     default command, so a leftover CMD would not be appended as arguments to
#     supercronic - but there being no CMD here at all is the more robust of
#     the two reasons.
#   - `gcloud run jobs deploy --command python --args manage.py`
#     (deploy/cloudrun/deploy_job.py) replaces both.
#
# A CMD here would be worse than useless: with an ENTRYPOINT set, Docker passes
# CMD as ARGUMENTS to it, so the Cloud Run SERVICE - which overrides neither -
# would run `/app/entrypoint.sh /bin/sh -c "gunicorn ..."`. `entrypoint.sh`
# execs a passed command on purpose (see its own comments), so that would have
# run gunicorn under a nested shell instead of failing visibly.
ENTRYPOINT ["/app/entrypoint.sh"]
