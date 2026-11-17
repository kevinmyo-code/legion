"""REST doors for the user-aspect records (engine-brain ticket 15): the phone's aspect engine
(`Aspect`, `RecordType`, `FieldDef`, `EngineRecord`) on the engine, in the homes shape
(`homes/views.py`): the generic synced routes plus `POST <prefix>batch/` for the backfill.

    /api/records/aspects/    GET ?since= ?after= ?active=1   PUT/DELETE <origin_guid>/   batch/
    /api/records/types/      (same)
    /api/records/fields/     (same)
    /api/records/entries/    (same)

A child names its parent by the parent's `origin_guid` (`aspect`, `record_type`), resolved only
inside the request's household, so a row can never hang off another family's tracker. Send
parents first: a record type whose aspect is not on the engine yet is refused by name in the
batch's `failed`, and the phone's next run sends it again.

A record's trash is a value (`trashed_at`), not the route's tombstone: the phone restores a
trashed record within 30 days, and a value can be un-set. `DELETE` sets `deleted_at` for a row
that is gone for good (a removed field), and, unlike a plain synced table, stamps `updated_at`
too, so the `?since=` feed carries the tombstone to every other client.

Shared in the household (ADR 0052, hand-made rows); outside `api/registry.py` like every home, so
`/api/changes` and the generic `read_records` tool never see them.
"""

from __future__ import annotations

import json

from rest_framework import serializers

from api.synced import SyncedModelViewSet, choice_error
from homes.models import (
    RECORD_PROVENANCES,
    UserAspect,
    UserFieldDef,
    UserRecord,
    UserRecordType,
)
from homes.views import (
    ManagedTombstoneViewSet,
    _batch_schema,
    _BatchView,
    _blank_ok,
    _ManagedSerializer,
)


class GuidParentField(serializers.SlugRelatedField):
    """A parent named by its `origin_guid`, found only in the request's household.

    The household's parents are read once per serializer context (one query for a whole batch
    page, not one per row) and a guid that names nothing here gets the same 400 as one that names
    another household's row: from inside this household there is no such parent.
    """

    def __init__(self, model, **kwargs):
        self.parent_model = model
        super().__init__(slug_field="origin_guid", queryset=model.objects.all(), **kwargs)

    def to_internal_value(self, data):
        household = self.context.get("household")
        if household is None:
            raise RuntimeError(
                f"Nothing was written. {self.field_name!r} was resolved without a `household` "
                f"in the serializer context."
            )
        cache = self.context.setdefault("_parents", {})
        key = self.parent_model._meta.db_table
        if key not in cache:
            cache[key] = {
                row.origin_guid: row
                for row in self.parent_model.objects.filter(
                    household=household, deleted_at__isnull=True
                )
            }
        found = cache[key].get(str(data))
        if found is None:
            raise serializers.ValidationError(
                f"No {key} row with origin_guid {data!r} is on the engine for this household; "
                f"send the parent first."
            )
        return found


class _ClientClocks(_ManagedSerializer):
    """The phone's own `createdAt` / `updatedAt` travel as `created_at_client` /
    `updated_at_client`; the engine's `created_at` / `updated_at` stay its own."""


class UserAspectSerializer(_ClientClocks):
    class Meta:
        model = UserAspect
        fields = [
            "id",
            "origin_guid",
            "name",
            "icon",
            "color",
            "position",
            "archived",
            "archived_at",
            "created_at_client",
            "updated_at_client",
            "created_at",
            "updated_at",
            "deleted_at",
        ]
        read_only_fields = ["id", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = _blank_ok("name", "icon", "color")


class UserRecordTypeSerializer(_ClientClocks):
    aspect = GuidParentField(UserAspect, help_text="The aspect's origin_guid.")

    class Meta:
        model = UserRecordType
        fields = [
            "id",
            "origin_guid",
            "aspect",
            "name",
            "primary_amount_field",
            "primary_due_date_field",
            "created_at_client",
            "updated_at_client",
            "created_at",
            "updated_at",
            "deleted_at",
        ]
        read_only_fields = ["id", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = _blank_ok("name")


class UserFieldDefSerializer(_ClientClocks):
    record_type = GuidParentField(UserRecordType, help_text="The record type's origin_guid.")

    class Meta:
        model = UserFieldDef
        fields = [
            "id",
            "origin_guid",
            "record_type",
            "name",
            "type",
            "required",
            "position",
            "config",
            "created_at_client",
            "updated_at_client",
            "created_at",
            "updated_at",
            "deleted_at",
        ]
        read_only_fields = ["id", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = _blank_ok("name", "config")

    def validate_config(self, value):
        if value in (None, ""):
            return value
        try:
            json.loads(value)
        except ValueError as exc:
            raise serializers.ValidationError("config must be JSON text.") from exc
        return value


class UserRecordSerializer(_ClientClocks):
    record_type = GuidParentField(UserRecordType, help_text="The record type's origin_guid.")
    payload = serializers.JSONField(
        help_text="Field origin_guid to value, in the phone's shapes (epoch-ms dates, cents)."
    )

    class Meta:
        model = UserRecord
        fields = [
            "id",
            "origin_guid",
            "record_type",
            "due_at",
            "amount_cents",
            "search_text",
            "provenance",
            "payload",
            "created_at_client",
            "updated_at_client",
            "trashed_at",
            "created_at",
            "updated_at",
            "deleted_at",
        ]
        read_only_fields = ["id", "created_at", "updated_at", "deleted_at"]
        extra_kwargs = _blank_ok("search_text")

    def validate_provenance(self, value: str) -> str:
        if value not in RECORD_PROVENANCES:
            raise choice_error("provenance", value, RECORD_PROVENANCES)
        return value

    def validate_payload(self, value):
        if not isinstance(value, dict):
            raise serializers.ValidationError("payload must be an object of field guid to value.")
        return value


class _RecordsViewSet(ManagedTombstoneViewSet):
    """`ManagedTombstoneViewSet`: a DELETE stamps `updated_at` so the `?since=` feed carries it."""

    aspect = "records"
    parent_field: str = ""

    @classmethod
    def decorate(cls, queryset):
        # The batch compares each row's parent: one join, not one query per row.
        return queryset.select_related(cls.parent_field) if cls.parent_field else queryset


class UserAspectViewSet(_RecordsViewSet):
    table = "user_aspects"
    url_segment = "aspects"
    serializer_class = UserAspectSerializer


class UserRecordTypeViewSet(_RecordsViewSet):
    table = "user_record_types"
    url_segment = "types"
    serializer_class = UserRecordTypeSerializer
    parent_field = "aspect"


class UserFieldDefViewSet(_RecordsViewSet):
    table = "user_field_defs"
    url_segment = "fields"
    serializer_class = UserFieldDefSerializer
    parent_field = "record_type"


class UserRecordViewSet(_RecordsViewSet):
    table = "user_records"
    url_segment = "entries"
    serializer_class = UserRecordSerializer
    parent_field = "record_type"


RECORD_VIEWSETS: tuple[type[SyncedModelViewSet], ...] = (
    UserAspectViewSet,
    UserRecordTypeViewSet,
    UserFieldDefViewSet,
    UserRecordViewSet,
)


class UserAspectBatchView(_BatchView):
    viewset_cls = UserAspectViewSet

    @_batch_schema(UserAspectViewSet)
    def post(self, request):
        return self._run(request)


class UserRecordTypeBatchView(_BatchView):
    viewset_cls = UserRecordTypeViewSet

    @_batch_schema(UserRecordTypeViewSet)
    def post(self, request):
        return self._run(request)


class UserFieldDefBatchView(_BatchView):
    viewset_cls = UserFieldDefViewSet

    @_batch_schema(UserFieldDefViewSet)
    def post(self, request):
        return self._run(request)


class UserRecordBatchView(_BatchView):
    viewset_cls = UserRecordViewSet

    @_batch_schema(UserRecordViewSet)
    def post(self, request):
        return self._run(request)


RECORD_BATCH_VIEWS: tuple[type[_BatchView], ...] = (
    UserAspectBatchView,
    UserRecordTypeBatchView,
    UserFieldDefBatchView,
    UserRecordBatchView,
)
