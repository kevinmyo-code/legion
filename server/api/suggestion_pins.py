"""Suggestion pins: one member's "I want to go" on one suggestion.

Kevin, 2026-10-09: *"i want my wife on the pwa to be able to open up her
calendar, see a suggested event, pick whichever she like, and pin it on the
household so i can see which ones she wanna go to. me vice versa as well from
android native"*.

**A pin is not a plan.** "Add to my plans" (`kind` becomes `event`) is
untouched and still the only way a suggestion becomes something the household
is doing. A pin says one member would like to go; the whole household sees
it, and nothing that counts plans (reminders, Today, busy/free) reads it.

**This module holds the DDL and the reads, deliberately together** (the
`ingest/category_overrides.py` precedent): the DDL, run by
`ingest/migrations/0019_suggestion_pins.py` and, on the pytest database, by
`tests/conftest.py`, and the `pinned_by` read every event serializer renders.
The two routes are `api/suggestion_pin_views.py`, apart only because they
render through `api/events.EventSerializer`, which imports this module.

- `POST /api/events/<id>/pins` pins as the caller. Idempotent on (event,
  member): a second pin returns the same row, an unpinned one is revived.
- `DELETE /api/events/<id>/pins/mine` tombstones the caller's pin. A member
  can only ever reach their OWN pin: there is no route that names another
  member's, so "cannot unpin someone else's" holds by construction.

Both answer with the EVENT, rendered exactly as `GET /api/events` renders it,
so a client replaces its row and has the new `pinned_by` in one step. Both
also bump the event's `updated_at`, so every replica that syncs events with
`?since=` sees the change on the row it already holds, without having to
store pins separately. `/api/changes?aspects=events` ALSO carries the pin rows
themselves (`suggestion_pins`) for a client that wants them.

**Visibility (ADR 0052): a pin has its event's.** Every route reads the event
through `household.tenancy.visible()`, so another member's private event (a
private suggestion is possible) is a 404 here, and its pins reach everyone
else's changes feed only as redacted tombstones (`OWNER_PATHS`).
"""
from __future__ import annotations

from rest_framework import serializers

from api.event_columns import KIND_SUGGESTION

TABLE = "suggestion_pins"

# `(id, household_id)` on `events` is unique by construction (`id` is the
# primary key); the constraint exists only so Postgres accepts it as the
# target of the composite foreign key below. Adding it is DDL, not an UPDATE.
EVENT_HOUSEHOLD_KEY = "events_id_household_key"

def create_sql(household_schema: str, user_schema: str) -> str:
    """The table, its constraints and its two triggers. Idempotent.

    What the database itself guarantees, so it holds even if Django has a bug
    (CLAUDE.md section 7):

    - **one row per (event, member)**: a second pin revives the first, never
      a duplicate;
    - **the pin and its event are in the same household**, by composite
      foreign key, so no bug can hang one family's pin on another family's
      event;
    - **only a live suggestion can be pinned, and only by a member of its
      household**, checked by trigger at the moment a pin becomes live (an
      insert, or reviving a tombstone). A pin already live when its event is
      added to the plans is left alone: it is history, and `pinned_by` renders
      only for suggestions;
    - a pin never moves to another event or another member.

    Every function lives in `public`, never `private`: the live engine role
    has no USAGE on `private` (the 2026-09-29 deploy failure,
    `ingest/category_overrides.py`).
    """
    hh = household_schema
    us = user_schema
    return f"""
create or replace function public.suggestion_pins_touch_updated_at()
    returns trigger
    language plpgsql
    set search_path = ''
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

do $$
begin
    if not exists (select 1 from pg_constraint where conname = '{EVENT_HOUSEHOLD_KEY}') then
        alter table public.events
            add constraint {EVENT_HOUSEHOLD_KEY} unique (id, household_id);
    end if;
end $$;

create table if not exists public.{TABLE} (
    id           uuid        primary key default gen_random_uuid(),
    household_id uuid        not null references {hh}.household_household (id),
    event_id     uuid        not null,
    user_id      uuid        not null references {us}.household_user (id) on delete cascade,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    deleted_at   timestamptz,
    constraint {TABLE}_one_per_member unique (event_id, user_id),
    constraint {TABLE}_same_household_as_event
        foreign key (event_id, household_id)
        references public.events (id, household_id)
        on delete cascade
);

create index if not exists {TABLE}_household_updated_idx
    on public.{TABLE} (household_id, updated_at);

drop trigger if exists touch_updated_at on public.{TABLE};
create trigger touch_updated_at
    before update on public.{TABLE}
    for each row execute function public.suggestion_pins_touch_updated_at();

create or replace function public.suggestion_pins_guard()
    returns trigger
    language plpgsql
    set search_path = ''
as $$
begin
    if tg_op = 'UPDATE' and (new.event_id <> old.event_id or new.user_id <> old.user_id) then
        raise exception
            'Nothing was saved. A pin belongs to one member and one suggestion, '
            'and never moves (pin %).',
            old.id using errcode = 'check_violation';
    end if;
    if new.deleted_at is null and (tg_op = 'INSERT' or old.deleted_at is not null) then
        if not exists (
            select 1 from public.events e
             where e.id = new.event_id and e.household_id = new.household_id
               and e.kind = '{KIND_SUGGESTION}' and e.deleted_at is null
        ) then
            raise exception 'Nothing was pinned. Only a live suggestion can be pinned (event %).',
                new.event_id using errcode = 'check_violation';
        end if;
        if not exists (
            select 1 from {hh}.household_householdmember m
             where m.user_id = new.user_id and m.household_id = new.household_id
        ) then
            raise exception 'Nothing was pinned. User % is not a member of household %.',
                new.user_id, new.household_id using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$$;

drop trigger if exists guard on public.{TABLE};
create trigger guard
    before insert or update on public.{TABLE}
    for each row execute function public.suggestion_pins_guard();
"""


DROP_SQL = f"""
drop table if exists public.{TABLE};
drop function if exists public.suggestion_pins_guard();
drop function if exists public.suggestion_pins_touch_updated_at();
alter table if exists public.events drop constraint if exists {EVENT_HOUSEHOLD_KEY};
"""


def create_table(cursor) -> str | None:
    """Creates the table if `events` is there to hang it on. None when it
    ran, or a sentence saying why it did not (the pytest database at
    `migrate` time, where the legacy tables do not exist yet;
    `tests/conftest.py` calls this again once they do)."""
    from household.tenancy_sql import HOUSEHOLD_TABLE, _table_schema
    from household.visibility_sql import MEMBER_TABLE, USER_TABLE

    cursor.execute("select to_regclass('public.events')")
    if cursor.fetchone()[0] is None:
        return f"{TABLE}: public.events does not exist here; nothing created."
    household_schema = _table_schema(cursor, HOUSEHOLD_TABLE)
    user_schema = _table_schema(cursor, USER_TABLE)
    member_schema = _table_schema(cursor, MEMBER_TABLE)
    if household_schema is None or user_schema is None or member_schema != household_schema:
        raise RuntimeError(
            f"Nothing was created. {TABLE} needs {HOUSEHOLD_TABLE}, {USER_TABLE} and "
            f"{MEMBER_TABLE} in one schema (found {household_schema}, {user_schema}, "
            f"{member_schema}). Run the household migrations first."
        )
    cursor.execute(create_sql(household_schema, user_schema))
    return None


# =============================================================================
# The read: `pinned_by` on every event
# =============================================================================


def display_name(user) -> str:
    """The member's account name, or the part of their email before "@" when
    they have not set one. Never blank: a pin always says who."""
    name = (getattr(user, "first_name", "") or "").strip()
    if name:
        return name
    email = getattr(user, "email", "") or ""
    return email.split("@", 1)[0] or "A member"


def pinned_by(event) -> list[dict]:
    """Who wants to go, oldest pin first: `[{"user_id", "display_name"}]`.
    Empty for anything that is not a suggestion."""
    from legacy.models.dates import SuggestionPin

    if getattr(event, "kind", None) != KIND_SUGGESTION:
        return []
    pins = (
        SuggestionPin.objects.filter(
            event_id=event.pk, household_id=event.household_id, deleted_at__isnull=True
        )
        .select_related("user")
        .order_by("created_at", "id")
    )
    return [{"user_id": str(pin.user_id), "display_name": display_name(pin.user)} for pin in pins]


class PinnedBySerializer(serializers.Serializer):
    user_id = serializers.UUIDField(help_text="The member, as listed in GET /api/households/me.")
    display_name = serializers.CharField(
        help_text="Their account name, or their email before \"@\" when they have not set one."
    )


# =============================================================================
# The pin rows, for `/api/changes`
# =============================================================================


class SuggestionPinSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    event = serializers.UUIDField(source="event_id")
    user_id = serializers.UUIDField()
    created_at = serializers.DateTimeField()
    updated_at = serializers.DateTimeField()
    deleted_at = serializers.DateTimeField(allow_null=True)
