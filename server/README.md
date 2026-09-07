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

## The OpenAPI contract: `server/openapi.yaml`

**A client generates from this file. It does not hand-write what it thinks
the shapes are.**

That is the whole reason the file exists (django-engine ticket 04, "the
schema is the handoff"). With one client a hand-written HTTP layer is
survivable; with two - the Android phone app and the head-unit app - it is
how they drift, and not on rules, which live in Django now, but on shapes:
a field renamed on the server, a date parsed differently, a null handled
two ways. One schema, generated clients, and a rename breaks the build.

### Regenerating

```
cd server
uv run manage.py write_openapi
```

That is the one command, and it is the one the staleness test names when it
fails. It wraps `manage.py spectacular` and adds three things:

- it resolves `server/openapi.yaml` from `BASE_DIR`, so it writes the same
  file whatever directory you run it from (plain `spectacular --file
  server/openapi.yaml` writes `server/server/openapi.yaml` from inside
  `server/`);
- `--fail-on-warn`, so a view drf-spectacular cannot describe is an error
  rather than a path silently missing from the contract;
- `--validate`, so an invalid document fails here instead of in whoever's
  code generator reads it next.

There is no flag to switch either off. If a new view cannot be described,
describe it - `@extend_schema` on a plain `APIView`, or nothing at all for
a table on `api/synced.SyncedModelViewSet`, which `api/schema.SyncedAutoSchema`
describes from the viewset's own serializer.

### The staleness test

`tests/test_openapi_schema.py` regenerates into a temporary file and
compares it line for line with the committed one, failing with the command
above and the first line that differs. Because it regenerates through
`write_openapi`, it fails on an undescribable view too - a view added
without an `@extend_schema` is caught by the test suite, not by whoever
generates a client from the file a week later.

Three of its checks go further and compare the schema against a REAL
response from the test client (`/api/places/`, `/api/events`,
`/api/changes`, and the `voice_notes` 404). That is the class of lie
nothing else catches: before `api/schema.py` existed, every `?since=` list
route was documented as returning a bare array of rows when it returns
`{"results": [...], "next": ...}`. Nothing was broken, no warning fired,
and the document was valid OpenAPI the whole time.

`.gitattributes` pins the file to LF so a Windows checkout does not read as
permanently stale.

### Reading it

`/api/schema/` serves the same document from a running server, and
`/api/schema/swagger/` renders it. Both are unauthenticated.

## Checking for drift

```
uv run ruff check .
uv run manage.py check
uv run manage.py makemigrations --check --dry-run
uv run manage.py write_openapi
```
