"""`/api/places` - one table, keyed by `label`, on the generic shape in
`api/synced.py`.

`label` as the identity is ticket 04's own named exception ("`places` |
keyed by `label` | `PUT /api/places/<label>/`, `DELETE` likewise"), and it
is not a stylistic choice: `public.places` has no `origin_guid` column at
all. Confirmed against the live schema on 2026-09-06 rather than inferred
from the model file, though `legacy/models/places.py`'s own class doc says
the same thing ("No `origin_guid` - confirmed absent from the live schema,
unlike almost every other table in this app"). `places.label_unique` is
what makes the key work, and `20260825000500_aspect_places_fleet.sql`'s own
comment says why the label has to stay unique and stable: `events.trigger_place_label`
names a place by this string, and the geofence layer uses it as the OS
requestId.

`PlacesBackend.kt` (`RemotePlace`, `fetchActive` / `upsert` / `softDelete`)
is the phone-side contract this mirrors, and the reason this aspect is the
only one with `put_revives_tombstone = True` - see `SupabasePlacesBackend`'s
`PlaceUpsertDto` doc comment, and `api/synced.py`'s own summary of it.
"""
from __future__ import annotations

import uuid

from django.db.models.functions import Now
from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from api.schema import NOT_FOUND, WRITE_REFUSED, DetailSerializer
from api.sync import save_or_400
from api.synced import SyncedModelViewSet, SyncedSerializer, blank_error, range_error
from household.tenancy import household_of
from legacy.enums import Provenance
from legacy.models.dates import Event
from legacy.models.places import Place

# `legacy/CONSTRAINTS.md`'s own `## places` section, read from the live
# schema. A caller outside these bounds is told the bounds (ticket 04:
# "a 400 naming the allowed set"), never handed Postgres's own constraint
# name.
LATITUDE_BOUNDS = (-90, 90)
LONGITUDE_BOUNDS = (-180, 180)

# The one rule on this table that Postgres does NOT hold, and the reason it
# is here (django-engine ticket 14). It came from
# `location/PlaceController.normalizeLabel`, which capped a label at 30
# characters and nowhere else did: `places.label` has no length CHECK, so a
# 200-character label was accepted by this API until 2026-09-07.
#
# **What the cap is FOR, since a bare number looks arbitrary.** A place label
# is minted by voice - "call this the fancy walmart" - and a misheard
# sentence arrives looking exactly like a label. 30 characters is the line
# between a name and a sentence. It is not a storage limit; `label` is
# `text`.
#
# It also has a second job the blank check shares: `label` IS this table's
# identity (`identity_field = "label"`, see this module's doc comment), it
# rides in the URL, and `events.trigger_place_label` and the OS geofence
# requestId both carry it. A key wants to be short.
LABEL_MAX_LENGTH = 30

# The address is a human string, spoken back and shown under the label. The
# longest real postal address is well under this; the cap exists so a whole
# misheard paragraph cannot be stored as one.
ADDRESS_MAX_LENGTH = 300


def validate_place_label(value: str) -> str:
    """The label rules, shared by `PlaceSerializer` and the rename route so a
    name refused on one path is refused in the same words on the other."""
    if not value or not value.strip():
        raise blank_error("label")
    if len(value) > LABEL_MAX_LENGTH:
        # Measured on the value AS IT WOULD BE STORED, not on a trimmed
        # copy: nothing here strips, so the stored string is what a
        # future CHECK would be applied to, and a check that measures
        # something other than what it stores is not the same check.
        raise serializers.ValidationError(
            f"Nothing was saved. That place name is {len(value)} characters long and a "
            f"place name can be at most {LABEL_MAX_LENGTH}. A name that long is usually a "
            f"whole sentence that was heard as one - try something short, like 'home' or "
            f"'the gym'."
        )
    return value


class PlaceSerializer(SyncedSerializer):
    class Meta:
        model = Place
        fields = [
            "id",
            "label",
            "latitude",
            "longitude",
            "address",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]
        # Optional on a PUT, so a phone one release behind (which never sends
        # it) re-tagging a place leaves an address already on the row alone
        # rather than wiping it. A client that DOES send it sends null for
        # "no address known".
        extra_kwargs = {
            "address": {"required": False, "allow_null": True, "allow_blank": True},
        }

    def validate_label(self, value: str) -> str:
        return validate_place_label(value)

    def validate_address(self, value: str | None) -> str | None:
        # One spelling of "no address": NULL. `places_address_not_blank`
        # refuses the blank string in SQL as well.
        if value is None or not value.strip():
            return None
        value = value.strip()
        if len(value) > ADDRESS_MAX_LENGTH:
            raise serializers.ValidationError(
                f"Nothing was saved. That address is {len(value)} characters long and an "
                f"address can be at most {ADDRESS_MAX_LENGTH}."
            )
        return value

    def validate_latitude(self, value: float) -> float:
        low, high = LATITUDE_BOUNDS
        if not low <= value <= high:
            raise range_error("latitude", value, low, high)
        return value

    def validate_longitude(self, value: float) -> float:
        low, high = LONGITUDE_BOUNDS
        if not low <= value <= high:
            raise range_error("longitude", value, low, high)
        return value


class PlaceViewSet(SyncedModelViewSet):
    aspect = "places"
    table = "places"
    serializer_class = PlaceSerializer
    identity_field = "label"
    # A re-tagged label brings a forgotten place back rather than leaving a
    # tombstone in place - the one aspect where that is true. See this
    # module's own doc comment.
    put_revives_tombstone = True


class PlaceRenameRequestSerializer(serializers.Serializer):
    to = serializers.CharField(
        allow_blank=True,
        trim_whitespace=False,
        help_text="The new label. Refused when a live place already has it.",
    )

    def validate_to(self, value: str) -> str:
        return validate_place_label(value)


class PlaceRenameResultSerializer(serializers.Serializer):
    place = PlaceSerializer(help_text="The place under its new label, as stored.")
    reminders_moved = serializers.IntegerField(
        help_text=(
            "How many live place-triggered reminders (`events.trigger_place_label`) were "
            "moved from the old label to the new one in the same transaction."
        )
    )
    detail = serializers.CharField(help_text="What happened, in words.")


class PlaceRenameView(APIView):
    """`POST /api/places/<label>/rename/` - a new label for the same place.

    **Why a route of its own rather than a PUT plus a DELETE from the
    client** (voice audit 2026-10-09, finding 2): "rename Home to Katie House"
    was carried out as `forget_place` then `tag_place`, which deleted home
    with nothing said and then re-pinned wherever the phone happened to be.
    A rename keeps the coordinates and the address and changes nothing but
    the name, so it is one write the engine owns, in one transaction.

    `label` is this table's identity and every phone keys its replica by it,
    so the rename is written as the sync feed can carry it: the new label is
    upserted (reviving its own tombstone if it once existed) with the old
    row's coordinates and address, and the old label is tombstoned. A row
    updated in place would reach other phones as a new label with no
    tombstone for the old one, and they would keep both forever.

    Place-triggered reminders name a place by this string
    (`events.trigger_place_label`), so the live ones move with it, in the
    same transaction; `touch_updated_at` on `events` puts them in the feed.
    """

    @extend_schema(
        operation_id="api_places_rename",
        tags=["places"],
        request=PlaceRenameRequestSerializer,
        responses={
            200: OpenApiResponse(
                response=PlaceRenameResultSerializer,
                description=(
                    "Renamed. The old label is tombstoned, the new one carries the same "
                    "coordinates and address, and live reminders on the old label moved."
                ),
            ),
            400: WRITE_REFUSED,
            404: NOT_FOUND,
            409: OpenApiResponse(
                response=DetailSerializer,
                description=(
                    "A live place already has the new label, or the two labels are the same. "
                    "Nothing was renamed."
                ),
            ),
        },
    )
    def post(self, request, label):
        body = PlaceRenameRequestSerializer(data=request.data)
        body.is_valid(raise_exception=True)
        to = body.validated_data["to"]
        household = household_of(request)
        places = Place.objects.filter(household=household)
        source = places.filter(label=label, deleted_at__isnull=True).first()
        if source is None:
            return Response(
                {"detail": f"Nothing was renamed. There is no saved place called {label!r}."},
                status=status.HTTP_404_NOT_FOUND,
            )
        refusal = _rename_conflict(places, label, to)
        if refusal is not None:
            return Response({"detail": refusal}, status=status.HTTP_409_CONFLICT)
        target = places.filter(label=to).first()

        def _write():
            renamed = _upsert_renamed(household, target, source, to)
            source.deleted_at = Now()
            source.save(update_fields=["deleted_at"])
            moved = Event.objects.filter(
                household=household, trigger_place_label=label, deleted_at__isnull=True
            ).update(trigger_place_label=to)
            renamed.refresh_from_db()
            return renamed, moved

        written, error = save_or_400(_write)
        if error is not None:
            return error
        renamed, moved = written
        plural = "s" if moved != 1 else ""
        reminders = "" if moved == 0 else f" {moved} reminder{plural} moved with it."
        return Response(
            {
                "place": PlaceSerializer(renamed).data,
                "reminders_moved": moved,
                "detail": f"Renamed {label!r} to {to!r}.{reminders}",
            },
            status=status.HTTP_200_OK,
        )


def _rename_conflict(places, label: str, to: str) -> str | None:
    """Why a rename may not happen, in words, or None when it may."""
    if to == label:
        return f"Nothing was renamed. That place is already called {label!r}."
    if places.filter(label=to, deleted_at__isnull=True).exists():
        return (
            f"Nothing was renamed. There is already a saved place called {to!r}. "
            f"Rename or forget that one first."
        )
    return None


def _upsert_renamed(household, target, source, to: str):
    """The row under the new label: revives `to`'s own tombstone when the
    label once existed (the unique key is `(household, label)`, tombstones
    included), otherwise a fresh row. Either way it carries the source's
    coordinates and address and nothing else of it."""
    if target is None:
        return Place.objects.create(
            id=uuid.uuid4(),
            household=household,
            label=to,
            latitude=source.latitude,
            longitude=source.longitude,
            address=source.address,
            provenance=Provenance.USER,
            created_at=Now(),
            updated_at=Now(),
        )
    target.latitude = source.latitude
    target.longitude = source.longitude
    target.address = source.address
    target.deleted_at = None
    target.save(update_fields=["latitude", "longitude", "address", "deleted_at"])
    return target
