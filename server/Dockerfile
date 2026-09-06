# Single image for the `web` and `worker` compose services (ADR 0044: worker
# is "same image, entrypoint supercronic /etc/crontab") AND for Cloud Run's
# service and Job (ticket 07 amendment: same image, the Job overrides the
# command to `python manage.py <task>` instead of running gunicorn). The
# default CMD is gunicorn; compose and `gcloud run jobs deploy --command`
# both override it, each their own way.
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

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# supercronic runs the worker's crontab (ticket 06 fills it in) without
# needing a full cron daemon or root-owned crontab files in the image.
ARG SUPERCRONIC_VERSION=v0.2.49
ARG SUPERCRONIC_SHA1=e63c11a9726b775a6a11801e81af4f3fb926aa68
RUN curl -fsSLO "https://github.com/aptible/supercronic/releases/download/${SUPERCRONIC_VERSION}/supercronic-linux-amd64" \
    && echo "${SUPERCRONIC_SHA1}  supercronic-linux-amd64" | sha1sum -c - \
    && chmod +x supercronic-linux-amd64 \
    && mv supercronic-linux-amd64 /usr/local/bin/supercronic

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

RUN mkdir -p /etc && touch /etc/crontab

# Cloud Run injects PORT and expects the container to listen on exactly
# that port; compose never sets it, so 8000 is the fallback both for
# compose's `web` service and for a bare `docker run` on this image. Using
# shell form (not exec-array CMD) so ${PORT:-8000} actually expands - the
# array form passes the literal string to gunicorn with no shell to
# substitute it, which is a silent way to make the health check fail against
# the wrong port.
EXPOSE 8000
CMD gunicorn legion.wsgi:application --bind 0.0.0.0:${PORT:-8000}
