"""LEGION's Django settings.

CLAUDE.md section 7: no Kevin-hosted anything. Every secret and every host
name comes from the environment, with no default for a secret, so a stranger
who clones this and forgets to set one gets a loud failure naming the exact
variable rather than a server that quietly runs on Kevin's values (there are
none) or on `DEBUG=True` (there isn't a default for that either, on purpose:
a household that forgets to set it should get the strict behaviour).

`.env.example` in `deploy/` lists every variable this file reads. `.env` is
gitignored; `python-dotenv` loads it in dev, and compose's `env_file:` does
the same job in the container - either way this module only ever reads
`os.environ`, never a file directly, so both paths are one code path.
"""
from __future__ import annotations

import os
from pathlib import Path

from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent

# Loads `deploy/.env` for local `manage.py` runs. In compose, `env_file:`
# has already populated `os.environ` before this module ever imports, and
# `load_dotenv` never overwrites a variable that is already set, so the two
# paths cannot disagree about which value wins.
load_dotenv(BASE_DIR.parent / "deploy" / ".env")


def required_env(name: str) -> str:
    """Read a secret from the environment or fail loudly naming it.

    A missing secret must never fall back to a hardcoded value - that is
    exactly the Kevin-hosted-default shape section 7 forbids, and it is how
    a cloned server would end up quietly configured with someone else's
    assumptions instead of refusing to start.
    """
    value = os.environ.get(name)
    if not value:
        raise RuntimeError(
            f"Required environment variable {name!r} is not set. "
            f"See deploy/.env.example and copy it to deploy/.env."
        )
    return value


SECRET_KEY = required_env("SECRET_KEY")

# DEBUG has no default either: an unset value means "I have not decided",
# and a household running this without deciding should see a crash, not a
# guess. Compose and .env.example both set it explicitly.
DEBUG = required_env("DJANGO_DEBUG").strip().lower() in {"1", "true", "yes", "on"}

ALLOWED_HOSTS = [
    host.strip()
    for host in required_env("ALLOWED_HOSTS").split(",")
    if host.strip()
]

# Ticket 07 amendment: compute is Cloud Run, reached at its own
# *.run.app URL and, once DNS is pointed there, a Cloudflare-fronted
# household domain. Both are comma-separated in the same variable as any
# other host - ALLOWED_HOSTS has no notion of "this one is temporary" and
# doesn't need one. No default: an empty ALLOWED_HOSTS from a household
# that has not set this yet means Django refuses every request with
# DisallowedHost, which is the correct failure - a silent default host
# would be the same Kevin-hosted-assumption section 7 forbids.

# Comma-separated origins CSRF trusts a POST's Origin/Referer header
# against - Django's own default (deriving it from ALLOWED_HOSTS) does not
# add the scheme, and a Cloud Run URL or a Cloudflare-fronted domain is
# always HTTPS, so this is stated explicitly rather than guessed. Optional,
# not `required_env`: local dev's compose profile talks to `web` over
# plain HTTP on localhost and never hits a CSRF-protected form (the phone
# and web app authenticate with a device token header, not a session
# cookie), so there is nothing to trust yet and no household is forced to
# decide a value it does not have.
CSRF_TRUSTED_ORIGINS = [
    origin.strip()
    for origin in os.environ.get("CSRF_TRUSTED_ORIGINS", "").split(",")
    if origin.strip()
]

# Cloud Run terminates TLS at its own front end and forwards the original
# scheme in this header before the request reaches the container, which
# otherwise looks like plain HTTP to Django - request.is_secure() and the
# CSRF and session "secure cookie" checks would all misfire behind the
# proxy without this. Safe to trust unconditionally here because Cloud
# Run's proxy is the only thing that can ever reach this container
# directly; there is no second hop it could be spoofed at.
SECURE_PROXY_SSL_HEADER = ("HTTP_X_FORWARDED_PROTO", "https")

INSTALLED_APPS = [
    "django.contrib.admin",
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django.contrib.staticfiles",
    "rest_framework",
    "drf_spectacular",
    # No models - houses the `django`-schema-creation migration that has to
    # run before any other app's first migration. See `core/apps.py`.
    "core",
    "household",
    "api",
    # Ticket 04 (django-engine map): the first tables Django owns end to
    # end, no legacy Supabase table to honour. MANAGED, unlike `legacy`
    # below - see `checklists/models.py`'s own module doc for why these
    # still land in `public` rather than the `django` schema every other
    # Django-owned table uses.
    "checklists",
    # Ticket 02 (django-engine map): the 41 `public` tables Supabase created,
    # read as `managed = False` mirrors. See `legacy/models/` and
    # `legacy/CONSTRAINTS.md`.
    "legacy",
    # Ticket 03 (django-engine map): the section 4 gate, moved out of
    # `public.commit_statement`/`public.commit_receipt` and into Python
    # (ADR 0044 decision 2). No models of its own - it writes `legacy`'s
    # four gated tables and `ingested_files`, which is why it needs no
    # migrations either.
    "ingest",
    # web-and-households ticket 04: the SPA shell, and nothing else. No models,
    # no serializers, one view that hands the browser the built `index.html`.
    # The web client is a limb like the phone is - it talks to the same API over
    # the same contract - so Django's only job for it is to serve the bundle.
    "web",
]

MIDDLEWARE = [
    "django.middleware.security.SecurityMiddleware",
    # Immediately after SecurityMiddleware, which is where WhiteNoise's own
    # documentation puts it and the position matters in both directions: after,
    # so SecurityMiddleware's HTTPS redirect still applies to a static file;
    # before everything else, so a hit on `/static/...` is answered and returned
    # without paying for sessions, auth or CSRF on a file that has no user.
    "whitenoise.middleware.WhiteNoiseMiddleware",
    "django.contrib.sessions.middleware.SessionMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.csrf.CsrfViewMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django.contrib.messages.middleware.MessageMiddleware",
    "django.middleware.clickjacking.XFrameOptionsMiddleware",
]

ROOT_URLCONF = "legion.urls"

TEMPLATES = [
    {
        "BACKEND": "django.template.backends.django.DjangoTemplates",
        "DIRS": [],
        "APP_DIRS": True,
        "OPTIONS": {
            "context_processors": [
                "django.template.context_processors.debug",
                "django.template.context_processors.request",
                "django.contrib.auth.context_processors.auth",
                "django.contrib.messages.context_processors.messages",
            ],
        },
    },
]

WSGI_APPLICATION = "legion.wsgi.application"
ASGI_APPLICATION = "legion.asgi.application"

# `DATABASE_URL` is parsed by hand rather than pulled in via `dj-database-url`
# - ticket 01 pins Django, DRF, psycopg, gunicorn, drf-spectacular,
#   pytest-django and ruff, and nothing else. Format:
#   postgres://user:password@host:port/dbname
DATABASE_URL = required_env("DATABASE_URL")


def _parse_database_url(url: str) -> dict:
    from urllib.parse import unquote, urlparse

    parsed = urlparse(url)
    if parsed.scheme not in {"postgres", "postgresql"}:
        raise RuntimeError(
            f"DATABASE_URL must be a postgres:// URL, got scheme {parsed.scheme!r}. "
            f"Section 4/5 rules assume Postgres; there is no SQLite fallback."
        )
    # `urlparse().username`/`.password` return the RAW substring between `:`
    # and `@`, NOT percent-decoded - confirmed the hard way (ticket 02,
    # django-engine map): a Supabase-generated password containing `@`,
    # written into the URL as `%40` per RFC 3986, was handed to psycopg
    # completely undecoded, so the literal string sent as the password
    # still contained `%40` instead of `@`. Every attempt authenticated as
    # the wrong password and Supabase's own pooler tripped its circuit
    # breaker after enough of them. `unquote()` on both fields is the fix;
    # a plain alphanumeric password happens to be its own unquoted form, so
    # this was invisible until a real credential with a reserved character
    # in it was used against this function for the first time.
    return {
        "ENGINE": "django.db.backends.postgresql",
        "NAME": (parsed.path or "").lstrip("/"),
        "USER": unquote(parsed.username or ""),
        "PASSWORD": unquote(parsed.password or ""),
        "HOST": parsed.hostname or "",
        "PORT": str(parsed.port or 5432),
    }


DATABASES = {"default": _parse_database_url(DATABASE_URL)}

# Django's own bookkeeping (`django_migrations`, `auth_permission`,
# `django_content_type`, `django_session`, `django_admin_log`, and every
# `household_*` table) lives in a schema named `django`, never in `public` -
# execution-plan.md Phase 0/1: "public stays owned by supabase/migrations/
# until ticket 02 hands ownership over table by table." Postgres does NOT
# create a schema named in `search_path` for you - confirmed empirically
# against the live database (an unqualified `CREATE TABLE` under a
# `search_path` naming a schema that does not yet exist silently falls
# through to the next schema in the list, `public`, with no error at all).
# `core/migrations/0001_create_django_schema.py` is what actually
# creates `django` before anything else runs, in every environment
# (compose's local Postgres, a fresh pytest test database, and the live
# server) - this `OPTIONS` entry only says where to look and create once it
# exists. Unqualified references to any of the 41 legacy tables still
# resolve correctly: `public` is second in the path, and none of Django's
# own table names collide with one of the 41.
DATABASES["default"]["OPTIONS"] = {"options": "-c search_path=django,public"}

# A second, read-only alias for ticket 02's per-table round-trip tests and
# for `inspectdb` itself - `LEGION_PG_URL` is the `legion_reader` role
# (SELECT + bypassrls, no DDL, no createdb), set only in an agent's
# `.claude/mcp.env`, never in `deploy/.env`. Optional, unlike every other
# database setting above: a household running this server by hand has no
# reason to ever have this variable set, and must not be forced to decide a
# value for a role it will never use. `legion_reader`'s own `search_path`
# is left at its default (effectively `public`, since it owns no schema of
# its own) - it never touches `django`.
_readonly_url = os.environ.get("LEGION_PG_URL")
if _readonly_url:
    DATABASES["readonly"] = _parse_database_url(_readonly_url)

# `managed = False` alone does NOT stop `makemigrations` from generating
# `CreateModel` operations for a model - it only stops `migrate` from
# emitting DDL for one. Confirmed the hard way: with `legacy/` a normal
# migrated app, `makemigrations --check --dry-run` proposed a 41-model
# initial migration despite every model being unmanaged. `MIGRATION_MODULES
# = None` is Django's actual mechanism for "this app has no migrations at
# all" - `legacy` therefore has no `migrations/` package (there is nothing
# for one to hold), and `makemigrations --check` now reports nothing.
MIGRATION_MODULES = {"legacy": None}

AUTH_USER_MODEL = "household.User"

AUTH_PASSWORD_VALIDATORS = [
    {"NAME": "django.contrib.auth.password_validation.UserAttributeSimilarityValidator"},
    {"NAME": "django.contrib.auth.password_validation.MinimumLengthValidator"},
    {"NAME": "django.contrib.auth.password_validation.CommonPasswordValidator"},
    {"NAME": "django.contrib.auth.password_validation.NumericPasswordValidator"},
]

LANGUAGE_CODE = "en-us"
# UTC on the server, on purpose - CLAUDE.md's IANA-timezone rule is about
# never handing the model a place-shaped id; the server layer stores instants
# and lets the device limb apply whatever offset it has for the user.
TIME_ZONE = "UTC"
USE_I18N = True
USE_TZ = True

STATIC_URL = "static/"

# Where `collectstatic` gathers everything, and where WhiteNoise serves from in
# production. Deliberately says nothing about WHERE that runs: this comment
# first read "ephemeral on Cloud Run", and hosting was reopened the same day it
# was written (web-and-households ticket 12, the Oracle VM). Nothing here is
# user data either way - it is build output, written into the image by the
# Dockerfile (ticket 09) and identical on every instance, so a host that wipes
# it on restart and one that does not are the same to this setting.
STATIC_ROOT = BASE_DIR / "staticfiles"

# `server/static/` holds the Vite bundle under `app/`, written there by
# `npm run build` in `server/frontend` (its `build.outDir`). Listing it as a
# source directory rather than as STATIC_ROOT is what lets the dev loop work
# with no `collectstatic` at all: with DEBUG on, the staticfiles finders read it
# straight from here.
STATICFILES_DIRS = [BASE_DIR / "static"]

# CompressedManifestStaticFilesStorage does two things that matter once Cloud
# Run is in front of this: it writes each file under a content-hashed name so a
# far-future cache header is safe, and it gzips/brotlis alongside so WhiteNoise
# can serve a pre-compressed body instead of compressing per request.
#
# The whole dict is restated because naming STORAGES replaces Django's default
# wholesale - omitting "default" here would leave file uploads with no storage
# backend at all, which fails at the first upload rather than at startup.
STORAGES = {
    "default": {"BACKEND": "django.core.files.storage.FileSystemStorage"},
    "staticfiles": {"BACKEND": "whitenoise.storage.CompressedManifestStaticFilesStorage"},
}

# Serve the built bundle's own files at the SITE ROOT, not under STATIC_URL.
#
# This is not an optimisation, it is what makes the page work at all. Vite is
# configured with `base: '/'` (see `frontend/vite.config.ts`), so the shell asks
# for `/assets/index-<hash>.js`, `/sw.js`, `/manifest.webmanifest` - root paths,
# because a service worker can only control the scope it is served from and a
# worker under `/static/app/` could not control `/`. Django serves static files
# under `/static/`, so without this every one of those URLs falls through to the
# catch-all in `legion/urls.py` and comes back as the HTML shell with a 200.
# Measured before this setting existed, 2026-09-08:
# `GET /assets/index-T8X4he9y.js` answered `200 text/html, 1226 bytes`. Nothing
# errors; the browser simply gets a page where it asked for a script, and the
# app renders blank.
#
# Same two-location rule as `web.views.spa_index`, and for the same reason:
# STATIC_ROOT after `collectstatic` (which also has the pre-compressed `.gz`
# siblings), the Vite output directory before it, so `npm run build` plus
# `runserver` is a complete dev loop.
_collected_app = STATIC_ROOT / "app"
_built_app = BASE_DIR / "static" / "app"
WHITENOISE_ROOT = _collected_app if _collected_app.is_dir() else _built_app

# WhiteNoise ignores Python's `mimetypes` module and carries its own table
# (`whitenoise/media_types.py`, "so behaviour is consistent across varied
# environments"), and that table has no `.webmanifest`. It served the PWA
# manifest as `application/octet-stream` - measured on the dev server
# 2026-09-08, after a first attempt at fixing this with
# `mimetypes.add_type()` changed nothing, which is how the private table came
# to light. A manifest with the wrong content type is worse than a broken one:
# the page loads, nothing logs, and the browser silently declines to offer
# "install", which reads as the PWA not working.
WHITENOISE_MIMETYPES = {".webmanifest": "application/manifest+json"}

MEDIA_URL = "/media/"
MEDIA_ROOT = required_env("MEDIA_ROOT")
# Cloud Run's filesystem is ephemeral (wiped on every cold start and never
# shared across instances), so MEDIA_ROOT there is scratch space at best.
# Ticket 05 owns the real fix - an R2 or GCS-backed Django storage - and is
# the ticket that gets to introduce a `MEDIA_BACKEND` switch; nothing reads
# one here yet, and adding an unused setting in this ticket would be a
# stub with no caller. This comment is the pointer for whoever picks up 05.

DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"

REST_FRAMEWORK = {
    "DEFAULT_AUTHENTICATION_CLASSES": [
        "household.authentication.DeviceTokenAuthentication",
        # Second, not first: a phone request always carries `Authorization:
        # Token <key>` and DeviceTokenAuthentication resolves it without
        # touching the session store, so listing this after it means a
        # phone call never pays a session lookup. The browser carries no
        # Authorization header, so this is the one that resolves it - and
        # DRF's SessionAuthentication enforces CSRF on unsafe methods by
        # design once it does (see household/views.py:SessionLogoutView).
        "rest_framework.authentication.SessionAuthentication",
    ],
    "DEFAULT_PERMISSION_CLASSES": [
        "household.permissions.IsHouseholdMember",
    ],
    "DEFAULT_SCHEMA_CLASS": "drf_spectacular.openapi.AutoSchema",
    "DEFAULT_THROTTLE_CLASSES": [
        "rest_framework.throttling.ScopedRateThrottle",
    ],
    "DEFAULT_THROTTLE_RATES": {
        # Ticket 01: login is rate-limited 5/min per IP.
        "login": "5/min",
        # web-and-households ticket 03. Its own scope rather than sharing
        # `login`, because the two doors are abused differently: `login`
        # guards a known email against password guessing, `signup` guards
        # the invite-code space against enumeration. Sharing one budget
        # would mean a burst of signup attempts locks the household OUT of
        # logging in, which is the wrong trade. Carried by `SignupView` and
        # by `InvitePreviewView` together, deliberately: previewing a code
        # and redeeming one are the same guess from the same stranger, so
        # they must not each get five a minute.
        "signup": "5/min",
    },
}

# web-and-households ticket 03, ADR 0045 decision 3: "Open signup is an
# environment switch, off by default, for a stranger's own compose stack."
#
# OFF is the default and there is deliberately no `required_env` for it -
# unlike `DJANGO_DEBUG`, an operator who has not thought about this must get
# the CLOSED behaviour, not a crash that tempts them into setting the first
# value that makes it start. On, `POST /api/auth/signup` accepts a request
# with no `invite_code` and founds a household for it; an invite code that IS
# supplied is still honoured exactly as it would be with this off, so a
# stranger's second adult joins by code rather than founding a second
# household nobody wanted.
LEGION_OPEN_SIGNUP = os.environ.get("LEGION_OPEN_SIGNUP", "").strip().lower() in {
    "1",
    "true",
    "yes",
    "on",
}

SPECTACULAR_SETTINGS = {
    "TITLE": "LEGION",
    "DESCRIPTION": "The engine. One Django server, every limb a client of it.",
    "VERSION": "0.1.0",
    "SERVE_INCLUDE_SCHEMA": False,
    # Two DIFFERENT choice sets are both spelled `provenance` on this API, and
    # drf-spectacular cannot name them apart on its own - it resolved the
    # collision as `Provenance382Enum`, a component name derived from a hash,
    # which would land in a generated client as a class called exactly that and
    # would change the moment either set did. Named here instead:
    #
    #   ProvenanceEnum          `public.provenance`, the four-value Postgres enum
    #                           on every gated and authored table (section 4 rule 4).
    #   VoiceNoteProvenanceEnum `voice_notes.provenance`, a plain text column pinned
    #                           by its own CHECK to the single literal LLM_DERIVED.
    #                           See `legacy/models/notes.py` and `api/voice_notes.py`
    #                           for why a voice note is none of the other four.
    #
    # Values are dotted paths, resolved by drf-spectacular's `deep_import_string`,
    # so the attribute on the model class works and neither set is restated here.
    "ENUM_NAME_OVERRIDES": {
        "ProvenanceEnum": "legacy.enums.Provenance",
        "VoiceNoteProvenanceEnum": "legacy.models.notes.VoiceNote.PROVENANCE_CHOICES",
        # `ingested_files.state`. Named for the same reason as the two above,
        # though no collision forced it: drf-spectacular derives a component
        # name from the FIELD, so this one would land in a generated client as
        # a class called `StateEnum` - which says nothing about what it is the
        # state OF, and would collide the first time any other table grows a
        # `state` column.
        "IngestStateEnum": "legacy.enums.IngestState",
    },
}
