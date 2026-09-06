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
from django.utils import timezone
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.sync import paginate_since, parse_since, save_or_400
from legacy.enums import Provenance
from legacy.models.dates import Event

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
        # deleted_at is set only by DELETE (never PATCH), updated_at only
        # by the `touch_updated_at` trigger already on `public.events`.
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
        now = timezone.now()
        # created_at/updated_at: NOT NULL with no Django-level default (see
        # this module's own CREATE_DEFAULTS comment) - both stamped to the
        # same "now" a genuine INSERT's own `default now()` would produce.
        # provenance: events are AUTHORED, not gated (CLAUDE.md section 4
        # applies to ingestion; this API is a person typing), so every row
        # created here carries Provenance.USER, matching the column's own
        # DB default - never accepted from the caller (read_only above).
        validated_data["created_at"] = now
        validated_data["updated_at"] = now
        validated_data["provenance"] = Provenance.USER
        return Event.objects.create(**validated_data)


class EventListCreateView(APIView):
    """`GET /api/events?since=<iso>` and `POST /api/events`."""

    def get(self, request):
        since = parse_since(request.query_params.get("since"))
        queryset = Event.objects.filter(updated_at__gte=since).order_by("updated_at")
        page, next_since = paginate_since(queryset)
        return Response({"results": EventSerializer(page, many=True).data, "next": next_since})

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
            existing = Event.objects.filter(origin_guid=origin_guid).first()
            if existing is not None:
                return Response(EventSerializer(existing).data, status=status.HTTP_200_OK)

        serializer = EventSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        instance, error = save_or_400(lambda: serializer.save())
        if error is not None:
            return error
        return Response(EventSerializer(instance).data, status=status.HTTP_201_CREATED)


class EventDetailView(APIView):
    """`PATCH`/`DELETE /api/events/<id>`."""

    def _get_object(self, pk):
        return Event.objects.filter(pk=pk).first()

    def patch(self, request, pk):
        instance = self._get_object(pk)
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
        if "done" in data and "done_at" not in data:
            data["done_at"] = timezone.now().isoformat() if data["done"] else None

        serializer = EventSerializer(instance, data=data, partial=True)
        serializer.is_valid(raise_exception=True)
        _saved, error = save_or_400(lambda: serializer.save())
        if error is not None:
            return error
        return Response(EventSerializer(instance).data)

    def delete(self, request, pk):
        instance = self._get_object(pk)
        if instance is None:
            return Response({"detail": f"No event with id {pk}."}, status=status.HTTP_404_NOT_FOUND)
        # Idempotent, matching EventsBackend.softDelete's own contract: a
        # second delete of an already-deleted row is still a 204, never an
        # error - "already gone" and "just removed" read the same to a
        # caller that does not care which happened.
        if instance.deleted_at is None:
            with transaction.atomic():
                instance.deleted_at = timezone.now()
                instance.save(update_fields=["deleted_at"])
        return Response(status=status.HTTP_204_NO_CONTENT)
