"""`visibility` on the wire (ADR 0052, spec D3): `"shared" | "private"`.

The stored fact is `owner_user_id`, and no user id ever goes on the wire
(`tests/test_visibility.py` and `tests/test_tenancy.py` both check). So the
serializers for `events` and `checklists` render this one word from the row
and accept it on the way in; the views turn it into an owner through
`household.tenancy.owner_after_change`, which holds the who-may-change rule.
"""

from __future__ import annotations

from rest_framework import serializers

from household.tenancy import VISIBILITY_CHOICES, visibility_of

VISIBILITY_HELP = (
    "`shared` (every member of the household sees it) or `private` (only you do). "
    "Defaults to `shared` on create. A shared row can be made private only by the "
    "member who added it, or by anyone when nobody is recorded as having added it; "
    "anything else is a 403 with a sentence. Another member's private row is never "
    "served: it is a 404, and a `?since=` feed carries it only as a redacted tombstone."
)


class VisibilityField(serializers.ChoiceField):
    """Reads the row (`source="*"`), writes `{"visibility": <word>}` into
    `validated_data` for the view to act on. Never maps to a column directly:
    the view decides whether the change is allowed before anything is saved."""

    def __init__(self, **kwargs):
        kwargs.setdefault("help_text", VISIBILITY_HELP)
        super().__init__(
            choices=VISIBILITY_CHOICES,
            source="*",
            required=False,
            error_messages={
                "invalid_choice": (
                    '"{input}" is not a visibility. Use one of: '
                    + ", ".join(VISIBILITY_CHOICES)
                    + ". Nothing was saved."
                )
            },
            **kwargs,
        )

    def to_representation(self, instance):
        return visibility_of(instance)

    def to_internal_value(self, data):
        return {"visibility": super().to_internal_value(data)}
