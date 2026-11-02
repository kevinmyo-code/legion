"""`POST /api/demo/enter`: the shared invite code in, one recruiter visitor out.

recruiter-demo ticket 02 (ADR 0068). Anonymous by construction, like the
login doors: this is the request that creates the credential. Throttled per
IP (`demo_enter`), because the caller has no identity yet and the code is
shared, so the IP is the only thing that tells one guesser from another.

What a successful entry leaves behind:

- a throwaway `User` (unusable password, `visitor-<hex>@demo.invalid`), a
  `recruiter` member of the demo household and of nothing else;
- a `read`-scoped `DeviceToken` that expires in two hours, returned once;
- a Django session for the same visitor, expiring with the token and tied to
  it (`household.permissions.DEMO_TOKEN_SESSION_KEY`), because the assistant
  doors are session-only (ADR 0056: "token minting is session-authenticated")
  and stay that way for recruiters too;
- a `DemoVisit` row carrying the `ref`.

The demo household is found by name (`settings.LEGION_DEMO_HOUSEHOLD_NAME`,
default "Demo") through ticket 03's own lookup,
`household.demo.find_demo_household`, the one the seed uses to find what it
built. With no such household, or with two households carrying the name, the
door refuses in words and creates nothing: a visitor put into the wrong one of
two same-named households is the failure this map exists to make impossible.
"""

from __future__ import annotations

import uuid
from datetime import timedelta

from django.contrib.auth import login
from django.db import transaction
from django.utils import timezone
from drf_spectacular.utils import OpenApiResponse, extend_schema, inline_serializer
from rest_framework import exceptions, serializers, status
from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView

from api.schema import DetailSerializer
from demo.models import DemoCode, DemoVisit
from household.demo import DemoHouseholdError, find_demo_household
from household.models import DeviceToken, Household, HouseholdMember, User
from household.permissions import DEMO_TOKEN_SESSION_KEY

DEMO_TAGS = ["demo"]
VISIT_LENGTH = timedelta(hours=2)

WRONG_CODE = "That invite code is not right. Nothing was created; check the code and try again."
RETIRED_CODE = (
    "That invite code has been retired. Nothing was created; ask for the current one."
)
DOOR_CLOSED = "The demo is closed right now. Nothing was created."
NO_DEMO_HOUSEHOLD = (
    "The demo household is not set up on this engine yet. Nothing was created; the "
    "operator has to seed it first."
)
AMBIGUOUS_DEMO_HOUSEHOLD = (
    "More than one household carries the demo household's name on this engine, so there "
    "is no single demo to join. Nothing was created; the operator has to fix the name first."
)
TOO_MANY = (
    "Too many tries from this network in a short time. Nothing was created; wait a while "
    "and try again."
)


class DemoEnterRequestSerializer(serializers.Serializer):
    code = serializers.CharField(max_length=64, trim_whitespace=True)
    ref = serializers.RegexField(
        r"^[A-Za-z0-9_.-]{1,64}$",
        required=False,
        allow_blank=True,
        help_text=(
            "Where the visitor came from, as the resume link said (`/try?ref=acme`). Letters, "
            "digits, `_`, `-` and `.`, up to 64. Stored on the visit; never shown to the model."
        ),
    )


DemoEnterResponse = inline_serializer(
    name="DemoVisitIssued",
    fields={
        "token": serializers.CharField(
            help_text=(
                "The visitor's read-only device token, shown once. The browser also holds a "
                "session for the same visitor, which is what the assistant accepts."
            )
        ),
        "expires_at": serializers.DateTimeField(
            help_text="The token and the session both stop working at this time."
        ),
        "ref": serializers.CharField(allow_blank=True),
    },
)


class DemoEnterThrottle(ScopedRateThrottle):
    """Per IP: `ScopedRateThrottle` keys an anonymous request on its address."""


class DemoEnterView(APIView):
    authentication_classes: list = []
    permission_classes = [AllowAny]
    throttle_classes = [DemoEnterThrottle]
    throttle_scope = "demo_enter"

    def throttled(self, request, wait):
        raise exceptions.Throttled(wait, detail=TOO_MANY)

    @extend_schema(
        tags=DEMO_TAGS,
        operation_id="api_demo_enter_create",
        summary="Enter the recruiter demo with the shared invite code",
        request=DemoEnterRequestSerializer,
        responses={
            201: DemoEnterResponse,
            400: OpenApiResponse(DetailSerializer, description="The body did not fit."),
            403: OpenApiResponse(
                DetailSerializer,
                description="Wrong code, a retired code, or the demo is closed. Nothing created.",
            ),
            429: OpenApiResponse(
                DetailSerializer, description="Too many tries from this IP. Nothing created."
            ),
            503: OpenApiResponse(
                DetailSerializer,
                description=(
                    "No demo household on this engine, or more than one household carries "
                    "its name. Nothing created."
                ),
            ),
        },
    )
    def post(self, request):
        body = DemoEnterRequestSerializer(data=request.data)
        if not body.is_valid():
            return Response(
                {"detail": f"Nothing was created. The request did not fit: {body.errors}"},
                status=status.HTTP_400_BAD_REQUEST,
            )
        code_text = body.validated_data["code"]
        ref = body.validated_data.get("ref", "") or ""

        if not DemoCode.live().exists():
            return _refuse(DOOR_CLOSED, status.HTTP_403_FORBIDDEN)
        code = DemoCode.match(code_text)
        if code is None:
            return _refuse(WRONG_CODE, status.HTTP_403_FORBIDDEN)
        if code.revoked_at is not None:
            return _refuse(RETIRED_CODE, status.HTTP_403_FORBIDDEN)

        try:
            household = find_demo_household()
        except DemoHouseholdError:
            return _refuse(AMBIGUOUS_DEMO_HOUSEHOLD, status.HTTP_503_SERVICE_UNAVAILABLE)
        if household is None:
            return _refuse(NO_DEMO_HOUSEHOLD, status.HTTP_503_SERVICE_UNAVAILABLE)

        expires_at = timezone.now() + VISIT_LENGTH
        with transaction.atomic():
            user, token, raw_key = _make_visitor(household, ref, expires_at)
            DemoVisit.objects.create(
                user=user, token=token, code=code, ref=ref, expires_at=expires_at
            )

        login(request, user, backend="django.contrib.auth.backends.ModelBackend")
        request.session.set_expiry(int(VISIT_LENGTH.total_seconds()))
        request.session[DEMO_TOKEN_SESSION_KEY] = token.pk
        return Response(
            {"token": raw_key, "expires_at": expires_at, "ref": ref},
            status=status.HTTP_201_CREATED,
            headers={"Cache-Control": "no-store"},
        )


def _refuse(message: str, code: int) -> Response:
    return Response({"detail": message}, status=code)


def _make_visitor(household: Household, ref: str, expires_at):
    user = User.objects.create_user(email=f"visitor-{uuid.uuid4().hex[:16]}@demo.invalid")
    user.set_unusable_password()
    user.save(update_fields=["password"])
    HouseholdMember.objects.create(
        user=user, household=household, role=HouseholdMember.RECRUITER
    )
    token, raw_key = DeviceToken.issue(
        user,
        f"Demo visit ({ref})" if ref else "Demo visit",
        DeviceToken.SCOPE_READ,
        expires_at=expires_at,
    )
    return user, token, raw_key
