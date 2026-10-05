"""The bought entry on the wire (purchase-log tickets 01 and 06).

No user id goes on the wire (ADR 0052): `owner_user` is rendered as the one
word `visibility`, `created_by` as `logged_by` (a first name, or null when
nobody was recorded) and `logged_by_me`. `household` is on no field list
(ADR 0045). `source` and `tick` are the engine's to set, never a caller's.
"""

from __future__ import annotations

from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import extend_schema_field
from rest_framework import serializers

from api.visibility import VisibilityField
from purchases.matching import day_to_date, logged_by_name
from purchases.models import Purchase

PRICE_NOTE = "entered by hand; never checked against the bank or added into ledger figures"

PRICE_HELP = (
    "Whole cents, entered by hand (499 is 4.99), or null when nobody said. Never reconciled "
    "against a bank statement and never summed into a ledger figure; every surface that shows "
    "it says it was entered by hand."
)


@extend_schema_field(OpenApiTypes.INT)
class CentsField(serializers.Field):
    """A non-negative whole number of cents, and nothing that merely looks
    like one. DRF's `IntegerField` quietly accepts `"12.0"` and `12.0`; money
    here is exact (CLAUDE.md section 4 rule 3), so a float, a string or a bool
    is refused in words."""

    default_error_messages = {
        "invalid": (
            "price_cents must be a whole number of cents, like 499 for 4.99. Nothing was saved."
        ),
        "negative": "price_cents cannot be negative. Nothing was saved.",
    }

    def __init__(self, **kwargs):
        kwargs.setdefault("allow_null", True)
        kwargs.setdefault("required", False)
        kwargs.setdefault("help_text", PRICE_HELP)
        super().__init__(**kwargs)

    def to_internal_value(self, data):
        if isinstance(data, bool) or not isinstance(data, int):
            self.fail("invalid")
        if data < 0:
            self.fail("negative")
        return data

    def to_representation(self, value):
        return value


class PurchaseSerializer(serializers.ModelSerializer):
    visibility = VisibilityField()
    price_cents = CentsField()
    bought_on = serializers.IntegerField(
        min_value=0,
        help_text="The local day it was bought, as an epoch day (`LocalDate.toEpochDay()`).",
    )
    bought_on_date = serializers.SerializerMethodField(
        help_text="`bought_on` as an ISO date (YYYY-MM-DD), for display."
    )
    logged_by = serializers.SerializerMethodField(
        help_text=(
            "The first name of the member who logged it. Null means NOT RECORDED (an entry "
            "imported from past Groceries ticks, which never stored who ticked them); say "
            "\"not recorded\", never guess."
        )
    )
    logged_by_me = serializers.SerializerMethodField(
        help_text="True when the member making this request logged it."
    )
    price_note = serializers.SerializerMethodField(
        help_text="Words to show beside the price, or null when there is no price."
    )

    class Meta:
        model = Purchase
        fields = [
            "id",
            "item",
            "bought_on",
            "bought_on_date",
            "logged_at",
            "logged_by",
            "logged_by_me",
            "store",
            "price_cents",
            "price_note",
            "quantity_note",
            "visibility",
            "source",
            "tick",
            "deleted_at",
            "sync_id",
        ]
        read_only_fields = ["id", "logged_at", "source", "tick", "deleted_at"]
        extra_kwargs = {
            "item": {"help_text": "What was bought, as written."},
            "source": {
                "help_text": (
                    "MANUAL (logged by a member), GROCERIES_TICK (a tick on the Groceries "
                    "list, ADR 0055) or GROCERIES_BACKFILL (a Groceries tick from before the "
                    "log existed; who ticked it was not recorded)."
                )
            },
        }

    def get_bought_on_date(self, row) -> str:
        return day_to_date(row.bought_on).isoformat()

    def get_logged_by(self, row) -> str | None:
        return logged_by_name(row.created_by)

    def get_logged_by_me(self, row) -> bool:
        request = self.context.get("request")
        user_id = getattr(getattr(request, "user", None), "pk", None)
        return user_id is not None and row.created_by_id == user_id

    def get_price_note(self, row) -> str | None:
        return PRICE_NOTE if row.price_cents is not None else None

    def validate_item(self, value: str) -> str:
        if not value or not value.strip():
            raise serializers.ValidationError("item cannot be blank. Nothing was saved.")
        return value.strip()

    def validate_store(self, value):
        return value.strip() or None if isinstance(value, str) else value

    def validate_quantity_note(self, value):
        return value.strip() or None if isinstance(value, str) else value
