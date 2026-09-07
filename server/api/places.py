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

from rest_framework import serializers

from api.synced import SyncedModelViewSet, SyncedSerializer, blank_error, range_error
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


class PlaceSerializer(SyncedSerializer):
    class Meta:
        model = Place
        fields = [
            "id",
            "label",
            "latitude",
            "longitude",
            "provenance",
            "created_at",
            "updated_at",
            "deleted_at",
        ]
        read_only_fields = ["id", "provenance", "created_at", "updated_at", "deleted_at"]

    def validate_label(self, value: str) -> str:
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
