"""Serializers for `/api/checklists` (django-engine ticket 04, Phase 2
slice). `checklists/models.py` is the schema; `checklists/ChecklistController.kt`
is the semantics this mirrors - `TickRequestSerializer`'s measured-item
refusal is copied VERBATIM from `ChecklistController.tick`'s own
`TickOutcome.Refused` message (this ticket's own rule: "the sentence the
phone's controller uses today") so a phone screen and this API say the
exact same words for the exact same refusal.
"""
from __future__ import annotations

from rest_framework import serializers

from checklists.models import Checklist, ChecklistItem, ChecklistTick

MEASURE_DIRECTION_CHOICES = ("AT_LEAST", "AT_MOST")
# "None" (a plain todo list) is not in this tuple because it is not a
# STRING a caller would ever send - a caller who wants "no schedule" omits
# the field or sends null, both of which skip this validator entirely (see
# ChecklistSerializer.validate_schedule_kind below).
SCHEDULE_KIND_CHOICES = ("DAILY", "WEEKLY")


def _choice_error(field: str, value, allowed: tuple[str, ...]) -> serializers.ValidationError:
    return serializers.ValidationError(
        f"'{value}' is not a valid {field}. Use one of: {', '.join(allowed)}."
    )


class ChecklistSerializer(serializers.ModelSerializer):
    class Meta:
        model = Checklist
        fields = [
            "id",
            "name",
            "schedule_kind",
            "schedule_every",
            "schedule_days_of_week",
            "sort_order",
            "archived",
            "created_at",
            "updated_at",
            "deleted_at",
            "sync_id",
        ]
        read_only_fields = ["id", "created_at", "updated_at", "deleted_at"]

    def validate_name(self, value: str) -> str:
        if not value or not value.strip():
            raise serializers.ValidationError("name cannot be blank.")
        return value

    def validate_schedule_kind(self, value: str | None) -> str | None:
        if value is not None and value not in SCHEDULE_KIND_CHOICES:
            raise _choice_error("schedule_kind", value, SCHEDULE_KIND_CHOICES)
        return value


class ChecklistItemSerializer(serializers.ModelSerializer):
    class Meta:
        model = ChecklistItem
        fields = [
            "id",
            "checklist",
            "text",
            "sort_order",
            "created_at",
            "updated_at",
            "deleted_at",
            "sync_id",
            "measure_unit",
            "measure_target",
            "measure_direction",
        ]
        read_only_fields = ["id", "created_at", "updated_at", "deleted_at"]

    def validate_text(self, value: str) -> str:
        if not value or not value.strip():
            raise serializers.ValidationError("text cannot be blank.")
        return value

    def validate_measure_direction(self, value: str | None) -> str | None:
        if value is not None and value not in MEASURE_DIRECTION_CHOICES:
            raise _choice_error("measure_direction", value, MEASURE_DIRECTION_CHOICES)
        return value


class ChecklistTickSerializer(serializers.ModelSerializer):
    """Read/representation shape only - a tick is never created or edited
    through this serializer directly; `ChecklistItemTickView` builds
    `ChecklistTick` rows itself so it can apply the revive-on-retick
    semantics `ChecklistController.tick`/`ChecklistTickDao.retick` describe,
    which a plain `ModelSerializer.create()` cannot express (it does not
    know to look for a soft-deleted row occupying the same `(item, day)`
    slot first)."""

    class Meta:
        model = ChecklistTick
        fields = [
            "id",
            "item",
            "day",
            "ticked_at",
            "updated_at",
            "deleted_at",
            "sync_id",
            "value",
            "source",
        ]
        read_only_fields = ["id", "ticked_at", "updated_at", "deleted_at"]


class TickRequestSerializer(serializers.Serializer):
    """`POST /api/checklists/<id>/items/<item>/tick` body - `{day, value?,
    source?}`. Deliberately a plain `Serializer`, not a `ModelSerializer`
    over `ChecklistTick`: the measured-item refusal (`validate`) needs the
    ITEM's own `measure_unit`, which is not a field on this request body at
    all (the item is named in the URL, not the payload) - so the check is
    written by hand rather than leaning on a field-level `validate_<name>`
    that has no access to a sibling object.
    """

    day = serializers.IntegerField()
    value = serializers.FloatField(required=False, allow_null=True, default=None)
    source = serializers.CharField(required=False, default="USER_REPORTED")

    def validate(self, attrs: dict) -> dict:
        item: ChecklistItem = self.context["item"]
        if item.measure_unit is not None and attrs.get("value") is None:
            # Verbatim from ChecklistController.tick's TickOutcome.Refused
            # message (Kevin's ruling, quoted there: "a number is the
            # point") - reused so the phone and this API refuse in the
            # exact same words.
            raise serializers.ValidationError(
                f'"{item.text}" is measured in {item.measure_unit} - '
                f"give a number to tick it, nothing was recorded."
            )
        return attrs
