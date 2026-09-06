---
map: django-engine
ticket: "01"
title: "Server skeleton: Django, Postgres, compose, two users, one token per device"
type: build
status: built
status-detail: "Written 2026-09-05: ruff clean, manage.py check passes with no DB, 14 modules import, migrations generated, missing SECRET_KEY fails loudly. Ticket 07's Cloud Run amendment landed the same day: docker-compose.yml gained a `local` profile (web+worker against Supabase, no postgres container) behind a `full`/`local` compose-profiles split documented at the top of the file; deploy/cloudrun/ gained deploy.py, deploy_job.py, install_schedule.py and a README, all `--dry-run`-verified against a fake project id; server/Dockerfile now binds $PORT for Cloud Run; legion/settings.py gained ALLOWED_HOSTS-for-Cloud-Run-URL support, CSRF_TRUSTED_ORIGINS and SECURE_PROXY_SSL_HEADER. OWED: gcloud SDK install and a first real deploy (no gcloud on this machine); Docker on this machine for compose up to /healthz, pytest against Postgres, and the auth round-trip; deploy/crontab does not exist yet (ticket 06, blocked on 02) so install_schedule.py has nothing to schedule until then."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# Server skeleton

Everything later tickets assume exists. Nothing LEGION-shaped yet except users and a health check.

## Step 0, this machine

`docker` is not installed here (checked 2026-09-05). Install Docker Desktop for Windows with the
WSL 2 backend. Fallback if it cannot be installed: PostgreSQL 16 Windows installer, a local
`legion` database, and `DATABASE_URL` pointing at it. Compose is the deployment shape either way;
the fallback is dev-only.

## Layout

```
server/
  manage.py
  pyproject.toml            # ruff + pytest config only
  requirements.txt          # pinned
  Dockerfile
  legion/                   # the project package
    settings.py             # every secret and host from os.environ, no defaults for secrets
    urls.py
    asgi.py wsgi.py
  household/                # users, membership, device tokens, auth endpoints
  api/                      # DRF routers, the OpenAPI schema, shared serializers and mixins
  tests/
    conftest.py             # pytest-django, real Postgres, never SQLite
deploy/
  docker-compose.yml        # postgres, web, worker, caddy
  Caddyfile
  .env.example
```

Django 5.2 LTS, djangorestframework 3.16, psycopg[binary] 3.2, gunicorn, drf-spectacular,
pytest-django, ruff. Nothing else in this ticket.

## `household`

- Custom user: `class User(AbstractUser)` with `id = models.UUIDField(primary_key=True, default=uuid4)`.
  **UUID on purpose:** ticket 10 sets the two migrated users' ids to their Supabase `auth.uid` so
  every row that references a user keeps referencing it. `email` is the login, `USERNAME_FIELD = "email"`.
- `DeviceToken(user FK, name text, key_hash text unique, created_at, last_seen_at, revoked_at null)`.
  Raw key shown once at creation, only the SHA-256 stored. One row per phone, per browser, per robot.
  Revoke one without touching the others.
- `HouseholdMember(user OneToOne)`. The existing `public.household_members` shape is kept in ticket
  02; this is the Django-side mirror so the auth check has no raw SQL.
- Authentication class `DeviceTokenAuthentication`: header `Authorization: Token <key>`, looks up the
  hash, refuses revoked, stamps `last_seen_at`. Permission `IsHouseholdMember`.
- Endpoints:
  - `POST /api/auth/login  {email, password, device_name}` -> `{token, user_id}`. Rate-limited 5/min per IP.
  - `POST /api/auth/logout` revokes the calling token.
  - `GET  /api/auth/me` -> `{user_id, email, device_name}`. This is the phone's membership check.
- Users are created by `manage.py createsuperuser` and the Django admin. No signup, no invite. Same
  ruling as backend-erp ticket 02.

## Compose

`postgres:16` with a named volume; `web` = gunicorn on 8000; `worker` = same image, entrypoint
`supercronic /etc/crontab` (ticket 06 fills the crontab); `caddy` terminating TLS, reverse proxy to
web. `.env.example` lists `SECRET_KEY`, `DATABASE_URL`, `ALLOWED_HOSTS`, `MEDIA_ROOT`. `.env` is
gitignored.

## Verification

- [ ] `docker compose up` on a clean checkout reaches `GET /healthz` -> `{"db": "ok"}`.
- [ ] `pytest` runs against the compose Postgres, green, and refuses to run on SQLite
      (assert in `conftest.py` on the engine name).
- [ ] Login with a wrong password returns 401 with no token; right password returns a token that
      passes `GET /api/auth/me`; a revoked token gets 401.
- [ ] `ruff check server` clean.
