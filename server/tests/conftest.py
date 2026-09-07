"""pytest-django wiring, plus the one guardrail ticket 01 asks for by name:
this suite must refuse to run against anything but Postgres.

Section 4's gate depends on exact-equality arithmetic and the
`forbid_mutation_of_facts` trigger that ships as a Django migration; SQLite
has neither the trigger nor Postgres's numeric semantics, so a green run
against SQLite would be testing a database this app never ships against.
"""
from __future__ import annotations

import pytest
from django.conf import settings
from rest_framework.test import APIClient

from tests.legacy_test_schema import (
    LEGACY_FLEET_TEST_SCHEMA_SQL,
    LEGACY_INGEST_TEST_SCHEMA_SQL,
    LEGACY_LEDGER_PANTRY_CONFIG_TEST_SCHEMA_SQL,
    LEGACY_PHASE5_TEST_SCHEMA_SQL,
)

# `legacy` is deliberately `managed = False` with `MIGRATION_MODULES =
# {"legacy": None}` (legion/settings.py) - Supabase's own migrations own
# these 41 tables' DDL, on purpose, so Django never touches it. That is
# exactly right for the LIVE database, and exactly wrong for pytest's own
# ephemeral test database: `django_db_setup` (pytest-django's own fixture)
# creates a brand-new, empty Postgres database and runs ONLY Django's own
# migrations against it - since `legacy` contributes none, a fresh test
# database has no `public.events` at all, confirmed empirically while
# writing `test_events_api.py` (every write came back as a 400 whose
# `save_or_400` message was `relation "events" does not exist`, not a
# genuine validation refusal).
#
# This is a minimal, TEST-DATABASE-ONLY mirror of the columns/constraints
# `supabase/migrations/20260825000400_aspect_dates_notes_merged.sql` and
# its later ALTERs (`.../20260826000400_events_starts_at_nullable.sql`,
# `.../20260827000100_events_structured_meta.sql`,
# `.../20260901000300_events_kind_completable_axis.sql`,
# `.../20260826000100_origin_guid.sql`) already define on the LIVE
# database - it does not touch `legacy`'s `managed = False` status, adds no
# migration to that app, and never runs anywhere but the disposable test
# database `django_db_setup` itself just created (guarded twice: layered on
# top of pytest-django's own fixture, which only ever targets the test
# database, AND by refusing to run at all unless the connected database's
# own name contains "test"). `vehicle_id` is a bare nullable uuid column
# with no `REFERENCES public.vehicles` here - this ticket's serializer
# never reads or writes it, so the `vehicles` table (and its own dependency
# chain) is not part of this mirror at all.
#
# Worth a second pair of eyes: every future ticket that writes to a
# `legacy` table (ledger, pantry, fleet in execution-plan.md's Phase 5)
# hits this exact same wall and will want the same pattern, or a shared
# one. Flagged in this ticket's own final report rather than generalised
# here.
#
# UPDATE, Phase 5 (places, voice notes, body, memory): that shared one now
# exists as `tests/legacy_test_schema.py`, and the fixture below applies it
# right after this constant. The events SQL stays here rather than moving
# into it - this comment is the history of how the wall was found, and
# moving the code away from it would leave the story without its example.
#
# **The sentence above used to end "Ledger, pantry and fleet still owe their
# own blocks in that module."** All three have them now: ledger and pantry in
# two blocks (the gate's five tables and the four authored ones), fleet in a
# fourth covering all twelve of its tables. Nothing is owed; the wall is
# fully walled.
_LEGACY_EVENTS_TEST_SCHEMA_SQL = """
create schema if not exists private;

do $$
begin
    if not exists (select 1 from pg_type where typname = 'provenance') then
        create type public.provenance as enum
            ('DETERMINISTIC', 'LLM_RECONCILED', 'UNRECONCILED', 'USER');
    end if;
end $$;

create or replace function private.touch_updated_at()
    returns trigger
    language plpgsql
    set search_path = ''
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

create table if not exists public.events (
    id                  uuid primary key,
    title               text        not null check (length(trim(title)) > 0),
    starts_at           timestamptz,
    ends_at             timestamptz,
    all_day             boolean     not null default false,
    location            text,
    notes               text,
    source              text        not null default 'legion'
                        check (source in ('legion', 'google')),
    google_event_id     text,
    done                boolean     not null default false,
    done_at             timestamptz,
    sort_order          integer,
    trigger_place_label text,
    repeat_kind         text
        check (repeat_kind in ('DAILY', 'WEEKLY', 'MONTHLY_ON_DATE', 'YEARLY')),
    repeat_every        integer check (repeat_every is null or repeat_every > 0),
    repeat_days_of_week text,
    repeat_day          integer check (repeat_day is null or repeat_day between 1 and 31),
    repeat_month        integer check (repeat_month is null or repeat_month between 1 and 12),
    repeat_end_kind     text check (repeat_end_kind in ('NEVER', 'ON_DATE', 'AFTER_COUNT')),
    repeat_end_date     date,
    repeat_end_count    integer check (repeat_end_count is null or repeat_end_count > 0),
    exact               boolean     not null default false,
    exact_downgraded    boolean     not null default false,
    missed_at           timestamptz,
    missed_dismissed_at timestamptz,
    logged_at           timestamptz,
    provenance          public.provenance not null default 'USER',
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    deleted_at          timestamptz,
    origin_guid         text unique,
    structured_meta     jsonb,
    vehicle_id          uuid,
    kind                text not null default 'reminder'
                        check (kind in ('reminder', 'event', 'task')),
    constraint events_recurring_not_done check (repeat_kind is null or done = false),
    constraint events_repeat_end_needs_kind
        check (repeat_end_kind is null or repeat_kind is not null)
);
create unique index if not exists events_google_event_id_idx
    on public.events (google_event_id) where google_event_id is not null;

drop trigger if exists touch_updated_at on public.events;
create trigger touch_updated_at
    before update on public.events
    for each row execute function private.touch_updated_at();

create table if not exists public.event_skips (
    id         uuid primary key default gen_random_uuid(),
    event_id   uuid not null references public.events (id) on delete cascade,
    skip_date  date not null,
    created_at timestamptz not null default now(),
    constraint event_skips_unique unique (event_id, skip_date)
);
"""


@pytest.fixture(scope="session")
def django_db_setup(django_db_setup, django_db_blocker):
    """Layers `_LEGACY_EVENTS_TEST_SCHEMA_SQL`, then
    `LEGACY_PHASE5_TEST_SCHEMA_SQL`, then `LEGACY_INGEST_TEST_SCHEMA_SQL`,
    then `LEGACY_LEDGER_PANTRY_CONFIG_TEST_SCHEMA_SQL`, then
    `LEGACY_FLEET_TEST_SCHEMA_SQL`
    on top of pytest-django's own `django_db_setup` (which creates and
    migrates the test database) - see the first constant's own module-level
    comment for why this exists at all, and
    `tests/legacy_test_schema.py`'s module doc for what the other four
    cover.

    Order matters only in that all five blocks guard the `provenance` enum
    and four of them create `private.touch_updated_at`; each does so
    idempotently (`create or replace`, `if not exists`), so running them in
    any order, or twice against a `--reuse-db` database, changes nothing.
    The third block was added by django-engine ticket 03 and brings the
    ledger and pantry tables the section 4 gate writes. The fourth was added
    by the ledger/pantry API ticket and brings the four AUTHORED tables of
    those same two aspects - the half the gate never touches, which is why
    they are a separate block and not more rows in the third. The fifth is
    the fleet API ticket's twelve tables.

    **`vehicles` arrives with that fifth block, and it is worth knowing why it
    was not needed before.** `_LEGACY_EVENTS_TEST_SCHEMA_SQL`'s own comment
    says `events.vehicle_id` is "a bare nullable uuid column with no
    `REFERENCES public.vehicles` here - this ticket's serializer never reads
    or writes it, so the `vehicles` table (and its own dependency chain) is
    not part of this mirror at all". That is still true of the events mirror,
    which is left exactly as it was: the fleet block creates the real
    `vehicles`, but the events mirror still does not point at it, so the two
    remain independent and neither block has to run before the other.

    **This docstring counted "three blocks", then four**, and said so in the
    same shape each time; the number is kept accurate rather than generalised
    away, because "all of them" would stop telling a reader whether their own
    table is here.
    """
    from django.db import connection

    db_name = connection.settings_dict.get("NAME", "")
    if "test" not in db_name.lower():
        raise RuntimeError(
            f"Refusing to run the legacy test schema against database "
            f"{db_name!r} - it does not look like a pytest test database, and "
            f"this SQL must never touch anything else."
        )
    with django_db_blocker.unblock():
        with connection.cursor() as cursor:
            cursor.execute(_LEGACY_EVENTS_TEST_SCHEMA_SQL)
            cursor.execute(LEGACY_PHASE5_TEST_SCHEMA_SQL)
            cursor.execute(LEGACY_INGEST_TEST_SCHEMA_SQL)
            cursor.execute(LEGACY_LEDGER_PANTRY_CONFIG_TEST_SCHEMA_SQL)
            cursor.execute(LEGACY_FLEET_TEST_SCHEMA_SQL)


@pytest.fixture(autouse=True, scope="session")
def _require_postgres():
    engine = settings.DATABASES["default"]["ENGINE"]
    if "postgresql" not in engine:
        pytest.exit(
            f"This suite requires Postgres; DATABASES['default']['ENGINE'] is "
            f"{engine!r}. There is no SQLite fallback (CLAUDE.md section 4/5, "
            f"ticket 01's own instruction not to substitute one)."
        )
    yield


@pytest.fixture
def household_user(db):
    """One household member with a working password - the same shape
    `test_auth_endpoints.py`'s own `_make_member` builds, promoted here so
    ticket 04's API tests do not each hand-roll it."""
    from household.models import HouseholdMember, User

    user = User.objects.create_user(email="kevin@example.com", password="correct horse battery")
    HouseholdMember.objects.create(user=user)
    return user


@pytest.fixture
def auth_client(household_user):
    """An `APIClient` carrying a live device token for `household_user` -
    every domain-API test in this ticket authenticates this way rather than
    against `AllowAny`, since `DeviceTokenAuthentication` +
    `IsHouseholdMember` are this project's real default
    (`REST_FRAMEWORK` in `legion/settings.py`), not an opt-in a test should
    have to arrange by hand each time."""
    from household.models import DeviceToken

    _token, raw_key = DeviceToken.issue(household_user, "Test client")
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Token {raw_key}")
    return client
