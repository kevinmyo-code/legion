# LEGION server

The engine (ADR 0044): one Django server over one Postgres, every other
piece a limb that talks to it over HTTPS JSON. See `../CLAUDE.md` and
`../docs/adr/0044-django-is-the-engine.md` for the architecture; this file
is local setup only.

## Local setup

```
cd server
uv sync
cp ../deploy/.env.example ../deploy/.env   # fill in SECRET_KEY, DATABASE_URL, etc.
uv run manage.py migrate
```

`legion/settings.py` refuses to start with a missing required variable,
naming it - there is no Kevin-hosted default (CLAUDE.md section 7).

## First-run: making the first user a household member

`manage.py createsuperuser` makes a `User` row. That alone is **not**
enough to pass `household.permissions.IsHouseholdMember`, which every
authenticated endpoint requires - a fresh superuser account gets a bare 403
everywhere until it also has a `household.models.HouseholdMember` row.
Found the hard way, 2026-09-05: the first superuser made on the live
database could not pass a single authenticated endpoint.

**A superuser gets this automatically now** (`household/signals.py`): the
moment `is_superuser` is true on a saved `User` - at creation via
`createsuperuser`, or later via the admin - a matching `HouseholdMember`
row is created if one does not already exist. Nothing to run by hand for
the first admin account.

**The second adult is not necessarily a superuser**, and needs the explicit
command:

```
uv run manage.py add_household_member second-adult@example.com
```

Idempotent - safe to run again, or unconditionally from a first-run script.
It says in words whether it created the row or found one already there,
and refuses in words (never a bare traceback) if the email does not match
any existing `User`:

```
$ uv run manage.py add_household_member nobody@example.com
CommandError: No user with email 'nobody@example.com'. Create one first
with 'manage.py createsuperuser' or the admin.
```

## Running the tests

```
uv run pytest
```

Requires Postgres - `tests/conftest.py` refuses to run against anything
else (CLAUDE.md section 4/5). Point `DATABASE_URL` at a role with
`createdb` (pytest creates and drops its own test database per run); never
point it at a database you cannot afford pytest to create a throwaway
sibling of.

## Checking for drift

```
uv run ruff check .
uv run manage.py check
uv run manage.py makemigrations --check --dry-run
```
