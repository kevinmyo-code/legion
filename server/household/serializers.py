"""Shape-only serializers for the auth endpoints. Nothing here does gate or
provenance work - that starts in ticket 03 - this file just describes the
request/response bodies the auth endpoints promise.

**2026-09-10 (web-and-households ticket 03 narrow slice):** added the
session-login request shape, reusing `MeResponseSerializer` for its
response body - same shape `/me` already returns. Device-token login and
`/me`'s `device_name` are unchanged - a session has no device to name, so
it sends `""`. **No `household` field was added here**:
`tests/test_tenancy.py::test_no_openapi_component_declares_household_id`
is a standing rule that no OpenAPI component may expose `household` or
`household_id` at all - a client cannot honestly read or choose its own
tenancy, ADR 0045's "one user, one household" makes the id redundant to
ask for, and ticket 03's own design puts household info on its own
`GET /api/households/me` endpoint, not on every response that happens to
authenticate someone.

**That sentence used to end "(not yet built)".** It is built - see
`HouseholdSerializer` below - and the rule it exists to respect is
unchanged and worth restating, because this file is where it would next be
broken: no serializer here may declare a field NAMED `household` or
`household_id`, whatever it holds.
`tests/test_tenancy.py::test_no_openapi_component_declares_household_id`
reads the generated components and fails the build on one. The household
RESOURCE is allowed to describe itself - `HouseholdSerializer.id` is the
household's own id on its own endpoint - because that is the resource, not
a tenancy field bolted onto a row."""
from __future__ import annotations

from rest_framework import serializers


class LoginRequestSerializer(serializers.Serializer):
    email = serializers.EmailField()
    password = serializers.CharField(write_only=True, trim_whitespace=False)
    device_name = serializers.CharField(max_length=255)


class LoginResponseSerializer(serializers.Serializer):
    token = serializers.CharField()
    user_id = serializers.UUIDField()


class SessionLoginRequestSerializer(serializers.Serializer):
    """`POST /api/auth/session/login`. No `device_name` - a browser session
    is not a device token (ADR 0044 rule 3: different credentials, different
    revocation stories) and mints none."""

    email = serializers.EmailField()
    password = serializers.CharField(write_only=True, trim_whitespace=False)


class MeResponseSerializer(serializers.Serializer):
    user_id = serializers.UUIDField()
    email = serializers.EmailField()
    device_name = serializers.CharField()


# =============================================================================
# Signup and invites (web-and-households ticket 03)
# =============================================================================


class SignupRequestSerializer(serializers.Serializer):
    """`POST /api/auth/signup`.

    `invite_code` is `required=False` at the SERIALIZER level and required
    at the VIEW level unless `LEGION_OPEN_SIGNUP` is on - the two are
    different questions and only the view knows the answer to the second.
    Declaring it required here would make the open-signup switch
    unreachable; declaring it optional here and forgetting the view check
    would make every engine open. `household/views.py:SignupView` is where
    the second half lives, and `tests/test_accounts.py` pins both.

    `name` is one field, not `first_name`/`last_name`: a person types their
    name once on a signup form, and this server has no use for the halves.
    It lands in `User.first_name`.
    """

    email = serializers.EmailField()
    password = serializers.CharField(write_only=True, trim_whitespace=False)
    name = serializers.CharField(max_length=150, required=False, allow_blank=True, default="")
    invite_code = serializers.CharField(max_length=64, required=False, allow_blank=True, default="")
    household_name = serializers.CharField(
        max_length=120, required=False, allow_blank=True, default=""
    )
    device_name = serializers.CharField(max_length=255)


class InvitePreviewSerializer(serializers.Serializer):
    """`GET /api/auth/invite/<code>` - what a code will do, WITHOUT spending
    it. Ticket 05's `/join/<code>` screen renders this before anyone types a
    password.

    Anonymous, so it says as little as it can get away with: whether the
    code is live, whether signing up with it joins a household or founds
    one, and - only when it joins an existing one - that household's NAME,
    so the person can tell they are joining the right family. Never its id
    (nothing a signed-out stranger can do with a uuid is honest), never who
    minted it, never how many uses are left when it is dead.
    """

    code = serializers.CharField()
    live = serializers.BooleanField()
    creates_household = serializers.BooleanField()
    household_name = serializers.CharField(
        allow_null=True,
        help_text=(
            "The household this code joins, or null when it founds a new one and the "
            "person signing up gets to name it."
        ),
    )
    uses_left = serializers.IntegerField()
    expires_at = serializers.DateTimeField()
    reason = serializers.CharField(
        allow_null=True,
        help_text="Why the code cannot be used, in words, or null when it can.",
    )


class InviteCreateRequestSerializer(serializers.Serializer):
    """`POST /api/households/me/invites`. Every field optional: the common
    case is an owner minting a code for one more adult in their own
    household, and that is what the defaults are."""

    creates_household = serializers.BooleanField(
        required=False,
        default=False,
        help_text=(
            "True mints a code that FOUNDS a household rather than joining this one. "
            "The first person to sign up with it names the household and becomes its "
            "owner; later uses join what the first one founded."
        ),
    )
    max_uses = serializers.IntegerField(required=False, default=2, min_value=1, max_value=50)
    expires_in_days = serializers.IntegerField(
        required=False, default=14, min_value=1, max_value=365
    )


class InviteSerializer(serializers.Serializer):
    """One live invite, code included.

    The code is here on purpose and only for live invites: an owner who
    minted a code yesterday and lost the message has to be able to read it
    back, and `GET /api/households/me/invites` returns only invites that are
    still live (`household/households.py:InviteListCreateView`), so a spent,
    expired or revoked code is never listed at all.
    """

    id = serializers.IntegerField()
    code = serializers.CharField()
    join_url = serializers.CharField()
    creates_household = serializers.BooleanField()
    max_uses = serializers.IntegerField()
    used_count = serializers.IntegerField()
    expires_at = serializers.DateTimeField()
    created_at = serializers.DateTimeField()


class HouseholdMemberSerializer(serializers.Serializer):
    role = serializers.CharField(
        help_text=(
            "`owner` or `member`. The ONLY role there is, and it governs membership "
            "alone (ADR 0045): an owner may invite and remove people, and sees exactly "
            "the same rows a member does."
        )
    )
    user_id = serializers.UUIDField()
    email = serializers.EmailField()
    name = serializers.CharField()
    joined_at = serializers.DateTimeField()


class HouseholdSerializer(serializers.Serializer):
    """`GET`/`PATCH /api/households/me` - the household resource itself.

    `id` is the household's own id, on the household's own endpoint, and is
    the one place a household uuid appears on this API. That is not the
    thing `test_no_openapi_component_declares_household_id` forbids: it
    forbids a tenancy field on a DATA row, where a client could either learn
    something it cannot act on or, worse, choose its own tenancy by sending
    one. A resource describing itself is not that.
    """

    id = serializers.UUIDField()
    name = serializers.CharField()
    created_at = serializers.DateTimeField()
    members = HouseholdMemberSerializer(many=True)


class HouseholdPatchRequestSerializer(serializers.Serializer):
    name = serializers.CharField(max_length=120)


class DeviceTokenSerializer(serializers.Serializer):
    """One live device token of the calling user. **Never the key** - the
    server holds only its hash and could not return it if it wanted to
    (`household/models.py:hash_device_key`)."""

    id = serializers.IntegerField()
    name = serializers.CharField()
    created_at = serializers.DateTimeField()
    last_seen_at = serializers.DateTimeField(allow_null=True)
    current = serializers.BooleanField(
        help_text="True for the token that made this request, so a client can avoid "
        "revoking the device it is running on by accident."
    )
