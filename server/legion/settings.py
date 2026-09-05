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

INSTALLED_APPS = [
    "django.contrib.admin",
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django.contrib.staticfiles",
    "rest_framework",
    "drf_spectacular",
    "household",
    "api",
]

MIDDLEWARE = [
    "django.middleware.security.SecurityMiddleware",
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
    from urllib.parse import urlparse

    parsed = urlparse(url)
    if parsed.scheme not in {"postgres", "postgresql"}:
        raise RuntimeError(
            f"DATABASE_URL must be a postgres:// URL, got scheme {parsed.scheme!r}. "
            f"Section 4/5 rules assume Postgres; there is no SQLite fallback."
        )
    return {
        "ENGINE": "django.db.backends.postgresql",
        "NAME": (parsed.path or "").lstrip("/"),
        "USER": parsed.username or "",
        "PASSWORD": parsed.password or "",
        "HOST": parsed.hostname or "",
        "PORT": str(parsed.port or 5432),
    }


DATABASES = {"default": _parse_database_url(DATABASE_URL)}

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

MEDIA_URL = "/media/"
MEDIA_ROOT = required_env("MEDIA_ROOT")

DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"

REST_FRAMEWORK = {
    "DEFAULT_AUTHENTICATION_CLASSES": [
        "household.authentication.DeviceTokenAuthentication",
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
    },
}

SPECTACULAR_SETTINGS = {
    "TITLE": "LEGION",
    "DESCRIPTION": "The engine. One Django server, every limb a client of it.",
    "VERSION": "0.1.0",
    "SERVE_INCLUDE_SCHEMA": False,
}
