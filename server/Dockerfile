# Single image for both the `web` and `worker` compose services (ADR 0044:
# worker is "same image, entrypoint supercronic /etc/crontab"). The default
# CMD is gunicorn; compose overrides the entrypoint for `worker`.
FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

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

EXPOSE 8000

CMD ["gunicorn", "legion.wsgi:application", "--bind", "0.0.0.0:8000"]
