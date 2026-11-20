"""`/api/auth/head-units*`: a head unit's own engine sign-in (T3b), DARK.

Every route here is a 404 before authentication unless `LEGION_HEADUNIT_SIGNIN=on`
(`headunit_signin.flags`), so with the switch off nothing is read, written or queried.

- `GET /api/auth/head-units`: the member's own live head units: label, created, last used,
  expires. Never a token (only hashes are kept). Device token or browser session.
- `POST /api/auth/head-units` `{"name", "kind": "head_unit"}`: called by the member's PHONE while
  it pairs with a unit. Answers `{"id", "token", "expires"}` once; the token is never shown
  again. A member's own device token only: never a browser session, never a recruiter, never a
  head-unit token (the route is not on its list), never `/mcp`. 5 an hour per member.
- `DELETE /api/auth/head-units/<id>`: "forget this unit". The member who paired it only;
  anyone else's id is a 404. Immediate.
- `POST /api/auth/head-units/revoke-all`: a lost or sold unit: every live head unit of the
  member, at once.
- `POST /api/auth/head-units/<id>/rotate`: the member's PHONE (its own write device token) asks
  for a new token for a live unit it paired, and hands it over the car link. The old token stops
  working at once; a later use of it is refused and counted (`stale_use_count`), never revoked
  (`authentication.record_stale_use` says why). Never past 90 days from pairing
  (`models.MAX_AGE`). 6 an hour per unit. A unit never rotates its own token (review B1: a thief
  holding it would lock the real unit out).
"""

from __future__ import annotations

from django.db import transaction
from django.utils import timezone
from drf_spectacular.utils import OpenApiResponse, extend_schema
from rest_framework import exceptions, serializers, status
from rest_framework.authentication import SessionAuthentication
from rest_framework.response import Response
from rest_framework.throttling import SimpleRateThrottle
from rest_framework.views import APIView

from api.schema import DetailSerializer
from headunit_signin import flags
from headunit_signin.models import (
    LABEL_MAX,
    REASON_FORGOTTEN,
    REASON_LOST_OR_SOLD,
    HeadUnitCredential,
)
from headunit_signin.scope import live_vehicles
from household.authentication import DeviceTokenAuthentication
from household.models import DeviceToken, is_recruiter
from household.permissions import IsHouseholdMember
from household.tenancy import household_of, visible

TAGS = ["auth"]
KIND = "head_unit"
OFF_WORDS = (
    "Nothing was done: head-unit sign-in is switched off on this server "
    "(LEGION_HEADUNIT_SIGNIN is not on)."
)
MINT_FROM_PHONE = (
    "Nothing was issued. A head unit's sign-in is minted by the member's phone while it pairs, "
    "with the phone's own device token, not from a browser."
)
NOT_FOR_RECRUITER = "Nothing was issued. A demo visit cannot sign in a head unit."
ROTATE_FROM_PHONE = (
    "Nothing was rotated. A head unit's token is rotated by the member's phone, with the phone's "
    "own device token, which then hands the new one to the unit over the car link."
)
NOT_ROTATED = "Nothing was rotated: no live head unit of yours has that id."
NOT_FOUND = "Nothing was revoked: no head unit of yours has that id."
NO_SUCH_VEHICLE = "Nothing was issued. No vehicle in this household has that vehicle_id."


class _OffBeforeAnything(APIView):
    # The switch first, before authentication: with it off, no query of any kind runs.
    def initial(self, request, *args, **kwargs):
        if not flags.signin_enabled():
            raise exceptions.NotFound(OFF_WORDS)
        super().initial(request, *args, **kwargs)


class _MemberView(_OffBeforeAnything):
    authentication_classes = [DeviceTokenAuthentication, SessionAuthentication]
    permission_classes = [IsHouseholdMember]


class MintThrottle(SimpleRateThrottle):
    scope = "headunit_mint"

    def get_cache_key(self, request, view):
        if request.method != "POST" or not request.user.is_authenticated:
            return None
        return self.cache_format % {"scope": self.scope, "ident": request.user.pk}


class RotateThrottle(SimpleRateThrottle):
    """Per credential (the id in the path), whoever asks."""

    scope = "headunit_rotate"

    def get_cache_key(self, request, view):
        return self.cache_format % {"scope": self.scope, "ident": view.kwargs["credential_id"]}


class UploadThrottle(SimpleRateThrottle):
    """A head unit's own log uploads, per unit (review B2). None for every other caller."""

    scope = "headunit_upload"

    def get_cache_key(self, request, view):
        if not isinstance(request.auth, HeadUnitCredential) or request.method != "POST":
            return None
        return self.cache_format % {"scope": self.scope, "ident": request.auth.pk}


class MintSerializer(serializers.Serializer):
    name = serializers.CharField(max_length=LABEL_MAX, trim_whitespace=True)
    kind = serializers.ChoiceField(choices=[KIND])
    vehicle_id = serializers.UUIDField(
        required=False,
        allow_null=True,
        help_text="The household vehicle this unit is in. Without one the unit sends no telemetry.",
    )


class IssuedSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    token = serializers.CharField(help_text="Shown once. The engine keeps only its hash.")
    expires = serializers.DateTimeField()


class HeadUnitSerializer(serializers.Serializer):
    id = serializers.UUIDField()
    label = serializers.CharField()
    created_at = serializers.DateTimeField()
    last_used_at = serializers.DateTimeField(allow_null=True)
    expires_at = serializers.DateTimeField()
    vehicle_id = serializers.UUIDField(allow_null=True)
    rotated_at = serializers.DateTimeField(allow_null=True)
    stale_use_count = serializers.IntegerField(
        help_text="Uses of a token after it was rotated away: a copy, or a lost hand-over."
    )
    stale_used_at = serializers.DateTimeField(allow_null=True)


class RevokedSerializer(serializers.Serializer):
    revoked = serializers.IntegerField()
    detail = serializers.CharField()


def _issued(row: HeadUnitCredential, raw: str) -> dict:
    return {"id": row.pk, "token": raw, "expires": row.expires_at}


def _mine(request):
    return visible(HeadUnitCredential, request).filter(owner_user=request.user)


class HeadUnitListView(_MemberView):
    throttle_classes = [MintThrottle]

    @extend_schema(
        operation_id="api_auth_head_units_list",
        tags=TAGS,
        responses={
            200: OpenApiResponse(
                HeadUnitSerializer(many=True),
                description="The calling member's own live head units, newest first. No token.",
            ),
            404: OpenApiResponse(DetailSerializer, description="Head-unit sign-in is off."),
        },
    )
    def get(self, request):
        now = timezone.now()
        rows = _mine(request).filter(revoked_at__isnull=True, expires_at__gt=now)
        return Response(HeadUnitSerializer(rows.order_by("-created_at"), many=True).data)

    @extend_schema(
        operation_id="api_auth_head_units_create",
        tags=TAGS,
        request=MintSerializer,
        responses={
            201: OpenApiResponse(IssuedSerializer, description="The token, shown this once."),
            400: DetailSerializer,
            403: DetailSerializer,
            404: OpenApiResponse(DetailSerializer, description="Head-unit sign-in is off."),
            429: DetailSerializer,
        },
    )
    def post(self, request):
        if not isinstance(request.auth, DeviceToken):
            return Response({"detail": MINT_FROM_PHONE}, status=status.HTTP_403_FORBIDDEN)
        if is_recruiter(request.user):
            return Response({"detail": NOT_FOR_RECRUITER}, status=status.HTTP_403_FORBIDDEN)
        body = MintSerializer(data=request.data)
        if not body.is_valid():
            return Response(
                {"detail": f"Nothing was issued. {body.errors}"},
                status=status.HTTP_400_BAD_REQUEST,
            )
        vehicle_id = body.validated_data.get("vehicle_id")
        if vehicle_id is not None and not live_vehicles(request).filter(pk=vehicle_id).exists():
            return Response({"detail": NO_SUCH_VEHICLE}, status=status.HTTP_400_BAD_REQUEST)
        row, raw = HeadUnitCredential.mint(
            household=household_of(request),
            owner_user=request.user,
            label=body.validated_data["name"],
            vehicle_id=vehicle_id,
        )
        return Response(_issued(row, raw), status=status.HTTP_201_CREATED)


class HeadUnitDetailView(_MemberView):
    @extend_schema(
        operation_id="api_auth_head_units_destroy",
        tags=TAGS,
        responses={
            204: OpenApiResponse(
                description="Revoked: that unit is signed out now. Already revoked is the same."
            ),
            404: DetailSerializer,
        },
    )
    def delete(self, request, credential_id):
        row = _mine(request).filter(pk=credential_id).first()
        if row is None:
            return Response({"detail": NOT_FOUND}, status=status.HTTP_404_NOT_FOUND)
        _mine(request).filter(pk=row.pk, revoked_at__isnull=True).update(
            revoked_at=timezone.now(), revoked_reason=REASON_FORGOTTEN
        )
        return Response(status=status.HTTP_204_NO_CONTENT)


class HeadUnitRevokeAllView(_MemberView):
    @extend_schema(
        operation_id="api_auth_head_units_revoke_all",
        tags=TAGS,
        request=None,
        responses={200: RevokedSerializer, 404: DetailSerializer},
    )
    def post(self, request):
        count = _mine(request).filter(revoked_at__isnull=True).update(
            revoked_at=timezone.now(), revoked_reason=REASON_LOST_OR_SOLD
        )
        words = f"Revoked {count}: every head unit of yours is signed out."
        return Response({"revoked": count, "detail": words})


class HeadUnitRotateView(_MemberView):
    throttle_classes = [RotateThrottle]

    @extend_schema(
        operation_id="api_auth_head_units_rotate",
        tags=TAGS,
        request=None,
        responses={
            200: OpenApiResponse(
                IssuedSerializer,
                description="The unit's new token, shown this once; the old one is dead now.",
            ),
            403: DetailSerializer,
            404: DetailSerializer,
            429: DetailSerializer,
        },
    )
    def post(self, request, credential_id):
        if not isinstance(request.auth, DeviceToken):
            return Response({"detail": ROTATE_FROM_PHONE}, status=status.HTTP_403_FORBIDDEN)
        with transaction.atomic():
            row = (
                _mine(request)
                .select_for_update()
                .filter(pk=credential_id, revoked_at__isnull=True, expires_at__gt=timezone.now())
                .first()
            )
            if row is None:
                return Response({"detail": NOT_ROTATED}, status=status.HTTP_404_NOT_FOUND)
            raw = row.rotate()
        return Response(_issued(row, raw))
