"""`/api/events` - django-engine ticket 04's Phase 2 slice, over the
`legacy.Event` model (`managed = False`; ORM CRUD works normally regardless
of that flag - see `legion/settings.py`'s own `MIGRATION_MODULES` comment
for what the flag actually controls, which is DDL, not reads/writes).

`app/.../backend/EventsBackend.kt` (`RemoteEvent`/`EventFields`) is the
phone-side contract this mirrors. **This ticket's own dispatch brief scopes
the endpoint shapes down to the Phase 2 slice - `POST` + `PATCH` on
`/api/events`/`/api/events/<id>`, not the fuller `.scratch/django-engine/
issues/04-domain-api-and-changes-feed.md`'s generic `PUT /<origin_guid>/`
`SyncedModelViewSet` shape** (that document predates the phase split
`research/execution-plan.md` Phase 1 describes: "the domain API and the
changes feed... slice for Phase 2: events and checklists"). Built against
the dispatch brief, which is the more specific and more recent of the two;
the divergence is worth a human's eyes when the map's ticket file is next
touched, since ticket 09 (the phone side) will need to know which shape it
is coding against.
"""
from __future__ import annotations

import uuid

from django.db import transaction
from django.db.models.functions import Now
from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import (
    NO_CONTENT,
    NOT_FOUND,
    SINCE_PARAMETER,
    WRITE_REFUSED,
    paged_serializer,
)
from api.sync import paginate_since, parse_since, save_or_400
from household.tenancy import household_of, scoped
from legacy.enums import Provenance
from legacy.models.dates import Event

EVENT_TAGS = ["events"]

# CONSTRAINTS.md's own `## events` section, read from the live schema
# (`legion_reader`, 2026-09-05) - the allowed sets this serializer's
# `validate_*` methods refuse anything outside of, naming the set in the
# 400 body rather than leaving the caller to guess (this ticket's own rule
# 2: "a wrong value returns 400 naming the allowed set").
KIND_CHOICES = ("reminder", "event", "task")
SOURCE_CHOICES = ("legion", "google")
REPEAT_KIND_CHOICES = ("DAILY", "WEEKLY", "MONTHLY_ON_DATE", "YEARLY")
REPEAT_END_KIND_CHOICES = ("NEVER", "ON_DATE", "AFTER_COUNT")

# Matches the DB's own `default` clause for each NOT NULL column
# (`supabase/migrations/20260825000400_aspect_dates_notes_merged.sql`,
# `.../20260901000300_events_kind_completable_axis.sql`) - applied here,
# in Python, rather than relying on Postgres's own DEFAULT firing on an
# omitted column, because `legacy.Event` carries no Django-level default
# for any of these fields (an inspectdb-generated, `managed = False` model
# never captures a column's DEFAULT clause) and a plain `.save()`/`.create()`
# always sends every field's current Python value - `None` for anything
# the caller never set - which would violate the very NOT NULL constraints
# these defaults exist to satisfy. Filled in by `EventSerializer.create`
# only when the caller omits the field entirely; an explicit value the
# caller DID send is never overridden by this table.
CREATE_DEFAULTS = {
    "all_day": False,
    "source": "legion",
    "done": False,
    "exact": False,
    "exact_downgraded": False,
    "kind": "reminder",
}


def _choice_error(field: str, value, allowed: tuple[str, ...]) -> serializers.ValidationError:
    return serializers.ValidationError(
        f"'{value}' is not a valid {field}. Use one of: {', '.join(allowed)}."
    )


class EventSerializer(serializers.ModelSerializer):
    class Meta:
        model = Event
        fields = [
            "id",
            "title",
            "starts_at",
            "ends_at",
            "all_day",
            "location",
            "notes",
            "source",
            "google_event_id",
            "done",
            "done_at",
            "sort_order",
            "trigger_place_label",
            "repeat_kind",
            "repeat_every",
            "repeat_days_of_week",
            "repeat_day",
            "repeat_month",
            "repeat_end_kind",
            "repeat_end_date",
            "repeat_end_count",
            "exact",
            "exact_downgraded",
            "missed_at",
            "missed_dismissed_at",
            "logged_at",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
            "origin_guid",
            "structured_meta",
            "kind",
        ]
        # provenance/created_at/updated_at/deleted_at are server facts, not
        # caller intent (matching EventFields's own doc comment: "these
        # four are server- or ack-side facts, not caller intent") -
        # deleted_at is set only by DELETE (never PATCH), updated_at by
        # `create` below on INSERT and by the `touch_updated_at` trigger
        # already on `public.events` on every UPDATE. Both of those read
        # the DATABASE's clock; see `create`'s own comment for why that
        # sentence is load-bearing. This comment used to say updated_at was
        # written "only by the trigger", which stopped being true the
        # moment `create` started stamping it explicitly.
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = {
            # Each of these has a stated DB default (CREATE_DEFAULTS above)
            # - optional here so a caller may omit it and get that default,
            # matching what an INSERT with the column left out of the
            # column list would do at the SQL level.
            "all_day": {"required": False},
            "source": {"required": False},
            "done": {"required": False},
            "exact": {"required": False},
            "exact_downgraded": {"required": False},
            "kind": {"required": False},
        }

    def validate_title(self, value: str) -> str:
        if not value or not value.strip():
            raise serializers.ValidationError("title cannot be blank.")
        return value

    def validate_kind(self, value: str) -> str:
        if value not in KIND_CHOICES:
            raise _choice_error("kind", value, KIND_CHOICES)
        return value

    def validate_source(self, value: str) -> str:
        if value not in SOURCE_CHOICES:
            raise _choice_error("source", value, SOURCE_CHOICES)
        return value

    def validate_repeat_kind(self, value: str | None) -> str | None:
        if value is not None and value not in REPEAT_KIND_CHOICES:
            raise _choice_error("repeat_kind", value, REPEAT_KIND_CHOICES)
        return value

    def validate_repeat_end_kind(self, value: str | None) -> str | None:
        if value is not None and value not in REPEAT_END_KIND_CHOICES:
            raise _choice_error("repeat_end_kind", value, REPEAT_END_KIND_CHOICES)
        return value

    def create(self, validated_data: dict) -> Event:
        for key, default in CREATE_DEFAULTS.items():
            validated_data.setdefault(key, default)
        # legacy.Event.id carries no Django-level default (an
        # inspectdb-generated, managed=False UUIDField primary key is never
        # auto-populated the way Django auto-specialcases AutoField/
        # BigAutoField) - unlike AutoField, Django does not omit a UUID pk
        # from the INSERT statement to let a DB-side `DEFAULT
        # gen_random_uuid()` fire, so this generates the id explicitly
        # rather than depending on that. Matches EventsBackend.upsert's own
        # contract for a create: "there is no natural key to upsert ON" -
        # the id is minted fresh, here, every time.
        validated_data["id"] = uuid.uuid4()
        # created_at/updated_at: NOT NULL with no Django-level default (see
        # this module's own CREATE_DEFAULTS comment), so they are supplied
        # here rather than left to the column's own `default now()` clause.
        #
        # **They come from POSTGRES's clock, never this process's.** These
        # lines used to read `now = timezone.now()` with both columns set
        # to it, described in this very comment as "the same 'now' a
        # genuine INSERT's own `default now()` would produce". That claim
        # was wrong for a reason nothing local could see: Django does not
        # run on the database's machine. `private.touch_updated_at` stamps
        # `updated_at := now()` from the DATABASE's clock on every UPDATE,
        # so a row inserted on the Python clock and later updated carried
        # two timestamps minted by two different machines. Measured against
        # this project's own Postgres on 2026-09-06, the two clocks
        # differed by 0.53s with PYTHON BEHIND; had the skew run the other
        # way, a row created and then updated would come back with an
        # `updated_at` EARLIER than its own creation, and a `?since=` feed
        # keyed on the value the phone was handed at create time would
        # silently never return that change - a sync path losing data
        # without failing. One clock for both writes removes the whole
        # class of failure, and it is the clock the trigger already uses.
        # `api/synced.py` (Phase 5) states the same rule at length and was
        # written this way from the start; this is the Phase 2 slice
        # catching up to it.
        validated_data["created_at"] = Now()
        validated_data["updated_at"] = Now()
        # provenance: events are AUTHORED, not gated (CLAUDE.md section 4
        # applies to ingestion; this API is a person typing), so every row
        # created here carries Provenance.USER, matching the column's own
        # DB default - never accepted from the caller (read_only above).
        validated_data["provenance"] = Provenance.USER
        # ADR 0045. From the REQUEST, never the body: `household` is not in
        # `Meta.fields` above, so it is not a value a caller can send. This
        # serializer predates `api/synced.SyncedSerializer` (it is the Phase 2
        # slice) and does the same assignment by hand for the same reason
        # every field it sets by hand is set by hand.
        household = self.context.get("household")
        if household is None:
            raise RuntimeError(
                "Nothing was written. EventSerializer was built without a `household` in "
                "its context, so there is no household to write this event into."
            )
        validated_data["household"] = household
        instance = Event.objects.create(**validated_data)
        # `Now()` is an unevaluated SQL expression until Postgres runs it;
        # the in-memory instance still holds the expression object, not a
        # datetime, so the row is read back before anything renders it.
        instance.refresh_from_db()
        return instance

    def update(self, instance: Event, validated_data: dict) -> Event:
        instance = super().update(instance, validated_data)
        # The `touch_updated_at` trigger has just overwritten `updated_at`
        # with the database's own clock, and a `done_at` derived by
        # `EventDetailView.patch` arrived as an unevaluated `Now()` - so
        # the values Django holds are stale, or are not datetimes at all,
        # the instant the UPDATE lands. Same read-back `api/synced.py`
        # does, and what lets a PATCH response claim to be the row as
        # stored the way `test_create_event_returns_the_row_as_stored`
        # already claims it of a POST.
        instance.refresh_from_db()
        return instance


class EventListCreateView(APIView):
    """`GET /api/events?since=<iso>` and `POST /api/events`."""

    @extend_schema(
        operation_id="api_events_list",
        tags=EVENT_TAGS,
        parameters=[SINCE_PARAMETER],
        responses={
            200: OpenApiResponse(
                response=paged_serializer(EventSerializer),
                description=(
                    "Rows changed at or after `since`, tombstones included, oldest first, "
                    "500 to a page. This route does NOT accept `?active=1` - the synced "
                    "routes do; here a client filters `deleted_at` itself."
                ),
            )
        },
    )
    def get(self, request):
        since = parse_since(request.query_params.get("since"))
        queryset = scoped(Event, request).filter(updated_at__gte=since).order_by("updated_at")
        page, next_since = paginate_since(queryset)
        return Response({"results": EventSerializer(page, many=True).data, "next": next_since})

    @extend_schema(
        operation_id="api_events_create",
        tags=EVENT_TAGS,
        request=EventSerializer,
        responses={
            201: OpenApiResponse(
                response=EventSerializer,
                description="Created. The body is the row as stored, server-minted `id`, "
                "`provenance` and timestamps included.",
            ),
            200: OpenApiResponse(
                response=EventSerializer,
                description=(
                    "**Not created - this `origin_guid` already exists**, and the body is "
                    "the row that was already there, unchanged. A retried create is a "
                    "no-op, which is what makes a lost acknowledgement safe to retry. A "
                    "client tells the two apart by the status code, never by the body."
                ),
            ),
            400: WRITE_REFUSED,
        },
    )
    def post(self, request):
        # origin_guid/sync_id honoured on POST for idempotent upsert (this
        # ticket's own rule 5): a retried create with the same origin_guid
        # returns the row that already exists rather than making a second
        # one. Never treated as a match when null - `origin_guid` is
        # nullable+unique, and Postgres already treats every NULL as
        # distinct from every other NULL, so this mirrors that at the
        # application layer too.
        origin_guid = request.data.get("origin_guid")
        if origin_guid:
            # Scoped, and the scoping is what keeps the idempotency honest:
            # unscoped, household B's retry of ITS origin_guid would find
            # household A's row and be answered with A's event as though it
            # were the one B just created.
            existing = scoped(Event, request).filter(origin_guid=origin_guid).first()
            if existing is not None:
                return Response(EventSerializer(existing).data, status=status.HTTP_200_OK)

        serializer = EventSerializer(
            data=request.data, context={"household": household_of(request)}
        )
        serializer.is_valid(raise_exception=True)
        instance, error = save_or_400(lambda: serializer.save())
        if error is not None:
            return error
        return Response(EventSerializer(instance).data, status=status.HTTP_201_CREATED)


class EventDetailView(APIView):
    """`PATCH`/`DELETE /api/events/<id>`."""

    def _get_object(self, pk, request):
        return scoped(Event, request).filter(pk=pk).first()

    @extend_schema(
        operation_id="api_events_partial_update",
        tags=EVENT_TAGS,
        request=EventSerializer,
        responses={
            200: OpenApiResponse(
                response=EventSerializer,
                description=(
                    "The row as stored. Every field is optional on the way in; only the "
                    "ones sent are changed. Sending `done` without `done_at` derives "
                    "`done_at` from the database clock (or clears it when `done` is false)."
                ),
            ),
            400: WRITE_REFUSED,
            404: NOT_FOUND,
        },
    )
    def patch(self, request, pk):
        instance = self._get_object(pk, request)
        if instance is None:
            return Response({"detail": f"No event with id {pk}."}, status=status.HTTP_404_NOT_FOUND)

        data = dict(request.data)
        # Tick/untick sets done AND done_at together (this ticket's own
        # rule 2) - a caller that states `done` without also stating
        # `done_at` gets one derived here, so the phone never has to
        # compute "now" itself just to flip a checkbox. A caller that DOES
        # supply done_at explicitly (the eventual migration/reconcile path,
        # matching EventFields.doneAtMs's own explicit-value posture) is
        # never overridden.
        #
        # The derived value is the DATABASE's clock. This line used to read
        # `data["done_at"] = timezone.now().isoformat()`, which put a
        # second machine's idea of "now" on a row whose `updated_at` the
        # trigger stamps from Postgres - see `EventSerializer.create` for
        # the measurement that retired that. `Now()` cannot travel through
        # the request body (an unevaluated SQL expression is not something
        # a DateTimeField can validate), so it rides in through
        # `serializer.save(**kwargs)`, which DRF merges into
        # `validated_data` after validation rather than before it.
        derived: dict = {}
        if "done" in data and "done_at" not in data:
            derived["done_at"] = Now() if data["done"] else None

        serializer = EventSerializer(instance, data=data, partial=True)
        serializer.is_valid(raise_exception=True)
        _saved, error = save_or_400(lambda: serializer.save(**derived))
        if error is not None:
            return error
        return Response(EventSerializer(instance).data)

    @extend_schema(
        operation_id="api_events_destroy",
        tags=EVENT_TAGS,
        responses={204: NO_CONTENT, 404: NOT_FOUND},
    )
    def delete(self, request, pk):
        instance = self._get_object(pk, request)
        if instance is None:
            return Response({"detail": f"No event with id {pk}."}, status=status.HTTP_404_NOT_FOUND)
        # Idempotent, matching EventsBackend.softDelete's own contract: a
        # second delete of an already-deleted row is still a 204, never an
        # error - "already gone" and "just removed" read the same to a
        # caller that does not care which happened.
        if instance.deleted_at is None:
            with transaction.atomic():
                # `Now()`, not `timezone.now()` - one clock, the database's,
                # for every timestamp this module writes. A tombstone is
                # the one row state a phone can learn about ONLY through
                # the `?since=` feed, so a `deleted_at` minted on a second
                # clock is the worst place to keep one. Matches
                # `SyncedModelViewSet.destroy` in `api/synced.py`.
                instance.deleted_at = Now()
                instance.save(update_fields=["deleted_at"])
        return Response(status=status.HTTP_204_NO_CONTENT)
